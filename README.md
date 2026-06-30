# chartering-clean

Self-contained, vessel-centric chartering stack: **Postgres + Spring Boot REST API + React/Ant Design SPA**, all wired together with Docker Compose. The whole thing builds and runs from this repo alone — the database seeds itself from a bundled dump, so there is **no dependency on any external database or project**.

## Stack

| Service              | Tech                                   | Container               | Host port |
|----------------------|----------------------------------------|-------------------------|-----------|
| `chartering_clean_db`| Postgres 16 (seeded from `db/seed/`)   | `chartering_clean_db`   | `5433`    |
| `clean-api`          | Spring Boot 3.4 / Java 21 / JPA        | `chartering_clean_api`  | `8081`    |
| `clean-ui`           | React 18 + Vite + Ant Design (nginx)   | `chartering_clean_ui`   | `8082`    |

The UI's nginx reverse-proxies `/api` → `clean-api`, which talks to `chartering_clean_db` over the internal `dbnet` network. The API uses `ddl-auto=validate` — the schema is owned by the seed dump, not Hibernate.

## Quick start

```bash
docker compose up -d --build
```

Then open:

- UI: http://localhost:8082
- API + Swagger: http://localhost:8081/swagger-ui/index.html
- OpenAPI JSON: http://localhost:8081/v3/api-docs

First boot: Postgres runs `db/seed/chartering_clean.sql` (full `pg_dump`: schema, ~4.5k vessels / 3k companies / 3.5k people / 7.5k contacts, views, indexes, `pg_trgm`). This only happens when the data volume is empty.

```bash
docker compose down        # stop, keep data
docker compose down -v     # stop + wipe DB; next `up` re-seeds from the dump
```

Override credentials/ports by copying `.env.example` to `.env`.

## Layout

```
docker-compose.yml          # db + api + ui, self-contained internal network
.env.example                # credential / port overrides
db/
  seed/chartering_clean.sql # auto-seed dump (runs on first DB init)
  chartering_clean.dump     # same data in pg_restore (-Fc) format, for manual restore
  schema.sql                # DDL reference (the dump already contains the schema)
clean-api/                  # Spring Boot backend (multi-stage Dockerfile)
clean-ui/                   # React SPA (multi-stage: node build -> nginx)
```

## Local dev (without Docker)

- **API:** needs JDK 21 + Maven and a Postgres on `localhost:5433` (the `dev` profile default). `cd clean-api && mvn spring-boot:run`. Easiest is to run just the DB via `docker compose up -d chartering_clean_db` and point the dev profile at it.
- **UI:** needs Node 20. `cd clean-ui && npm install && npm run dev` → http://localhost:5173 (Vite proxies `/api` → `localhost:8081`).

## Reseeding / restoring manually

```bash
# plain SQL into a running DB
docker exec -i chartering_clean_db psql -U chartering_user -d chartering_clean < db/seed/chartering_clean.sql

# or the custom-format dump
docker exec -i chartering_clean_db pg_restore -U chartering_user -d chartering_clean --no-owner < db/chartering_clean.dump
```
