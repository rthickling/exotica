import {
  computed,
  DestroyRef,
  inject,
  Injectable,
  signal,
} from "@angular/core";
import { HttpErrorResponse } from "@angular/common/http";
import { Api, newId } from "./api";
import {
  ApiProblem,
  Comparison,
  Enquiry,
  Market,
  Offer,
  Product,
  Terms,
  Trade,
  Valuation,
} from "./models";

const SESSION = "exotica.session.v1";
interface PendingBooking {
  enquiryId: string;
  offerId: string;
  key: string;
  version: number;
}
interface Session {
  enquiryId?: string;
  tradeId?: string;
  pending?: PendingBooking | null;
}

@Injectable({ providedIn: "root" })
export class Workspace {
  private readonly api = inject(Api);
  readonly enquiry = signal<Enquiry | null>(null);
  readonly trade = signal<Trade | null>(null);
  readonly comparison = signal<Comparison | null>(null);
  readonly firm = signal<Offer | null>(null);
  readonly valuation = signal<Valuation | null>(null);
  readonly pending = signal<PendingBooking | null>(null);
  readonly templates = signal<Partial<Record<Product, Terms>>>({});
  readonly market = signal<Market | null>(null);
  readonly samplePaths = signal<
    Partial<Record<Product, Record<string, number>>>
  >({});
  readonly busy = signal("");
  readonly error = signal<ApiProblem | null>(null);
  readonly notice = signal("");
  readonly now = signal(Date.now());
  readonly booked = computed(
    () => this.enquiry()?.state === "SIMULATED_BOOKED",
  );
  readonly audit = computed(() =>
    [...(this.enquiry()?.audit ?? [])].sort((a, b) =>
      b.occurredAt.localeCompare(a.occurredAt),
    ),
  );
  readonly comparable = computed(
    () =>
      this.comparison()?.offers.filter(
        (row) => this.usable(row.offer) && row.comparability === "COMPARABLE",
      ).length ?? 0,
  );

  constructor() {
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  usable(offer: Offer): boolean {
    const enquiry = this.enquiry();
    return (
      !!enquiry &&
      offer.status === "AVAILABLE" &&
      Date.parse(offer.expiresAt) > this.now() &&
      offer.termsVersion === enquiry.termsVersion &&
      offer.termsHash === enquiry.termsHash &&
      offer.currency === enquiry.terms.currency &&
      (offer.kind !== "FIRM" || offer.sourceOfferId === enquiry.selectedOfferId)
    );
  }

  async initialize(): Promise<void> {
    await this.run("Loading workspace", async () => {
      const [templates, market, paths] = await Promise.all([
        this.api.templates(),
        this.api.market(),
        this.api.paths(),
      ]);
      this.templates.set(templates);
      this.market.set(market);
      this.samplePaths.set(paths);
      const session = this.readSession();
      if (session.enquiryId) {
        this.pending.set(session.pending ?? null);
        await this.load(session.enquiryId, session.tradeId);
      }
    });
  }

  startNew(): void {
    if (this.pending()) return;
    this.enquiry.set(null);
    this.trade.set(null);
    this.firm.set(null);
    this.comparison.set(null);
    this.valuation.set(null);
    this.error.set(null);
    this.notice.set("");
    this.pending.set(null);
    this.persist();
  }

  create(product: Product, client: string, operator: string, clientReference: string | null = null): Promise<boolean> {
    if (this.pending()) return this.preventSwitch();
    return this.run("Creating enquiry", async () => {
      const enquiry = await this.api.create(product, client, operator, clientReference);
      this.pending.set(null);
      this.trade.set(null);
      await this.load(enquiry.id);
      this.notice.set(
        "Enquiry created. Request indicative offers to continue.",
      );
    });
  }

  reopen(id: string, kind: "enquiry" | "trade"): Promise<boolean> {
    if (
      this.pending() &&
      !(kind === "enquiry" && id === this.pending()!.enquiryId)
    )
      return this.preventSwitch();
    return this.run("Opening record", async () => {
      const trade = kind === "trade" ? await this.api.trade(id) : null;
      const enquiryId = trade?.enquiryId ?? id;
      const session = this.readSession();
      this.pending.set(
        session.pending?.enquiryId === enquiryId ? session.pending : null,
      );
      await this.load(enquiryId, trade?.id);
      this.notice.set("Saved record opened.");
    });
  }

  refresh(): Promise<boolean> {
    return this.run("Refreshing record", () =>
      this.load(this.enquiry()!.id, this.trade()?.id),
    );
  }

  quotes(mode: string): Promise<boolean> {
    const scenarios: Record<string, string> =
      mode === "partial"
        ? { "Issuer-B": "TIMEOUT" }
        : mode === "invalid"
          ? {
            "Issuer-A": "EXPIRED",
            "Issuer-B": "STALE",
            "Issuer-C": "INCOMPARABLE",
          }
          : {};
    return this.mutate("Requesting indicative offers", () =>
      this.api.quotes(this.enquiry()!.id, scenarios),
    );
  }
  match(outcome: string): Promise<boolean> {
    return this.mutate("Requesting simulated matching response", () =>
      this.api.match(this.enquiry()!.id, outcome),
    );
  }
  select(offer: Offer, rationale: string): Promise<boolean> {
    return this.mutate("Recording offer selection", async () => {
      await this.api.select(this.enquiry()!.id, offer.id, rationale);
      this.firm.set(null);
    });
  }
  instruct(reference: string): Promise<boolean> {
    return this.mutate("Recording simulated instruction", () =>
      this.api.instruct(this.enquiry()!.id, reference),
    );
  }
  requestFirm(scenario: string): Promise<boolean> {
    return this.mutate("Obtaining a firm offer", async () => {
      this.firm.set(await this.api.firm(this.enquiry()!.id, scenario));
    });
  }

  book(): Promise<boolean> {
    return this.run("Booking simulated trade", async () => {
      const enquiry = this.enquiry()!;
      const pending = this.pending() ?? {
        enquiryId: enquiry.id,
        offerId: this.firm()!.id,
        key: `ui-book-${newId()}`,
        version: enquiry.version,
      };
      this.pending.set(pending);
      this.persist();
      try {
        const trade = await this.api.accept(
          pending.enquiryId,
          pending.offerId,
          pending.key,
          pending.version,
        );
        this.pending.set(null);
        this.trade.set(trade);
        this.persist();
        await this.load(enquiry.id, trade.id);
        this.notice.set(
          "Trade booked in simulation. No live order has been sent.",
        );
      } catch (error) {
        // A network/5xx error may hide a successful commit. Retry the exact persisted key and offer.
        if (
          error instanceof HttpErrorResponse &&
          error.status >= 400 &&
          error.status < 500
        ) {
          this.pending.set(null);
          this.persist();
        }
        throw error;
      }
    });
  }

  value(
    market: Market,
    paths: number,
    seed: number,
    bump: number,
  ): Promise<boolean> {
    return this.mutate("Calculating valuation", async () => {
      this.valuation.set(
        await this.api.value(this.enquiry()!.id, market, paths, seed, bump),
      );
    });
  }
  replay(fixings: Record<string, number>): Promise<boolean> {
    return this.mutate("Processing dated observations", () =>
      this.api.replay(this.trade()!.id, fixings),
    );
  }
  advance(asOf: string, settle: boolean): Promise<boolean> {
    return this.mutate(
      settle ? "Recording simulated settlement" : "Marking due obligations",
      () => this.api.advance(this.trade()!.id, asOf, settle),
    );
  }

  private mutate(
    label: string,
    action: () => Promise<unknown>,
  ): Promise<boolean> {
    return this.run(label, async () => {
      await action();
      await this.load(this.enquiry()!.id, this.trade()?.id);
      this.notice.set("Record updated.");
    });
  }

  private async load(id: string, tradeId?: string): Promise<void> {
    const [enquiry, trade] = await Promise.all([
      this.api.enquiry(id),
      tradeId ? this.api.trade(tradeId) : Promise.resolve(null),
    ]);
    if (trade && trade.enquiryId !== enquiry.id)
      throw new Error("Mismatched saved record");
    const comparison = enquiry.offers?.some((o) => o.kind === "INDICATIVE")
      ? await this.api.comparison(id)
      : null;
    const valuation =
      [...(enquiry.valuations ?? [])].sort((a, b) =>
        (b.createdAt ?? "").localeCompare(a.createdAt ?? ""),
      )[0] ?? null;
    const firmId = this.pending()?.offerId;
    const firm =
      enquiry.offers
        ?.filter(
          (o) =>
            o.kind === "FIRM" && o.sourceOfferId === enquiry.selectedOfferId,
        )
        .sort((a, b) => b.receivedAt.localeCompare(a.receivedAt))
        .find((o) => !firmId || o.id === firmId) ?? null;
    // Publish one coherent snapshot only after every required request has succeeded.
    this.enquiry.set(enquiry);
    this.trade.set(trade);
    this.comparison.set(comparison);
    this.valuation.set(valuation);
    this.firm.set(firm);
    this.persist();
  }

  private preventSwitch(): Promise<boolean> {
    this.error.set({
      message:
        "Retry the pending booking before opening another record or starting an enquiry.",
      code: "BOOKING_PENDING",
      status: 0,
      correlationId: "",
    });
    return Promise.resolve(false);
  }

  private async run(
    label: string,
    action: () => Promise<void>,
  ): Promise<boolean> {
    if (this.busy()) return false;
    this.busy.set(label);
    this.error.set(null);
    this.notice.set("");
    try {
      await action();
      return true;
    } catch (error) {
      const response = error instanceof HttpErrorResponse ? error : null;
      this.error.set({
        message:
          response?.error?.message ??
          (response?.status === 0
            ? "Cannot reach the service. Check that Docker is running, then retry."
            : "Unable to complete the request. Refresh the record and try again."),
        code: response?.error?.error ?? "REQUEST_FAILED",
        status: response?.status ?? 0,
        correlationId:
          response?.error?.correlationId ??
          response?.headers.get("X-Correlation-ID") ??
          "",
      });
      return false;
    } finally {
      this.busy.set("");
    }
  }

  private readSession(): Session {
    try {
      return JSON.parse(localStorage.getItem(SESSION) ?? "{}") as Session;
    } catch {
      return {};
    }
  }
  private persist(): void {
    try {
      localStorage.setItem(
        SESSION,
        JSON.stringify({
          enquiryId: this.enquiry()?.id,
          tradeId: this.trade()?.id,
          pending: this.pending(),
        }),
      );
    } catch {
      /* Browsers may disable local storage. The active in-memory workflow still works. */
    }
  }
}
