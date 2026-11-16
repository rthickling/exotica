import { HttpClient, HttpInterceptorFn } from "@angular/common/http";
import { Injectable, inject } from "@angular/core";
import { firstValueFrom } from "rxjs";
import {
  Comparison,
  Enquiry,
  Market,
  Product,
  Terms,
  Trade,
  Offer,
  Valuation,
} from "./models";

// getRandomValues is also available to a browser testing http://ui inside Docker.
export function newId(): string {
  return Array.from(crypto.getRandomValues(new Uint32Array(4)), (n) =>
    n.toString(16).padStart(8, "0"),
  ).join("");
}
export const correlationInterceptor: HttpInterceptorFn = (request, next) =>
  next(
    request.url.startsWith("/api/")
      ? request.clone({ setHeaders: { "X-Correlation-ID": `ui-${newId()}` } })
      : request,
  );

@Injectable({ providedIn: "root" })
export class Api {
  private readonly http = inject(HttpClient);
  templates = () =>
    firstValueFrom(this.http.get<Record<Product, Terms>>("/api/templates"));
  market = () => firstValueFrom(this.http.get<Market>("/fixtures/market.json"));
  paths = () =>
    firstValueFrom(
      this.http.get<Record<Product, Record<string, number>>>(
        "/fixtures/lifecycle-paths.json",
      ),
    );
  enquiry = (id: string) =>
    firstValueFrom(this.http.get<Enquiry>(`/api/enquiries/${id}`));
  trade = (id: string) =>
    firstValueFrom(this.http.get<Trade>(`/api/trades/${id}`));
  comparison = (id: string) =>
    firstValueFrom(
      this.http.get<Comparison>(`/api/enquiries/${id}/comparison`),
    );
  create = (product: Product, clientId: string, operatorId: string, clientReference: string | null) =>
    this.post<Enquiry>("/enquiries", { product, clientId, operatorId, clientReference });
  quotes = (id: string, scenarios: Record<string, string>) =>
    this.post(`/enquiries/${id}/rfq`, { scenarios, deadlineMillis: 200 });
  match = (id: string, outcome: string) =>
    this.post(`/enquiries/${id}/match`, { outcome });
  select = (id: string, offerId: string, rationale: string) =>
    this.post(`/enquiries/${id}/selection`, { offerId, rationale });
  instruct = (id: string, reference: string) =>
    this.post(`/enquiries/${id}/instruction`, { authorised: true, reference });
  firm = (id: string, scenario: string) =>
    this.post<Offer>(`/enquiries/${id}/firm`, { scenario });
  accept = (
    id: string,
    offerId: string,
    idempotencyKey: string,
    expectedVersion: number,
  ) =>
    this.post<Trade>(`/enquiries/${id}/accept`, {
      offerId,
      idempotencyKey,
      expectedVersion,
      failAfterTrade: false,
    });
  value = (
    id: string,
    market: Market,
    paths: number,
    seed: number,
    bump: number,
  ) =>
    this.post<Valuation>(`/enquiries/${id}/value`, {
      market,
      settings: { paths, seed, bump },
      historicalFixings: {},
      settledThrough: null,
    });
  replay = (id: string, fixings: Record<string, number>) =>
    this.post(`/trades/${id}/lifecycle`, { fixings });
  advance = (id: string, asOf: string, settle: boolean) =>
    this.post(`/trades/${id}/obligations/advance`, { asOf, settle });
  private post<T = unknown>(path: string, body: unknown): Promise<T> {
    return firstValueFrom(this.http.post<T>(`/api${path}`, body));
  }
}
