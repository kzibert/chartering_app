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
  `V14__add_vessel_positions.sql` exist; the next one is V15.
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
offer ships nobody had checked. The score is the share of the *applicable* weight that
passed — criteria the cargo says nothing about drop out of both halves of the fraction, so a
cargo with no draft limit does not reward a shallow ship, while criteria it does state and
the vessel cannot answer stay in the denominator, which is what makes a documented hull
outrank an unknown one carrying the same guesses.

Two asymmetries in there are deliberate and easy to "fix" wrongly. A cargo needing gear rules
out a gearless ship, but a cargo *not* needing gear does not rule out a geared one — cranes
she does not need cost the charterer nothing. And timing counts from her **last** free day,
not her first: a ship open 1/3 September is not sailing on the 1st, and the optimistic end
would put ships on lists they cannot make.

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

They write on their **own endpoints, never as part of the vessel's PUT**: they are rows in
another table, one gets added whenever a circular reveals one, and folding them into the
whole-record save would let a form opened five minutes ago delete a ship's history while
somebody was correcting her deadweight.

The vessel record also gained `geared`, `gear_description`, `holds`, `hatches`,
`grain_fitted`, `timber_fitted`, `imo_fitted` and `ice_class` — every one of them read off
the position lists this mailbox already receives, and every one nullable, because null is
"not on file" and false would be a claim about four thousand rows nobody has checked.

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
