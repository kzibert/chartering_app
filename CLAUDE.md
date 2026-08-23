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
  `V1__baseline_schema.sql` and `V3__add_person_job_title.sql` exist; the next one is V4.
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
`banned`, `hasWhatsapp` (recorded by hand — WhatsApp cannot be queried).

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

## Conventions

The code in this repo carries unusually long explanatory comments on the *why* of a decision
— especially where a simpler-looking alternative was rejected. Match that when touching those
files; a change that silently contradicts one of those comments is a bug report waiting to
happen. Follow the density and idiom of the surrounding code.

Secrets live in `.env` (gitignored) with `.env.example` as the annotated template. Note that
`.gitignore` covers `.env` but **not** `.env.bak*` — check `git status` before staging, and
never use `git add -A` in this repo.
