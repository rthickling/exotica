import { bootstrapApplication } from "@angular/platform-browser";
import { provideHttpClient, withInterceptors } from "@angular/common/http";
import { provideRouter } from "@angular/router";
import { App } from "./app/app";
import { routes } from "./app/routes";
import { correlationInterceptor } from "./app/api";

bootstrapApplication(App, {
  providers: [
    provideRouter(routes),
    provideHttpClient(withInterceptors([correlationInterceptor])),
  ],
}).catch((error) => console.error("Unable to start Exotica", error));
