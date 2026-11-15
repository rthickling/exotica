import copy
from datetime import date
import json
import math
from pathlib import Path
import unittest

from engine import ValidationError, digest, path_generator, replay, value

ROOT = Path(__file__).resolve().parents[1]


def fixture(name):
    return json.loads((ROOT / "fixtures" / "terms" / f"{name}.json").read_text())


def request(name="note-v1", paths=4096):
    return {
        "terms": fixture(name),
        "market": {
            "snapshotId": "synthetic-20261116-v1",
            "asOf": "2026-11-16",
            "spot": 100,
            "rate": 0.03,
            "dividend": 0.01,
            "volatility": 0.25,
        },
        "settings": {"seed": 42, "paths": paths, "bump": 0.5},
        "historicalFixings": {},
        "settledThrough": None,
    }


class ContractTests(unittest.TestCase):
    def run_path(self, name, levels):
        t = fixture(name)
        return replay({"terms": t, "fixings": dict(zip(t["observationDates"], levels))})

    def test_note_first_call_coupon_on_call_and_single_redemption(self):
        r = self.run_path("note-v1", [100, 100, 10, 10])
        self.assertEqual(
            [(x["paymentDate"], x["kind"], x["cash"]) for x in r["obligations"]],
            [
                ("2027-02-18", "COUPON", 2016.44),
                ("2027-05-19", "COUPON", 1972.60),
                ("2027-05-19", "REDEMPTION", 100000),
            ],
        )
        self.assertEqual(r["processedObservations"], 2)
        self.assertEqual(r["terminationDate"], "2027-05-17")
        self.assertEqual(r, self.run_path("note-v1", [100, 100, 20, 20]))

    def test_note_equality_coupon_capital_and_nonmemory(self):
        r = self.run_path("note-v1", [70, 69.99, 70, 60])
        self.assertEqual(
            [(x["kind"], x["cash"]) for x in r["obligations"]],
            [("COUPON", 2016.44), ("COUPON", 1994.52), ("REDEMPTION", 100000)],
        )
        self.assertEqual(
            self.run_path("note-v1", [69, 69, 69, 59])["obligations"][0]["cash"], 59000
        )
        self.assertEqual(
            len(self.run_path("note-v1", [69, 69, 69, 59])["obligations"]), 1
        )

    def test_note_uncalled_maturity_and_coupons(self):
        r = self.run_path("note-v1", [99, 99, 99, 99])
        self.assertEqual(
            [x["cash"] for x in r["obligations"]],
            [2016.44, 1972.60, 1994.52, 2016.44, 100000],
        )
        self.assertEqual(r["obligations"][-1]["paymentDate"], "2027-11-18")

    def test_accumulator_equality_leverage_and_ko_exclusion(self):
        r = self.run_path("acc-v1", [90, 89, 110, 1])
        self.assertEqual(
            [(x["paymentDate"], x["cash"], x["shares"]) for x in r["obligations"]],
            [("2026-12-18", -900, 10), ("2027-01-20", -1800, 20)],
        )
        self.assertEqual(r["terminationDate"], "2027-02-16")
        self.assertEqual(self.run_path("acc-v1", [110, 1, 1, 1])["obligations"], [])

    def test_accumulator_max_exposure_and_stable_keys(self):
        first = self.run_path("acc-v1", [80, 80, 80, 80])
        second = self.run_path("acc-v1", [80, 80, 80, 80])
        self.assertEqual(first, second)
        self.assertEqual(sum(x["cash"] for x in first["obligations"]), -7200)
        self.assertEqual(sum(x["shares"] for x in first["obligations"]), 80)
        self.assertEqual(len({x["eventKey"] for x in first["obligations"]}), 4)

    def test_prefix_and_gap_validation(self):
        self.assertEqual(self.run_path("acc-v1", [90])["processedObservations"], 1)
        t = fixture("note-v1")
        with self.assertRaises(ValidationError):
            replay({"terms": t, "fixings": {"2027-05-17": 100}})
        with self.assertRaises(ValidationError):
            replay({"terms": t, "fixings": {"2027-02-16": None}})
        t["knockoutAccrues"] = True
        with self.assertRaises(ValidationError):
            replay({"terms": t, "fixings": {}})


class ValuationTests(unittest.TestCase):
    def test_repeat_and_snapshot_hash(self):
        r = request(paths=2048)
        first = value(r)
        second = value(r)
        self.assertEqual(first, second)
        self.assertEqual(first["provenance"]["marketHash"], digest(r["market"]))
        r["marketHash"] = "bad"
        with self.assertRaises(ValidationError):
            value(r)

    def test_deterministic_physical_delivery_values_asset_and_cash(self):
        r = request("acc-v1", paths=2)
        r["market"].update(spot=80, rate=0, dividend=0, volatility=0)
        # Four 20-share purchases at90; delivery shares valued at80 => -800.
        self.assertAlmostEqual(value(r)["npv"], -800, places=8)

    def test_contractual_half_cent_rounding_matches_replay(self):
        r = request(paths=2)
        r["market"].update(spot=59.000005, rate=0, dividend=0, volatility=0)
        self.assertEqual(value(r)["npv"], 59000.01)
        t = r["terms"]
        self.assertEqual(
            replay(
                {"terms": t, "fixings": dict.fromkeys(t["observationDates"], 59.000005)}
            )["obligations"][0]["cash"],
            59000.01,
        )

    def test_pending_physical_quantity_stays_fixed_after_observation(self):
        r = request("acc-v1", paths=2)
        # A one-observation test contract isolates an already-determined delivery.
        r["terms"]["termsVersion"] = "acc-pending-test-v1"
        r["terms"]["observationDates"] = r["terms"]["observationDates"][:1]
        r["terms"]["paymentDates"] = r["terms"]["paymentDates"][:1]
        r["market"].update(
            asOf="2026-12-17", spot=120, rate=0, dividend=0, volatility=0
        )
        r["historicalFixings"] = {"2026-12-16": 89}
        # Historical89 locked20 shares: now worth2400, cash1800 =>600.
        # Current120 must not retroactively knock out the historical purchase.
        actual = value(r)
        self.assertEqual(actual["npv"], 600)
        self.assertEqual(actual["delta"], 20)

    def test_historical_termination_and_settlement(self):
        r = request(paths=2)
        r["market"].update(asOf="2027-05-18", spot=120)
        r["historicalFixings"] = {"2027-02-16": 100, "2027-05-17": 100}
        r["settledThrough"] = "2027-02-18"
        expected = (100000 + 1972.60) * math.exp(-0.03 / 365)
        self.assertAlmostEqual(value(r)["npv"], expected, places=8)
        self.assertAlmostEqual(value(r)["delta"], 0, places=8)
        r["settledThrough"] = "2027-05-18"
        self.assertAlmostEqual(value(r)["npv"], expected, places=8)
        r["market"]["asOf"] = "2027-05-20"
        r["settledThrough"] = "2027-05-19"
        self.assertEqual(value(r)["npv"], 0)

    def test_past_fixings_required_and_reference_fixed_under_delta(self):
        r = request("acc-v1", paths=2)
        before = copy.deepcopy(r)
        value(r)
        self.assertEqual(r, before)
        r["market"]["asOf"] = "2027-01-01"
        with self.assertRaises(ValidationError):
            value(r)

    def test_vanilla_simulation_against_independent_formula(self):
        r = request(paths=65536)
        m = r["market"]
        n = r["settings"]["paths"]
        seed = r["settings"]["seed"]
        g = path_generator(m, 100, ["2027-11-16"], seed)
        payoffs = []
        for _ in range(n):
            terminal = g.next().value()[1]
            payoffs.append(math.exp(-0.03) * max(terminal - 100, 0))
        from engine import mean_stderr

        observed, se = mean_stderr(payoffs)
        # Independent closed-form BSM, not engine path/payoff helper.
        phi = lambda x: 0.5 * (1 + math.erf(x / math.sqrt(2)))
        d1 = (0.03 - 0.01 + 0.5 * 0.25**2) / 0.25
        d2 = d1 - 0.25
        expected = 100 * math.exp(-0.01) * phi(d1) - 100 * math.exp(-0.03) * phi(d2)
        self.assertLessEqual(abs(observed - expected), 4 * se + 1e-8)

    def test_limits_and_unsupported_conventions(self):
        r = request()
        r["settings"]["paths"] = True
        with self.assertRaises(ValidationError):
            value(r)
        r = request()
        r["settings"]["paths"] = 262145
        with self.assertRaises(ValidationError):
            value(r)
        r = request("acc-v1")
        r["terms"]["knockoutAccrues"] = True
        with self.assertRaises(ValidationError):
            value(r)


if __name__ == "__main__":
    unittest.main(verbosity=2)
