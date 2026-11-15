import { Routes } from "@angular/router";
import { EnquiryPage } from "./pages/enquiry";
import { OffersPage } from "./pages/offers";
import { ValuationPage } from "./pages/valuation";
import { LifecyclePage } from "./pages/lifecycle";
import { ActivityPage } from "./pages/activity";

export const routes: Routes = [
  { path: "workspace", component: EnquiryPage, title: "Workspace · Exotica" },
  {
    path: "offers",
    component: OffersPage,
    title: "Offers & booking · Exotica",
  },
  { path: "valuation", component: ValuationPage, title: "Valuation · Exotica" },
  { path: "lifecycle", component: LifecyclePage, title: "Lifecycle · Exotica" },
  { path: "activity", component: ActivityPage, title: "Activity · Exotica" },
  { path: "", pathMatch: "full", redirectTo: "workspace" },
  { path: "**", redirectTo: "workspace" },
];
