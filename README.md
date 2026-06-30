# chartering

Self-contained, vessel-centric chartering stack: **Postgres + Spring Boot REST API + React/Ant Design SPA**, all wired together with Docker Compose. The whole thing builds and runs from this repo alone — the database seeds itself from a bundled dump, so there is **no dependency on any external database or project**.

## Stack

| Service | Tech                                   | Container (compose project `chartering`) | Host port |
|---------|----------------------------------------|------------------------------------------|-----------|
| `db`    | Postgres 16 (seeded from `db/seed/`)   | `chartering-db-1`                        | `5433`    |
| `api`   | Spring Boot 3.4 / Java 21 / JPA        | `chartering-api-1`                       | `8081`    |
| `ui`    | React 18 + Vite + Ant Design (nginx)   | `chartering-ui-1`                        | `8082`    |

The UI's nginx reverse-proxies `/api` → the `api` service, which talks to the `db` service over the compose network. The API uses `ddl-auto=validate` — the schema is owned by the seed dump, not Hibernate.

## Quick start

```bash
docker compose up -d --build
```

Then open:

- UI: http://localhost:8082
- API + Swagger: http://localhost:8081/swagger-ui/index.html
- OpenAPI JSON: http://localhost:8081/v3/api-docs

First boot: Postgres runs `db/seed/chartering.sql` (full `pg_dump`: schema, ~4.5k vessels / 3k companies / 3.5k people / 7.5k contacts, views, indexes, `pg_trgm`). This only happens when the data volume is empty.

```bash
docker compose down        # stop, keep data
docker compose down -v     # stop + wipe DB; next `up` re-seeds from the dump
```

Override credentials/ports by copying `.env.example` to `.env`.

## Layout

```
docker-compose.yml       # db + api + ui (compose project "chartering")
.env.example             # credential / port overrides
db/
  seed/chartering.sql    # auto-seed dump (runs on first DB init)
  chartering.dump        # same data in pg_restore (-Fc) format, for manual restore
  schema.sql             # DDL reference (the dump already contains the schema)
api/                     # Spring Boot backend, package com.chartering (multi-stage Dockerfile)
ui/                      # React SPA (multi-stage: node build -> nginx)
```

## Local dev (without Docker)

- **API:** needs JDK 21 + Maven and a Postgres on `localhost:5433` (the `dev` profile default). `cd api && mvn spring-boot:run`. Easiest is to run just the DB via `docker compose up -d db` and point the dev profile at it.
- **UI:** needs Node 20. `cd ui && npm install && npm run dev` → http://localhost:5173 (Vite proxies `/api` → `localhost:8081`).

## Reseeding / restoring manually

```bash
# plain SQL into a running DB
docker compose exec -T db psql -U chartering_user -d chartering < db/seed/chartering.sql

# or the custom-format dump
docker compose exec -T db pg_restore -U chartering_user -d chartering --no-owner < db/chartering.dump
```
