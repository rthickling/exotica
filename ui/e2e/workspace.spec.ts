import { expect, Page, test } from "@playwright/test";
import { join } from "node:path";

const output = process.env["PLAYWRIGHT_OUTPUT_DIR"] ?? "/repo/evidence/ui";
const nav = (page: Page, name: RegExp) =>
  page.getByRole("navigation").getByRole("link", { name });

async function create(page: Page, product = "Autocallable note") {
  await page.goto("/workspace");
  await expect(
    page.getByRole("button", { name: /Create enquiry/ }),
  ).toBeEnabled();
  await page.getByRole("radio", { name: new RegExp(product) }).check();
  await page.getByRole("button", { name: /Create enquiry/ }).click();
  await expect(
    page.getByRole("heading", { name: "Offers & booking", exact: true }),
  ).toBeVisible();
  await expect(page.getByTestId("workflow-state")).toHaveText("Enquiry");
}
async function prepareFirm(page: Page) {
  await page
    .getByRole("button", { name: "Request indicative offers", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Select Issuer-A", exact: true }),
  ).toBeEnabled();
  await page
    .getByRole("button", { name: "Select Issuer-A", exact: true })
    .click();
  await expect(page.getByTestId("workflow-state")).toHaveText("Selected");
  await page.getByLabel("I have simulated client authority").check();
  await page
    .getByRole("button", { name: "Record instruction", exact: true })
    .click();
  await expect(page.getByTestId("workflow-state")).toHaveText("Instructed");
  await page
    .getByRole("button", { name: "Get firm offer", exact: true })
    .click();
  await expect(page.getByText("Firm offer", { exact: true })).toBeVisible();
}
async function book(page: Page) {
  await prepareFirm(page);
  await page.getByLabel("I confirm this simulated booking").check();
  await page
    .getByRole("button", { name: "Book simulated trade", exact: true })
    .click();
  await expect(page.getByTestId("workflow-state")).toHaveText(
    "Simulated booked",
  );
  const id = await page.getByTestId("trade-id").textContent();
  expect(id).toMatch(/^[\da-f-]{36}$/);
  return id!;
}

test("workspace is usable on desktop and a narrow screen", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/workspace");
  await expect(
    page.getByRole("button", { name: /Create enquiry/ }),
  ).toBeEnabled();
  await expect(
    page.getByText("Simulation environment", { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: join(output, "workspace-desktop.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(
    page.getByRole("button", { name: /Create enquiry/ }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: join(output, "workspace-mobile.png"),
    fullPage: true,
  });
  expect(errors).toEqual([]);
});

test("note enquiry to real valuation and simulated settlement survives reload", async ({
  page,
}) => {
  await create(page);
  const tradeId = await book(page);
  await page.reload();
  await expect(page.getByTestId("trade-id")).toHaveText(tradeId);
  await nav(page, /Valuation & risk/).click();
  await page
    .getByRole("button", { name: "Calculate valuation", exact: true })
    .click();
  await expect(page.getByTestId("npv")).toContainText("HKD");
  await expect(page.getByTestId("delta")).toHaveText(/-?[\d,.]+/);
  await nav(page, /Trade lifecycle/).click();
  await expect(page.getByTestId("lifecycle-trade-id")).toHaveText(tradeId);
  await page
    .getByRole("button", { name: "Process observations", exact: true })
    .click();
  await expect(page.getByTestId("obligation-state")).toHaveCount(3);
  await expect(page.getByTestId("obligation-state").first()).toHaveText(
    "Determined",
  );
  await page.getByRole("button", { name: "Mark due", exact: true }).click();
  await expect(page.getByTestId("obligation-state").first()).toHaveText("Due");
  await page.getByLabel("I confirm recording simulated settlement").check();
  await page
    .getByRole("button", { name: "Simulate settlement", exact: true })
    .click();
  await expect(page.getByTestId("obligation-state")).toHaveText([
    "Simulated settled",
    "Simulated settled",
    "Simulated settled",
  ]);
  await page
    .getByRole("button", { name: "Process observations", exact: true })
    .click();
  await expect(page.getByTestId("obligation-state")).toHaveCount(3);
  await page.screenshot({
    path: join(output, "note-lifecycle.png"),
    fullPage: true,
  });
  await nav(page, /Activity & records/).click();
  await expect(
    page.getByText("Trade Booked", { exact: true }).first(),
  ).toBeVisible();
  await expect(
    page.getByText("Committed · not published", { exact: true }),
  ).toBeVisible();
});

test("accumulator creates two share purchases and rejects a changed historical fixing", async ({
  page,
}) => {
  await create(page, "Equity accumulator");
  const savedTradeId = await book(page);
  await nav(page, /Trade lifecycle/).click();
  await expect(page.getByText("HKD 7,200.00", { exact: true })).toBeVisible();
  await page
    .getByRole("button", { name: "Process observations", exact: true })
    .click();
  await expect(page.getByTestId("obligation-state")).toHaveCount(2);
  await page.getByLabel("Fixing for 2026-12-16", { exact: true }).fill("91");
  await page
    .getByRole("button", { name: "Process observations", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText("FIXING_CONFLICT");
  await expect(page.getByTestId("obligation-state")).toHaveCount(2);
  await page.evaluate(() => localStorage.clear());
  await page.goto("/workspace");
  await expect(
    page.getByRole("button", { name: /Create enquiry/ }),
  ).toBeEnabled();
  await page.getByLabel("Saved record ID", { exact: true }).fill(savedTradeId);
  await page.getByRole("button", { name: "Open trade", exact: true }).click();
  await expect(page.getByTestId("workflow-state")).toHaveText(
    "Simulated booked",
  );
  await nav(page, /Trade lifecycle/).click();
  await expect(page.getByTestId("lifecycle-trade-id")).toHaveText(savedTradeId);
  await expect(page.getByTestId("obligation-state")).toHaveCount(2);
});

test("a partial issuer timeout leaves valid offers selectable", async ({
  page,
}) => {
  await create(page);
  await page
    .getByLabel("Reply scenario", { exact: true })
    .selectOption("partial");
  await page
    .getByRole("button", { name: "Request indicative offers", exact: true })
    .click();
  await expect(page.getByText("Timeout", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Select Issuer-B", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "Select Issuer-A", exact: true }),
  ).toBeEnabled();
});

test("expired, stale and incomparable offers cannot be selected", async ({
  page,
}) => {
  await create(page);
  await page
    .getByLabel("Reply scenario", { exact: true })
    .selectOption("invalid");
  await page
    .getByRole("button", { name: "Request indicative offers", exact: true })
    .click();
  for (const issuer of ["Issuer-A", "Issuer-B", "Issuer-C"]) {
    await expect(
      page.getByRole("button", { name: `Select ${issuer}`, exact: true }),
    ).toBeDisabled();
  }
  await expect(page.getByText("Expired", { exact: true })).toBeVisible();
  await expect(page.getByText("Stale terms", { exact: true })).toBeVisible();
  await expect(page.getByText("Not comparable", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Record instruction", exact: true }),
  ).toBeDisabled();
});

test("backend validation errors expose the request reference", async ({
  page,
}) => {
  await page.goto("/workspace");
  await expect(
    page.getByRole("button", { name: /Create enquiry/ }),
  ).toBeEnabled();
  await page.getByLabel("Client ID", { exact: true }).fill("not-approved");
  await page.getByRole("button", { name: /Create enquiry/ }).click();
  await expect(page.getByRole("alert")).toContainText("FIXTURE_NOT_ELIGIBLE");
  await expect(page.getByRole("alert")).toContainText("Reference: ui-");
  await expect(page.getByTestId("workflow-state")).toHaveText("Ready to begin");
});

test("lost booking response reuses its saved idempotency key after reload", async ({
  page,
}) => {
  await create(page);
  await prepareFirm(page);
  const attempts: { offerId: string; idempotencyKey: string }[] = [];
  let committedTradeId = "";
  await page.route("**/api/enquiries/*/accept", async (route) => {
    attempts.push(route.request().postDataJSON());
    if (attempts.length === 1) {
      // Actually commit on the real backend, then simulate losing the HTTP response.
      const result = await route.fetch();
      expect(result.ok()).toBe(true);
      committedTradeId = (await result.json()).id;
      await route.abort("failed");
    } else await route.continue();
  });
  await page.getByLabel("I confirm this simulated booking").check();
  await page
    .getByRole("button", { name: "Book simulated trade", exact: true })
    .click();
  await expect(page.getByRole("alert")).toBeVisible();
  await nav(page, /Enquiry workspace/).click();
  await expect(
    page.getByRole("button", { name: "Open enquiry", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "Open trade", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByText("Resolve the pending booking before switching records.", {
      exact: false,
    }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => JSON.parse(localStorage.getItem("exotica.session.v1")!).pending.key,
    ),
  ).toBe(attempts[0].idempotencyKey);
  await nav(page, /Offers & booking/).click();
  await page.reload();
  await expect(
    page.getByRole("button", { name: "Retry booking", exact: true }),
  ).toBeVisible();
  await page.getByLabel("I confirm this simulated booking").check();
  await page
    .getByRole("button", { name: "Retry booking", exact: true })
    .click();
  await expect(page.getByTestId("trade-id")).toHaveText(committedTradeId);
  expect(attempts).toHaveLength(2);
  expect(attempts[1].idempotencyKey).toBe(attempts[0].idempotencyKey);
  expect(attempts[1].offerId).toBe(attempts[0].offerId);
  const trade = await (
    await page.request.get(`/api/trades/${committedTradeId}`)
  ).json();
  expect(
    trade.audit.filter(
      (event: { eventType: string }) => event.eventType === "TradeBooked",
    ),
  ).toHaveLength(1);
});

test("matching adds a simulated response without choosing it for the user", async ({
  page,
}) => {
  await create(page);
  await page
    .getByRole("button", { name: "Request indicative offers", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Select Issuer-A", exact: true }),
  ).toBeEnabled();
  await page
    .getByText("Optional simulated matching response", { exact: true })
    .click();
  await page
    .getByRole("button", { name: "Request matching response", exact: true })
    .click();
  await expect(
    page.getByRole("button", {
      name: "Select Barclays-simulated",
      exact: true,
    }),
  ).toBeEnabled();
  await expect(page.getByTestId("workflow-state")).toHaveText("Enquiry");
});

test("stale firm terms are blocked in the UI and rejected by the backend", async ({
  page,
}) => {
  await create(page);
  await prepareFirm(page);
  await page.getByLabel("Firm reply", { exact: true }).selectOption("STALE");
  await page
    .getByRole("button", { name: "Get firm offer", exact: true })
    .click();
  await page.getByLabel("I confirm this simulated booking").check();
  await expect(
    page.getByText("Unavailable / stale / expired", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Book simulated trade", exact: true }),
  ).toBeDisabled();
  const session = await page.evaluate(() =>
    JSON.parse(localStorage.getItem("exotica.session.v1")!),
  );
  const enquiry = await (
    await page.request.get(`/api/enquiries/${session.enquiryId}`)
  ).json();
  const firm = enquiry.offers
    .filter((offer: { kind: string }) => offer.kind === "FIRM")
    .at(-1);
  const result = await page.request.post(
    `/api/enquiries/${session.enquiryId}/accept`,
    {
      data: {
        offerId: firm.id,
        idempotencyKey: "stale-ui-" + session.enquiryId,
        expectedVersion: enquiry.version,
      },
    },
  );
  expect(result.status()).toBe(409);
  expect((await result.json()).error).toBe("STALE_TERMS");
  await expect(page.getByTestId("workflow-state")).toHaveText("Instructed");
  await expect(page.getByTestId("trade-id")).toHaveCount(0);
});

test("a failed comparison load leaves the previous record intact", async ({
  page,
}) => {
  await create(page);
  const previous = await page.evaluate(
    () => JSON.parse(localStorage.getItem("exotica.session.v1")!).enquiryId,
  );
  const second = await (
    await page.request.post("/api/enquiries", {
      data: {
        product: "ACCUMULATOR",
        clientId: "client-demo",
        operatorId: "staff-demo",
      },
    })
  ).json();
  await page.request.post(`/api/enquiries/${second.id}/rfq`, {
    data: { scenarios: {}, deadlineMillis: 200 },
  });
  await page.route(`**/api/enquiries/${second.id}/comparison`, (route) =>
    route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({
        error: "COMPARISON_UNAVAILABLE",
        message: "Comparison temporarily unavailable",
      }),
    }),
  );
  await nav(page, /Enquiry workspace/).click();
  await expect(page).toHaveURL(/\/workspace$/);
  await expect(page.getByTestId("enquiry-id")).toHaveText(previous);
  await page.getByLabel("Saved record ID", { exact: true }).fill(second.id);
  await expect(page.getByLabel("Saved record ID", { exact: true })).toHaveValue(
    second.id,
  );
  await page.getByRole("button", { name: "Open enquiry", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("COMPARISON_UNAVAILABLE");
  await expect(page.getByTestId("enquiry-id")).toHaveText(previous);
  expect(
    await page.evaluate(
      () => JSON.parse(localStorage.getItem("exotica.session.v1")!).enquiryId,
    ),
  ).toBe(previous);
});
