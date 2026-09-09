# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Git workflow

**`main` is the source of truth.** `render` is a deployment branch and is only ever updated
by merging `main` into it — never commit to `render` directly, and never merge a feature
branch into it.

Each new feature goes on its own `feature/<feature_name>` branch. When development is done,
open a PR into `main`.

What the user's words mean:

| They say | Do this |
|---|---|
| "push" | Push the current working branch. Nothing else. |
| "push and merge into main" | Merge into `main` directly, no PR. |
| "deploy to render" | Merge `main` into `render`. |

Pushing `render` triggers a Render deploy, so treat it as an outward-facing action.
`render.yaml` pins no `branch:` — which branch each service tracks is set in the Render
dashboard.

**A deploy that carries a changed `render.yaml` re-syncs the blueprint, and every key the
file pins a `value:` for is re-asserted over whatever the dashboard holds.** It is not a
default that a dashboard edit overrides; it is an assertion, reapplied. This has already
cost one outage — `MAIL_ENABLED` had been switched on by hand months earlier and a commit
touching an unrelated key in the same file turned sending back off, with the mailbox still
syncing (that is IMAP, and a different switch) so that nothing looked wrong until somebody
tried to answer a broker. Keys the file does not mention are left alone, which is why the
credentials survived. So: a value that is a fact about the repository (`ANALYSIS_ENABLED`,
`MAIL_REPLY_PROVIDER`) is pinned deliberately; a value that is a fact about one deployment
gets `sync: false`, which names the key and leaves the dashboard owning it.

## Commands

```bash
# whole stack (api + ui), the normal way to run it
docker compose up -d --build
docker compose up -d --force-recreate api    # after changing .env only

# api alone: needs JDK 21 + Postgres on localhost:5433 (the sibling chartering-db project)
cd api && AUTH_PASSWORD=dev JWT_SECRET=a-development-signing-key-32-chars-long mvn spring-boot:run
mvn -q compile -DskipTests                   # compile check
mvn test                                     # all tests
mvn test -Dtest=AuthServiceTest              # one test class
mvn test -Dtest=AuthServiceTest#methodName   # one test method

# ui: needs Node 20. Vite proxies /api -> localhost:8081
cd ui && npm install && npm run dev
npx tsc --noEmit                             # typecheck alone (npm run build does this first)
```

`mvn spring-boot:run` does **not** read `.env` — that file belongs to compose. Without
`AUTH_PASSWORD` in the environment every request comes back 401.

There is no linter and no UI test runner. `npm run build` is `tsc --noEmit && vite build`,
so a typecheck failure fails the build.

## Architecture

Spring Boot 3.4 / Java 21 REST API (`com.chartering`) + React 18 / Vite / Ant Design SPA,
two containers wired by compose. The UI's nginx reverse-proxies `/api` → the api service,
so the browser is always same-origin and CORS is irrelevant behind it.

**The database is not in this repo.** It is a connection string (`DB_URL`), normally a
hosted Postgres. A sibling project `../chartering-db` provides a local one on port 5433 for
working offline and for trying a migration somewhere harmless. Nothing in this project holds
state, which is why compose has no `db` service, no volume, and no `down -v`.

API layering is plain and deliberately thin: `controller` → `service` → `repository`, with
one shared `mapper/DtoMapper` doing all entity→DTO mapping (services stay thin, mapping stays
consistent) and `specification/*Specification` holding the JPA criteria for filtered search.
Response DTOs are records annotated `@JsonInclude(NON_NULL)`, so a null field is absent from
the JSON rather than sent as null. List endpoints return the custom `PageResponse<T>`, never
Spring's `Page<T>`.

### The schema is owned by Flyway

Migrations in `api/src/main/resources/db/migration/` run on api startup, before Hibernate.
`ddl-auto: validate` is a second opinion, not the mechanism — if it refuses to start and
names a column, an entity changed without a migration.

Three things bite here:

- **Numbering starts above 2.** The databases in use were adopted at `baseline-version: 2`,
  so Flyway records anything at or below V2 as already applied and silently never runs it.
  `V1__baseline_schema.sql`, `V3__add_person_job_title.sql`,
  `V4__add_company_country_website_and_contact_label.sql`, `V5__add_data_changes.sql`,
  `V6__add_contact_from_file.sql`, `V7__add_mail_replies.sql`,
  `V8__add_analysis_samples.sql`, `V9__add_trade_areas.sql`, `V10__seed_trade_areas.sql`,
  `V11__add_vessel_ex_names.sql`, `V12__add_vessel_specs.sql`, `V13__add_cargoes.sql` and
  `V14__add_vessel_positions.sql`, `V15__add_trade_area_aliases_from_corpus.sql`,
  `V16__add_email_parsing.sql`, `V17__add_vessel_lookups.sql`,
  `V18__add_port_geography_and_sea_routes.sql` and
  `V19__seed_sea_routes_and_port_geography.sql`, `V20__add_intake_item_sources.sql` and
  `V21__collapse_duplicate_pending_vessel_items.sql` and
  `V22__recover_former_names_from_change_log.sql` and
  `V23__add_cargo_max_ballast_days.sql` exist; the next one is V24.
- **A migration deployed from an unmerged branch makes `main` undeployable, and it has
  happened.** V8 reached the hosted database from `feature/ai_email_parsing` before that
  branch reached `main`. Every build from `main` then refused to start, because
  `out-of-order` is off and `validate-on-migrate` is on: Flyway found an applied V8 with no
  file behind it and failed with *"Detected applied migration not resolved locally: 8"*. It
  is not a corrupt database and `flyway repair` is not the fix - the fix is that the branch
  carrying the migration must be merged before, or in the same deploy as, the migration
  itself.
- **`db/migration/.gitattributes` marks `*.sql` as `-text`** and must stay. Flyway checksums migrations,
  and a rewritten line ending is a changed checksum — an app that will not start in whichever
  environment did not apply the file first. Source files in this repo are a mix of CRLF and
  LF; migrations must round-trip byte-for-byte.
- **Never edit a migration that has run.** Corrections are the next migration. Flyway
  Community has no undo, so backing one out is a manual `ALTER TABLE`.

### The domain: companies, people, contacts

A `Contact` is **one email address or phone number**, not a person. It hangs off a `Person`,
a `Company`, or both. Person-less + company-set is a *company-wide* address — a `chartering@`
or `ops@` desk — and is a supported shape everything must handle, not an edge case.

Facts that belong to the **person** are stored once there and read through their contacts,
never copied down onto each address: `hasLeft` (they left the company, so every address of
theirs is off circulations), `jobTitle` (their position), and the greeting fallback. The
reasoning is always the same — one human with three addresses would otherwise carry three
copies of the same fact, free to drift apart. `Person.title` is the **honorific** ("Mr.",
"Capt.", varchar(20)) printed before the greeting name; it is not a job title.

`Contact.greetingName` is the one deliberate override: the contact's own greeting wins, else
the person's, else the merge falls through to a neutral salutation. `DtoMapper` exposes both
the effective value (`greetingName`) and the raw override (`ownGreetingName`) — edit forms
must use the raw one, or saving pins a frozen copy of the person's greeting onto the contact.

Contact flags are not interchangeable and each means something specific: `main` (one per
company per kind), `circ` (use this in circulations), `noCirc` (never bulk-mail this, but it
is still the right address to write to by hand), `working` (false = bounced/disconnected),
`banned`, `hasWhatsapp` (recorded by hand — WhatsApp cannot be queried). `label` (Work,
Mobile, Direct, Fax) is phones-only and free text, so an imported label survives whatever
word its source used; an email carrying one would be a guess about the person rather than a
fact about the address, and the service clears it on a kind change.

### Importing a contacts file

`POST /contacts/import/preview` parses an export and reports what it would do; `POST
/contacts/import` writes the preview as the user left it. Nothing is stored between the
two — the whole parse travels to the browser and back, so there is no staging table, no
import id, and an abandoned review costs nothing.

The review step is not ceremony. A real export arrives with a company named by its own
advertising slogan, a website column holding an email address, one mailbox listed against
two managers, and phone labels buried inside a comma-joined cell
(`Work,+32.3.821.13.35,Mobile,+32.475.89.02.67`, where a label governs every number after
it until the next one). All of that parses cleanly and all of it is wrong, so the screen
that shows the result before it is a result is the feature.

Three rules worth knowing:

- **An address listed against two people at one company becomes company-wide** — person
  null, company set, the `chartering@` shape `RecipientSelectionService` already groups on
  its own. Filing it under whichever row came first would make the choice an accident of
  ordering; duplicating it would mail the desk twice.
- **A matched company or person is never overwritten, only gap-filled.** The file is a lead
  sheet, not a source of record: a city somebody typed beats one scraped off a signature
  block, and losing it silently is the worse failure.
- **Everything lands unconfirmed and unflagged** — not main, not `circ`. Eighty addresses
  arriving pre-flagged for circulation is one send away from a bounce storm.
- **An imported address is marked `from_file`, and is not `legacy`.** `is_legacy` means
  carried over from the old database, and an address met at a trade fair last month is new
  data whichever door it came in through — so the two are separate columns and a file
  import is never legacy. Between them they answer the People tab's Source filter: added
  in the app (neither), imported from a file, or out of the old database. The filter asks
  about the *address*, so an import that added one number to somebody already on file
  brings that person back, which is what reviewing an import wants.

Company matching is exact (case-insensitive) or nothing; a name that differs only by its
legal form comes back flagged `similar` as a *suggestion*, never applied silently, because
two firms a broker keeps apart must not be merged by an importer.

### The change log

Every write to an audited entity lands in `data_changes` — one row per changed field for an
update, one row carrying a JSON snapshot for a create or a delete. `field_name` tells the
two shapes apart.

Nothing calls it. A Hibernate post-insert/update/delete listener (`audit/`) reads the state
arrays Hibernate already has and writes through **plain JDBC on the transaction's own
connection**. All three parts are load-bearing:

- **The listener, not the services** — the before value is already loaded, so there is no
  second query and nothing for a service to remember. A change made from a form, the
  importer or a one-off fixup is logged identically, because they all end in a flush.
- **JDBC, not the EntityManager** — persisting during a flush appends to the action queue
  being drained, which is how a flush becomes a `ConcurrentModificationException`.
- **The same connection** — the log commits with the data or dies with it. A log that can
  survive its own rollback records edits that never happened.

Do not move this to a `beforeCommit` hook. Spring's `JpaTransactionManager` fires those
*before* the session flushes, so the hook would run before the updates it describes exist.

`AuditedEntities` is a **whitelist**, and that is deliberate: auditing `mail_messages` would
write more history per sync than the sync writes messages, to record a machine copying a
mailbox to itself. Synced mail, circulation runs (already history), list entries (a working
document) and the reference tables are out. Adding an entity is one line there and nothing
else.

`ChangeContext.describe("…")` names the current transaction's change set — the importer uses
it, so eighty creates read as one event. Every row of a transaction shares a change-set id
and one timestamp.

**Reverting is one field of one update, and only that.** A create's undo is a cascading
delete (`people.company_id` and `contacts.company_id` are both `ON DELETE CASCADE`); a
delete's undo either reuses an id the sequence has moved past or takes a new one and leaves
every reference dangling. Both are data-repair jobs with the snapshot in hand, not buttons.
A revert is refused if the field changed again since, and is itself logged as an ordinary
edit.

### Who a circular actually goes to

`RecipientSelectionService` holds the rule, applied **per person**, with a company's
person-less addresses forming one more group of their own:

```
any address flagged circ?  -> take all of them
else a main address?       -> take that one
else                       -> take every working address
```

Dead, banned and `noCirc` addresses are excluded **in the query**, before grouping — which
matters, because an address removed first cannot win its group. Exclusions are honoured
twice: at bulk collection, and again at send time, so an address already sitting on a saved
list still cannot be mailed.

### Circulation lists and sending

Recipients live in **circulation lists** in Postgres: one unnamed *current list* (what the
Circulars tab sends to) plus any number of saved lists. A list is a **prepared document** —
editing a row edits the list, never the contact record, and mail-merge fields are snapshotted
when the row is added.

A circular is sent **individually to every address, never CC/BCC**. There are two send routes
— Mailbox SMTP and the Brevo API — and the choice is a *runtime* setting stored in
`AppSetting`, not an environment variable. `MAIL_ENABLED` is the master switch for both;
with it off the UI still composes and previews, and explains what is missing instead of
offering a Send button.

### Mailbox

Read-only IMAP sync into `mail_messages`. The rail shows two taxonomies side by side: the
server's own folder tree (mirrored into `mail_server_folders` each sync, system folders
matched by IMAP SPECIAL-USE rather than by name, since they may not be in English) and the
app's own folders/rules. Every server folder is synced, so mail diverted by a server-side
filter is still visible.

**Replying is the one thing that writes** — and it writes over SMTP, not over IMAP. The sync
stays read-only: nothing is appended to a folder, no flag is set. A reply goes out through
the mailbox and comes *back* through the ordinary sync, as the provider's own copy in the
Sent folder.

- **The mailbox, not whichever provider circulars are set to.** A reply has to come from the
  address the correspondent wrote to and thread with what they sent; Brevo is bulk
  infrastructure with its own envelope and reputation. The circulars setting has no bearing
  on it in either direction.
- **`MAIL_REPLY_PROVIDER=BREVO` is the one exception, and it is a deployment fact, not a
  preference.** Some hosts do not permit the mailbox flow at all: Render blocks outbound
  ports 25, 465 and 587 on free instances, and the symptom is not a refusal but silence —
  IMAP 993 is untouched, so the mailbox syncs normally and only replying fails, with
  "Connect timed out" after fifteen seconds. There the choice is Brevo or no reply, so the
  variable exists and `render.yaml` sets it. It is **not** a Settings-tab option and must
  not become one: settings live in `app_settings`, and the hosted instance and the office
  one point at the same database while needing opposite answers. Everything below the
  transport is identical — same composition, footer, merge, quoted original, same
  `mail_replies` row. What is given up is real and permanent: no Sent-folder copy at all,
  and threading only if Brevo passes `In-Reply-To` through, which its API documents as
  carrying non-standard headers only. It also needs the sending *domain* authenticated in
  Brevo rather than a single verified sender, because the From is the mailbox address and
  not the one circulars go out as. A paid instance unblocks 465/587 and is the better fix.
- **The footer, the quote and the merge are applied server-side**, so what is stored as
  having been sent is the string the mail server was handed. The composer holds only what
  the user typed — which is also why a 100KB Outlook chain is not in the editor.
- **`email_footers` carries two "default" flags**, `is_default` for circulars and
  `is_reply_default` for replies, each with its own partial unique index. A circular closes
  with the desk's full block; a reply inside somebody else's thread usually wants three
  lines.
- **`mail_replies` is not `mail_messages`.** That table is a mirror of the server, written
  only by the sync; a row this app invented would be a message no folder holds. The reply
  table exists anyway because it is written the moment the send returns (so the day's count
  is right before the next poll), it survives a provider that keeps no Sent copy, and it is
  the only record of *which* message was answered — In-Reply-To is not among the headers the
  sync stores. Failures are not recorded: a reply that did not send is an error on a screen
  still holding its text.

### What "sent today" counts

Three sources, three failure modes, and they are never added together.

- **Circulars this app sent**, from `circulation_run_recipients` — split into SMTP and Brevo.
- **Brevo's account-wide figures**, asked of Brevo, because its allowance is spent by
  everything on the account.
- **What the mailbox itself sent**, counted from the synced Sent folder — which is the only
  way a reply written in Outlook, the webmail or on a phone can be counted at all, and the
  reason the counter is honest about the mailbox's daily cap.

The third overlaps the first: the provider files this app's own SMTP circulars into that
same Sent folder (Zoho does; not every provider does), so adding them would double-count by
an amount only the provider knows. The Sent-folder figure is also only as fresh as the last
poll, which is why this app's own replies are counted separately from `mail_replies` as
well — exact and immediate, and inside the folder figure once it syncs. Under
`MAIL_REPLY_PROVIDER=BREVO` it never joins that figure at all, because the reply does
not pass through the mailbox: there the `mail_replies` count is the whole record, which
is the clearest reason the two are reported side by side rather than reconciled.

### Analysis: mail kept as training data (local deployments only)

The Analysis tab collects incoming mail as finetuning examples for a model that reads cargo
offers and vessel opening positions. Nothing here calls a model — it gathers the pairs one
would be trained on and exports them.

**`ANALYSIS_ENABLED` is the switch, and it is true in both compose and `render.yaml`.** It
was false on Render until 2026-08-27, on the argument that a corpus accumulated over months
and worked through in long sittings is a poor fit for a free instance that sleeps after
fifteen minutes. What settled it the other way is where the corpus actually lives:
`analysis_samples` is a table in the same hosted database, not state on the instance, so the
sleep costs a cold start in front of a labelling session and nothing else. Off, the tab is
absent from the navigation and every endpoint answers **404** — the feature is not part of that deployment, so neither 403 ("you may not")
nor 503 ("not yet") is honest. `GET /analysis/status` always answers, because it is what the
UI asks before deciding whether the tab exists. The table is created everywhere regardless:
Flyway builds one schema, not one per environment.

- **`analysis_samples` is not `mail_messages`**, the same distinction `mail_replies` makes.
  That table is a mirror of the IMAP server and its rows come and go with the mailbox; a
  corpus on top of it would lose examples to housekeeping, and the annotation — the expensive
  half — would go with them. A sample carries its own copy of the text; `mail_message_id` is
  provenance, `ON DELETE SET NULL`. Not audited, for the reason `CirculationListEntry` is
  not: one capture writes eighty rows recording a machine copying eighty emails.
- **Capture leaves no mark on the mailbox** and labels nothing. Everything lands
  `UNLABELLED`/`NEW`; a capture that guessed would produce a corpus whose labels are the
  guess, and nobody would find the ones it got wrong. Dedupe is on Message-ID, so re-running
  after a sync adds only what is new.
- **Two axes, not one.** `label` is what kind of email it is (`BOTH` is a real answer — the
  daily circular carries cargoes *and* open tonnage); `status` is whether this example is fit
  to train on. `READY` is the only status the export reads and is refused without a label and
  an annotation. `SKIPPED` is kept rather than deleted, so the next capture does not bring the
  same junk back.
- **The annotation is text holding JSON, checked only for parsing.** The extraction shape is
  still being worked out, and a shape still moving must not need a migration each time it
  moves. `AnalysisAnnotationTemplates` serves a skeleton per label so the corpus is annotated
  consistently — suggestions, never validated against.
- **The export is JSONL in the chat shape, and its system prompt is the same on every line
  and one a real caller could send** — it asks for the classification too, because at
  inference time which kind of email arrived is the question rather than the premise. Rows
  come out in id order, so two exports of one corpus are the same file.

### Cargoes, open fleet, and the match between them

Three tabs and one rule engine. A day here is cargoes arriving, tonnage positions arriving,
and the two being put against each other; these are those three things.

**A `Cargo` is a charterer's requirement as it arrived, and almost every field is nullable.**
A real first email says "25,000 MT Wheat +/- 10%, Chornomorsk to Spain Med, geared bulker abt
28-35,000 DWT, laycan please advise" and stops. A record that cannot be saved until it is
complete is a record kept on paper instead. Its field names deliberately track the cargo half
of the mail-corpus annotation template, so the email parser can write into these columns
without a translation layer between them.

Quantity is four columns for one number, and the tolerance is why. `quantity` +
`quantity_tolerance` are the email's words; `quantity_min`/`quantity_max` are the range Match
compares a hull against. "+/- 10%" is arithmetic and becomes a range; MOLOO is a percentage
the charter party settles and this email does not state, so it produces **no range at all**
rather than a guessed five percent — a guess would exclude ships that fit and nothing on
screen would ever say it had. See `QuantityTolerance`.

**A `VesselPosition` is one row per report, never one per vessel.** A position is a fact with
a date on it: "SPOT AT MARMARA" was true on Monday and is a lie by Friday. The same hull is
reported by several brokers who disagree, and both readings are the record. Open Fleet shows
the newest live row per vessel — a fleet list with the same ship on it twice cannot be
counted — and the vessel's own history shows the lot. A new live report supersedes the *same
reporter's* previous one and nobody else's. Nothing is deleted on replacement; `SUPERSEDED`
is a status, because "she was said to be open Adriatic and then wasn't" is worth looking back
at.

**A vessel's own record shows her latest reading, and can change it.** `GET /vessels/{id}`
carries `lastPosition` — one indexed row off `(vessel_id, reported_at DESC)`, the same index
Open Fleet is built on — so "where is she" is answered on the record you already opened
rather than in a second tab. It is the latest of *any* status, not the latest live one: if
she has since fixed, where she was last reported free is still the useful answer and the
status says which. The shape is deliberately slimmer than the Open Fleet one and carries no
vessel inside it — there a position is the subject and needs the whole ship on it, here the
ship is the subject and already surrounds it.

The drawer offers **two** ways to change it, and the split is not a nicety. Positions are
append-only, so "a newer list arrived" and "I typed that wrong" cannot be one button:
*Update* records a new reading and leaves the old one in her history, which is the common
case; *Correct* rewrites the reading itself, for a typo. One button doing the first would
lie about what the record keeps; one doing the second for a fresh list would destroy the
ship's history a week at a time. Opened from a vessel's own record the vessel picker is
locked, because there it was never a choice.

**Trade areas are the vocabulary both sides are written in, and they are not `regions`.**
That table is a circulation-targeting list ("Israel - no", "Europe ports EXCLUDED") with
place names mixed into it at four different scales. `trade_areas` nests one level (West Med
inside the Mediterranean — containment, not adjacency), `trade_area_aliases` holds the
spellings the market actually writes, and `trade_area_distances` holds ballast days between
the pairs this desk would consider. The aliases are the load-bearing half: one week of this
mailbox carried "W.MED", "WEST MED", "SPAIN MED" and "W.ITALY" for the same water. The
distance table is deliberately sparse — an absent pair means "too far to consider", which is
a different and more honest answer than a large number, and the Caspian has no distances at
all because a ship there cannot ballast to a Med cargo in any number of days.

`TradeAreaGraph` caches the whole vocabulary in memory as **flattened records, not
entities**. A cached entity is a detached entity, and the first caller to read `getParent()`
outside the transaction that loaded it gets a lazy-init failure from the very field the class
exists to answer questions about.

**Ports are the same vocabulary one level finer, and they answer a different question.** An
area says which water; `ports.latitude/longitude` say where the berth is, which is what a
laycan actually turns on. Constanza and Rostov are both "Black Sea" and two days apart — one
is a deepwater berth two hours off the lane, the other is 250 miles up the Don and behind the
Kerch Strait — and no trade area can tell them apart. `port_aliases` is `trade_area_aliases`
again, down to the generated `alias_key` and the unique index on it, and for the same reason:
a circular arriving this week still writes ILLICHIVSK for a berth renamed in 2016 and BOMBAY
for one renamed in 1995, and `findByExactName` finds neither.

`PortDirectory` holds it, flattened like the areas, with two rules that are not constraints
and cannot be. **A port's own name beats any alias** — the two tables share no index, so the
rule lives in the class, and it is what makes an alias safe to add: the worst a wrong one can
do is answer a question no port name already answered. **A name two berths share answers
nothing**, the refusal the vessel and company lookups make. Scanning a phrase (`findIn`)
joins *words*, not substrings, unlike the area graph: area names are distinctive and port
names are often ordinary words, and a substring scan finds a berth in Montenegro inside
"grain in bulk, bar none".

**Marine distance is a waypoint network, not a matrix and not a straight line.**
`sea_waypoints` are points at sea and `sea_legs` join the pairs with open water between them;
every port hangs off one by `gateway_waypoint_id`. A great circle alone is wrong in the
direction that loses money — Odessa to Genoa on one crosses Bulgaria, Serbia and the Alps and
reads 1,050 miles against a real 1,650. A port-to-port table is forty thousand numbers nobody
would keep true. Sixty-odd nodes and a hundred legs describe every route this desk quotes, a
new berth costs one link, and `SeaRouteGraph` runs Dijkstra over it — checked against
published distances on fifteen real routes and landing between 4% short and 9% long.

Three things in there are load-bearing:

- **A leg's `distance_nm` is normally null, and that is the design.** Putting the row there
  asserts the water is open, so the great circle between the two coordinates *is* the
  distance. The override is for the legs that are not straight — the Bosphorus is seventeen
  miles of bends, Suez is a canal, the Don is a river — and `ports.gateway_nm` is the same
  idea for a berth 250 miles up one.
- **`delay_hours` on a waypoint is what a distance table cannot hold.** A bulker at anchor
  off Kavak waiting for a northbound convoy is not sailing, and on this desk that is every
  Black Sea passage there is. Charged for waypoints *passed through*, never for the ends: a
  ship opening at Istanbul has not queued for the Bosphorus, she is there.
- **No route is an answer.** The Caspian waypoint has no legs at all, which produces the same
  honest "too far to consider" the sparse area table gives. Everywhere the network answers
  nothing — an unplaced berth, a pair it does not join — Match falls back to the area table,
  so a port nobody has placed is never worse off than before the network existed.

**Matching computes on every request and stores nothing but the human's answer.** A stored
score goes stale the moment a position or a cargo moves, so it would need invalidating on
every write in the feature — for arithmetic over fields already in memory. What *is* stored
is `cargo_vessel_matches`: one row per pairing holding the last decision, and `DISMISSED` is
the reason it exists. Without it the screen proposes the same fifteen ships every morning,
four already offered and two the owner declined on Tuesday.

`MatchScorer` gives every test one of **three verdicts, and the third is the whole point**:

- `PASS` — she meets what the cargo asked for.
- `FAIL` — we hold data saying she does not. This is what rules a pairing out.
- `UNKNOWN` — nothing on file to answer it. Costs points, never excludes.

Half this fleet has no gear recorded and 2,355 hulls have no DWCC. Reading "not on file" as
"does not fit" would rule out most of the tonnage on the desk; reading it as "fits" would
offer ships nobody had checked. The score is the share of the *applicable* weight that was
earned — criteria the cargo says nothing about drop out of both halves of the fraction, so a
cargo with no draft limit does not reward a shallow ship, while criteria it does state and
the vessel cannot answer stay in the denominator, which is what makes a documented hull
outrank an unknown one carrying the same guesses.

A check normally earns all of its weight or none, and `Check.credit` reads as the verdict
does. **Two are graded, and they are the two that decide the order of a list.**

The first is intake, and it is the check that stops a
14,000-tonner being offered for a 4,000-tonne parcel. Size has no objection to that pairing —
she lifts it easily, which is exactly the problem — but freight is earned by the tonne and
the ship is paid for whole, so at 29% full she earns 29% of what she costs and no owner takes
it. Below the floor she is ruled out; above the ideal she scores full marks; between them she
passes and earns the share of the distance she has come, because part cargoes are real and a
60%-full ship is an argument rather than a mistake. Both figures live in `app_settings` with
the ballast speed and the port allowance (`MatchSettings`, the Settings tab) — they are what
a broker argues with the screen about, and a constant would make that argument a redeploy.

The second is ballast, and it is the one that says a nearer ship is a better ship. It has
the same floor-and-ideal shape and its own pair of settings: under `idealBallastDays` the leg
costs the pairing nothing, past `maxBallastDays` the pairing is ruled out, and between them
she passes and earns the share of the distance she has come. Without it two hulls that both
make a cancelling date three weeks out scored identically, which is how a list ends up
leading with a ship on the wrong side of the Med — and when the enquiry says "laycan: please
advise", which is half of them, it is the only thing that can order the list at all.
**`maxBallastDays` is a default and `cargoes.max_ballast_days` overrides it per enquiry**,
because a full cargo worth crossing an ocean for and a part cargo nobody would cross the Med
for are both an ordinary week. Null there means "use the setting" rather than "no limit"; a
cargo that really would take any ballast says so with a large number. A FAIL here is a desk
policy rather than a fact about the hull — the same stretch the intake floor already makes,
in the same direction.

**Whether she can make it is a separate check from how far she has to come**, because they
are separate arguments and one weight covering both made the wrong one invisible. The laycan
check tests the arrival against the cancelling date and does nothing else; where the cargo
names no cancelling date it **does not apply and drops out of both halves of the score**,
rather than being paid full marks for meeting a deadline nobody set. Where she is on a list
with no dates against her at all it is UNKNOWN, which is what a gap in the record is.

Four asymmetries in there are deliberate and easy to "fix" wrongly:

- A cargo needing gear rules out a gearless ship, but a cargo *not* needing gear does not
  rule out a geared one — cranes she does not need cost the charterer nothing.
- Timing counts from her **last** free day, not her first: a ship open 1/3 September is not
  sailing on the 1st, and the optimistic end would put ships on lists they cannot make. And
  **never from a day already past.** A position stays LIVE after its dates run out — nothing
  withdraws it — so a list swept three weeks ago still reports her open 25/28 August, and
  counting the leg from the 28th printed "Could present 2 September" on a screen being read
  on the 9th. Worse than the date being silly: it passed her for cancelling dates she cannot
  reach, because the arrival it compared them against was one nobody could sail to. Whatever
  the list said, the earliest she can leave is today.
- Intake is measured against the **most** the cargo could load, never the least. A "25,000
  +/- 10%" enquiry will put 27,500 into a hull that takes it, because the option is the
  charterer's — and where the cargo gives no upper figure at all ("min 3,000 mt") the check
  does not apply, because a floor is not a bound on the intake.
- Intake is **silent whenever the cargo states a DWT range**. "Abt 28-35,000 DWT" is the
  charterer's own answer, size has already tested it, and a ratio arguing with it would rule
  out a ship they asked for by name.

The ballast leg has two sources, asked in order of what they can tell apart: the sea network
where both ends name a placed berth (miles, the straits on the way, the convoy wait in the
total), the trade-area table where either end is only a water — which is how most circulars
write a position, and what that table was built for. Half a day is as fine as the result is
quoted, because the speed is an assumption and the laycan is a spread.

The list is sorted best first and **the nearer ship breaks a tie, and only breaks a tie**.
How far she has to come is already inside the score, so sorting on it ahead of the score
would put a nearby ship answering half of what the charterer asked above a documented one
across the water — "closest" is not the question the list is answering. Below the score it
settles the pairs a hundred points over eight checks cannot, which is a great many of them.

Match reads in both directions, because the desk does. Most of the mail here is somebody
else's tonnage asking for work — "pls propose suitable cgoes for our below home tonnages"
arrives weekly — and answering it is the same scorer read the other way round.

Every reason is shown with its figures ("Draws 7.9m, berth takes 7.0m"), never as "failed
draft check". The value of the screen is that a broker can disagree with it, and they can
only disagree with a reason they can read.

### A vessel's former names

`vessel_ex_names` exists because owners rename ships constantly and a position list may use a
name this database has never seen for a hull it has held for ten years. The IMO number is the
only identifier that never moves, and it is exactly what a broker's circular leaves out.

V11 extracted 299 of these out of the `name` column, where somebody had typed the history
into it ("LOIRE RIVER/ EX AMIKO", "ELEMENTS / EX GUBERNATOR KAMCHATKI/ EX KATERINA"), and
cleaned the name down to the current one. Those rows carry `source = 'backfill'` — a
machine's reading of a free-text field, and the first thing to suspect if a vessel ever looks
wrong. The vessel search matches current and former names alike, which is the entire point of
having them, and the list prints the former names under the current one so a row nobody
searched for by that name explains itself.

**A save that changes her name files the old one.** Same rule the Intake tab already
followed when accepting a name out of a circular, now on the edit form too, because a
position list arriving next week under the previous name otherwise matches nothing and she
is entered a second time. Case and surrounding space are folded, so correcting the spelling
of a name records nothing; the row carries `source = 'rename'` rather than `manual` to say
nobody entered it, a save produced it — which is where to look when a former name turns out
to have been a typo. This does not contradict the paragraph below: that is about a stale
form *deleting* history, and this only ever adds.

They write on their **own endpoints, never as part of the vessel's PUT**: they are rows in
another table, one gets added whenever a circular reveals one, and folding them into the
whole-record save would let a form opened five minutes ago delete a ship's history while
somebody was correcting her deadweight.

The vessel record also gained `geared`, `gear_description`, `holds`, `hatches`,
`grain_fitted`, `timber_fitted`, `imo_fitted` and `ice_class` — every one of them read off
the position lists this mailbox already receives, and every one nullable, because null is
"not on file" and false would be a claim about four thousand rows nobody has checked.

### Intake: mail read into cargoes and positions

The other half of the Analysis tab, and what the corpus was collected for. A model reads an
incoming email and the app files what it found — positions onto Open Fleet, cargoes onto
Cargoes — so Match has both sides to work with without anybody typing a circular in.

**The model is not in this application and not in this repository.** It is an HTTP endpoint:
the sibling `chartering-ml` project serving a finetuned Qwen3-4B through llama.cpp behind a
JSON schema (`make serve-docker`, port 8090). `PARSER_ENABLED` is the switch, true in compose
and pinned **false** in `render.yaml` — that instance has no GPU and no route to one, which is
a harder fact than the one behind `ANALYSIS_ENABLED`. Off, the tab is absent and every
endpoint answers 404 except `GET /intake/status`, which the UI asks first.

`EmailParserClient` sends `AnalysisAnnotationTemplates.SYSTEM_PROMPT` — the constant, not a
copy — the same Date/Subject/blank/body user turn `AnalysisExportService` builds, and
`parser/extraction-schema.json` on the request. All four are what the model was measured
under; a prompt or a layout that drifts from the corpus loses accuracy while every test still
passes. The schema is a copy of chartering-ml's `serve/schema.json`; regenerate it there
(`make schema`) and copy it back if the templates grow a field.

**What lands unwatched and what waits is the whole design.** A parse may write anything that
only *adds*: a position for a hull already on file, a cargo nothing else looks like, a
particular filling a column that was empty. It may not write anything that *changes* what a
person put there. The line is drawn at what a wrong reading costs, not at how confident the
model is — an invented position is superseded by tomorrow's list, while an invented deadweight
sits in the record looking checked and every match run afterwards is quietly wrong.

So three things stop and become `intake_items`:

- **`NEW_VESSEL`** — a hull with no match. Matching is **IMO, then name (current or former),
  then nothing**, each exact: "ATLANTIC" matching "ATLANTIC BREEZE" would file one owner's
  position against another owner's ship and nothing downstream would question it. A third,
  inexact tier runs only after the first two fail and only *suggests* — hulls whose deadweight
  is within 5% or whose name starts the same, each carrying its figures, so linking is a click
  rather than a search. Accepting creates her; linking to an existing hull also files the name
  the email used as an ex-name, which is what stops the next circular asking again.
- **`VESSEL_FIELDS`** — she is on file and the email disagrees. One item per vessel per email,
  accepted whole or per field. Gap fills are *not* queued: an empty column is written straight
  away, on the importer's rule that a matched record is never overwritten, only gap-filled.
  **A stored `0` counts as empty** — the older rows say "not on file" that way, and reading it
  as a figure made "DWCC 0 t against 6,750 t" a question for a human, thousands of times over.
  Numbers compare with half a percent of slack and text loosely ("2x30T CRANES" is "2 x 30 t
  cranes"), because a queue that fires on a broker's rounding is a queue nobody reads.
  **One pending item per vessel, however many emails raise it.** The question is about a
  hull rather than about an email: a broker re-sends his list on Monday and again on
  Wednesday, two brokers carry the same ship, and every arrival used to produce its own
  row — so the queue showed one vessel three times and answering one left the others still
  asking. A second email merges into the waiting item instead, union by field with the
  newer reading winning where both speak, and every arrival kept in `intake_item_sources`.
  That table is `cargo_sources` again and for the same reason — one record several brokers
  describe — and it is what lets the drawer offer each original email to read and each
  sending firm to attach to the hull, in its own chosen capacity. Exact suppression was the
  old answer and was too brittle to be one: FOX came back twice over a single reworded
  word, "GENERAL-DRY CARGO VESSEL / DOUBLE SKIN/BOX" against "GENERAL-DRY CARGO VESSEL",
  every other figure identical. Loosening the comparison would not have helped — those two
  strings genuinely differ — because the mistake was treating it as two questions.
  Only *pending* items merge: an answered question is history, and an email disagreeing
  afterwards is a new question about a record that has since been decided.
- **`CARGO_MERGE`** — a cargo that looks like one in hand. Never merged silently: two cargoes
  cannot be un-merged. The key is same commodity + the load point actually agreeing +
  quantity within 20% + laycans overlapping, where **an absent field abstains rather than
  agreeing or objecting** — a cargo email is mostly silent. A merge gap-fills and leaves every
  disagreement alone: neither broker is the charterer, so there is no reason to believe the
  second over the first.

**A position that repeats is a re-confirmation, not a new row.** An identical reading from the
same reporter against a row still LIVE moves that row's `reported_at` forward — and only
forward, so a swept backlog cannot make a fresh reading look stale. Anything differing by so
much as a date is a new row superseding that reporter's previous one, which is the existing
rule. Twinning every repeat would make a hull's history a record of Mondays rather than of
openings.

**`cargo_sources` is why a merge is safe.** A position is already one row per report carrying
its reporter, and Open Fleet collapses them per hull — the sources are the rows. A cargo is
one record several brokers describe, so the provenance moves to its own table: one row per
arrival, kept through the merge, which is what answers "who else is working this". **The
cargo's own drawer opens them**: a figure on that screen is a broker's typing, and the only
thing that settles whether it is right is what he actually wrote — with a picker when several
sent it, since a merged cargo is exactly the case where which of them said what is the
question. `components/OriginalEmail` is shared with the Intake tab and takes a neutral list,
because the two features keep provenance in different tables with different column names.
Cargoes predating `cargo_sources` have only `source_mail_message_id` on the cargo itself, and
the button falls back to it rather than being missing on the oldest rows. Not behind the
parser switch, for the reason the endpoint is not: a merged cargo's sources are part of the
cargo.

The sweep is on a timer whose interval, batch size and **lookback window** live in
`app_settings` and on the Settings tab, not in the environment — they are knobs turned while
watching the queue. **0 turns the timer off and leaves "Parse now" as the only way in, which
is a supported way to run it.** The lookback (30 days by default, matching
`IMAP_INITIAL_DAYS`) is what stops the first run reading a mailbox's whole history: a
year-old position list is not information — the ship sailed and the cargo fixed — so parsing
it spends GPU to put rows on Open Fleet that are wrong by construction and look right until
somebody offers the ship. 0 removes the limit. The header count and the sweep's own queue are
computed from one resolved snapshot of these, or the screen would say "0 waiting" above a run
that then read twenty. A ticker checks
the clock every minute rather than Spring binding `fixedDelay` at startup. `EmailParseRunner`
is a bean of its own so its per-message transaction is not a self-invocation, the same split
`MailIngestService` makes; one transaction per message, never one per sweep. A sweep stops at
the first unreachable-server error rather than spending forty timeouts discovering the same
thing.

**The sender's company can be attached to the hull from the review item**, in a chosen
capacity (`owner`, `exclusive_broker`, `broker`). **"Not this ship — create her" asks the
capacity while creating her**, because the hull it creates has nobody on her at all and the
firm that sent the list is usually the answer to who works her — the card that would have
recorded it sits below a drawer that closes the moment she exists. Asked rather than
assumed, with "create her without linking" as a first-class answer beside it: sending a
position list is not evidence of ownership, so there is no capacity safe enough to default
to. Still two writes with two change sets, the same split the web figures keep. Where
several emails raised the item, `companyId` picks which sender — it must be one of the
item's own, since attaching an unrelated firm is a decision about the ship and belongs on
her record. The ordinary case is a position list from a
broker who is not the owner on file, and that the broker works her is worth keeping — it is
who to ring about her. Its own endpoint, delegating to `VesselService.setLink` so the Intake
tab and the vessel screen cannot drift into two notions of what a link is, and the capacity is
chosen rather than assumed because `owner` displaces whoever is on the record.

### Looking a hull up on the open web

`V17`/`vessel_lookups`. When a circular names a ship and no IMO — which is nearly always — and
this database has no hull of that name either, the desk's own answer is to type the name into
a ship database and read the number off. `VesselLookupService` automates that: one search per
question, the candidates it returns, and a person deciding which if any is her.

**Off by default (`LOOKUP_ENABLED`), and the reasons to leave it off are real.** The only
implementation reads a public search page: the site's terms do not invite it, the parse depends
on somebody else's class names, and the traffic lands on a server this desk does not pay for.
That was chosen over the paid APIs (£100–£700 a month) deliberately. What follows from it is
the care taken elsewhere — one request at a time process-wide, a configured gap between them,
a cap per pass, and `VesselLookupProvider` as a port so the trade stays reversible: an API key
arrives, one class is written, one setting changes.

- **Her own record can ask too, and only on request.** The queue searches because an email
  raised a question; most hulls here were never the subject of one, and a ship opened to be
  worked on is exactly where somebody notices her IMO is blank. Same search, same scoring,
  same card — one implementation, or the same confidence figure would mean two things
  depending on which screen printed it. `vessel_lookups` needed no migration for it: the
  unique index on `intake_item_id` is partial, so a row belonging to a vessel rather than to
  an item was already a supported shape. The vessel's card reads only lookups with no intake
  item behind them, because a search a circular prompted three weeks ago is not something
  somebody just ran. The timed pass still works the review queue and nothing else — a sweep
  over four thousand hulls is precisely the traffic this feature is careful not to send.
  `GET /vessels/lookup-status` answers whatever `LOOKUP_ENABLED` says, like the intake and
  analysis status endpoints, because a screen has to know whether a card exists before it can
  decide not to draw it.
- **A hull is looked up because somebody has to answer a question about her**, never because
  an email mentioned her. That is the cost control: a circular naming eighty ships produces a
  handful of review items and only those are searched for. A pass runs on a timer, and after
  every sweep that raised anything, so the answer is on the screen before the item is opened;
  the drawer also has a button.
- **Only five fields can be written**: IMO, DWT, year built, flag, type. Draft, capacities,
  gear and fittings are deliberately absent — a tracking page's draught is the AIS-reported
  *loaded* figure and the column it would land in is a design maximum, which is invisible and
  wrong in the direction that loses cargoes.
- **The name is not evidence.** It is what was searched for, so a candidate agreeing on it is
  the query coming back. `LookupMatcher` scores name (2), build year (3), deadweight (3) and
  flag (1), and separately reports whether anything *beyond the name* agreed. A name-only
  match scores 100% and is flagged uncorroborated; with more than one candidate it is refused
  outright, as are ties.
- **A number already on a hull here converts the item rather than advising about it.** An IMO
  is identity: two records carrying one are one ship, with no judgement in it. So a
  `NEW_VESSEL` item whose lookup returns a number this database holds is not a question about
  creating a ship — it is a question about her particulars, and it is rewritten into one
  (`VESSEL_FIELDS`, `matchedBy = LOOKUP_IMO`), with her position filed on the hull because
  that is an *add* and the perishable half. Seventeen of forty-one hulls waiting in the queue
  were that. The rename then reads as an ordinary row — "Name: CELIA → LIUDMILA" — for a
  person to accept, and the former name is written by accepting it rather than by the
  conversion: that is a claim about identity which steers every future match. It runs on its
  own pass (`IntakeReconcileRunner`) because the two answers arrive hours apart, and because
  the lookup service writing items would be a dependency cycle.
  **A converted item is scored on the facts the search actually had** — the email's alone,
  since the hull was not known to be hers until the number came back. Scoring against her
  record would credit the search with corroboration it never earned, which is this feature's
  standing failure in the other direction: LIUDMILA matched on the name and nothing else, and
  CELIA's record agrees with the candidate about three more things the search never saw.
- **The best thing it does is find a rename.** The search returns an IMO; that IMO is already
  on a hull here under the name she carried three owners ago. Without it she is entered twice.
  `ux_vessels_imo` is a *partial unique index*, so writing a number another vessel holds fails
  — caught and reported as what it is ("IMO 9195470 is already on GEISE — that is the same
  hull under another name"), not as a constraint violation.
- **Applying is its own action with its own change set**, separate from accepting the email's
  figures. Two origins, two writes, so the History tab reads
  `DWT 0 → 6,977 · Web lookup (vesselfinder) IMO 9014561 — <url>`. Folding them together would
  save a click and lose the only answer to "where did this figure come from".

Nothing in `parsed_emails`, `intake_items` or `cargo_sources` is audited — they are machine
writes and each is already a record of its own event. What a person *decides* is, because
accepting writes to `Vessel` or `Cargo`, with the change set named so a merge reads as one
event.

### Auth

One account. `AUTH_PASSWORD` (or `AUTH_PASSWORD_HASH`) plus a JWT signed with `JWT_SECRET`;
`security/JwtAuthFilter` + `JwtService`. There is deliberately no working default password —
until one is set the api starts normally and refuses every login. Leaving `JWT_SECRET` unset
generates a key per boot, which logs everyone out on each restart.

### One UI, two layouts

The same React tree serves a desktop and a phone; there is no second mobile app and no
mobile build. `responsive/useIsMobile` is the single source of truth — viewport width under
768px, the same number as antd's `md`, so the `xs`/`md` Cols in the filter forms and the
shell always agree about which layout is on.

Three pieces carry it:

- **`components/ResponsiveTable`** — antd's `Table` on a desktop, a list of cards on a
  phone. It takes the props `Table` takes plus a `mobile` prop describing the card
  (title/subtitle/fields/actions). A page keeps **one** set of columns and handlers; adding
  a column is adding it to both layouts. It also re-sends the active sorter with every page
  change, because `useTableControls` reads an empty sorter as "sorting cleared".
- **`components/FilterPanel`** — the filter card. Fields move into a bottom drawer behind a
  Filters button on a phone, with a badge counting what is set. Put the `<Form>` *outside*
  it: the drawer is a portal at the end of `<body>`, and only a Form above it in the React
  tree still reaches those fields. It owns Search and Reset — pages no longer spell them.
- **`components/AppLayout`** — the sider becomes a header, a nav drawer and a bottom tab
  bar (Dashboard / Vessels / Companies / People / More).

`src/index.css` is the only stylesheet, and holds just what inline styles cannot express:
media queries, and overrides of antd's own class names. Two things there are load-bearing —
every fixed Drawer/Modal width is capped centrally (antd does not clamp them, so a
`width={720}` drawer hangs off a 390px screen), and inputs are forced to 16px on phones or
iOS Safari zooms in on focus and never zooms back out. That override needs `!important`:
antd v5 emits `.ant-input.css-<hash>`, two classes, and loses to nothing less.

The control width cap in that file is **not** a general safety net, whatever it looks like.
A percentage max-width only bites when the parent has a width of its own, so it works
inside a Form.Item in a Col and does nothing inside an antd `Space`, where it resolves
against an item sized by its own content. Give a wide control in a Space an explicit
mobile width at the call site — that is why the Mailbox search box takes a row of its own
on a phone rather than trusting the stylesheet to rein it in.

### Where a record's dangerous actions live

Delete, ban, confirm and "has left the company" sit in **`components/RecordActions`, a
section at the foot of the record's edit form** — not in the list row and not in the drawer
header. A list of a hundred people with a Delete on every row is a hundred chances to
remove one from a screen you opened to read; the edit form is the one place you arrive at
by saying you mean to change this thing. Lists and drawers keep an Edit button and the tags
that explain the flags, nothing that writes.

They are **not part of Save** — each fires its own endpoint on click, and the note above
them says so. A form holding a record therefore keeps its own copy of it (`record` state)
and updates it from what those endpoints return: the `editing` prop is a snapshot of the
clicked row and does not move when a flag does. Which controls appear depends on the
record — a person has no confirm or ban flag and brings the left-the-company toggle
instead; every record has Delete, and its confirmation names what else goes with it, which
means reading the FK constraints rather than guessing.

## Conventions

The code in this repo carries unusually long explanatory comments on the *why* of a decision
— especially where a simpler-looking alternative was rejected. Match that when touching those
files; a change that silently contradicts one of those comments is a bug report waiting to
happen. Follow the density and idiom of the surrounding code.

Secrets live in `.env` (gitignored) with `.env.example` as the annotated template. Note that
`.gitignore` covers `.env` but **not** `.env.bak*` — check `git status` before staging, and
never use `git add -A` in this repo.
