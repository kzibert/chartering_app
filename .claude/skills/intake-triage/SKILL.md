---
name: intake-triage
description: Work through the Intake tab's pending review items (NEW_VESSEL, VESSEL_FIELDS, COMPANY_DETAILS, CARGO_MERGE) on a running api - gather the evidence, decide each item, get the user's approval, apply through the api's own endpoints, verify, and file every decision as a case (situation, reason, source email) for later parser and auto-resolution work. Use when asked to resolve, clear, triage or answer the Intake queue / pending review items.
---

# Intake triage

The Intake queue holds the questions the sweep may not answer alone: they *change* what a person
put on file (see CLAUDE.md, "Intake: mail read into cargoes and positions"). Answering them writes
to the live, shared database and the change log can revert only one field of one update, so:

**Nothing is written before the user approves a decision table.** Approval of one triage does not
carry to the next run or to items that arrived after the table was drawn.

## 1. Fetch (read-only)

```bash
python -I .claude/skills/intake-triage/scripts/fetch_queue.py --out <scratchpad>/intake-<date>
```

It logs in (`CHARTERING_USER`/`CHARTERING_PASSWORD`, else `.env`'s seeded admin), saves every
non-minor pending item (`--minor` adds the minor ones) under `items/<KIND>/<id>.json`, and writes
`evidence_<KIND>.md` per kind: the email's reading, the web lookup, similar hulls, field diffs,
the signed company against the one on file, and whether a looked-up IMO is already on a hull here.
The api must be running (`docker compose up -d`); check `pendingItems` in the printed status.

Read the evidence files yourself. For a large queue, Haiku workers may condense or cross-check a
kind, but the decisions are yours: workers report, they do not decide.

## 2. Decide - generic rules

Judge from the evidence, never from who the firm or ship is. Each rule below is about the *shape*
of the evidence, so it holds for any desk's correspondents and fleet.

**NEW_VESSEL**
- Lookup corroborated (name plus at least one of built / DWT / flag agree, single candidate) and its
  IMO not on file -> `ACCEPT` with `applyLookup` (create her, fill IMO and any empty DWT / built / flag).
- The lookup's IMO is already on a hull -> `ALTERNATIVE` to that hull (it is identity, not a guess).
- Two pending items that are the same hull (same lookup IMO, or names equal after case and accent
  folding) -> accept one, `ALTERNATIVE` the other with `vesselOfItem`.
- A reading with no particulars at all (no IMO, DWT, DWCC, built, flag) whose notes read like
  space or cargo on offer (load / discharge ranges, "need cargo", laycan only) -> `DISCARD`: it is
  not a ship. Placeholders (TBN etc.) are already skipped by the code.
- Real particulars, no usable lookup (NO_MATCH, or a candidate contradicting size / age / type) ->
  `ACCEPT` without the lookup; say in the table that she will have no IMO.
- An unreadable or garbled name -> ask the user rather than choose.

**VESSEL_FIELDS** - `ACCEPT` with only the rows to write; every row left out is recorded as KEPT.
- Corroborated lookup agreeing with the email -> write; agreeing with the record -> keep.
- Rename on a hull matched by IMO / lookup IMO with a corroborated lookup -> write the new name.
- The sender is the hull's own owner / manager (their own fleet list) -> their figures win.
- Same value spelled differently (flag adjective vs country, "&" vs "and", case, typo; gear
  wording with the same count x SWL; "classed" vs "class") -> keep; if the record itself is the
  odd spelling, write it with a `correction` in the canonical form.
- Not that field at all (a build place in the flag, a note in the gear) -> keep.
- Physically implausible (draft far beyond the DWT's band, DWCC above DWT, a capacity that is
  impossible for her size in either unit) -> keep, or write the record-fixing side when the record
  is the impossible one.
- Nothing corroborating either side and not trivial -> keep (the conservative answer; it only
  stops the same firm asking about the same value).
- Every row kept -> `DISCARD`.

**COMPANY_DETAILS**
- No firm on file, a real correspondent -> `COMPANY` (create). The mailbox's own provider,
  system or notification senders -> `DISCARD`.
- A firm on file by email / phone / non-webmail domain -> `COMPANY` with `companyId` (adds only
  people and addresses it lacks); `companyChanges` only for fields empty on file or clearly
  outdated - never rename a firm for a legal-form, office or branch suffix.
- Everything already on file, or the only "new person" is the firm's own name -> `DISCARD`.

**CARGO_MERGE** - `ACCEPT` merges, `ALTERNATIVE` keeps a separate cargo, `DISCARD` writes nothing.
Merge only when anchors that can disagree do not (commodity, load point, quantity within reason,
laycan overlap); two firms with similar cargoes look alike from here.

**File every decision under a situation.** `scripts/situations.py` lists the codes: each names
a shape of evidence and the part of the code it points at (`PARSE_*` the model's reading,
`NORMALISE_*` / `PLAUSIBILITY_*` the comparison, `QUEUE_*` the bookkeeping, `LOOKUP_*` the web
lookup, `COMPANY_*` the company reader and matcher, `TRUST_*` whose word counts, `DECIDED_*`
what only a person can settle). Each step in `decisions.json` carries a `situation`, a one-line
`reason`, and on VESSEL_FIELDS a `rows` map where rows were decided for different reasons. A
situation no code fits: use `OTHER` with a clear reason, and add a code to `situations.py` if it
recurs. This is what later turns a triage into parser and rule work.

## 3. Ask for approval

Show one table per kind: item, subject, decision, the evidence that decided it, and what will be
written. Put the judgement calls the user may want to change first. Wait for an explicit yes; take
their changes into the decisions.

## 4. Apply

Write `decisions.json` (format in the docstring of `scripts/apply_decisions.py`, steps run in the
order given - put a hull's creation before any `vesselOfItem` pointing at it), dry-run, then run:

```bash
python -I .claude/skills/intake-triage/scripts/apply_decisions.py --dir <same dir> --decisions <file> --dry-run
python -I .claude/skills/intake-triage/scripts/apply_decisions.py --dir <same dir> --decisions <file>
```

The script refuses a step without a known `situation` and a `reason`. Each applied step appends
one case to `local/notes/intake-cases.jsonl`: the decision and its situation, the outcome, the
email's reading, the lookup, the diffs, and the source pointers (mail message, board post, parse
rows). An item answered meanwhile by the reweigh / reconcile pass comes back 400 "already
answered": check it reached the intended state and file it as `QUEUE_ALREADY_SETTLED`. For
items answered by hand on the screen, file the cases with `--record-only`.

## 5. Verify and record

- `GET /intake/status` and the pending list; read back a sample of written vessels / companies.
- Copy `apply_log.txt` and `decisions.json` to `local/data-fixes/<date>-intake-triage.*`.
- Regenerate the case report, grouped by code area with every case's source:
  `python -I .claude/skills/intake-triage/scripts/cases_report.py --out local/notes/intake-cases-report.md`
  (`--since <date>` for one triage). When working on an area - the parser prompt, a corpus
  example, `VesselFieldDiff`, `CompanyMatcher` - start from its section: the parse rows are the
  readings to re-run, the mail / post ids the texts to annotate.
- Update `local/notes/intake-auto-resolution.md` - the rule backlog - with any situation that
  grew or appeared, stated generically (an evidence shape, a count, the code it points at),
  never a firm, ship or person name. The case log keeps the concrete examples; the backlog and
  any code written from it stay generic, so every desk gets the same behaviour.
- Report: counts per decision and per situation, failures with their output, items that arrived
  after the table.
