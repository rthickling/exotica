# Synthetic contracts and independent worked paths

Status: assumed synthetic contracts, frozen as `note-v1` and `acc-v1`. These are teaching examples, not Barclays terms or a legal/suitability decision. Currency HKD, one fictional equity `SYNTH_EQ`, initial reference HKD100/share. The fixture JSONs are the authoritative numerical terms. Contractual reference levels stay fixed when current spot is bumped. Any edit requires a new terms version.

## Funded up-trigger note: note-v1

Funded principal/notional is HKD100,000. Par at100% excluding fees is a reference, not an assumption that every offer costs exactly100,000. The workflow records actual firm originalPurchasePrice (issue price plus disclosed fee cash) separately from remaining-cashflow NPV; the synthetic offers may differ, for example100,100 or99,900. Start16 November2026; redemption principalHKD100,000. Observation/payment pairs: 16/18 February 2027; 17/19 May; 16/18 August; 16/18 November. These are explicitly supplied synthetic schedules: payment is two weekdays later; holidays are deliberately ignored. Accrual is actual days/365 from start or preceding observation: 92,90,91,92 days. Annual coupon8%; each period's non-memory coupon is paid iff observed spot **>=70**. Coupon is paid on a call date. No missed coupon accrues forward.

Call eligibility starts at observation index1, the second observation. If eligible spot **>=100**, pay that period's conditional coupon plus principal once on that observation's payment date and cancel all later obligations. No call on first observation even above100. If uncalled, final principal is100,000 when final spot **>=60**, otherwise100,000×final spot/100. Coupon and maturity capital are separate tests. The final observation applies coupon then early-call-or-maturity exactly once. There is no continuous barrier, memory, basket, quanto or issuer-credit model.

The following expected obligations were calculated arithmetically before writing payoff code. Values shown to cents; deterministic replay retains exact decimal arithmetic then rounds contractual cash half-up to cents. Coupon amounts are2016.44,1972.60,1994.52,2016.44.

* `[100,100,10,10]`: 18Feb coupon2016.44; 19May coupon1972.60 and redemption100,000. Later fixings cannot create obligations. First100 cannot call; second equality does call.
* `[70,69.99,70,60]`: coupon2016.44 on18Feb; no May coupon; coupon1994.52 on18Aug; final no coupon and redemption100,000 on18Nov. Capital equality is protected; coupon equality pays.
* `[69,69,69,59]`: no coupons; redemption59,000 on18Nov. Loss below barrier applies to full reference ratio, not just amount below60.
* `[99,99,99,99]`: all four coupons and100,000 on18Nov. The four rounded coupons total8000.00.

## Physical equity accumulator: acc-v1

No funded principal. Fixing/settlement pairs:16/18 December2026;18/20 January2027;16/18 February;16/18 March. Each live fixing first checks spot **>=110**: terminate without a purchase for that fixing. There is no guarantee period. Below110 accrue10 shares at strikeHKD90 each; if spot **<90**, accrue20 shares; equality90 accrues10. Settlement is physical: receive positive shares and pay negative cash at the fixed strike. Cash and share legs stay linked in one purchase obligation. No sale of the shares is assumed after settlement. Maximum contractual purchases are80 shares, cashHKD7200, across four doubled observations; this is potential cash funding, not invested principal or maximum investment loss.

Independent expected cases:

* `[90,89,110,1]`:18Dec +10 shares/-900 cash;20Jan +20/-1800; KO16Feb creates no purchase and no later obligation. Equality90 uses base quantity; equality110 knocks out before accrual.
* `[80,80,80,80]`: each settlement+20 shares/-1800 cash; total+80/-7200. At fixed settlement spot80 the economic delivery-minus-cash value is-800 undiscounted, not-7200.
* `[110,1,1,1]`: no obligations, terminated16Dec.
* `[100,100,100,100]`: each+10/-900, total+40/-3600; mark at100 would produce+400 undiscounted.

Repeated replay returns the same event keys; database uniqueness must enforce processing idempotency. Replay determines obligations, not whether money or shares have settled. Due/settled states belong to workflow.

## Library contract mapping

The payoff rules above are **custom code** in `pricer/engine.py`; QuantLib supplies Black–Scholes dynamics, curves and random paths, not these contract definitions. This resolves product-name ambiguity by making each threshold and settlement rule explicit. ORE's documented `Autocallable_01` is down-trigger and is not substituted for this funded up-trigger note. ORE accumulator conventions were inspected but not claimed to match this physical contract. See `valuation.md` for exact sources, feasibility decision and executed checks.
