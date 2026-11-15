# Sales workspace UI

A simple Angular 21 standalone application for the synthetic structured-product workflow. It keeps one current enquiry in a root-provided `Workspace` service and reads authoritative records from Spring. The staff badge and client instruction are demonstration inputs, not authentication.

## Run

From the repository root, with Docker Compose v2+ and `just` 1.56+ installed:

```sh
just up
```

Open [localhost:4200](http://localhost:4200/workspace). No host Node, npm or Angular CLI is required. Linux, macOS and native Windows PowerShell use the root `just` commands; Windows needs Docker Desktop in Linux-container mode. Actual execution evidence is Linux only. A previous Windows forwarding probe is not native Windows full-stack verification; container Chromium tests do not establish native browser compatibility.

The Docker build uses Node 24, `npm ci`, the committed lockfile and Angular's production compiler. The final image is Nginx, listening on port 80 and mapped to host localhost:4200. `/api/` and `/actuator/` proxy to `app:8080`, so browser requests remain on the same origin. Other application routes fall back to `index.html`, allowing route refreshes. Static market and lifecycle fixtures are copied from the repository during the build. Node is a build dependency, not a runtime server. The component `ui/justfile` also provides `just build`, `just test` and `just run` from `ui/`, forwarding to the shared root workflows.

## Pages and workflow

* [Enquiry workspace](http://localhost:4200/workspace): create an autocallable note or accumulator using `client-demo` / `staff-demo`; inspect frozen terms; reopen an enquiry or trade using its saved UUID. **New enquiry** clears the current workspace without deleting server records.
* [Offers & booking](http://localhost:4200/offers): request all responses, an Issuer B timeout, or expired/stale/incomparable replies. Compare equivalent terms, size and currency, enter a selection rationale, select a usable offer and record simulated client authority. Optional matching adds a synthetic response. Request an available, expired, declined or stale firm reply; booking requires a separate confirmation checkbox. The server checks validity, eligibility, state, terms, version and uniqueness regardless of button state.
* [Valuation & risk](http://localhost:4200/valuation): submit an explicit synthetic market snapshot and simulation settings to the actual Python/QuantLib boundary. Rates and volatility are annual fractions (`0.25` means 25%). Results show investor NPV, delta, Monte Carlo standard error, delta quality and provenance. This UI supports initial-date valuation; historical fixings and settled-through valuation inputs remain API features. Sampling uncertainty excludes model error, issuer credit, calibration and commercial costs.
* [Trade lifecycle](http://localhost:4200/lifecycle): use a booked or reopened trade, load the sample dated path and process observations. Recorded fixings are immutable. Replaying identical inputs must not duplicate obligations. **Mark due** uses a controlled business date; **Simulate settlement** requires confirmation and records a simulated state change, not payment or share delivery.
* [Activity & records](http://localhost:4200/activity): inspect persisted enquiry events, correlation references and booking outbox records. Outbox records committed with booking; Kafka publication is not implemented.

The browser stores the last enquiry/trade IDs and a pending booking tuple in `localStorage` under `exotica.session.v1`. A network or server error can hide a successful booking commit. **Retry booking** resends the exact saved enquiry, firm offer, idempotency key and expected version, including after a reload. Record-switch and new-enquiry controls are blocked until the pending booking is resolved. Do not clear browser storage or create a replacement booking to resolve an uncertain result. A definite HTTP 4xx clears the pending tuple; normal eligibility/version rules still apply. If storage is unavailable the active in-memory workflow works, but pending recovery across reloads cannot be relied upon. Stored IDs/keys are not credentials or permission checks. Reopen by trade ID if opening a booked enquiry alone does not load its trade.

## Source map

* [`src/main.ts`](src/main.ts): standalone bootstrap, router and HTTP providers, correlation interceptor.
* [`src/app/app.ts`](src/app/app.ts), [`src/app/routes.ts`](src/app/routes.ts): shared navigation, status/error display and five routes.
* [`src/app/store.ts`](src/app/store.ts): singleton state, writable/computed signals, asynchronous commands, authoritative reloads and booking recovery.
* [`src/app/api.ts`](src/app/api.ts), [`src/app/models.ts`](src/app/models.ts): typed DTOs and HTTP boundary; RxJS `firstValueFrom` converts one-response `HttpClient` observables to promises. Types do not validate runtime JSON.
* [`src/app/pages/`](src/app/pages/): standalone page components and reactive forms, including a typed fixing `FormArray`.
* [`tsconfig.json`](tsconfig.json), [`angular.json`](angular.json): strict TypeScript/templates and production build budgets.
* [`Dockerfile`](Dockerfile), [`nginx.conf`](nginx.conf), [`playwright.config.ts`](playwright.config.ts): container compilation, hosting/proxy and browser verification.

## Verification

Run these from the repository root:

```sh
just build
just test-ui
just test
just ci
```

`test-ui` starts the healthy stack and runs Playwright 1.64.1 with Chromium entirely inside the matching Docker image against `http://ui`. The test stage installs the package with `npm ci`; the image supplies browser binaries and system libraries. Keep the package and image versions aligned, as described in the [official Playwright Docker guide](https://playwright.dev/docs/docker). This is one configured browser, not a Firefox/WebKit or native OS test matrix.

`playwright.config.ts` configures one worker, no automatic retries, a 1440×1000 viewport, HTML at `evidence/ui/report/index.html`, JUnit XML at `evidence/ui/results.xml` and failure screenshots/traces under `evidence/ui/test-results/`. Report paths are relative to the repository root on the host. `test` includes Python, Java/PostgreSQL and UI tests; `ci` adds HTTP journeys, health checks, numerical checks and source hashes. GitHub Actions retains the evidence and publishes app, pricer and UI images only after verification on eligible pushes. Verification on 15 November 2026: `just ci` passed on Linux AMD64 with **14 Python tests, 8 Java tests (none skipped), 10 Chromium browser scenarios, 91 API journey requests and numerical checks**. Browser scenarios cover desktop/narrow layouts, note valuation/lifecycle and reload, accumulator fixing immutability and reopening by ID, partial timeout, invalid indicative/firm offers, validation references, optional matching, atomic record refresh after an HTTP failure, and lost-response booking recovery. The lost-response test lets the real backend commit before aborting the browser response, then confirms the same saved key returns the same trade with one booking audit event. Desktop (1440×1000) and narrow-screen (390×844) screenshots were inspected. Production compilation passed strict TypeScript and Angular template checks. Reports and screenshots are in `evidence/ui/`; the retained complete run is `evidence/ui-ci-verified.log`.

Angular 21, TypeScript 5.9, RxJS 7.8 and Node 24 are consistent with the [official Angular compatibility table](https://angular.dev/reference/versions). Exact dependency patches and container digests are recorded in the package lockfile and Dockerfile.

For a reproducible product walkthrough, use the [UI user guide](../docs/ui-guide.md). No live offers, real authentication, production authorization, payment settlement or event publisher is provided.
