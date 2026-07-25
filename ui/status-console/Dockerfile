# The status console ships as its own container, one per baseline.
#
# Two stages: pnpm builds the static bundle, then a minimal web image serves it. The runtime stage
# carries no Node and no node_modules - a static bundle needs neither, and shipping them would be
# attack surface for nothing.
FROM node:24-alpine AS build
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
# VITE_* values are inlined at build time, so they are build arguments rather than runtime env:
# a value absent here is absent in the image, however the container is later configured.
ARG VITE_API_BASE_URL
ARG VITE_KEYCLOAK_URL
ARG VITE_KEYCLOAK_REALM
ARG VITE_KEYCLOAK_CLIENT_ID
RUN pnpm build

FROM nginx:1.29-alpine AS runtime
# Non-root, per the platform image standard. The stock nginx image runs its worker as nginx
# already; this makes the master non-root too, so no process in the container is root.
RUN chown -R nginx:nginx /usr/share/nginx/html /var/cache/nginx /var/log/nginx \
  && touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid
COPY nginx.conf /etc/nginx/nginx.conf
COPY --from=build /app/dist /usr/share/nginx/html
USER nginx
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q --spider http://localhost:8080/ || exit 1
