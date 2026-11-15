FROM node:24-bookworm-slim@sha256:dc26ae92c9fb15cf6b1d63e20f0944b2efe0f115b20e00be48fb23fceb061c54 AS dependencies
WORKDIR /ui
ENV NG_CLI_ANALYTICS=false
COPY ui/package.json ui/package-lock.json ./
RUN npm ci --no-audit --no-fund

FROM dependencies AS build
COPY ui/ ./
COPY fixtures/market.json fixtures/lifecycle-paths.json /fixtures/
RUN npm run build

FROM mcr.microsoft.com/playwright:v1.64.1-noble@sha256:2f05f8a734509a7b3f26a912639488ee08f58a7b292d14d62271bcff9038768f AS test
WORKDIR /ui
ENV HOME=/tmp
COPY ui/package.json ui/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY ui/playwright.config.ts ./
COPY ui/e2e ./e2e
CMD ["npm", "run", "test:e2e"]

FROM nginx:stable-alpine@sha256:901e593c95dc85f5a729f5765516321366ef4ac401821431d408897e95287d0a AS runtime
COPY ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /ui/dist/exotica/browser /usr/share/nginx/html
EXPOSE 80
