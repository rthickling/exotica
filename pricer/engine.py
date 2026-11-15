"""Explicit synthetic payoffs on QuantLib Black-Scholes paths.

Contract cash is Decimal; model arithmetic is float. No ORE product is reused.
The HTTP server runs one request at a time because QL evaluationDate is global.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
import math
from pathlib import Path
import platform
import statistics
from typing import Any

import QuantLib as ql

MODEL = "BlackScholesMerton-flat-r-q-vol/Actual365Fixed"
MAX_PATHS = 262144
COMMON = {
    "product",
    "termsVersion",
    "currency",
    "underlying",
    "initialReference",
    "startDate",
    "observationDates",
    "paymentDates",
}
NOTE = {
    "notional",
    "couponRate",
    "couponBarrier",
    "autocallBarrier",
    "firstCallIndex",
    "capitalBarrier",
}
ACC = {
    "strike",
    "baseQuantity",
    "multiplier",
    "knockoutBarrier",
    "guaranteedObservations",
    "knockoutAccrues",
}


class ValidationError(ValueError):
    pass


def canonical(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)


def digest(value: Any) -> str:
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def number(
    x: Any, name: str, minimum: float | None = None, maximum: float | None = None
) -> float:
    if isinstance(x, bool) or not isinstance(x, (int, float)) or not math.isfinite(x):
        raise ValidationError(f"{name} must be a finite number")
    if minimum is not None and x < minimum or maximum is not None and x > maximum:
        raise ValidationError(f"{name} outside supported range [{minimum}, {maximum}]")
    return float(x)


def iso(x: Any, name: str) -> date:
    try:
        if not isinstance(x, str) or len(x) != 10:
            raise ValueError()
        return date.fromisoformat(x)
    except ValueError as exc:
        raise ValidationError(f"{name} must be an ISO calendar date") from exc


def validate_terms(t: dict) -> None:
    if not isinstance(t, dict) or t.get("product") not in (
        "AUTOCALLABLE",
        "ACCUMULATOR",
    ):
        raise ValidationError("Unsupported product; use AUTOCALLABLE or ACCUMULATOR")
    fields = COMMON | (NOTE if t["product"] == "AUTOCALLABLE" else ACC)
    if set(t) != fields:
        raise ValidationError(
            f"Terms fields missing={sorted(fields - set(t))}, unsupported={sorted(set(t) - fields)}"
        )
    if not isinstance(t["termsVersion"], str) or not 1 <= len(t["termsVersion"]) <= 80:
        raise ValidationError("termsVersion must be a nonempty version identifier")
    if (
        not isinstance(t["currency"], str)
        or len(t["currency"]) != 3
        or not t["currency"].isupper()
    ):
        raise ValidationError("currency must be three uppercase letters")
    if not isinstance(t["underlying"], str) or not t["underlying"]:
        raise ValidationError("underlying required")
    number(t["initialReference"], "initialReference", 0.000001)
    start = iso(t["startDate"], "startDate")
    obs, pay = t["observationDates"], t["paymentDates"]
    if (
        not isinstance(obs, list)
        or not isinstance(pay, list)
        or not 1 <= len(obs) <= 32
        or len(obs) != len(pay)
    ):
        raise ValidationError("Require 1..32 paired observation/payment dates")
    ods = [iso(x, "observationDate") for x in obs]
    pds = [iso(x, "paymentDate") for x in pay]
    if (
        ods != sorted(set(ods))
        or ods[0] <= start
        or any(p < o for o, p in zip(ods, pds))
    ):
        raise ValidationError(
            "Observations must increase after start; payments cannot precede observation"
        )
    if (pds[-1] - start).days > 3650:
        raise ValidationError("Maximum supported horizon is ten years")
    if t["product"] == "AUTOCALLABLE":
        for key in ("notional", "couponBarrier", "autocallBarrier", "capitalBarrier"):
            number(t[key], key, 0.000001)
        number(t["couponRate"], "couponRate", 0, 1)
        i = t["firstCallIndex"]
        if isinstance(i, bool) or not isinstance(i, int) or not 0 <= i < len(obs):
            raise ValidationError(
                "firstCallIndex is zero-based and must be in observation schedule"
            )
    else:
        for key in ("strike", "baseQuantity", "knockoutBarrier"):
            number(t[key], key, 0.000001)
        number(t["multiplier"], "multiplier", 1, 10)
        if isinstance(t["multiplier"], bool) or not isinstance(t["multiplier"], int):
            raise ValidationError("multiplier must be an integer")
        if (
            t["guaranteedObservations"] != 0
            or isinstance(t["guaranteedObservations"], bool)
            or t["knockoutAccrues"] is not False
        ):
            raise ValidationError(
                "Only zero guarantee and KO-before-accrual excluding KO date are supported"
            )


def money(x: Decimal) -> float:
    return float(x.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def coupon_amounts(t: dict) -> list[float]:
    result, previous = [], iso(t["startDate"], "startDate")
    for d in t["observationDates"]:
        current = iso(d, "observationDate")
        result.append(
            money(
                Decimal(str(t["notional"]))
                * Decimal(str(t["couponRate"]))
                * Decimal((current - previous).days)
                / Decimal(365)
            )
        )
        previous = current
    return result


def replay(request: dict) -> dict:
    if not isinstance(request, dict):
        raise ValidationError("Request must be an object")
    t, fixings = request.get("terms"), request.get("fixings", {})
    validate_terms(t)
    if not isinstance(fixings, dict) or set(fixings) - set(t["observationDates"]):
        raise ValidationError(
            "fixings must map contractual observation dates to positive spots"
        )
    for d, value in fixings.items():
        number(value, f"fixing {d}", 0.000001)
    obligations, terminated, termination_date, processed = [], False, None, 0
    coupons = coupon_amounts(t) if t["product"] == "AUTOCALLABLE" else []

    def obligation(i: int, kind: str, cash: float, shares: float = 0) -> None:
        d = t["observationDates"][i]
        obligations.append(
            {
                "eventKey": f"{t['termsVersion']}:{d}:{kind}",
                "observationDate": d,
                "paymentDate": t["paymentDates"][i],
                "kind": kind,
                "cash": cash,
                "shares": shares,
                "currency": t["currency"],
            }
        )

    for i, d in enumerate(t["observationDates"]):
        if d not in fixings:
            if any(x in fixings for x in t["observationDates"][i + 1 :]):
                raise ValidationError(
                    "fixings must be a contiguous prefix of observations"
                )
            break
        s = Decimal(str(fixings[d]))
        processed += 1
        if t["product"] == "AUTOCALLABLE":
            if s >= Decimal(str(t["couponBarrier"])):
                obligation(i, "COUPON", coupons[i])
            called = i >= t["firstCallIndex"] and s >= Decimal(
                str(t["autocallBarrier"])
            )
            if called or i == len(t["observationDates"]) - 1:
                principal = Decimal(str(t["notional"]))
                if not called and s < Decimal(str(t["capitalBarrier"])):
                    principal *= s / Decimal(str(t["initialReference"]))
                obligation(i, "REDEMPTION", money(principal))
                terminated, termination_date = True, d
        else:
            if s >= Decimal(str(t["knockoutBarrier"])):
                terminated, termination_date = True, d
            else:
                qty = Decimal(str(t["baseQuantity"]))
                if s < Decimal(str(t["strike"])):
                    qty *= Decimal(str(t["multiplier"]))
                obligation(
                    i, "PURCHASE", -money(qty * Decimal(str(t["strike"]))), float(qty)
                )
                if i == len(t["observationDates"]) - 1:
                    terminated, termination_date = True, d
        if terminated:
            break
    return {
        "termsVersion": t["termsVersion"],
        "terminated": terminated,
        "terminationDate": termination_date,
        "processedObservations": processed,
        "obligations": obligations,
    }


def validate_request(request: dict) -> dict:
    if not isinstance(request, dict):
        raise ValidationError("Request must be an object")
    allowed = {
        "terms",
        "market",
        "marketHash",
        "settings",
        "historicalFixings",
        "settledThrough",
    }
    if set(request) - allowed:
        raise ValidationError(
            f"Unsupported request fields: {sorted(set(request) - allowed)}"
        )
    t, m, settings = (
        request.get("terms"),
        request.get("market"),
        request.get("settings", {}),
    )
    validate_terms(t)
    if not isinstance(m, dict) or set(m) != {
        "snapshotId",
        "asOf",
        "spot",
        "rate",
        "dividend",
        "volatility",
    }:
        raise ValidationError(
            "market needs exactly snapshotId, asOf, spot, rate, dividend, volatility"
        )
    if not isinstance(m["snapshotId"], str) or not m["snapshotId"]:
        raise ValidationError("market.snapshotId required")
    asof = iso(m["asOf"], "asOf")
    if asof < iso(t["startDate"], "startDate"):
        raise ValidationError("Pre-start valuation is unsupported")
    number(m["spot"], "spot", 0.000001, 1e8)
    number(m["rate"], "rate", -0.1, 1)
    number(m["dividend"], "dividend", 0, 1)
    number(m["volatility"], "volatility", 0, 3)
    if request.get("marketHash") is not None and request["marketHash"] != digest(m):
        raise ValidationError(
            "marketHash does not match canonical numerical market snapshot"
        )
    if not isinstance(settings, dict) or set(settings) - {"seed", "paths", "bump"}:
        raise ValidationError("settings supports only seed, paths and absolute bump")
    settings = {
        "seed": settings.get("seed", 42),
        "paths": settings.get("paths", 16384),
        "bump": settings.get("bump", 0.5),
    }
    for key, lower, upper in (("seed", 1, 2**32 - 1), ("paths", 2, MAX_PATHS)):
        if (
            isinstance(settings[key], bool)
            or not isinstance(settings[key], int)
            or not lower <= settings[key] <= upper
        ):
            raise ValidationError(f"{key} must be an integer in [{lower},{upper}]")
    number(settings["bump"], "bump", 0.000001, m["spot"] * 0.2)
    history = request.get("historicalFixings", {})
    if not isinstance(history, dict) or any(
        x not in t["observationDates"] or iso(x, "fixing date") > asof for x in history
    ):
        raise ValidationError(
            "historicalFixings supports contractual dates on or before asOf only"
        )
    past = replay({"terms": t, "fixings": history})
    if not past["terminated"] and any(
        iso(x, "date") <= asof and x not in history for x in t["observationDates"]
    ):
        raise ValidationError("All past/on-asOf observations require historicalFixings")
    settled = request.get("settledThrough")
    if settled is not None and iso(settled, "settledThrough") > asof:
        raise ValidationError("settledThrough cannot follow asOf")
    return {
        "terms": t,
        "market": m,
        "settings": settings,
        "historicalFixings": history,
        "settledThrough": settled,
    }


def ql_date(d: date) -> ql.Date:
    return ql.Date(d.day, d.month, d.year)


def process(m: dict, spot: float) -> ql.BlackScholesMertonProcess:
    today = ql_date(iso(m["asOf"], "asOf"))
    ql.Settings.instance().evaluationDate = today
    dc = ql.Actual365Fixed()
    return ql.BlackScholesMertonProcess(
        ql.QuoteHandle(ql.SimpleQuote(spot)),
        ql.YieldTermStructureHandle(ql.FlatForward(today, float(m["dividend"]), dc)),
        ql.YieldTermStructureHandle(ql.FlatForward(today, float(m["rate"]), dc)),
        ql.BlackVolTermStructureHandle(
            ql.BlackConstantVol(today, ql.NullCalendar(), float(m["volatility"]), dc)
        ),
    )


def path_generator(m: dict, spot: float, future_dates: list[str], seed: int):
    asof = iso(m["asOf"], "asOf")
    grid = ql.TimeGrid(
        [0.0] + [(iso(d, "grid date") - asof).days / 365 for d in future_dates]
    )
    rng = ql.GaussianRandomSequenceGenerator(
        ql.UniformRandomSequenceGenerator(
            len(future_dates), ql.UniformRandomGenerator(seed)
        )
    )
    return ql.GaussianPathGenerator(process(m, spot), grid, rng, False)


def simulate(request: dict, spot: float) -> list[float]:
    t, m, settings = request["terms"], request["market"], request["settings"]
    asof = m["asOf"]
    history = request["historicalFixings"]
    settled = request["settledThrough"]
    future = sorted({d for d in t["observationDates"] + t["paymentDates"] if d > asof})
    generator = path_generator(m, spot, future, settings["seed"]) if future else None
    coupon = coupon_amounts(t) if t["product"] == "AUTOCALLABLE" else []
    # Cache deterministic dates/discount factors outside the path loop.
    times = {
        d: max(0, (iso(d, "paymentDate") - iso(asof, "asOf")).days / 365)
        for d in t["paymentDates"]
    }
    discount = {d: math.exp(-m["rate"] * times[d]) for d in t["paymentDates"]}
    principal_money = (
        money(Decimal(str(t["notional"]))) if t["product"] == "AUTOCALLABLE" else None
    )
    cash_by_multiplier = (
        {}
        if t["product"] == "AUTOCALLABLE"
        else {
            factor: money(
                Decimal(str(t["baseQuantity"]))
                * Decimal(factor)
                * Decimal(str(t["strike"]))
            )
            for factor in (1, t["multiplier"])
        }
    )
    result = []
    for _ in range(settings["paths"]):
        if generator:
            path = generator.next().value()
            simulated = {d: path[j + 1] for j, d in enumerate(future)}
        else:
            simulated = {}
        value = 0.0
        for i, d in enumerate(t["observationDates"]):
            # validate_request guarantees the past prefix is present until termination.
            s = history[d] if d <= asof else simulated[d]
            pay = t["paymentDates"][i]
            include = settled is None or pay > settled
            if t["product"] == "AUTOCALLABLE":
                if include and s >= t["couponBarrier"]:
                    value += coupon[i] * discount[pay]
                called = i >= t["firstCallIndex"] and s >= t["autocallBarrier"]
                if called or i == len(t["observationDates"]) - 1:
                    principal = principal_money
                    if not called and s < t["capitalBarrier"]:
                        principal = money(
                            Decimal(str(t["notional"]))
                            * Decimal(str(s))
                            / Decimal(str(t["initialReference"]))
                        )
                    if include:
                        value += principal * discount[pay]
                    break
            else:
                if s >= t["knockoutBarrier"]:
                    break
                factor = t["multiplier"] if s < t["strike"] else 1
                qty = t["baseQuantity"] * factor
                if include:
                    # Physical delivery: both acquired asset and strike cash are valued at settlement.
                    settlement_spot = simulated[pay] if pay > asof else spot
                    cash = cash_by_multiplier[factor]
                    value += (qty * settlement_spot - cash) * discount[pay]
        result.append(value)
    return result


def mean_stderr(values: list[float]) -> tuple[float, float]:
    return statistics.fmean(values), statistics.stdev(values) / math.sqrt(len(values))


def value(request: dict) -> dict:
    req = validate_request(request)
    m, settings = req["market"], req["settings"]
    spot, bump = m["spot"], settings["bump"]
    base = simulate(req, spot)
    # Reinitialise the same seed on the same grid: common random numbers.
    up, down = simulate(req, spot + bump), simulate(req, spot - bump)
    paired = [(u - d) / (2 * bump) for u, d in zip(up, down)]
    npv, stderr = mean_stderr(base)
    delta, delta_stderr = mean_stderr(paired)
    delta_status = (
        "NOISY"
        if delta_stderr > max(abs(delta) * 0.1, 0.01)
        else "SAMPLING_CHECK_OK_BUMP_CHECK_REQUIRED"
    )
    warnings = [
        "Clean teaching model; excludes calibration smile/skew, credit, funding, collateral and sales costs.",
        "Monte Carlo standard error measures sampling uncertainty, not total model error.",
    ]
    pending = replay({"terms": req["terms"], "fixings": req["historicalFixings"]})[
        "obligations"
    ]
    if any(
        o["paymentDate"] < m["asOf"]
        and (req["settledThrough"] is None or o["paymentDate"] > req["settledThrough"])
        for o in pending
    ):
        warnings.append(
            "Past-due unsettled obligations are assumed payable now; shares marked at current spot; no default/late-interest model."
        )
    provenance = {
        **req,
        "marketHash": digest(m),
        "termsHash": digest(req["terms"]),
        "model": MODEL,
        "payoffCodeHash": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "libraryVersions": {
            "QuantLib": ql.__version__,
            "Python": platform.python_version(),
        },
        "valuationDateConvention": "end-of-day; observations on asOf supplied as historicalFixings",
        "settlementConvention": "settledThrough excludes payments on/before date; null asserts none settled",
    }
    return {
        "status": "APPROXIMATE_CLEAN_MODEL",
        "npv": npv,
        "currency": req["terms"]["currency"],
        "monteCarloStandardError": stderr,
        "confidence95": [npv - 1.96 * stderr, npv + 1.96 * stderr],
        "delta": delta,
        "deltaUnits": f"{req['terms']['currency']} NPV per one {req['terms']['currency']}/share spot unit",
        "deltaStandardError": delta_stderr,
        "deltaStatus": delta_status,
        "warnings": warnings,
        "provenance": provenance,
    }
