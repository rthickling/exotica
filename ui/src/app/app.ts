import { Component, inject, OnInit } from "@angular/core";
import {
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from "@angular/router";
import { Workspace } from "./store";
import { humanize, productName } from "./models";

@Component({
  selector: "exotica-app",
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    <a class="skip-link" href="#main">Skip to workspace</a>
    <aside class="sidebar">
      <a class="brand" routerLink="/workspace" aria-label="Exotica workspace"
        ><span class="brand-mark">E</span
        ><span
          >exotica<span class="brand-caption">STRUCTURED PRODUCTS</span></span
        ></a
      >
      <div class="nav-label">SALES DESK</div>
      <nav aria-label="Main navigation">
        @for (item of navigation; track item.path) {
          <a
            [routerLink]="item.path"
            routerLinkActive="active"
            ariaCurrentWhenActive="page"
            ><span class="nav-symbol" aria-hidden="true">{{ item.icon }}</span
            >{{ item.label
            }}<span class="nav-arrow" aria-hidden="true">›</span></a
          >
        }
      </nav>
      <div class="sidebar-foot">
        <span class="connection-dot"></span> Local demonstration<span
          >Synthetic products. Real calculations.</span
        >
      </div>
    </aside>
    <div class="app-body">
      <header class="topbar">
        <span
          >Workspace <span class="slash">/</span>
          <strong>Structured products</strong></span
        ><span class="staff"><span class="avatar">SD</span> staff-demo</span>
      </header>
      <div class="simulation-banner">
        <span class="badge-dot"></span><strong>Simulation environment</strong
        ><span
          >Offers, client instructions and settlement are simulated. No live
          orders.</span
        >
      </div>
      <main id="main" tabindex="-1">
        <div class="page-heading">
          <div>
            <p class="eyebrow">EXOTIC & STRUCTURED PRODUCTS</p>
            <h1>Sales workspace</h1>
            <p class="muted">From the first enquiry to the final obligation.</p>
          </div>
          <button
            class="button secondary"
            (click)="newEnquiry()"
            [disabled]="!!store.busy() || !!store.pending()"
          >
            + New enquiry
          </button>
        </div>
        <section class="summary-grid" aria-label="Current record summary">
          <div class="summary-card">
            <span class="summary-label">CURRENT PRODUCT</span
            ><strong>{{
              store.enquiry()
                ? productName(store.enquiry()!.product)
                : "No active enquiry"
            }}</strong
            ><span>{{
              store.enquiry()?.termsVersion ??
                "Create or reopen a record to begin"
            }}</span>
          </div>
          <div class="summary-card">
            <span class="summary-label">WORKFLOW STATUS</span
            ><strong class="status-text" data-testid="workflow-state">{{
              humanize(store.enquiry()?.state ?? "READY_TO_BEGIN")
            }}</strong
            ><span>{{
              store.enquiry()?.clientId ?? "Synthetic client workspace"
            }}</span>
          </div>
          <div class="summary-card">
            <span class="summary-label">COMPARABLE OFFERS</span
            ><strong
              >{{ store.comparable()
              }}<span class="small-count"> available</span></strong
            ><span>Same terms, size and currency</span>
          </div>
          <div class="summary-card">
            <span class="summary-label">TRADE</span
            ><strong>{{
              store.trade() ? "Simulated booked" : "Not booked"
            }}</strong
            ><span>{{
              store.trade()
                ? "Lifecycle management available"
                : "Instruction and firm offer required"
            }}</span>
          </div>
        </section>
        @if (store.busy()) {
          <div class="busy-message" role="status">
            <span class="spinner"></span>{{ store.busy() }}…
          </div>
        }
        @if (store.error(); as error) {
          <section class="message error" role="alert">
            <div>
              <strong>{{ error.message }}</strong
              ><span
                >{{ error.code }}
                @if (error.correlationId) {
                  · Reference: <code>{{ error.correlationId }}</code>
                }
              </span>
            </div>
            <button
              class="text-button"
              (click)="store.error.set(null)"
              aria-label="Dismiss error"
            >
              Dismiss
            </button>
          </section>
        }
        @if (store.notice()) {
          <div class="message success" role="status">{{ store.notice() }}</div>
        }
        <router-outlet />
        <footer>
          EXOTICA <span>·</span> Synthetic sales & lifecycle lab
          <span>·</span> HKD / SYNTH_EQ
        </footer>
      </main>
    </div>
  `,
})
export class App implements OnInit {
  readonly store = inject(Workspace);
  private readonly router = inject(Router);
  readonly humanize = humanize;
  readonly productName = productName;
  readonly navigation = [
    { path: "/workspace", label: "Enquiry workspace", icon: "▦" },
    { path: "/offers", label: "Offers & booking", icon: "⇄" },
    { path: "/valuation", label: "Valuation & risk", icon: "↗" },
    { path: "/lifecycle", label: "Trade lifecycle", icon: "◷" },
    { path: "/activity", label: "Activity & records", icon: "≡" },
  ];
  ngOnInit(): void {
    void this.store.initialize();
  }
  newEnquiry(): void {
    this.store.startNew();
    void this.router.navigateByUrl("/workspace");
  }
}
