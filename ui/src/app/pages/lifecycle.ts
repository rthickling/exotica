import { Component, effect, inject } from "@angular/core";
import { DecimalPipe } from "@angular/common";
import {
  FormArray,
  FormBuilder,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from "@angular/forms";
import { RouterLink } from "@angular/router";
import { Workspace } from "../store";
import { humanize } from "../models";

type Observation = FormGroup<{
  date: FormControl<string>;
  spot: FormControl<number>;
}>;
@Component({
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, DecimalPipe],
  template: `
    <div class="section-heading">
      <div>
        <h2>Trade lifecycle</h2>
        <p class="muted">
          Replay contractual observations and follow the resulting obligations.
        </p>
      </div>
      <span class="section-tag">04 / LIFECYCLE</span>
    </div>
    @if (store.trade(); as trade) {
      <section class="panel trade-strip">
        <div>
          <span class="summary-label">SIMULATED TRADE</span
          ><code data-testid="lifecycle-trade-id">{{ trade.id }}</code>
        </div>
        <div>
          <span class="summary-label">ORIGINAL PURCHASE PRICE / FEE</span
          ><strong
            >HKD {{ trade.originalPurchasePrice | number: "1.2-2" }}</strong
          >
        </div>
        @if (trade.maximumPurchaseExposure !== null) {
          <div>
            <span class="summary-label">MAXIMUM PURCHASE EXPOSURE</span
            ><strong
              >HKD {{ trade.maximumPurchaseExposure | number: "1.2-2" }}</strong
            >
          </div>
        }
      </section>
      <div class="two-column">
        <section class="panel">
          <div class="panel-heading">
            <h3>Observation fixings</h3>
            <button
              class="text-button"
              (click)="sample()"
              [disabled]="!!store.busy()"
            >
              Load sample path
            </button>
          </div>
          <p class="field-hint">
            Submit a contiguous dated path. The sample demonstrates early
            termination. Recorded historical fixings cannot be changed.
          </p>
          <form [formGroup]="form" (ngSubmit)="replay()">
            <div class="fixing-header">
              <span>Observation date</span><span>Equity fixing</span>
            </div>
            <div formArrayName="observations">
              @for (
                row of observations.controls;
                track row.controls.date.value;
                let i = $index
              ) {
                <div class="fixing-row" [formGroupName]="i">
                  <label [for]="'fixing-' + i">{{
                    row.controls.date.value
                  }}</label
                  ><input
                    [id]="'fixing-' + i"
                    type="number"
                    min="0.01"
                    step="0.01"
                    formControlName="spot"
                    [attr.aria-label]="'Fixing for ' + row.controls.date.value"
                  />
                </div>
              }
            </div>
            <div class="panel-actions">
              <button
                type="submit"
                class="button primary"
                [disabled]="form.invalid || !!store.busy()"
              >
                Process observations
              </button>
            </div>
          </form>
        </section>
        <section class="panel">
          <span class="step-label">OBLIGATION PROGRESSION</span>
          <h3>Determine → due → settle</h3>
          <p class="muted">
            A fixing determines an obligation. A controlled business date marks
            it due. Settlement here records a simulation, not a payment.
          </p>
          <label>Business date<input type="date" [formControl]="asOf" /></label
          ><label class="check-field"
            ><input type="checkbox" [formControl]="settlementConfirmed" /><span
              >I confirm recording simulated settlement for due
              obligations.</span
            ></label
          >
          <div class="panel-actions">
            <button
              class="button secondary"
              (click)="store.advance(asOf.value, false)"
              [disabled]="
                asOf.invalid || !trade.obligations?.length || !!store.busy()
              "
            >
              Mark due</button
            ><button
              class="button primary"
              (click)="store.advance(asOf.value, true)"
              [disabled]="
                !settlementConfirmed.value ||
                asOf.invalid ||
                !trade.obligations?.length ||
                !!store.busy()
              "
            >
              Simulate settlement
            </button>
          </div>
        </section>
      </div>
      <section class="panel">
        <div class="panel-heading">
          <h3>Dated obligations</h3>
          <span class="pill">{{ trade.obligations?.length ?? 0 }} records</span>
        </div>
        <div class="table-scroll">
          <table>
            <caption class="sr-only">
              Trade obligations
            </caption>
            <thead>
              <tr>
                <th>Observation</th>
                <th>Payment date</th>
                <th>Obligation</th>
                <th>Cash (HKD)</th>
                <th>Shares</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              @for (
                obligation of trade.obligations ?? [];
                track obligation.id
              ) {
                <tr>
                  <td>{{ obligation.observationDate }}</td>
                  <td>{{ obligation.paymentDate }}</td>
                  <td>{{ humanize(obligation.kind) }}</td>
                  <td class="numeric">
                    {{ obligation.cash | number: "1.2-2" }}
                  </td>
                  <td>{{ obligation.shares | number: "1.0-2" }}</td>
                  <td>
                    <span
                      class="pill"
                      [class.good]="obligation.state === 'SIMULATED_SETTLED'"
                      data-testid="obligation-state"
                      >{{ humanize(obligation.state) }}</span
                    >
                  </td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="6" class="empty-cell">
                    Process observations to determine the first obligations.
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    } @else {
      <section class="panel empty-state">
        <h3>A booked trade is required</h3>
        <p>
          Complete the instruction and firm-offer steps, or reopen a saved trade
          by ID.
        </p>
        <a class="button primary" routerLink="/offers"
          >Open offers & booking →</a
        ><a class="text-link" routerLink="/workspace">Reopen a trade</a>
      </section>
    }
  `,
})
export class LifecyclePage {
  readonly store = inject(Workspace);
  private readonly fb = inject(FormBuilder);
  readonly humanize = humanize;
  readonly observations = new FormArray<Observation>([]);
  readonly form = new FormGroup({ observations: this.observations });
  readonly asOf = new FormControl("", {
    nonNullable: true,
    validators: Validators.required,
  });
  readonly settlementConfirmed = new FormControl(false, { nonNullable: true });
  private initializedFor = "";
  constructor() {
    effect(() => {
      const enquiry = this.store.enquiry();
      if (enquiry && this.initializedFor !== enquiry.id) {
        this.initializedFor = enquiry.id;
        this.observations.clear();
        for (const date of enquiry.terms.observationDates)
          this.observations.push(
            this.fb.nonNullable.group({
              date,
              spot: [100, [Validators.required, Validators.min(0.01)]],
            }),
          );
        this.asOf.setValue(enquiry.terms.paymentDates.at(-1) ?? "");
        this.sample();
      }
    });
  }
  sample(): void {
    const enquiry = this.store.enquiry();
    const path = enquiry
      ? this.store.samplePaths()[enquiry.product]
      : undefined;
    for (const row of this.observations.controls)
      row.controls.spot.setValue(path?.[row.controls.date.value] ?? 100);
  }
  replay(): void {
    if (this.form.valid)
      void this.store.replay(
        Object.fromEntries(
          this.observations.getRawValue().map((row) => [row.date, row.spot]),
        ),
      );
  }
}
