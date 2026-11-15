import { defineConfig } from "@playwright/test";
import { join } from "node:path";

const output = process.env["PLAYWRIGHT_OUTPUT_DIR"] ?? "/repo/evidence/ui";
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  retries: 0,
  outputDir: join(output, "test-results"),
  reporter: [
    ["list"],
    ["html", { outputFolder: join(output, "report"), open: "never" }],
    ["junit", { outputFile: join(output, "results.xml") }],
  ],
  use: {
    actionTimeout: 15_000,
    baseURL: process.env["UI_BASE_URL"] ?? "http://ui",
    browserName: "chromium",
    viewport: { width: 1440, height: 1000 },
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
    launchOptions: { args: ["--no-sandbox"] },
  },
});
