# Web UI user guide

The web workspace supports the synthetic structured-product sales and lifecycle workflow. Client eligibility, issuer offers, instruction authority, booking and settlement are simulated. Valuation uses the real Java–Python HTTP boundary and QuantLib calculations with synthetic inputs.

## Start and reopen records

With Docker Compose v2+ and `just` 1.56+ installed, run from the repository root:

```sh
just up
```

Open [the workspace](http://localhost:4200/workspace). All language runtimes and build/test tools run in containers. The same commands are used on Linux, macOS and native Windows PowerShell; Windows Docker Desktop must use Linux containers. See [platform support](../README.md#platform-support) for verification limits.

Keep the enquiry and trade IDs displayed by the UI. PostgreSQL retains records after `just down`. **New enquiry** changes the current workspace without deleting server records. Reload restores this browser's last IDs. To reopen another record, paste its UUID and choose **Open enquiry** or **Open trade**. Reopen by trade ID for lifecycle management.

`just reset` deliberately deletes the application's synthetic database. It is not needed to start another enquiry.

## Autocallable note

### Create, compare and book

1. Choose **Autocallable note**, leave the demonstration identities as `client-demo` and `staff-demo`, and click **Create enquiry**. The saved contract uses frozen `note-v1` terms; no trade has been booked.
2. On **Offers & booking**, choose **All issuers respond** and click **Request indicative offers**. Compare issue price, fees, total upfront cost, expiry and comparability. A lower cost does not establish suitability; issuer exposure remains separate.
3. Enter a **Selection rationale** and select a usable offer. The comparison requires identical terms, size and currency. Optional matching adds a simulated response without automatically selecting it.
4. Enter an **Instruction reference**, confirm simulated client authority and click **Record instruction**.
5. With **Firm reply** set to **Available**, click **Get firm offer**. This is distinct from the indicative offer. If it expires, obtain a fresh valid reply before proceeding.
6. Check the simulated-booking confirmation and click **Book simulated trade**. Save the trade ID. The enquiry becomes **Simulated booked** and cannot book a second trade. No live order is sent.

If a booking result is uncertain, use **Retry booking** rather than starting a replacement booking. The browser retains the original offer and idempotency key, including across reloads. New-enquiry and record-switch controls remain blocked while the booking is pending. Do not clear browser storage while recovering an uncertain result.

### Value the contract

1. Open **Valuation & risk**. The supplied synthetic snapshot uses date **2026-11-16**, spot **100**, rate **0.03**, dividend **0.01** and volatility **0.25**. Rates and volatility are annual fractions, so `0.25` means 25%.
2. Keep the default simulation settings of **16384** paths, seed **42** and absolute spot bump **0.5**, or change them under **Simulation settings**. Click **Calculate valuation**.
3. Review investor NPV, delta, Monte Carlo standard error, delta quality and saved provenance. A noisy delta calls for more paths and bump-sensitivity checks. Sampling uncertainty does not include total model error. These are teaching estimates, not dealer prices.

This page supports initial-date valuation. Historical fixings and settled-through valuation scenarios remain available through the API.

### Process observations and obligations

1. Open **Trade lifecycle** and click **Load sample path**. The four note fixings are **100, 100, 10, 10**. Click **Process observations**.
2. Expect three obligations: a **HKD 2,016.44** coupon due **2027-02-18**, then a **HKD 1,972.60** coupon and **HKD 100,000** redemption due **2027-05-19**. The first observation cannot call the note; equality at the second observation's autocall barrier causes early redemption. Later fixings create no additional obligations.
3. Process the identical path again. The same obligations remain without duplicates. Changing an already recorded fixing is rejected; historical observations are immutable.
4. Set **Business date** to **2027-05-19** and click **Mark due**. The three obligations become **Due**.
5. Confirm simulated settlement and click **Simulate settlement**. They become **Simulated settled**. No actual payment is made.
6. On **Activity & records**, refresh the activity and expand record details to inspect saved events and correlation references. The booking outbox is **Committed · not published**: database commit does not mean Kafka delivery.

## Equity accumulator

1. Start a new enquiry for **Equity accumulator**. Repeat the comparison, selection, instruction, firm-offer and booking steps above. Save the new trade ID.
2. In **Trade lifecycle**, load the sample fixings **90, 89, 110, 1** and process them.
3. Expect two purchase obligations: **+10 shares / −HKD 900** due **2026-12-18**, then **+20 shares / −HKD 1,800** due **2027-01-20**. Equality at strike uses base quantity; a below-strike fixing doubles quantity. Equality at knockout 110 terminates before accrual, so no further purchases arise.
4. Mark the obligations due using **2027-01-20**, then confirm simulated settlement. Identical replay must not duplicate them.

The displayed **HKD 7,200 maximum purchase exposure** is potential contractual funding, not funded principal or NPV. The purchased shares also have value; negative purchase cashflow alone is not the economic loss. Full contractual examples are in [terms and payoffs](terms-and-payoffs.md).

## Controlled failure scenarios

* **Issuer B times out** produces a partial RFQ; other valid replies can still be selected.
* **Expired / stale / incomparable** produces unavailable rows. Earlier replies remain visible and may still be valid; requesting another RFQ does not erase prior offers.
* Unavailable or stale firm offers cannot be booked through the UI. The backend independently checks authority, terms, expiry, version and uniqueness.
* Invalid demonstration identities are rejected. Errors include correlation references where available.
* A failed record refresh keeps the previous coherent workspace instead of mixing data from different enquiries.

The browser is a usability layer, not the authority for permissions, booking or payoff rules. See the [UI implementation and test documentation](../ui/README.md) and the [API contract](../app/README.md) for technical details.
