#!/usr/bin/env python3
"""Exercise real HTTP journeys and save every request/response; uses standard library only.

Run against an isolated development instance with FAULT_INJECTION=true.
Never runs against an institutional system; all products and issuer offers are fixtures.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
import threading
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--base-url", default="http://localhost:8080")
parser.add_argument("--evidence", type=Path, default=ROOT / "evidence" / "latest")
args = parser.parse_args()
args.evidence.mkdir(parents=True, exist_ok=True)
records = []
lock = threading.Lock()
run_id = str(uuid.uuid4())


def request(method, path, body=None, expected=(200,)):
    correlation = f"journey-{uuid.uuid4()}"
    raw = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(args.base_url + path, data=raw, method=method,
        headers={"Content-Type": "application/json", "X-Correlation-ID": correlation})
    start = datetime.now(timezone.utc).isoformat()
    try:
        with urllib.request.urlopen(req, timeout=60) as res:
            status, data, headers = res.status, res.read(), dict(res.headers)
    except urllib.error.HTTPError as exc:
        status, data, headers = exc.code, exc.read(), dict(exc.headers)
    try:
        value = json.loads(data)
    except json.JSONDecodeError:
        value = data.decode(errors="replace")
    with lock:
        records.append({"startedAt": start, "method": method, "url": args.base_url + path,
            "requestHeaders": {"X-Correlation-ID": correlation}, "input": body,
            "status": status, "responseHeaders": headers, "output": value})
    assert status in expected, f"{method} {path}: expected {expected}, got {status}: {value}"
    return value, status


def post(path, body, expected=(200,)):
    return request("POST", path, body, expected)[0]


def create(product="AUTOCALLABLE"):
    return post("/api/enquiries", {"product": product, "clientId": "client-demo", "operatorId": "staff-demo"})


def prepare(product="AUTOCALLABLE", firm_scenario="OK"):
    enquiry = create(product)
    base = f"/api/enquiries/{enquiry['id']}"
    rfq = post(base + "/rfq", {"scenarios": {}, "deadlineMillis": 200})
    offer = next(o for o in rfq["offers"] if o["status"] == "AVAILABLE")
    post(base + "/selection", {"offerId": offer["id"], "rationale": "Synthetic exercise: aligned terms and explicit issuer exposure reviewed"})
    post(base + "/instruction", {"authorised": True, "reference": "synthetic-authority-" + run_id})
    firm = post(base + "/firm", {"scenario": firm_scenario})
    return enquiry, firm


def value_result(value):
    if "npv" in value:
        return value
    for key in ("result", "valuation", "response"):
        if key in value and isinstance(value[key], dict):
            return value_result(value[key])
    raise AssertionError(f"No valuation result in {value.keys()}")


def main():
    request("GET", "/actuator/health")
    # Invalid input is rejected before a durable enquiry is created.
    post("/api/enquiries", {"product": "UNKNOWN", "clientId": "client-demo", "operatorId": "staff-demo"}, (400,))
    post("/api/enquiries", {"product": "AUTOCALLABLE", "clientId": "not-approved", "operatorId": "staff-demo"}, (400, 403))
    market = json.loads((ROOT / "fixtures/market.json").read_text())
    paths = json.loads((ROOT / "fixtures/lifecycle-paths.json").read_text())
    trades = {}
    valuations = {}
    for product in ("AUTOCALLABLE", "ACCUMULATOR"):
        enquiry = create(product)
        base = f"/api/enquiries/{enquiry['id']}"
        rfq = post(base + "/rfq", {"scenarios": {"Issuer-C": "TIMEOUT"}, "deadlineMillis": 150})
        statuses = {o["issuer"]: o["status"] for o in rfq["offers"]}
        assert statuses["Issuer-A"] == "AVAILABLE" and statuses["Issuer-C"] == "TIMEOUT", statuses
        comparison = request("GET", base + "/comparison")[0]
        assert comparison["basis"] and comparison["offers"]
        # Both possible commercial outcomes are valid; no automatic real-bank preference.
        post(base + "/match", {"outcome": "UNIMPROVED"})
        post(base + "/match", {"outcome": "DECLINED"})
        offer = next(o for o in rfq["offers"] if o["issuer"] == "Issuer-A")
        # An indicative quote cannot be accepted, even if the caller supplies an idempotency key.
        post(base + "/accept", {"offerId": offer["id"], "idempotencyKey": "indicative-" + str(uuid.uuid4())}, (400, 409))
        post(base + "/selection", {"offerId": offer["id"], "rationale": "Identical payoff terms; choose fictional issuer A after considering its separate credit fixture"})
        post(base + "/instruction", {"authorised": True, "reference": "synthetic-client-authority"})
        firm = post(base + "/firm", {"scenario": "OK"})
        val = post(base + "/value", {"market": market, "settings": {"seed": 42, "paths": 16384, "bump": 0.5}, "historicalFixings": {}, "settledThrough": None})
        result = value_result(val)
        assert result["currency"] == "HKD" and result["monteCarloStandardError"] >= 0
        assert result["provenance"]
        valuations[product] = result
        key = "journey-" + str(uuid.uuid4())
        # Inject failure after inserting a trade: the whole transaction must roll back.
        post(base + "/accept", {"offerId": firm["id"], "idempotencyKey": key, "failAfterTrade": True}, (500,))
        trade = post(base + "/accept", {"offerId": firm["id"], "idempotencyKey": key, "failAfterTrade": False})
        repeated = post(base + "/accept", {"offerId": firm["id"], "idempotencyKey": key})
        assert repeated["id"] == trade["id"]
        for _ in range(2):
            replay = post(f"/api/trades/{trade['id']}/lifecycle", {"fixings": paths[product]})
        obs = replay["obligations"]
        expected = 3 if product == "AUTOCALLABLE" else 2
        assert len(obs) == expected, obs
        cash = sorted(round(float(o["cash"]), 2) for o in obs)
        if product == "AUTOCALLABLE":
            assert cash == [1972.60, 2016.44, 100000.00], cash
        else:
            assert cash == [-1800.00, -900.00], cash
            assert sorted(float(o["shares"]) for o in obs) == [10, 20]
        inspect = request("GET", f"/api/trades/{trade['id']}")[0]
        assert len(inspect["outbox"]) == 1, inspect
        assert len(inspect["obligations"]) == expected
        changed_path = dict(paths[product])
        changed_path[next(iter(changed_path))] = 110
        post(f"/api/trades/{trade['id']}/lifecycle", {"fixings": changed_path}, (409,))
        unchanged = request("GET", f"/api/trades/{trade['id']}")[0]
        assert unchanged["obligations"] == inspect["obligations"], unchanged
        assert all(o["state"] == "DETERMINED" for o in inspect["obligations"])
        post(f"/api/trades/{trade['id']}/obligations/advance", {"asOf": "2028-01-01", "settle": False})
        due = request("GET", f"/api/trades/{trade['id']}")[0]
        assert all(o["state"] == "DUE" for o in due["obligations"]), due
        post(f"/api/trades/{trade['id']}/obligations/advance", {"asOf": "2028-01-01", "settle": True})
        settled = request("GET", f"/api/trades/{trade['id']}")[0]
        assert all(o["state"] == "SIMULATED_SETTLED" for o in settled["obligations"]), settled
        trades[product] = {"enquiryId": enquiry["id"], "tradeId": trade["id"], "obligations": expected}

    # Controlled stale/incomparable/expired/error outcomes, no wall-clock sleeps.
    for scenarios, expected_states in (
        ({"Issuer-A": "STALE", "Issuer-B": "INCOMPARABLE", "Issuer-C": "EXPIRED"}, None),
        ({"Issuer-A": "ERROR", "Issuer-B": "DECLINED", "Issuer-C": "UNSUPPORTED"}, {"ERROR", "DECLINED", "UNSUPPORTED"}),
    ):
        enquiry = create()
        base = f"/api/enquiries/{enquiry['id']}"
        rfq = post(base + "/rfq", {"scenarios": scenarios, "deadlineMillis": 150})
        if expected_states:
            assert {o["status"] for o in rfq["offers"]} == expected_states, rfq
        else:
            comp = request("GET", base + "/comparison")[0]
            assert all(o["comparability"] != "COMPARABLE" for o in comp["offers"]), comp
        for offer in rfq["offers"]:
            post(base + "/selection", {"offerId": offer["id"], "rationale": "Must reject"}, (400, 409))
    for firm_scenario in ("EXPIRED", "DECLINED", "STALE"):
        enquiry, firm = prepare(firm_scenario=firm_scenario)
        post(f"/api/enquiries/{enquiry['id']}/accept", {"offerId": firm["id"], "idempotencyKey": "rejected-" + str(uuid.uuid4())}, (400, 409))

    # A real terms revision invalidates the earlier selection, authority and firm offer.
    enquiry, firm = prepare()
    base = f"/api/enquiries/{enquiry['id']}"
    revised = dict(enquiry["terms"])
    revised["termsVersion"] = "note-revised-" + run_id
    revised["couponRate"] = 0.07
    after = post(base + "/revision", {"terms": revised})
    assert after["termsVersion"] == revised["termsVersion"] and after.get("selectedOfferId") is None
    post(base + "/accept", {"offerId": firm["id"], "idempotencyKey": "stale-" + str(uuid.uuid4())}, (400, 409))

    # Two simultaneous HTTP requests with distinct keys contend for the same enquiry.
    enquiry, firm = prepare()
    barrier = threading.Barrier(2)
    keys = ["race-" + str(uuid.uuid4()) for _ in range(2)]
    def contender(key):
        barrier.wait(timeout=5)
        return request("POST", f"/api/enquiries/{enquiry['id']}/accept",
            {"offerId": firm["id"], "idempotencyKey": key}, expected=(200, 409))
    with ThreadPoolExecutor(max_workers=2) as executor:
        outcomes = list(executor.map(contender, keys))
    assert sorted(status for _, status in outcomes) == [200, 409], outcomes
    winner = next(value for value, status in outcomes if status == 200)
    final = request("GET", f"/api/trades/{winner['id']}")[0]
    assert len(final["outbox"]) == 1
    summary = {"runId": run_id, "completedAt": datetime.now(timezone.utc).isoformat(),
        "status": "passed", "journeys": trades,
        "valuationSummaries": {p: {k: v[k] for k in ("npv", "currency", "delta", "deltaStatus", "monteCarloStandardError")} for p, v in valuations.items()},
        "concurrentAcceptanceStatuses": [status for _, status in outcomes], "raceTradeId": winner["id"],
        "boundaries": {"JavaToPostgres": "real", "JavaToPythonHttp": "real", "issuerOffers": "simulated", "executionAndSettlement": "simulated", "eventDelivery": "deferred; committed outbox only"},
        "requests": len(records)}
    (args.evidence / "journey-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


try:
    main()
finally:
    (args.evidence / "http-trace.json").write_text(json.dumps(records, indent=2) + "\n")
