export type Product = "AUTOCALLABLE" | "ACCUMULATOR";
export interface Terms {
  product: Product;
  termsVersion: string;
  currency: string;
  underlying: string;
  observationDates: string[];
  paymentDates: string[];
  startDate: string;
  notional?: number;
  couponRate?: number;
  couponBarrier?: number;
  autocallBarrier?: number;
  capitalBarrier?: number;
  strike?: number;
  baseQuantity?: number;
  multiplier?: number;
  knockoutBarrier?: number;
}
export interface Offer {
  id: string;
  sourceOfferId: string | null;
  issuer: string;
  kind: "INDICATIVE" | "FIRM";
  status: string;
  price: number;
  fee: number;
  currency: string;
  size: number;
  expiresAt: string;
  receivedAt: string;
  termsVersion: string;
  termsHash: string;
  priceConvention: string;
  creditInformation: string;
}
export interface ComparedOffer {
  offer: Offer;
  comparability: string;
  netUpfrontCost: number | null;
}
export interface Comparison {
  offers: ComparedOffer[];
  interpretation: string;
}
export interface Audit {
  id: string;
  eventType: string;
  correlationId: string;
  occurredAt: string;
  payload: unknown;
}
export interface Enquiry {
  termsHash: string;
  id: string;
  version: number;
  product: Product;
  state: string;
  terms: Terms;
  termsVersion: string;
  clientId: string;
  clientReference: string | null;
  operatorId: string;
  selectedOfferId: string | null;
  selectionRationale: string | null;
  instruction: { reference: string; authorised: boolean } | null;
  offers?: Offer[];
  audit?: Audit[];
  valuations?: Valuation[];
}
export interface Obligation {
  id: string;
  observationDate: string;
  paymentDate: string;
  kind: string;
  cash: number;
  shares: number;
  currency: string;
  state: string;
}
export interface Trade {
  id: string;
  enquiryId: string;
  offerId: string;
  state: string;
  bookedAt: string;
  originalPurchasePrice: number;
  maximumPurchaseExposure: number | null;
  obligations?: Obligation[];
  audit?: Audit[];
  outbox?: { id: string; state: string; eventType: string }[];
}
export interface Market {
  snapshotId: string;
  asOf: string;
  spot: number;
  rate: number;
  dividend: number;
  volatility: number;
}
export interface Valuation {
  id: string;
  createdAt?: string;
  request: { market: Market };
  result: {
    npv: number;
    currency: string;
    delta: number;
    deltaStatus: string;
    monteCarloStandardError: number;
    provenance?: unknown;
  };
}
export interface ApiProblem {
  message: string;
  code: string;
  correlationId: string;
  status: number;
}
export const productName = (product: Product): string =>
  product === "AUTOCALLABLE" ? "Autocallable note" : "Equity accumulator";
export const humanize = (value: string): string =>
  value
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/^./, (c) => c.toUpperCase());
