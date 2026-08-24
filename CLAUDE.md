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
  `V6__add_contact_from_file.sql` and `V7__add_mail_replies.sql` exist; the next one is V8.
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

- **Always the mailbox, never Brevo**, whichever provider circulars are set to. A reply has
  to come from the address the correspondent wrote to and thread with what they sent;
  Brevo is bulk infrastructure with its own envelope and reputation.
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
well — exact and immediate, and inside the folder figure once it syncs.

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
