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
  email_templates.sql    # idempotent patch: circular templates + footers (baked into the seed)
  main_contact_flag.sql  # idempotent patch: per-company main email/phone (baked into the seed)
  not_working_contact_flag.sql # idempotent patch: dead email/phone flag (baked into the seed)
  vessel_company_links.sql # idempotent patch: vessel<->company broker roles + solo flag
  chartering.dump        # same data in pg_restore (-Fc) format, for manual restore
  schema.sql             # DDL reference (the dump already contains the schema)
db-export/               # portable full snapshot for reproducing the DB elsewhere
  chartering-full.dump   # pg_dump -Fc, --no-owner (restore with pg_restore)
  chartering-full.sql    # same content as plain SQL (restore with psql)
  README.md              # restore instructions
api/                     # Spring Boot backend, package com.chartering (multi-stage Dockerfile)
ui/                      # React SPA (multi-stage: node build -> nginx)
logs/                    # campaign send log, bind-mounted from the api container (gitignored)
```

## Circulars (bulk email)

The **Circulars** tab composes one circular and sends it **individually to every address on
the Email list tab** — a separate message per recipient, never CC or BCC.

Set the credentials in `.env` (copy from `.env.example`) and restart the api:

```bash
docker compose up -d --force-recreate api
```

Until `MAIL_ENABLED=true` and the credentials are present, the tab still composes and
previews; it just refuses to send and shows which settings are missing.

**Zoho notes.** `MAIL_USERNAME` is the full mailbox address, and `MAIL_PASSWORD` must be an
app-specific password (Zoho → Security → App Passwords) whenever two-factor auth is on the
account — the normal login password is rejected over SMTP. `MAIL_FROM` has to be the
authenticated account or a verified alias, or Zoho refuses the message. SMTP access must be
enabled under Mail Settings → Mail Accounts.

### What protects the sending mailbox

| Rule | Setting |
|---|---|
| One message per recipient, no CC/BCC | always on |
| Gap between messages drawn at random from a range (default 3–10s), never a fixed interval | `MAIL_MIN_DELAY_MS`, `MAIL_MAX_DELAY_MS` |
| Duplicate addresses dropped (case-insensitive) | always on |
| Per-campaign ceiling, checked before the first send | `MAIL_MAX_RECIPIENTS` |
| Transient (4xx) failures retried with doubling backoff; permanent (5xx) never retried | `MAIL_MAX_RETRIES`, `MAIL_RETRY_BACKOFF_MS` |
| Consecutive failures abort the run, so a throttle doesn't escalate into a block | `MAIL_ABORT_AFTER_FAILURES` |
| Auth rejection aborts immediately instead of retrying a bad password 200 times | always on |
| SMTP reachability checked before the first message | always on |
| `List-Unsubscribe` header, real `From` display name, `Reply-To` | `MAIL_UNSUBSCRIBE`, `MAIL_FROM_NAME`, `MAIL_REPLY_TO` |
| `multipart/alternative` with a generated plain-text part | always on |
| Per-recipient mail merge, so no two messages are byte-identical | `{{greeting}}`, `{{name}}`, `{{title}}`, `{{company}}`, `{{email}}` |

Only one campaign runs at a time process-wide — a second start returns `409`. Two concurrent
runs would each honour the throttle while together doubling the real send rate.

### Templates and footers

Both live in Postgres and are managed from the Circulars tab.

- **Templates** (`email_templates`) store a name, subject and HTML body. Pick one from the
  dropdown to load it into the compose form; **Save template** overwrites the selected one,
  or saves a copy if you give it a new name in the prompt.
- **Footers** (`email_footers`) are reusable signature blocks appended at send time. One may
  be marked default and is pre-selected; choosing *No footer* sends without one. Manage them
  with the gear button next to the footer picker.

Placeholders work in footers as well as bodies, so a signature can carry `{{company}}` or
echo `{{email}}` back to the recipient. The footer is resolved once per campaign, so editing
it mid-run can't change what half the recipients receive.

The compose editor has an **HTML** button that switches to a source view — that's where to
paste a designed footer or hand-tuned markup. The visual surface still forces pasted content
to plain text, because Word/Outlook markup is a common cause of mail rendering badly.
Incoming HTML is stripped of `<script>`/`<iframe>` blocks, `on*` handlers and `javascript:`
URLs before storage; mail clients drop these anyway, and their presence hurts spam scoring.

Schema comes from `db/email_templates.sql` (idempotent, same house pattern as
`db/banned_flags.sql`) and is baked into the seed dump, so a fresh `docker compose up` has
both tables and one starter footer already.

### The campaign log

`logs/campaign-current.log` (bind-mounted from the container) records every recipient with
its outcome. A run that finished cleanly is **overwritten** by the next one; a run that
failed, aborted, or was cancelled is **rotated** to `campaign-current-<timestamp>.log` first,
since that's exactly the record you need to see who already received the circular. The
outcome is recovered from the log's own end marker, so the rule survives an API restart.

Endpoints: `POST /api/v1/campaigns` (202, sends in the background), `GET /campaigns/current`,
`POST /campaigns/current/cancel`, `GET /campaigns/current/log`, `POST /campaigns/test?to=…`,
`GET /campaigns/config`, plus CRUD on `/api/v1/email-templates` and `/api/v1/email-footers`.

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
