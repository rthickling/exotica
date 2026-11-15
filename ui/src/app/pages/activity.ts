import { Component, inject } from "@angular/core";
import { DatePipe, JsonPipe } from "@angular/common";
import { RouterLink } from "@angular/router";
import { Workspace } from "../store";

@Component({
  standalone: true,
  imports: [RouterLink, DatePipe, JsonPipe],
  template: `
    <div class="section-heading">
      <div>
        <h2>Activity & records</h2>
        <p class="muted">
          Follow the decisions and saved records for the active enquiry.
        </p>
      </div>
      <button
        class="button secondary"
        (click)="store.refresh()"
        [disabled]="!store.enquiry() || !!store.busy()"
      >
        Refresh activity
      </button>
    </div>
    @if (store.enquiry(); as enquiry) {
      <section class="panel">
        <div class="panel-heading">
          <h3>Enquiry activity</h3>
          <code>{{ enquiry.id }}</code>
        </div>
        <ol class="activity-list">
          @for (event of store.audit(); track event.id) {
            <li>
              <span class="activity-dot"></span>
              <div>
                <strong>{{ label(event.eventType) }}</strong
                ><span class="muted small">{{
                  event.occurredAt | date: "dd MMM y, HH:mm:ss"
                }}</span>
                <details>
                  <summary>Record details</summary>
                  <p class="field-hint">
                    Request reference: <code>{{ event.correlationId }}</code>
                  </p>
                  <pre>{{ event.payload | json }}</pre>
                </details>
              </div>
            </li>
          } @empty {
            <li>No saved activity yet.</li>
          }
        </ol>
      </section>
      @if (store.trade(); as trade) {
        <section class="panel">
          <div class="panel-heading">
            <h3>Committed event records</h3>
            <span class="pill">Outbox</span>
          </div>
          <p class="muted">
            These records committed with booking. Kafka delivery is not
            implemented; committed does not mean published.
          </p>
          @for (event of trade.outbox ?? []; track event.id) {
            <div class="outbox-row">
              <strong>{{ label(event.eventType) }}</strong
              ><span class="pill warning">Committed · not published</span
              ><code>{{ event.id }}</code>
            </div>
          }
        </section>
      }
    } @else {
      <section class="panel empty-state">
        <h3>No active record</h3>
        <p>Create or reopen an enquiry to inspect its saved activity.</p>
        <a class="button primary" routerLink="/workspace">Open workspace →</a>
      </section>
    }
  `,
})
export class ActivityPage {
  readonly store = inject(Workspace);
  label(value: string): string {
    return value.replace(/([a-z])([A-Z])/g, "$1 $2");
  }
}
