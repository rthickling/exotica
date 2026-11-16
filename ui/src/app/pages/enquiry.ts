import { Component, inject } from "@angular/core";
import { FormBuilder, ReactiveFormsModule, Validators } from "@angular/forms";
import { Router, RouterLink } from "@angular/router";
import { Workspace } from "../store";
import { Product, productName } from "../models";

@Component({
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <div class="section-heading">
      <div>
        <h2>Enquiry workspace</h2>
        <p class="muted">
          Start with a product and the approved demonstration client.
        </p>
      </div>
      <span class="section-tag">01 / ENQUIRY</span>
    </div>
    @if (store.enquiry(); as enquiry) {
      <div class="two-column">
        <section class="panel">
          <div class="panel-heading">
            <h3>{{ productName(enquiry.product) }}</h3>
            <span class="pill">{{ enquiry.termsVersion }}</span>
          </div>
          <dl class="detail-grid">
            <div>
              <dt>Enquiry ID</dt>
              <dd>
                <code data-testid="enquiry-id">{{ enquiry.id }}</code>
              </dd>
            </div>
            <div>
              <dt>Client / staff</dt>
              <dd>{{ enquiry.clientId }} / {{ enquiry.operatorId }}</dd>
            </div>
            <div>
              <dt>Client Reference</dt>
              <dd>{{ enquiry.clientReference ?? "Not provided" }}</dd>
            </div>
            <div>
              <dt>Currency / underlying</dt>
              <dd>
                {{ enquiry.terms.currency }} / {{ enquiry.terms.underlying }}
              </dd>
            </div>
            <div>
              <dt>Start date</dt>
              <dd>{{ enquiry.terms.startDate }}</dd>
            </div>
            <div>
              <dt>Observation dates</dt>
              <dd>
                {{ enquiry.terms.observationDates.length }} contractual
                observations
              </dd>
            </div>
            <div>
              <dt>Selection rationale</dt>
              <dd>
                {{ enquiry.selectionRationale ?? "No offer selected yet" }}
              </dd>
            </div>
          </dl>
          <div class="panel-actions">
            <a class="button primary" routerLink="/offers"
              >Continue to offers <span aria-hidden="true">→</span></a
            ><button
              class="button secondary"
              (click)="store.refresh()"
              [disabled]="!!store.busy()"
            >
              Refresh record
            </button>
          </div>
        </section>
        <section class="panel accent-panel">
          <span class="eyebrow">NEXT IN THE WORKFLOW</span>
          <h3>
            {{
              store.booked()
                ? "Manage the booked contract"
                : "Compare before you select"
            }}
          </h3>
          <p>
            {{
              store.booked()
                ? "Value the contract and replay dated observations. Payments and share purchases remain simulated."
                : "Request indicative replies, compare like-for-like terms, and record why you selected an offer. A lower upfront cost is not a suitability decision."
            }}
          </p>
          <a
            [routerLink]="store.booked() ? '/lifecycle' : '/offers'"
            class="text-link"
            >{{
              store.booked() ? "Open trade lifecycle" : "Open offer comparison"
            }}
            →</a
          >
        </section>
      </div>
    } @else {
      <div class="two-column wide-left">
        <section class="panel">
          <div class="panel-heading">
            <h3>Create an enquiry</h3>
            <span class="pill">Synthetic client</span>
          </div>
          <form [formGroup]="form" (ngSubmit)="create()">
            <fieldset class="product-options">
              <legend>Choose a product</legend>
              <label
                class="product-option"
                [class.chosen]="form.controls.product.value === 'AUTOCALLABLE'"
                ><input
                  type="radio"
                  formControlName="product"
                  value="AUTOCALLABLE"
                /><span
                  ><strong>Autocallable note</strong
                  ><small
                    >Funded note with conditional coupons and early
                    redemption.</small
                  ><span class="product-fact"
                    >HKD 100,000 principal <span>·</span> 4 observations</span
                  ></span
                ></label
              >
              <label
                class="product-option"
                [class.chosen]="form.controls.product.value === 'ACCUMULATOR'"
                ><input
                  type="radio"
                  formControlName="product"
                  value="ACCUMULATOR"
                /><span
                  ><strong>Equity accumulator</strong
                  ><small
                    >Dated share purchases with a strike and knockout
                    level.</small
                  ><span class="product-fact"
                    >HKD 90 strike <span>·</span> 10 base shares</span
                  ></span
                ></label
              >
            </fieldset>
            <div class="form-grid">
              <label
                >Client ID<input
                  formControlName="clientId"
                  autocomplete="off" /></label
              ><label
                >Staff ID<input formControlName="operatorId" autocomplete="off"
              /></label>
              <label
                >Client Reference (Optional)<input
                  formControlName="clientReference"
                  maxlength="100"
                  autocomplete="off"
              /></label>
            </div>
            <p class="field-hint">
              The demonstration is approved only for client-demo and staff-demo.
            </p>
            <div class="panel-actions">
              <button
                class="button primary"
                type="submit"
                [disabled]="form.invalid || !!store.busy()"
              >
                Create enquiry <span aria-hidden="true">→</span></button
              ><span class="muted small">No trade is booked at this step.</span>
            </div>
          </form>
        </section>
        <section class="panel accent-panel">
          <span class="eyebrow">THE DESK WORKFLOW</span>
          <h3>One contract.<br />A clear decision trail.</h3>
          <ol class="workflow-list">
            <li>
              <span>01</span>
              <div>
                <strong>Enquire & compare</strong
                ><small>Gather like-for-like indicative offers.</small>
              </div>
            </li>
            <li>
              <span>02</span>
              <div>
                <strong>Instruct & book</strong
                ><small>Record authority, then accept a firm offer.</small>
              </div>
            </li>
            <li>
              <span>03</span>
              <div>
                <strong>Value & manage</strong
                ><small>Review model risk and dated obligations.</small>
              </div>
            </li>
          </ol>
          <div class="quiet-note">
            Issuer offers and settlement are simulated. Pricing uses the actual
            QuantLib service.
          </div>
        </section>
      </div>
    }
    <section class="panel reopen-panel">
      <div>
        <h3>Reopen a saved record</h3>
        <p class="muted">
          Records persist in PostgreSQL. The last active record also reopens in
          this browser.
          @if (store.pending()) {
            Resolve the pending booking before switching records.
          }
        </p>
      </div>
      <form [formGroup]="openForm" (ngSubmit)="open('enquiry')">
        <label class="sr-only" for="record-id">Saved record ID</label
        ><input
          id="record-id"
          formControlName="id"
          placeholder="Paste an enquiry or trade ID"
          [readOnly]="!!store.pending()"
        /><button
          class="button secondary"
          type="submit"
          [disabled]="openForm.invalid || !!store.busy() || !!store.pending()"
        >
          Open enquiry</button
        ><button
          class="button secondary"
          type="button"
          (click)="open('trade')"
          [disabled]="openForm.invalid || !!store.busy() || !!store.pending()"
        >
          Open trade
        </button>
      </form>
    </section>
  `,
})
export class EnquiryPage {
  readonly store = inject(Workspace);
  private readonly fb = inject(FormBuilder);
  private readonly router = inject(Router);
  readonly productName = productName;
  readonly form = this.fb.nonNullable.group({
    product: ["AUTOCALLABLE" as Product, Validators.required],
    clientId: ["client-demo", Validators.required],
    clientReference: ["", Validators.maxLength(100)],
    operatorId: ["staff-demo", Validators.required],
  });
  readonly openForm = this.fb.nonNullable.group({
    id: [
      "",
      [
        Validators.required,
        Validators.pattern(
          /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
        ),
      ],
    ],
  });
  async create(): Promise<void> {
    if (this.form.invalid) return;
    const value = this.form.getRawValue();
    if (
      await this.store.create(value.product, value.clientId, value.operatorId, value.clientReference || null)
    )
      await this.router.navigateByUrl("/offers");
  }
  async open(kind: "enquiry" | "trade"): Promise<void> {
    if (!this.openForm.invalid)
      await this.store.reopen(this.openForm.getRawValue().id, kind);
  }
}
