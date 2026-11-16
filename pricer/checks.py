"""Retain exact input/output and numerical evidence. Run with `just checks`.

Tolerances are declared before results: vanilla <=4SE+1e-8; fresh-repeat
NPV/delta abs1e-9; path-count compatibility <=4sqrt(SE1²+SE2²). Delta bump
compatibility <=4sqrt(DSE1²+DSE2²)+max(.01,.02*|baseline delta|).
Failure of delta stability marks the Greek unreliable; it does not change payoff.
"""

import copy
import json
import math
import os
import platform
import subprocess
import sys
from pathlib import Path

from engine import mean_stderr, path_generator, replay, value
from test_engine import request

DEST = Path(__file__).resolve().parent / "evidence"
DEST.mkdir(exist_ok=True)


def save(name, obj):
    (DEST / name).write_text(json.dumps(obj, indent=2, allow_nan=False) + "\n")


def main():
    numerical = {
        "environment": {
            "python": platform.python_version(),
            "platform": platform.platform(),
        },
        "command": "just checks",
        "tolerances": {
            "vanilla": "absolute error <= 4*MC_SE + 1e-8",
            "freshRepeatAbsolute": 1e-9,
            "pathCounts": "difference <=4*sqrt(SE_low^2+SE_high^2)",
            "deltaPathCounts": "difference <=4*sqrt(DSE_low^2+DSE_high^2)+max(0.01,0.02*abs(delta_high))",
            "deltaBumps": "difference <=4*sqrt(DSE_a^2+DSE_b^2)+max(0.01,0.02*abs(delta_baseline))",
        },
        "products": {},
    }
    for name in ("note-v1", "acc-v1"):
        req = request(name, paths=65536)
        result = value(req)
        save(name + "-request.json", req)
        save(name + "-response.json", result)
        env = {**os.environ, "PYTHONPATH": str(Path(__file__).resolve().parent)}
        repeat = json.loads(
            subprocess.check_output(
                [
                    sys.executable,
                    "-c",
                    "import json,sys; from engine import value; print(json.dumps(value(json.load(sys.stdin))))",
                ],
                input=json.dumps(req).encode(),
                env=env,
            )
        )
        repeat_ok = all(abs(result[k] - repeat[k]) <= 1e-9 for k in ("npv", "delta"))
        if not repeat_ok:
            raise AssertionError("Fresh process repeat outside declared tolerance")
        save(name + "-fresh-repeat.json", repeat)
        counts = []
        for n in (4096, 16384, 65536):
            r = copy.deepcopy(req)
            r["settings"]["paths"] = n
            v = result if n == 65536 else value(r)
            counts.append(
                {
                    k: v[k]
                    for k in (
                        "npv",
                        "monteCarloStandardError",
                        "delta",
                        "deltaStandardError",
                        "deltaStatus",
                    )
                }
                | {"paths": n}
            )
        comparisons = []
        for low in counts[:-1]:
            high = counts[-1]
            limit = 4 * math.hypot(
                low["monteCarloStandardError"], high["monteCarloStandardError"]
            )
            difference = abs(low["npv"] - high["npv"])
            comparisons.append(
                {
                    "lowPaths": low["paths"],
                    "highPaths": high["paths"],
                    "difference": difference,
                    "limit": limit,
                    "pass": difference <= limit,
                }
            )
        delta_comparisons = []
        for low in counts[:-1]:
            high = counts[-1]
            limit = 4 * math.hypot(
                low["deltaStandardError"], high["deltaStandardError"]
            ) + max(0.01, 0.02 * abs(high["delta"]))
            difference = abs(low["delta"] - high["delta"])
            delta_comparisons.append(
                {
                    "lowPaths": low["paths"],
                    "highPaths": high["paths"],
                    "difference": difference,
                    "limit": limit,
                    "pass": difference <= limit,
                }
            )
        bumps = []
        for bump in (0.25, 0.5, 1.0):
            r = copy.deepcopy(req)
            r["settings"]["bump"] = bump
            v = result if bump == 0.5 else value(r)
            bumps.append(
                {
                    "bump": bump,
                    **{k: v[k] for k in ("delta", "deltaStandardError", "deltaStatus")},
                }
            )
        baseline = bumps[1]
        stable = all(
            abs(b["delta"] - baseline["delta"])
            <= 4 * math.hypot(b["deltaStandardError"], baseline["deltaStandardError"])
            + max(0.01, 0.02 * abs(baseline["delta"]))
            for b in bumps
        )
        reliable = stable and all(b["deltaStatus"] != "NOISY" for b in bumps)
        numerical["products"][name] = {
            "freshRepeatPass": repeat_ok,
            "pathCounts": counts,
            "pathCountComparisons": comparisons,
            "deltaPathCountComparisons": delta_comparisons,
            "deltaBumps": bumps,
            "deltaBumpCompatible": stable,
            "deltaAssessment": "SAMPLING_AND_BUMP_CHECKS_OK"
            if reliable
            else "UNRELIABLE: increase paths and revisit bump; do not quote precision",
            "deltaReferenceLevelsHeldFixed": True,
        }
        if not reliable:
            refined = []
            for bump in (0.25, 0.5, 1.0):
                rr = copy.deepcopy(req)
                rr["settings"].update(paths=262144, bump=bump)
                rv = value(rr)
                refined.append(
                    {
                        "paths": 262144,
                        "bump": bump,
                        **{
                            k: rv[k]
                            for k in (
                                "npv",
                                "monteCarloStandardError",
                                "delta",
                                "deltaStandardError",
                                "deltaStatus",
                            )
                        },
                    }
                )
                if bump == 0.5:
                    save(name + "-refinement-request.json", rr)
                    save(name + "-refinement-response.json", rv)
            anchor = refined[1]
            refinement_stable = all(
                abs(b["delta"] - anchor["delta"])
                <= 4 * math.hypot(b["deltaStandardError"], anchor["deltaStandardError"])
                + max(0.01, 0.02 * abs(anchor["delta"]))
                for b in refined
            )
            path_limit = 4 * math.hypot(
                anchor["deltaStandardError"], result["deltaStandardError"]
            ) + max(0.01, 0.02 * abs(anchor["delta"]))
            path_ok = abs(anchor["delta"] - result["delta"]) <= path_limit
            refinement_reliable = (
                refinement_stable
                and path_ok
                and all(b["deltaStatus"] != "NOISY" for b in refined)
            )
            numerical["products"][name]["refinement"] = {
                "reason": "Predeclared response to NOISY: increase to maximum262144 and repeat every bump once",
                "results": refined,
                "bumpCompatible": refinement_stable,
                "deltaPathCountComparison": {
                    "lowPaths": 65536,
                    "highPaths": 262144,
                    "difference": abs(anchor["delta"] - result["delta"]),
                    "limit": path_limit,
                    "pass": path_ok,
                },
                "assessment": "SAMPLING_AND_BUMP_CHECKS_OK_FOR_TEACHING"
                if refinement_reliable
                else "UNRELIABLE: limitation retained; do not quote precise delta",
            }
        levels = [100, 100, 10, 10] if name == "note-v1" else [90, 89, 110, 1]
        replay_req = {
            "terms": req["terms"],
            "fixings": dict(zip(req["terms"]["observationDates"], levels)),
        }
        save(name + "-replay-request.json", replay_req)
        save(name + "-replay-response.json", replay(replay_req))
    m = request()["market"]
    g = path_generator(m, 100, ["2027-11-16"], 42)
    pv = [math.exp(-0.03) * max(g.next().value()[1] - 100, 0) for _ in range(65536)]
    observed, se = mean_stderr(pv)
    N = lambda x: 0.5 * (1 + math.erf(x / math.sqrt(2)))
    d1 = (0.03 - 0.01 + 0.5 * 0.25**2) / 0.25
    d2 = d1 - 0.25
    analytic = 100 * math.exp(-0.01) * N(d1) - 100 * math.exp(-0.03) * N(d2)
    numerical["vanillaBenchmark"] = {
        "modelInputs": m,
        "strike": 100,
        "maturity": "2027-11-16",
        "paths": 65536,
        "seed": 42,
        "analytic": analytic,
        "simulated": observed,
        "standardError": se,
        "absoluteError": abs(analytic - observed),
        "tolerance": 4 * se + 1e-8,
        "pass": abs(analytic - observed) <= 4 * se + 1e-8,
    }
    save("numerical-checks.json", numerical)
    print(json.dumps(numerical, indent=2))
    if not numerical["vanillaBenchmark"]["pass"] or not all(
        all(x["pass"] for x in p["pathCountComparisons"])
        for p in numerical["products"].values()
    ):
        raise AssertionError("Numerical check failed; inspect retained evidence")


if __name__ == "__main__":
    main()
