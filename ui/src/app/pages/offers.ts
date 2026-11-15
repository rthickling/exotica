import { Component, inject } from "@angular/core";
import { DatePipe, DecimalPipe } from "@angular/common";
import {
  FormBuilder,
  FormControl,
  ReactiveFormsModule,
  Validators,
} from "@angular/forms";
import { RouterLink } from "@angular/router";
import { Workspace } from "../store";
import { ComparedOffer, humanize } from "../models";

@Component({
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, DatePipe, DecimalPipe],
  template: `
    <div class="section-heading">
      <div>
        <h2>Offers & booking</h2>
        <p class="muted">
          Compare the same payoff. Record the decision. Book once.
        </p>
      </div>
      <span class="section-tag">02 / EXECUTION</span>
    </div>
    @if (store.enquiry(); as enquiry) {
      @if (store.booked()) {
        <section class="panel booking-success">
          <span class="success-icon" aria-hidden="true">✓</span>
          <div>
            <h3>Simulated trade booked</h3>
            <p>
              No live order was sent. This enquiry cannot book another trade.
            </p>
            @if (store.trade(); as trade) {
              <code data-testid="trade-id">{{ trade.id }}</code>
            } @else {
              <p class="muted">
                Reopen using the trade ID to manage its obligations.
              </p>
            }
          </div>
          <a routerLink="/lifecycle" class="button primary"
            >Manage lifecycle →</a
          >
        </section>
      }
      <section class="panel">
        <div class="panel-heading">
          <div>
            <h3>Indicative offer comparison</h3>
            <p class="muted small">
              Compare upfront cost for identical terms. Issuer exposure remains
              separate.
            </p>
          </div>
          <span class="pill"
            >{{ enquiry.terms.currency }} · {{ enquiry.termsVersion }}</span
          >
        </div>
        <div class="toolbar">
          <label
            >Reply scenario<select
              aria-label="Reply scenario"
              [formControl]="scenario"
            >
              <option value="normal">All issuers respond</option>
              <option value="partial">Issuer B times out</option>
              <option value="invalid">Expired / stale / incomparable</option>
            </select></label
          ><button
            class="button primary"
            (click)="store.quotes(scenario.value)"
            [disabled]="!!store.busy() || store.booked() || !!store.pending()"
          >
            Request indicative offers</button
          ><button
            class="button secondary"
            (click)="store.refresh()"
            [disabled]="!!store.busy()"
          >
            Refresh
          </button>
        </div>
        @if (store.comparison(); as comparison) {
          <div class="table-scroll">
            <table>
              <caption class="sr-only">
                Indicative offers and comparability
              </caption>
              <thead>
                <tr>
                  <th>Issuer</th>
                  <th>Issue price</th>
                  <th>Fee</th>
                  <th>Upfront cost</th>
                  <th>Status</th>
                  <th>Expires</th>
                  <th><span class="sr-only">Selection</span></th>
                </tr>
              </thead>
              <tbody>
                @for (row of comparison.offers; track row.offer.id) {
                  <tr
                    [class.selected-row]="
                      enquiry.selectedOfferId === row.offer.id
                    "
                  >
                    <td>
                      <strong>{{
                        row.offer.issuer.replace("-simulated", "")
                      }}</strong
                      ><small>{{
                        row.offer.kind === "INDICATIVE"
                          ? "Indicative · simulated"
                          : "Firm · simulated"
                      }}</small>
                    </td>
                    <td>
                      {{
                        enquiry.product === "AUTOCALLABLE"
                          ? (row.offer.price | number: "1.2-2") + "%"
                          : "—"
                      }}
                    </td>
                    <td>{{ row.offer.fee | number: "1.2-2" }}</td>
                    <td class="numeric">
                      {{
                        row.netUpfrontCost === null
                          ? "—"
                          : (row.netUpfrontCost | number: "1.2-2")
                      }}
                    </td>
                    <td>
                      <span
                        class="pill"
                        [class.good]="canSelect(row)"
                        [class.warning]="!canSelect(row)"
                        >{{ status(row) }}</span
                      >
                    </td>
                    <td class="small">
                      {{ row.offer.expiresAt | date: "HH:mm:ss" }}
                    </td>
                    <td>
                      <button
                        class="button compact secondary"
                        (click)="store.select(row.offer, rationale.value)"
                        [attr.aria-label]="'Select ' + row.offer.issuer"
                        [disabled]="
                          !canSelect(row) ||
                          !rationale.valid ||
                          !!store.busy() ||
                          store.booked() ||
                          !!store.pending()
                        "
                      >
                        {{
                          enquiry.selectedOfferId === row.offer.id
                            ? "Selected"
                            : "Select"
                        }}
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <label class="full-field"
            >Selection rationale<textarea
              [formControl]="rationale"
              rows="2"
              maxlength="2000"
            ></textarea>
          </label>
          <details class="optional">
            <summary>Optional simulated matching response</summary>
            <p class="field-hint">
              Add a Barclays-simulated response to the cheapest comparable
              external offer. This does not imply a real Barclays capability or
              recommendation.
            </p>
            <div class="toolbar">
              <label
                >Matching outcome<select
                  aria-label="Matching outcome"
                  [formControl]="matching"
                >
                  <option>IMPROVED</option>
                  <option>MATCHED</option>
                  <option>UNIMPROVED</option>
                  <option>DECLINED</option>
                </select></label
              ><button
                class="button secondary"
                (click)="store.match(matching.value)"
                [disabled]="
                  !!store.busy() ||
                  !store.comparable() ||
                  store.booked() ||
                  !!store.pending()
                "
              >
                Request matching response
              </button>
            </div>
          </details>
        } @else {
          <div class="empty-inline">
            <span aria-hidden="true">⇄</span>
            <p>
              No offers yet. Request indicative replies to start your
              comparison.
            </p>
          </div>
        }
      </section>
      @if (!store.booked() || store.pending()) {
        <div class="two-column booking-columns">
          <section class="panel">
            <span class="step-label">STEP 1</span>
            <h3>Record client instruction</h3>
            <p class="muted">
              Select an offer above, then record the simulated authority to
              proceed.
            </p>
            <form
              [formGroup]="instruction"
              (ngSubmit)="store.instruct(instruction.controls.reference.value)"
            >
              <label
                >Instruction reference<input
                  formControlName="reference"
                  maxlength="200" /></label
              ><label class="check-field"
                ><input type="checkbox" formControlName="authorised" /><span
                  >I have simulated client authority for the selected
                  offer.</span
                ></label
              ><button
                class="button secondary"
                type="submit"
                [disabled]="
                  instruction.invalid ||
                  !enquiry.selectedOfferId ||
                  !!store.busy() ||
                  !!store.pending()
                "
              >
                Record instruction
              </button>
            </form>
            @if (enquiry.instruction) {
              <p class="inline-success">
                ✓ Instruction recorded: {{ enquiry.instruction.reference }}
              </p>
            }
          </section>
          <section class="panel">
            <span class="step-label">STEP 2</span>
            <h3>Obtain & accept a firm offer</h3>
            <p class="muted">
              Indicative selection alone cannot book. The firm offer must still
              be valid.
            </p>
            <div class="toolbar">
              <label
                >Firm reply<select
                  aria-label="Firm reply"
                  [formControl]="firmScenario"
                >
                  <option value="OK">Available</option>
                  <option value="EXPIRED">Expired (demonstration)</option>
                  <option value="DECLINED">Declined (demonstration)</option>
                  <option value="STALE">Stale terms (demonstration)</option>
                </select></label
              ><button
                class="button secondary"
                (click)="store.requestFirm(firmScenario.value)"
                [disabled]="
                  !enquiry.instruction || !!store.busy() || !!store.pending()
                "
              >
                Get firm offer
              </button>
            </div>
            @if (store.firm(); as firm) {
              <div class="firm-summary">
                <strong
                  >{{ firm.issuer }}
                  <span class="pill" [class.good]="store.usable(firm)">{{
                    store.usable(firm)
                      ? "Firm offer"
                      : "Unavailable / stale / expired"
                  }}</span></strong
                ><span
                  >Expires {{ firm.expiresAt | date: "HH:mm:ss" }} · fee
                  {{ firm.currency }} {{ firm.fee | number: "1.2-2" }}</span
                >
              </div>
              <label class="check-field"
                ><input type="checkbox" [formControl]="confirmation" /><span
                  >I confirm this simulated booking. No live order will be
                  sent.</span
                ></label
              >
              @if (store.pending()) {
                <p class="field-hint">
                  The previous result is uncertain. Retry uses the same saved
                  booking key and firm offer.
                </p>
              }
              <button
                class="button primary"
                (click)="store.book()"
                [disabled]="
                  !confirmation.value ||
                  (!store.usable(firm) && !store.pending()) ||
                  !!store.busy()
                "
              >
                {{ store.pending() ? "Retry booking" : "Book simulated trade" }}
              </button>
            }
          </section>
        </div>
      }
    } @else {
      <section class="panel empty-state">
        <h3>Start with an enquiry</h3>
        <p>Create or reopen a record before requesting offers.</p>
        <a class="button primary" routerLink="/workspace">Open workspace →</a>
      </section>
    }
  `,
})
export class OffersPage {
  readonly store = inject(Workspace);
  private readonly fb = inject(FormBuilder);
  readonly scenario = new FormControl("normal", { nonNullable: true });
  readonly matching = new FormControl("IMPROVED", { nonNullable: true });
  readonly firmScenario = new FormControl("OK", { nonNullable: true });
  readonly rationale = new FormControl(
    "Compared identical payoff terms, size and currency; recorded upfront cost and separate issuer exposure.",
    {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(2000)],
    },
  );
  readonly confirmation = new FormControl(false, { nonNullable: true });
  readonly instruction = this.fb.nonNullable.group({
    reference: [
      "synthetic-instruction-ui",
      [Validators.required, Validators.maxLength(200)],
    ],
    authorised: [false, Validators.requiredTrue],
  });
  canSelect(row: ComparedOffer): boolean {
    return row.comparability === "COMPARABLE" && this.store.usable(row.offer);
  }
  status(row: ComparedOffer): string {
    return humanize(
      row.comparability === "COMPARABLE" && !this.store.usable(row.offer)
        ? "EXPIRED"
        : row.comparability,
    );
  }
}
