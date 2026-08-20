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

**Time is local, not UTC.** Both containers run on `TZ` (default `Europe/Warsaw`), so every
timestamp the app shows — circulation history, the campaign log, the day counter on the
Circulars tab — is wall-clock time where the desk is. Timestamps are stored as absolute
instants, so changing `TZ` re-reads existing history in the new zone rather than shifting it:

```bash
# in .env
TZ=Europe/Athens
docker compose up -d --force-recreate api db
```

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
  no_circ_flag.sql       # idempotent patch: "never circulate to this address" flag (baked in)
  whatsapp_flag.sql      # idempotent patch: "this number is on WhatsApp" flag (baked in)
  app_settings.sql       # idempotent patch: runtime settings edited from the Settings tab (baked in)
  mailbox.sql            # idempotent patch: synced mail, its folders and its filing rules (baked in)
  circulation_provider.sql # idempotent patch: which flow each message left by (baked in)
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
previews; it just refuses to send and shows which settings are missing. `MAIL_ENABLED` is the
master switch for both flows below.

### Two ways to send

A circular can leave by either of two routes, and the choice is a **runtime setting** — the
*Use Brevo for circs* checkbox on the Settings tab — not an environment variable. The Circulars
tab shows which one is in force as the first of its config tags (`via Mailbox (SMTP)` /
`via Brevo API`), because it decides what the recipient sees and whose quota is being spent, and
nothing else on that screen would give it away.

| | **Mailbox (SMTP)** — the original flow | **Brevo API** |
|---|---|---|
| How it leaves | one SMTP message per recipient from your own mailbox | one `POST /v3/smtp/email` per recipient |
| Appears in your Sent folder | yes | no — Brevo does the delivering |
| Whose reputation is at stake | your mailbox's | the Brevo account's |
| A bounce costs | a strike against the mailbox | a bounce record in Brevo |
| Realistic pace | ~11 min per 100 recipients | ~1 min per 100 recipients |
| Credential | `MAIL_USERNAME` / `MAIL_PASSWORD` | `BREVO_API_KEY` |

Everything *around* the send is identical: pacing, retries, the circuit breaker, the circulation
history, pause/resume/restart, the campaign log, and the mail merge. Only the transport differs,
so a circulation is recorded the same way whichever route it took — and a run paused under one
provider simply finishes under whichever is selected when it is resumed.

The **From identity is shared**: both flows send as the address on the Settings tab, so
recipients see one sender either way.

**Zoho notes (SMTP flow).** `MAIL_USERNAME` is the full mailbox address, and `MAIL_PASSWORD`
must be an app-specific password (Zoho → Security → App Passwords) whenever two-factor auth is
on the account — the normal login password is rejected over SMTP. `MAIL_FROM` has to be the
authenticated account or a verified alias, or Zoho refuses the message. SMTP access must be
enabled under Mail Settings → Mail Accounts.

**Brevo notes.** Two one-off steps in Brevo before the checkbox is usable:

1. *SMTP & API → API Keys* → generate a **v3** key and put it in `.env` as `BREVO_API_KEY`.
2. *Senders, Domains & Dedicated IPs* → **verify the From address**. Brevo refuses anything
   else, and it is the Settings-tab From that is used. An unverified sender shows up as a
   `400` naming the address, recorded against that recipient like any other permanent failure.

The key is checked against `GET /v3/account` before the first message of every run, so a wrong
or revoked key is one error on screen rather than 200 identical failures. Per-message delivery
logs, bounces and blocks live in Brevo's own dashboard; the app records only what it was told at
send time, exactly as it does for SMTP. Errors map onto the same three outcomes the SMTP flow
uses: `401`/`403` aborts the run, `429` and `5xx` retry with backoff, any other `4xx` is
permanent and skips the address.

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
| Provider reachability checked before the first message (SMTP connect, or Brevo `GET /v3/account`) | always on |
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

Nothing in the app enforces a daily cap, so the Circulars tab carries a **`N sent today`**
counter beside the pacing tags — every circular email delivered since local midnight, across
every circulation, climbing live while a campaign runs. It counts each address by its own send
time, not by the run it belongs to, so a circulation started last night and resumed this morning
puts its messages on the day they actually left. Read it against your plan's daily limit before
starting another circular.

### Today, split by flow

The counter breaks down by the route each message took — `12 sent today · 8 mailbox · 4 Brevo ·
296/300 left` — on the Circulars tab as tags, and on the Settings tab as a fuller panel. Both
read the same query, so the two tabs cannot drift into quoting different totals.

**The two halves are counted differently, and have to be.** SMTP gives no way to ask a mailbox
what it has already sent, so the mailbox figure can only be counted here, from circulation
history. Brevo can be asked, and is: `GET /v3/smtp/statistics/aggregatedReport` for the day's
volume and the `sendLimit` entry of `GET /v3/account` for what is left of the allowance.

That distinction matters, because **Brevo's figure is account-wide**. It includes anything sent
on that key's account — a campaign launched from Brevo's own dashboard, another integration, a
test send from this app (which deliberately writes no history) — and it is Brevo's number, not
this app's, that the cap is enforced against. A purely local tally would read "plenty left" right
up to the send Brevo refuses, which is exactly the failure this is meant to prevent. So both are
shown: `viaBrevo` is what this app sent, `brevo.sent` is what the account spent.

**The 300/day ceiling is derived, not hardcoded.** Brevo publishes only the remainder, so the
limit is read back as `sent + remaining` — which stays correct on a paid plan, and if the free
tier's allowance ever changes. A plan carrying purchased credits rather than a daily ceiling
reports no `remaining` at all, and the panel says so instead of inventing one. Brevo is asked at
most twice every 30 seconds however hard the tab polls; a reporting call that cannot reach Brevo
shows the reason and leaves the mailbox half of the figure untouched.

Which flow each message left by is recorded **per recipient**, not per run (`db/circulation_provider.sql`).
A circulation paused under the mailbox flow and resumed after the switch genuinely left by two
routes, and a run-level column would have to lie about one half of it. Rows that predate the
column are backfilled `SMTP`, because that was the only flow there was.

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
reach them on" are different questions.

#### Keeping an address out of circulations

Two flags do that, and they mean different things:

| Flag | Means | The address is |
|---|---|---|
| **not working** | the mailbox is dead — it bounced, or the account is gone | unusable for anything |
| **not for circ** | it works, it just must never be bulk-mailed | still the right one to write to by hand |

`not for circ` (`db/no_circ_flag.sql`) is for an `accounts@` or `ops@` inbox, or a broker who asked
to come off the circular. Without it the only way to achieve that was to mark a live address dead,
which loses real information: the address stops being offered anywhere, and nobody later can tell a
bounced mailbox from a deliberate exclusion.

Both are honoured **twice** — left out of bulk collection, and dropped again when a campaign starts
— so an address already sitting in a saved list still cannot be mailed, and one flagged during a
pause is dropped when the run resumes. The circulation history records *which* of the two applied
rather than collapsing them, because "their mailbox is dead" and "they are off the circular" send
you somewhere completely different when someone asks why a broker never heard from us.

`not for circ` is the exact opposite of `circ`, so setting either clears the other — the two could
otherwise be held at once, leaving the address in a state no rule could read.

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

| Setting | Default (SMTP) | Default (Brevo) |
|---|---|---|
| Use Brevo for circs | off | — |
| From name / address | `Maritella Chartering Desk` / `desk@example.com` | same |
| SMTP host / port | `smtp.zoho.eu` / `465` | unused |
| Gap between messages (random within the range) | 3–10s | 0.2–0.8s |
| Max recipients per run | 200 | 500 |
| Pause between runs of a split circulation | 15 min | 1 min |

Only values you actually change are stored. An absent key falls through to `application.yml` and
therefore to the `.env` variables, so those stay meaningful as the baseline for a fresh
deployment and **Reset to defaults** is simply a delete. A row holding an unreadable value falls
back to the default rather than breaking a send.

**Pacing is stored per provider.** One set of knobs on screen, but two sets of values behind
them, because three seconds between messages is prudent through a personal mailbox and merely
wasteful through an ESP: the mailbox rails exist to stop an account being suspended for
exceeding its hourly cap, while Brevo absorbs the rate itself and only its *daily* plan
allowance really binds. So ticking the box swaps the pacing to something appropriate straight
away, and unticking it hands the mailbox flow back exactly the numbers it had — nothing is
overwritten, and **Reset to defaults** covers only the provider on screen. The SMTP flow keeps
the original unprefixed `app_settings` keys, so an installation tuned before this feature
existed carries its settings forward with no migration.

Changes apply to the **next** circulation started; a run already in flight keeps the pacing and
cap it began with, the same rule the footer follows. Changing the port moves the TLS mode with
it by convention — 465 means implicit SSL, anything else STARTTLS — because a port change alone
would otherwise just fail to connect; leaving the port alone keeps whatever `MAIL_SSL` /
`MAIL_STARTTLS` said.

The From identity is editable because it is not a secret, but it is not free either: Zoho (like
every provider) refuses a From that is not the authenticated mailbox or one of its verified
aliases, and Brevo refuses one that is not a verified sender on the account — so changing it to
an unverified address will make every send fail.

**Credentials are not settings.** `MAIL_USERNAME`, `MAIL_PASSWORD`, `BREVO_API_KEY` and
`MAIL_REPLY_TO` stay in `.env`: this table is served to the browser, which is the wrong place
for a mailbox password or an API key with full send rights. The Settings tab does report
*whether* a Brevo key is present, so a checkbox that could not work says so before it is used.

Endpoints: `GET /api/v1/settings/circulation`, `PUT` to change, `PUT /provider` to switch flows,
`DELETE` to reset. The provider is its own call rather than a field on the form: the pacing in
the form belongs to whichever provider is in force, so the switch has to land first and the form
redraw with the new values — one combined save would write the old provider's numbers against
the new one.

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

## WhatsApp on contacts

A phone number that turns out to be on WhatsApp is worth knowing about — it is often the only way
to get a quick answer out of a broker who does not read email until the afternoon. Two things
support that: a way to find out, and a place to remember the answer.

**Finding out.** There is no API behind this button and the app does not pretend otherwise:
nothing available to us can ask WhatsApp whether a number is registered — the Business API's
contact check is not open, and scraping `wa.me` would be both against the terms and unreliable.
So the check is the honest one. With **Edit** on, every phone row carries a **WA?** button whose
popup shows the number exactly as it will be dialled, opens `https://wa.me/<number>?text=<message>`
in a new tab, and then asks what you saw. WhatsApp itself gives the answer: a chat opens, or it
tells you the number is not registered.

**Remembering it.** Saying yes sets `has_whatsapp` on the contact (`db/whatsapp_flag.sql`), and
from then on the number carries a green WhatsApp icon linking straight to that chat, with the same
greeting prefilled. That link is *not* behind the Edit toggle — messaging someone is reading the
contact, not editing it, and being able to reach them from wherever the number happens to be
listed is the entire point of having flagged it. The flag is somebody's observation, never
inferred and never cleared automatically; the same popup clears it if a number goes dead.

**The numbers are messy.** Thirty years of hand-typed values means `+38-050-472-44-19`,
`+49 (0) 521 5225178` and `0216 333 20 00 - 2419` all sit in the same column, while `wa.me` wants
bare international digits. Everything but the digits is stripped, and a `(0)` is dropped because
it is a national trunk digit spelled out for a domestic caller. What cannot be repaired is
flagged rather than blocked: a number starting `0` has no country code, one under 8 digits is too
short, one over 15 usually has an extension typed into the same field. The popup says so and
still opens the link — you are about to look at the result in WhatsApp anyway, which is a better
check than any rule here, and refusing to open it would only hide the number that needs fixing.

**The message** is one field on the **Settings** tab, defaulting to `Good day, {{greeting}}`. It
takes the same placeholders as a circular — `{{greeting}}`, `{{name}}`, `{{title}}`, `{{company}}`
— substituted in the browser from the contact on the row, with the same fallbacks (greeting name,
then the person's name, then "Sirs" for a number filed against a company with nobody's name on
it). `{{email}}` is not offered: it can mean nothing on a phone contact. Nothing is ever sent by
the app — the text arrives typed into the chat box and you press send, or don't.

Endpoints: `PATCH /api/v1/contacts/{id}/whatsapp?hasWhatsapp=…` for the flag (phone contacts
only — an email can never be on WhatsApp, so the call is refused rather than storing a fact that
could not be true), and `GET`/`PUT`/`DELETE /api/v1/settings/whatsapp` for the message.

## Mailbox (incoming mail)

The **Mailbox** tab is the other half of the correspondence: mail that arrives, synced from
IMAP into Postgres, attached to the company it came from, and filed into folders by rules
the desk writes. It is what lets "who is this from?" be answered by the app that already
knows every broker, rather than by a mail client that knows none of them.

**The mailbox is opened read-only.** The app sets no flags, moves nothing and deletes
nothing on the server. Folders and rules here are the app's own, stored in `mail_folders` /
`mail_rules` — so filing a message moves a row in this database, and the worst a mis-written
rule can do is rearrange that table. Your real mailbox is exactly as you left it.

```bash
# in .env  (see .env.example for the full annotated block)
IMAP_ENABLED=true
IMAP_HOST=imap.zoho.eu
IMAP_PORT=993
# left blank these fall back to MAIL_USERNAME / MAIL_PASSWORD
IMAP_USERNAME=
IMAP_PASSWORD=

docker compose up -d --force-recreate api
```

Until `IMAP_ENABLED=true` and the credentials resolve, the tab still loads and names the
settings that are missing. Zoho needs an app-specific password when two-factor auth is on the
account, exactly as SMTP does, and IMAP access must be enabled under Mail Settings → Mail
Accounts.

### What is synced, and how much

A poller wakes every `IMAP_POLL_MS` (default 5 min) and asks for what arrived above the last
IMAP UID it recorded. Two bounds keep it honest:

| | |
|---|---|
| **First sync** | no cursor to resume from, so it takes the newest `IMAP_MAX_PER_POLL` messages and keeps those inside `IMAP_INITIAL_DAYS` (default 30). Pointing the app at fifteen years of mail must not mean downloading fifteen years of mail. |
| **Every sync** | capped at `IMAP_MAX_PER_POLL` (default 200), **oldest first**. A backlog drains over several polls in arrival order, so the cursor only moves forward and an interrupted catch-up resumes where it stopped. |

Headers, both body parts and the *names* of attachments are stored; the attachment files
themselves are not. Deduplication is by `Message-ID`, which is the only identifier that
survives a re-fetch, a re-index, or the provider reissuing UIDVALIDITY — so a re-sync is
idempotent rather than a second copy of the inbox. When UIDVALIDITY does change, the reader
notices, falls back to the date window instead of a cursor that has quietly started lying,
and the dedupe absorbs the re-read.

**Read/unread is the app's own.** It is seeded once from the server's `\Seen` flag when a
message is first stored, and owned here from then on. It cannot be otherwise: the app never
writes to the mailbox, so following the server afterwards would keep resetting what was read
here.

### One search box, and one checkbox

Address, person, company and subject all go in the same field. Every whitespace-separated
term has to match somewhere, but no term is tied to a particular field — so `ali position`
finds Ali's position list, and typing an address, a name or a company all work without first
deciding which kind of thing you are typing. It is the same rule the circulation-list search
follows, deliberately: two search boxes in one app that behave differently is worse than
either behaviour.

**"Search message text" is the checkbox beside it, and it is off by default.** Sender,
subject, recipients and the linked company are trigram-indexed and searched always. The
bodies are not indexed at all — a GIN index over every message body is large, slow to write
on each sync, and would be maintained for a search that is usually off. So the message-text
search is a sequential scan of the largest columns in the table, taken knowingly, when it is
asked for.

### Folders and rules

Rules run **as mail arrives**, in `sortOrder`, and the **first match wins** — a message lives
in one folder, so "every matching rule applies" would really mean "the last one applies".
Each rule is a target folder plus one or more conditions:

| Field | Tests |
|---|---|
| `FROM` | the sender's address *and* display name |
| `FROM_DOMAIN` | only the part after the `@`, so a display name quoting the domain cannot match |
| `TO` | To and Cc |
| `SUBJECT` | the subject |
| `BODY` | the message text |
| `ANY` | all of the above together |

with `contains` / `does not contain` / `is exactly` / `starts with` / `ends with`, combined as
**all** or **any**. A rule may also mark what it files as read. A rule with no conditions is
refused — it would match every message and empty the Inbox into one folder.

**Rules never touch mail you filed by hand.** They claim a message only when it is in the
Inbox, or when a rule put it where it is. Without that line, correcting a mis-filed message
would last exactly until the next sync. Moving a message back to the Inbox by hand returns it
to the rules' reach, which is what "put it back" ought to mean.

Because rules are evaluated against stored rows rather than live IMAP messages, **Apply now**
re-runs them over mail that arrived before the rule existed. Mail that a rule had filed and
that no rule now claims goes back to the Inbox, so rule-managed mail stays a function of the
rules as they stand. Deleting a folder returns its mail to the Inbox rather than deleting it.

### The link to companies

The sender's address is resolved against `contacts` at sync time: contact → person → company.
A message from a known address carries its company straight through to the company drawer.

For an unknown sender, **Link to company** attaches it by hand, and offers to record the
address as a contact at the same time — that is the half worth doing, since it makes every
*later* message from that sender link itself and puts the address in front of the rest of the
app (the company drawer, the circulation lists, the bulk collect). A hand-set link is flagged
`link_manual` and no automatic pass will overwrite it. `POST /api/v1/mailbox/relink`
re-resolves every *automatic* link against the contacts as they are now — worth running after
adding a batch of contacts, since the link is otherwise resolved only once, at sync time.

Endpoints: `GET /api/v1/mailbox/messages` (the search above), `GET /mailbox/messages/{id}`
(full body, HTML sanitized on the way out; opening marks it read), `PATCH …/read`,
`PATCH …/folder`, the `POST /mailbox/messages/{read,folder}` bulk pair, `PUT|DELETE …/link`,
`POST /mailbox/relink`, `GET /mailbox/status`, `POST /mailbox/sync` (202 — runs on its own
thread), plus CRUD on `/api/v1/mailbox/folders` and `/api/v1/mailbox/rules` and
`POST /mailbox/rules/apply`.

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
