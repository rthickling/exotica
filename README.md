# exotica
Demonstration Exotic and Structured Product Sales and Management System

An Angular 21 sales workspace backed by a small Java 21/Spring application, PostgreSQL and an independent Python/QuantLib pricer. It covers two frozen synthetic contracts: a funded up-trigger autocallable note and a physical equity accumulator. Staff can request and compare indicative offers, record simulated authority, obtain a distinct firm offer, book once, value the contract and replay dated obligations.

All clients, staff permissions, issuer offers, commercial matching, execution and settlement are **simulated**. The model calculation and the Java–Python HTTP and PostgreSQL boundaries are real. The system requires no institutional credentials. A committed outbox is retained; Kafka/Avro delivery, live connectivity and production identity/issuance/confirmation/custody systems are deferred.

## Start the web UI

Install **Docker with Compose v2+** and **`just` 1.56+**, then run from this directory:

```sh
just up
```

Open [the sales workspace](http://localhost:4200/workspace). Create an enquiry using `client-demo` and `staff-demo`, compare indicative offers, record a rationale and simulated instruction, obtain a firm offer, then explicitly confirm the simulated booking. The five pages cover enquiry, offers/booking, valuation, lifecycle and activity. You can create another enquiry or reopen an enquiry/trade by ID; this browser remembers the last IDs. See [UI details](ui/README.md) and the [UI user guide](docs/ui-guide.md).

The UI uses actual HTTP requests and persisted records. Pricing calls the real QuantLib-backed service; offers, authority and settlement are simulated. The UI supports initial-date valuation with numerical market inputs and sampling uncertainty, immutable fixing replay, due/settled progression and inspection of committed audit/outbox records. Outbox delivery is not implemented.

Node 24 compiles the Angular application inside Docker; Nginx serves it on container port 80, published at localhost:4200, and proxies `/api/` and `/actuator/` to `app:8080`. No host Node, npm, Angular CLI or browser-test installation is required. PostgreSQL, app, pricer and UI are the four runtime services. The one-off tools/test services are opt-in.

## Reproduce the API journeys

The only installed requirements are **Docker with Compose v2+** and **`just` 1.56+**. Java, Gradle, PostgreSQL, Python, QuantLib, Node/npm, the Angular compiler and Playwright all run in containers; no language runtimes or package managers are needed on the host. Use a Linux/macOS terminal, native Windows PowerShell, or WSL2. On Windows, Docker Desktop must be running Linux containers; Windows PowerShell 5.1 is sufficient, with no Git Bash or PowerShell 7 installation required. The first build needs public access to Docker Hub, Gradle distributions/Plugin Portal, Maven Central, PyPI, npm and Microsoft Container Registry. Later builds can reuse caches. The checked environment and retained results are in `evidence/verification.md`. The UI, application and pricer listen only on localhost ports 4200, 8080 and 8090 respectively. PostgreSQL is private to the Compose network.

From this directory:

```bash
just up
just journeys
just inspect-db
```

The journey script creates fresh synthetic enquiries. It saves every input, response, status and correlation ID in `evidence/latest/http-trace.json` and a summary in `journey-summary.json`. Expected result: `"status": "passed"`; one note journey with three obligations, one accumulator journey with two purchases, and one winning trade from two concurrent conflicting requests. Repeated runs create additional independent enquiries; they do not overwrite prior trades.

The script also exercises invalid eligibility/product input, partial RFQ timeout, declined/error/unsupported replies, incomparable/stale/expired offers, rejection of indicative acceptance, terms revision, rollback after a flushed trade insert, duplicate acceptance, changed-fixing rejection, repeated lifecycle processing and determined → due → simulated-settled transitions. Injected rollback deliberately returns HTTP 500. The conflicting acceptance deliberately returns HTTP 409. Those are expected controls, not successful executions.

```bash
just health
just logs
just down
```

`just down` preserves the synthetic database. For an intentionally empty start, `just reset` deletes **this Compose project's synthetic database**; then run the startup and journey commands again. Do not use that reset against data you want to retain. Development fault injection is enabled in this local Compose file for the rollback exercise and defaults off in the application itself.

Run `just` to list tasks; `just compose <arguments>` forwards other Compose commands. The default is `docker compose`; set `DOCKER_COMPOSE_BIN` to the absolute path of a standalone Compose v2+ binary if needed. The `app`, `pricer` and `ui` directories also have justfiles: run `just build`, `just test` or `just run` there. All component recipes use containers too. `just journeys` starts the stack automatically before running the request script inside the Compose network.

## Platform support

The shared commands target Linux/macOS terminals, native Windows PowerShell and WSL2. Real execution evidence is from Linux; Windows argument forwarding was previously probed, which does not establish native Windows execution. Native macOS/Windows full-stack verification remains outstanding. UI browser tests run Chromium inside a Linux container, rather than native host browsers. Run `just`, not an OS-specific entry point. Windows checkouts should use the committed `.gitattributes` rules: container scripts require LF line endings. The Gradle Wrapper is invoked through `sh` inside the JDK container, so Windows executable-bit handling does not affect it.

Shared workflows live in the root and component justfiles. `tasks/platform.just` imports `tasks/unix.just` and `tasks/windows.just`; platform attributes select the shell, user IDs and Compose adapter. Variadic recipes use script arguments instead of interpolating user input into shell commands. `tasks/windows.ps1` only launches native processes with preserved arguments and exit codes. It contains no build or test logic. New workflows should remain shared and use these adapters for argument forwarding.

## Inspect and change the system

`app/README.md` documents exact endpoints and DTOs. Begin with `GET /api/templates`, then `POST /api/enquiries`; `scripts/journeys.py` provides complete executable requests and the retained HTTP trace provides concrete IDs and responses. Use a new `termsVersion` for a change to contractual terms. Revision preserves the earlier terms and invalidates the enquiry's old offers/authority/selection. Historical fixings already accepted for a trade cannot change.

Main source paths:

* `app/src/main/java/lab/ApiController.java`: validated immutable request records and external API.
* `WorkflowService.java`: RFQ concurrency, comparison, selection/authority, transactional booking, valuation recording and lifecycle persistence.
* `IssuerGateway.java`: interchangeable gateway plus controlled synthetic offers. Quotes compare upfront cost for otherwise fixed payoffs; they are not generated by the valuation model.
* `PricingGateway.java`: actual HTTP request, error and deadline handling.
* `Persistence.java` and `app/src/main/resources/db/migration/V1__core.sql`: JPA mappings, version column and database constraints, including trade/idempotency and obligation/fixing uniqueness.
* `ProductTerms.java`: product-specific record views checked when loading fixtures; complete terms are retained as immutable versioned JSON.
* `pricer/engine.py`: explicit contractual rules and QuantLib Black–Scholes paths. `pricer/server.py` serializes calculations within a worker to isolate QuantLib's valuation-date state.

JPA entities stay behind API mappings. Open-session-in-view is off. Booking enters `WorkflowService.accept` through a Spring-managed call; trade, audit and outbox share its transaction. The unique enquiry constraint prevents a second trade even when concurrent attempts have different keys. An idempotency key binds the enquiry and firm offer; conflicting reuse is rejected.


## Products and valuation

Read `docs/terms-and-payoffs.md` alongside `fixtures/terms/*.json`. It contains independently worked dated paths, threshold equality, ordering and termination rules. The accumulator's HKD7,200 maximum contractual purchase exposure is distinct from the note's HKD100,000 principal. Cash paid for shares is not the accumulator's NPV: the acquired asset also has value.

`fixtures/market.json` is an explicit teaching snapshot, not live or calibrated market data. All rates/volatility are annual fractions; equity spot and strike are currency per share. Deterministic replay applies contractual rules to one supplied path. Valuation discounts the remaining investor cash/share value over simulated paths. Purchase price/fees are recorded separately. The model omits smile/skew, stochastic rates, credit/funding, collateral and commercial costs.

`docs/valuation.md` explains conventions, sources, provenance, numerical tolerances and results. `pricer/evidence/` retains complete requests/responses, fresh-process repeats, path-count checks, independent analytic vanilla benchmark and common-random-number delta bump checks. At the baseline path count the note's delta is flagged noisy; a separately retained higher-path calculation demonstrates the prescribed remedy. Sampling uncertainty is not total model error. Quote prices are independent workflow fixtures, not a benchmark validating the model.

Snapshot IDs describe the submitted fixture; the pricer computes a canonical hash over the actual numerical market object and includes it in immutable valuation provenance. The workflow's terms hash identifies its serialized terms, while the pricer's canonical terms hash identifies its own numerical input representation. They use different encodings; do not compare those two hashes as if they were the same identifier. Full inputs are saved to remove ambiguity.

## Build and test

From the repository root:

```sh
just build           # Build application, pricer and UI images; compile/type-check Angular
just test            # Run Python, Java/PostgreSQL and containerized UI browser tests
just test-ui         # Start the stack and run Playwright Chromium tests inside Docker
just journeys        # Run the actual Java–Python–PostgreSQL HTTP journeys
```

For focused work:

```sh
just test-pricer
just test-app
just checks          # Regenerate pricer/evidence numerical results
just build-app       # Produce app/build/libs/structured-products-lab-1.0.0.jar
just test-db-down    # Remove the isolated test database when finished
```

`just test-app` (also available as `just test-integration`) starts a separate PostgreSQL container, waits until healthy and runs the complete Java suite (eight tests in the retained backend verification). Its database is private to the test Compose network, has no published port and never uses the application database. That retained suite has five tests inspecting real committed PostgreSQL rows, including competing acceptance transactions; its three HTTP adapter tests use a loopback stub. The journeys establish the actual Python boundary separately. Tests deliberately insert synthetic data; `just test-db-down` deletes that test project's database volume.

The JDK container uses the checksum-pinned Gradle Wrapper and caches downloads in `.gradle/`. The JAR, JUnit XML and HTML reports appear under `app/build/`; reports are at `app/build/reports/tests/test/index.html`. Python evidence, journey traces and source checkpoints are also written into the checkout. On Linux/macOS (including WSL), container tooling uses the invoking user's ID so generated files stay editable. Native Windows uses Docker Desktop's bind-mount ownership mapping. Subsequent runs reuse Docker and Gradle caches.

`just test-ui` uses Playwright 1.64.1 and its matching browser image against `http://ui` inside Compose. Its configured HTML report, JUnit XML and failure artifacts are under `evidence/ui/`; see [UI verification details](ui/README.md#verification). Browser test counts and pass status must come from the generated report for the current source, not the older backend evidence. The Angular production build has been confirmed with strict template checks (about 375 kB output / 97 kB estimated transfer). Browser execution remains pending; no UI browser pass is claimed here.

`just down` stops the application project; `just test-db-down` stops the separate test project. `just reset` deletes only the application project's synthetic database. No host networking or host database is used.

`docs/decisions.md` links primary version-specific framework/product sources and records limitations. `evidence/checkpoint.json`, generated by `just checkpoint`, identifies the delivered source by SHA256. Recompute it after changes; a passing result applies to its tested checkpoint.

## GitHub Actions

[`.github/workflows/ci-cd.yml`](.github/workflows/ci-cd.yml) runs on pull requests, pushes to `main`, `v*` tags and manual dispatch. CI runs `just ci`: all Python, Java/PostgreSQL and Docker-hosted UI browser tests, the real HTTP journeys, health checks, numerical checks and a source checkpoint. It uploads test reports, journey/numerical evidence and container logs for 14 days, even after a failure, then stops the containers. CI uses an Ubuntu runner; it does not establish native Windows/macOS execution.

After successful verification, pushes to `main` and `v*` tags publish all three images to GitHub Container Registry:

* `ghcr.io/rthickling/exotica-app`
* `ghcr.io/rthickling/exotica-pricer`
* `ghcr.io/rthickling/exotica-ui`

Each image supports Linux AMD64 and ARM64. Tags include `sha-<full-commit-sha>`, `latest` for `main`, and the pushed version tag (for example `v1.0.0`). Use a matching commit tag or image digest for reproducible delivery. Pull requests and manual verification runs never publish images. ARM64 images are built, but the integration suite currently runs on AMD64.

No custom registry secrets are needed: publishing uses GitHub's automatic `GITHUB_TOKEN`, with package-write permission only in the publishing jobs. Repository/organization policies must allow Actions to create packages; existing packages must grant this repository Actions access. Package visibility is controlled separately in GitHub Packages. Third-party actions are pinned to commit IDs, and Dependabot proposes weekly action updates.

Delivery currently ends at publishing container images. A running deployment requires a hosting destination and its configuration; this workflow does not provision a server or deploy the demonstration application. Push these files to GitHub to start the first run. You can run the same checks locally with `just ci`.
