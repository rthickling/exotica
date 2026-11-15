import { Component, effect, inject } from "@angular/core";
import { DecimalPipe, JsonPipe } from "@angular/common";
import { FormBuilder, ReactiveFormsModule, Validators } from "@angular/forms";
import { RouterLink } from "@angular/router";
import { Workspace } from "../store";
import { humanize } from "../models";

@Component({
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, DecimalPipe, JsonPipe],
  template: `
    <div class="section-heading">
      <div>
        <h2>Valuation & risk</h2>
        <p class="muted">
          Run the real pricing model against an explicit synthetic market
          snapshot.
        </p>
      </div>
      <span class="section-tag">03 / VALUATION</span>
    </div>
    @if (store.enquiry()) {
      <div class="two-column valuation-layout">
        <section class="panel">
          <h3>Market assumptions</h3>
          <p class="field-hint">
            Rates and volatility are annual fractions: 0.25 means 25%. This is
            not live or calibrated market data.
          </p>
          <form [formGroup]="form" (ngSubmit)="calculate()">
            <div class="form-grid">
              <label
                >Valuation date<input
                  type="date"
                  formControlName="asOf" /></label
              ><label
                >Equity spot<input
                  type="number"
                  step="0.01"
                  min="0.01"
                  formControlName="spot" /></label
              ><label
                >Interest rate<input
                  type="number"
                  step="0.01"
                  min="-0.1"
                  max="1"
                  formControlName="rate" /></label
              ><label
                >Dividend yield<input
                  type="number"
                  step="0.01"
                  min="-0.1"
                  max="1"
                  formControlName="dividend" /></label
              ><label
                >Volatility<input
                  type="number"
                  step="0.01"
                  min="0"
                  max="3"
                  formControlName="volatility" /></label
              ><label>Snapshot ID<input formControlName="snapshotId" /></label>
            </div>
            <details class="optional">
              <summary>Simulation settings</summary>
              <div class="form-grid">
                <label
                  >Monte Carlo paths<input
                    type="number"
                    min="2"
                    max="262144"
                    step="1"
                    formControlName="paths" /></label
                ><label
                  >Random seed<input
                    type="number"
                    min="1"
                    step="1"
                    formControlName="seed" /></label
                ><label
                  >Absolute spot bump<input
                    type="number"
                    min="0.000001"
                    step="0.1"
                    formControlName="bump"
                /></label>
              </div>
            </details>
            <div class="panel-actions">
              <button
                class="button primary"
                type="submit"
                [disabled]="form.invalid || !!store.busy()"
              >
                Calculate valuation</button
              ><span class="muted small">Uses the frozen contract terms.</span>
            </div>
          </form>
        </section>
        <section class="panel result-panel">
          <span class="eyebrow">MODEL ESTIMATE</span>
          @if (store.valuation(); as valuation) {
            <p class="valuation-amount" data-testid="npv">
              {{ valuation.result.currency }}
              {{ valuation.result.npv | number: "1.2-2" }}
            </p>
            <p class="muted">Net present value · investor perspective</p>
            <div class="result-metrics">
              <div>
                <span>Delta</span
                ><strong data-testid="delta">{{
                  valuation.result.delta | number: "1.2-2"
                }}</strong
                ><small>NPV change per spot unit</small>
              </div>
              <div>
                <span>Monte Carlo standard error</span
                ><strong>{{
                  valuation.result.monteCarloStandardError | number: "1.2-2"
                }}</strong
                ><small>Sampling uncertainty only</small>
              </div>
            </div>
            <div
              class="model-note"
              [class.caution]="valuation.result.deltaStatus === 'NOISY'"
            >
              <strong>{{ humanize(valuation.result.deltaStatus) }}</strong>
              <p>
                {{
                  valuation.result.deltaStatus === "NOISY"
                    ? "Delta is noisy. Increase the path count and examine bump sensitivity before using it."
                    : "Review both sampling and bump sensitivity. This estimate is a teaching result, not a dealer price."
                }}
              </p>
            </div>
            <dl class="detail-grid">
              <div>
                <dt>Saved valuation</dt>
                <dd>
                  <code>{{ valuation.id }}</code>
                </dd>
              </div>
              <div>
                <dt>Snapshot</dt>
                <dd>{{ valuation.request.market.snapshotId }}</dd>
              </div>
            </dl>
            <details class="optional">
              <summary>Model provenance</summary>
              <pre>{{ valuation.result.provenance | json }}</pre>
            </details>
          } @else {
            <div class="empty-inline tall">
              <span aria-hidden="true">↗</span>
              <h3>Ready to calculate</h3>
              <p>
                Submit market assumptions to see value, delta and sampling
                uncertainty.
              </p>
            </div>
          }
        </section>
      </div>
      <p class="bottom-note">
        Initial-date valuation is supported here. Historical-fixing and
        settled-through valuation scenarios remain available through the API.
        Purchase fees are separate from model value; the accumulator's maximum
        purchase exposure is not its NPV.
      </p>
    } @else {
      <section class="panel empty-state">
        <h3>Choose a contract first</h3>
        <p>Create or reopen an enquiry before valuing its terms.</p>
        <a class="button primary" routerLink="/workspace">Open workspace →</a>
      </section>
    }
  `,
})
export class ValuationPage {
  readonly store = inject(Workspace);
  private readonly fb = inject(FormBuilder);
  readonly humanize = humanize;
  readonly form = this.fb.nonNullable.group({
    asOf: ["", Validators.required],
    snapshotId: ["", Validators.required],
    spot: [100, [Validators.required, Validators.min(0.01)]],
    rate: [
      0.03,
      [Validators.required, Validators.min(-0.1), Validators.max(1)],
    ],
    dividend: [
      0.01,
      [Validators.required, Validators.min(-0.1), Validators.max(1)],
    ],
    volatility: [
      0.25,
      [Validators.required, Validators.min(0), Validators.max(3)],
    ],
    paths: [
      16384,
      [
        Validators.required,
        Validators.min(2),
        Validators.max(262144),
        Validators.pattern(/^\d+$/),
      ],
    ],
    seed: [
      42,
      [
        Validators.required,
        Validators.min(1),
        Validators.max(4294967295),
        Validators.pattern(/^\d+$/),
      ],
    ],
    bump: [0.5, [Validators.required, Validators.min(0.000001)]],
  });
  constructor() {
    effect(() => {
      const market = this.store.market();
      if (market) this.form.patchValue(market);
    });
  }
  async calculate(): Promise<void> {
    if (this.form.invalid) return;
    const { paths, seed, bump, ...market } = this.form.getRawValue();
    await this.store.value(market, paths, seed, bump);
  }
}
