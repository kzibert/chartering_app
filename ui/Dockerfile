# Multi-stage: build the SPA with Node, serve static via nginx (which also proxies /api).
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
# ci rather than install: it installs exactly what the lockfile pins, and fails instead of
# quietly resolving something newer when the two have drifted apart.
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
# Rendered by the image's own entrypoint at container start — see default.conf.template.
COPY default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

# Defaults are the compose setup: nginx on 80, the api container next door on 8081. A
# platform that dictates a port (Render sets PORT) or a different address for the api
# overrides these, and nothing needs rebuilding.
ENV PORT=80
ENV API_HOST=api:8081
# Substitute these two and nothing else. Without the filter, envsubst would also consider
# nginx's own $host / $scheme / $uri fair game if a variable of that name existed.
ENV NGINX_ENVSUBST_FILTER="^(PORT|API_HOST)$"

EXPOSE 80
