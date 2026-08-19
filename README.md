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

First boot: Postgres runs `db/seed/chartering.sql` (full `pg_dump`: schema, ~4.5k vessels / 3k companies / 3.6k people / 7.5k contacts, views, indexes, `pg_trgm`). This only happens when the data volume is empty.

Every `db/*.sql` patch is baked into that dump, so a fresh volume already validates against the
API — none of them needs applying by hand. The seed deliberately carries **no activity data**:
circulation history and the `app_settings` overrides are excluded, so a new environment starts
with a clean send history and the settings its own `.env` specifies. `db-export/` is the
opposite — a complete snapshot including both, for moving an existing database elsewhere.

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
  vessel_company_links.sql # idempotent patch: vessel<->company broker roles + solo flag (baked in)
  circulations.sql       # idempotent patch: circ flag, circulation lists, circulation history (baked in)
  app_settings.sql       # idempotent patch: runtime settings edited from the Settings tab (baked in)
  chartering.dump        # same data in pg_restore (-Fc) format, for manual restore
  schema.sql             # DDL reference (the dump already contains the schema)
db-export/               # portable full snapshot for reproducing the DB elsewhere
  chartering-full.dump   # pg_dump -Fc, --no-owner (restore with pg_restore)
  chartering-full.sql    # same content as plain SQL (restore with psql)
  history/               # timestamped copy of every refresh (gitignored)
  README.md              # restore instructions
  EXPORTING.md           # how to refresh all four dumps, and how to verify them
refresh-db-export.bat    # one-click refresh of db-export/, keeping the previous dumps
api/                     # Spring Boot backend, package com.chartering (multi-stage Dockerfile)
ui/                      # React SPA (multi-stage: node build -> nginx)
logs/                    # campaign send log, bind-mounted from the api container (gitignored)
```

## Circulars (bulk email)

The **Circulars** tab composes one circular and sends it **individually to every address on
the current circulation list** — a separate message per recipient, never CC or BCC.

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
| Gap between messages drawn at random from a range (default 3–10s), never a fixed interval | Settings tab (defaults `MAIL_MIN_DELAY_MS`, `MAIL_MAX_DELAY_MS`) |
| Duplicate addresses dropped (case-insensitive) | always on |
| Per-run ceiling: a longer list is split into several runs, spaced apart, rather than refused | Settings tab (defaults `MAIL_MAX_RECIPIENTS`, `MAIL_BATCH_PAUSE_MS`) |
| Transient (4xx) failures retried with doubling backoff; permanent (5xx) never retried | `MAIL_MAX_RETRIES`, `MAIL_RETRY_BACKOFF_MS` |
| Consecutive failures abort the run, so a throttle doesn't escalate into a block | `MAIL_ABORT_AFTER_FAILURES` |
| Auth rejection aborts immediately instead of retrying a bad password 200 times | always on |
| SMTP reachability checked before the first message | always on |
| `List-Unsubscribe` header, real `From` display name, `Reply-To` | `MAIL_UNSUBSCRIBE`, `MAIL_REPLY_TO`; From is on the Settings tab |
| `multipart/alternative` with a generated plain-text part | always on |
| Per-recipient mail merge, so no two messages are byte-identical | `{{greeting}}`, `{{name}}`, `{{title}}`, `{{company}}`, `{{email}}` |

Only one campaign runs at a time process-wide — a second start returns `409`. Two concurrent
runs would each honour the throttle while together doubling the real send rate.

**Lists longer than the per-run cap are split, not refused.** 256 recipients with the cap at 50
go out as six runs of up to 50, `MAIL_BATCH_PAUSE_MS` apart (default 15 min), driven by the same
worker and the same campaign: the Circulars tab shows `run 3 of 6`, the progress bar counts the
whole 256, and the estimate includes the pauses. Between runs the campaign is *paused* — nothing
is in flight and **Cancel** ends it there, leaving the runs still to come unsent. An abort (auth
rejection, or the consecutive-failure breaker) likewise stops the whole campaign, not just the
run it happened in. The pause is held in memory, so an API restart mid-campaign ends it — the
history entry closes as `ABORTED` with its unreached recipients still `PENDING`.

What splitting buys is a smaller burst, which is what per-hour allowances measure. It does not
fit a campaign inside a **daily** cap that is smaller than the campaign: 300 messages count as
300 whatever their spacing, and exceeding a plan's daily limit can suspend outgoing mail on the
account. That total is shown before sending, on the Send confirmation and in the plan banner.

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

### Circulation lists

Recipients live in **circulation lists**, stored in Postgres (`db/circulations.sql`). There is
one unnamed **current list** — what the Circulars tab sends to, and what every other tab adds
into by default — plus any number of **saved lists** prepared in advance. Building the current
list lives entirely on this tab; the Circulars tab only sends it, so there is one place to look
when the question is "who is on it".

- **Circulation lists** tab: switch between the current list and the saved ones, edit any row's
  address or mail-merge fields inline, and *Save as list* to keep a copy of the current one.
  Editing a row edits the list, never the contact record — a list is a prepared document.
- Both views have row checkboxes, and every action below acts on the ticked rows, or on the whole
  list when nothing is ticked — so one button covers "all" and "only these".
- On the **current list**, *Add … to list* picks one of your saved lists as the destination. The
  current list keeps its rows; this files a copy. (*Save as list* is the same move into a list that
  does not exist yet.)
- On a **saved list**, three actions against the current list, none of which changes the saved
  list itself:
  - **Add … to current** — union, deduped by address.
  - **Remove … from current** — subtract. This is how you exclude a list you have already
    circulated to: build the current list broadly, then subtract "Sent in March".
  - **Replace current** — discard the current list and load this one, ready to send.
- Addresses are deduped per list, case-insensitively — the same rule the sender applies, so the
  count on screen is the number of messages that will go out.
- From **Companies**, **Vessels** and **People** you can tick rows and *Add N selected*, or
  *Add all N matching* to take the whole filtered set (not just the visible page). Both open a
  dialog that previews the resulting address count, lets you pick the target list (or create one),
  and offers a confirmed-contacts-only filter.

Endpoints: CRUD on `/api/v1/circulation-lists` (`/current` for the draft, `/{id}/copy` for save-as,
`/{id}/load/{sourceId}` to replace contents, `/{id}/entries` for the rows,
`POST /{id}/entries/remove` to subtract a set of addresses), plus the collection endpoints
`GET /companies/contacts`, `GET /people/contacts` and `GET /vessels/contacts`.

Subtraction matches on the **address**, not on ids — the same key dedupe and the sender use — so
it still works when the same mailbox was collected through two different contacts, or typed by
hand on one side.

### Which addresses get collected

Bulk-collecting every address a company has on file is how a circular reaches the same desk four
times; taking only one is how it misses the person who actually charters. Two per-contact flags
decide it, and the rule runs **per person** — with a company's person-less addresses forming one
more group of their own:

| The group has… | …and collection takes |
|---|---|
| one or more addresses flagged **circ** | all of its circ addresses |
| no circ, but a **main** address | that one address |
| neither | every working address |

So flagging one person's address never silences their colleagues. **circ** is set with the paper-plane
button on a contact row (edit mode) and, unlike the main-contact star, is not a radio choice: any
number of a person's addresses may carry it, because "who gets the circular" and "one address to
reach them on" are different questions. Addresses flagged not-working are never collected under
any flag, and are dropped again at send time.

### Circulation history

Every run is recorded permanently and reachable from the **History** dropdown on the Circulars tab.
Opening one shows when it ran, the identity it went out under, the list and footer it used, and
every address it touched — including those skipped as duplicates or as dead, and those a stopped
run never reached. Clicking a recipient reproduces **the exact message that person received**, both
the HTML and the plain-text alternative.

The recipients table has checkboxes and an **Add … to current list** button, so a past
circulation can seed the next one. It acts on the ticked rows, or on whatever the Outcome filter
leaves on screen when nothing is ticked — so filtering to *failed* or *not reached* and adding
them all is how you build a chase list. Copying never alters the history entry.

That reproduction is not a stored copy per recipient. The composed circular is written **once** per
run, and each recipient row stores only the mail-merge fields it was rendered with; since the merge
is a pure function of the two, replaying it reproduces the message byte for byte. A 300-address run
therefore costs one copy of the body rather than three hundred, which is what makes keeping the
history indefinitely reasonable. Template, footer and list are recorded by **name**, so deleting a
footer later cannot rewrite what history says was sent.

Endpoints: `GET /api/v1/circulations` (paged, newest first), `GET /circulations/{id}`,
`GET /circulations/{id}/recipients/{recipientId}/message`, `DELETE /circulations/{id}`.

### Pausing, resuming and restarting

A circulation can be stopped part-way and picked up later, and a finished one can be sent
again. Neither needs anything held in memory: the history above already stores the composed
circular and every address with its own status, so **the queue for a resume is simply the rows
still marked `PENDING`**. That is what makes a resume survive an API restart — there is no
in-flight state anywhere to lose, only rows to read back.

- **Pause** stops after the message already handed to the transport (stopping sooner would have
  history claim someone was never reached who has the circular in their inbox) and leaves the
  run open as `PAUSED`. **Cancel** stops the same way but closes the run as `CANCELLED`.
- **Resume** carries the *same* run on, mailing only what it never reached — one circular sent
  over two sittings stays one entry in history, so "who received this?" keeps one answer. The
  progress bar and counters continue from where they stopped rather than restarting at zero.
- **Restart** opens a *new* run over the same circular and the same addresses. The first send
  happened; rewriting its record afterwards would make the history useless. The circular is
  replayed exactly as that run stored it, footer included, so a re-send is the same message
  even if the footer has been edited or deleted since.
- A run left `RUNNING` by an API restart is reopened as `INTERRUPTED` at next startup rather
  than abandoned, with its run-level counters rebuilt from the recipient rows. Shutting the API
  down mid-send asks the run to pause and gives it a moment to record that, so the usual case
  is a clean `PAUSED`; `INTERRUPTED` is the backstop for a hard kill.
- Anything paused, cancelled, aborted or interrupted with people still to reach is offered on
  the Circulars tab as a banner, and carries **Resume** and **Restart** in its History entry.
  *Not now* hides the banner in this browser only — the run stays resumable, because having
  people still to reach is a fact about the run and not a preference.
- A resume re-checks the remaining addresses against the not-working flags, so one flagged dead
  during the pause is dropped then rather than mailed. Those move from the run's total to its
  skipped count, which is why a resumed run can finish with a smaller total than it started with.
- Only one campaign runs at a time, so resume and restart are refused while another is sending.

Endpoints: `POST /api/v1/campaigns/current/pause`, `GET /campaigns/resumable`,
`POST /campaigns/runs/{runId}/resume`, `POST /campaigns/runs/{runId}/restart`.

### Settings

The **Settings** tab (bottom of the sidebar) edits the circulation knobs at runtime, stored in
`app_settings` (`db/app_settings.sql`):

| Setting | Default |
|---|---|
| From name / address | `Maritella Chartering Desk` / `desk@example.com` |
| SMTP host / port | `smtp.zoho.eu` / `465` |
| Gap between messages (random within the range) | 3–10s |
| Max recipients per run | 200 |
| Pause between runs of a split circulation | 15 min |

Only values you actually change are stored. An absent key falls through to `application.yml` and
therefore to the `.env` variables, so those stay meaningful as the baseline for a fresh
deployment and **Reset to defaults** is simply a delete. A row holding an unreadable value falls
back to the default rather than breaking a send.

Changes apply to the **next** circulation started; a run already in flight keeps the pacing and
cap it began with, the same rule the footer follows. Changing the port moves the TLS mode with
it by convention — 465 means implicit SSL, anything else STARTTLS — because a port change alone
would otherwise just fail to connect; leaving the port alone keeps whatever `MAIL_SSL` /
`MAIL_STARTTLS` said.

The From identity is editable because it is not a secret, but it is not free either: Zoho (like
every provider) refuses a From that is not the authenticated mailbox or one of its verified
aliases, so changing it to an unverified address will make every send fail.

**Credentials are not settings.** `MAIL_USERNAME`, `MAIL_PASSWORD` and `MAIL_REPLY_TO` stay in
`.env`: this table is served to the browser, which is the wrong place for a mailbox password.

Endpoints: `GET /api/v1/settings/circulation`, `PUT` to change, `DELETE` to reset.

### The campaign log

`logs/campaign-current.log` (bind-mounted from the container) records every recipient with
its outcome. A run that finished cleanly is **overwritten** by the next one; a run that
failed, aborted, was cancelled or was paused is **rotated** to `campaign-current-<timestamp>.log`
first,
since that's exactly the record you need to see who already received the circular. The
outcome is recovered from the log's own end marker, so the rule survives an API restart.

It is a convenience view of the run in progress, not the record of it — the durable audit trail is
the circulation history above, which survives rotation, restarts and the next run.

Endpoints: `POST /api/v1/campaigns` (202, sends in the background), `GET /campaigns/current`,
`POST /campaigns/current/cancel`, `POST /campaigns/current/pause`, `GET /campaigns/current/log`,
`POST /campaigns/test?to=…`, `GET /campaigns/config`, plus the resume/restart endpoints above
and CRUD on `/api/v1/email-templates` and `/api/v1/email-footers`.

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

Going the other way — refreshing the dumps *from* the current database — is
`refresh-db-export.bat` for `db-export/`, and [db-export/EXPORTING.md](db-export/EXPORTING.md)
for all four files plus the checks worth running before trusting them.
