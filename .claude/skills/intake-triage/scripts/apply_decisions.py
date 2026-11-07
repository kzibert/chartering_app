"""Apply an approved Intake triage through the api's own endpoints, in the order given, and file
every decision as a case for later work on the parser and the auto-resolution rules.

    python -I apply_decisions.py --dir <fetch dir> --decisions <decisions.json> [--dry-run | --record-only]

decisions.json is {"steps": [...]}; each step names one item, one answer, and why:

  {"item": 1, "action": "ACCEPT", "applyLookup": true}   NEW_VESSEL: create her, then copy the
        lookup's IMO / DWT / built / flag into whichever of them she was created without
  {"item": 2, "action": "ALTERNATIVE", "vesselId": 123}  NEW_VESSEL: her position belongs to that hull
  {"item": 3, "action": "ALTERNATIVE", "vesselOfItem": 1} ...the hull an earlier step created
  {"item": 4, "action": "ACCEPT", "fields": ["flag"], "corrections": {"flag": "Panama"}}
        VESSEL_FIELDS: write only the named rows (corrections typed in place of the email's);
        every row left out is recorded as KEPT for the firms that sent it
  {"item": 5, "action": "DISCARD"}                         any kind: write nothing (on
        VESSEL_FIELDS, every row is recorded as KEPT)
  {"item": 6, "action": "ALTERNATIVE"}                     CARGO_MERGE: keep it as a cargo of its own
  {"item": 7, "action": "COMPANY"}                         COMPANY_DETAILS: create the firm as signed
  {"item": 8, "action": "COMPANY", "companyId": 42, "companyChanges": {"cityName": "..."}}
        COMPANY_DETAILS onto a firm on file: people matched by the server's compare, only
        addresses the firm lacks are added, existing contacts are never reassigned, and the
        company changes only in the fields named

Every step also carries (required):
  "situation": a code from situations.py - the shape of the evidence that decided it
  "reason":    one line, in words, of what decided it
and VESSEL_FIELDS steps may carry "rows": {"<field code>": "<situation>"} where rows of one item
were decided for different reasons (a flag spelling kept, a DWT the lookup confirmed written).

Each step appends one JSON line to the case log (default local/notes/intake-cases.jsonl): the
decision, the outcome, the email's reading, the lookup, and the pointers back to the source
(mail message, board post, parse rows) so the reading can be re-run or turned into a training
example. --record-only files the cases without calling the api, for a triage applied by hand.
"""
import argparse
import datetime
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from fetch_queue import REPO, Api  # noqa: E402
from situations import SITUATIONS  # noqa: E402

sys.stdout.reconfigure(encoding="utf-8")
LOOKUP_FIELDS = {"imoNumber", "deadweightTonnage", "yearBuilt", "flag"}  # never vesselType: categories are ours
DEFAULT_CASES = REPO / "local" / "notes" / "intake-cases.jsonl"


def validate(steps):
    problems = []
    for step in steps:
        codes = [step.get("situation")] + list((step.get("rows") or {}).values())
        for code in codes:
            if code not in SITUATIONS:
                problems.append(f"item {step.get('item')}: unknown situation {code!r}")
        if not (step.get("reason") or "").strip():
            problems.append(f"item {step.get('item')}: no reason")
    if problems:
        sys.exit("decisions refused:\n  " + "\n  ".join(problems))


def evidence(item):
    """What the case needs to be re-examined later: where it came from and what was read."""
    if not item:
        return {}
    p = item.get("payload") or {}
    lk = item.get("lookup") or {}
    m = lk.get("matched") or {}
    out = {
        "subject": item.get("subjectLabel"),
        "summary": item.get("summary"),
        "source": {
            "kind": item.get("sourceKind"),
            "mailMessageId": item.get("mailMessageId"),
            "feedItemId": item.get("feedItemId"),
            "mailSubject": item.get("mailSubject"),
            "receivedAt": item.get("receivedAt"),
            "senderCompanyId": item.get("senderCompanyId"),
            "sources": [{k: s.get(k) for k in ("parsedEmailId", "mailMessageId", "feedItemId", "senderCompanyId")}
                        for s in item.get("sources") or []],
        },
    }
    if lk:
        out["lookup"] = {"status": lk.get("status"), "confidence": lk.get("confidence"),
                         "corroborated": lk.get("corroborated"), "imo": m.get("imo"), "name": m.get("name"),
                         "dwt": m.get("deadweightTonnage"), "built": m.get("yearBuilt"), "flag": m.get("flag"),
                         "type": m.get("vesselType"), "disagreements": lk.get("disagreements")}
    kind = item.get("kind")
    if kind in ("NEW_VESSEL", "VESSEL_FIELDS"):
        v = p.get("vessel") or {}
        out["reading"] = {k: v.get(k) for k in ("name", "imo", "vesselType", "dwt", "dwcc", "draft", "built", "flag",
                                                 "grainCapacity", "baleCapacity", "capacityUnit", "gearDescription",
                                                 "iceClass", "openPort", "openText")}
        out["reading"]["notes"] = (v.get("notes") or "")[:400]
    if kind == "VESSEL_FIELDS":
        out["vesselId"], out["matchedBy"] = p.get("vesselId"), p.get("matchedBy")
        out["diffs"] = [{k: d.get(k) for k in ("field", "current", "incoming", "minor", "note")} for d in p.get("diffs") or []]
    if kind == "NEW_VESSEL":
        out["suggestions"] = [{k: s.get(k) for k in ("vesselId", "name", "imoNumber", "reason")} for s in p.get("suggestions") or []]
    if kind == "COMPANY_DETAILS":
        draft = p.get("draft") or {}
        out["reading"] = {"company": draft.get("company"), "people": draft.get("people"), "contacts": draft.get("contacts")}
        out["matched"] = {"companyId": p.get("companyId"), "name": p.get("companyName"), "by": p.get("matchedBy"),
                          "candidates": draft.get("matches")}
    if kind not in ("NEW_VESSEL", "VESSEL_FIELDS", "COMPANY_DETAILS"):
        out["payload"] = json.dumps(p, ensure_ascii=False)[:2000]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True, help="the --out directory fetch_queue.py wrote")
    ap.add_argument("--decisions", required=True)
    ap.add_argument("--base", default="http://localhost:8081/api/v1")
    ap.add_argument("--cases", default=str(DEFAULT_CASES), help="the case log to append to")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true", help="print what would be sent; write nothing")
    mode.add_argument("--record-only", action="store_true", help="file the cases; call nothing")
    args = ap.parse_args()
    root = Path(args.dir)
    steps = json.loads(Path(args.decisions).read_text(encoding="utf-8"))["steps"]
    validate(steps)

    api = None
    if not (args.dry_run or args.record_only):
        api = Api(args.base, (root / "token").read_text().strip())
        if api.call("GET", "/intake/status")[0] == 401:
            api.login()
    log = open(root / "apply_log.txt", "a", encoding="utf-8")
    cases = None
    if not args.dry_run:
        Path(args.cases).parent.mkdir(parents=True, exist_ok=True)
        cases = open(args.cases, "a", encoding="utf-8")
    triage = datetime.date.today().isoformat()
    created = {}

    def record(step, what, status, extra=""):
        line = f"{step['item']}\t{what}\tHTTP {status}\t{extra}"
        print(line)
        log.write(line + "\n")
        log.flush()

    def item_file(item_id):
        found = list((root / "items").glob(f"*/{item_id}.json"))
        return json.loads(found[0].read_text(encoding="utf-8")) if found else None

    def file_case(step, item, outcome):
        if cases is None:
            return
        diffs = [d.get("field") for d in ((item or {}).get("payload") or {}).get("diffs") or []]
        written = (step.get("fields") or diffs) if step["action"] == "ACCEPT" and item and item.get("kind") == "VESSEL_FIELDS" else []
        row = {
            "triage": triage, "item": step["item"], "kind": (item or {}).get("kind"),
            "action": step["action"], "situation": step["situation"], "reason": step["reason"],
            "rows": step.get("rows"), "corrections": step.get("corrections"),
            "written": written or None, "kept": [f for f in diffs if f not in written] or None,
            "target": {k: step[k] for k in ("vesselId", "vesselOfItem", "companyId", "companyChanges") if step.get(k) is not None} or None,
            "outcome": outcome, **evidence(item),
        }
        cases.write(json.dumps({k: v for k, v in row.items() if v is not None}, ensure_ascii=False) + "\n")
        cases.flush()

    for step in steps:
        item_id, action = step["item"], step["action"]
        item = item_file(item_id)
        if args.dry_run:
            record(step, f"would {json.dumps(step, ensure_ascii=False)}", "-")
            continue
        if args.record_only:
            file_case(step, item, {"recordedOnly": True})
            continue

        if action == "COMPANY":
            draft = (item or {}).get("payload", {}).get("draft") or {}
            if not draft:
                record(step, "COMPANY skipped: no draft in the fetched item", "-")
                file_case(step, item, {"skipped": "no draft"})
                continue
            if step.get("companyId") is None:
                body = {"company": draft["company"], "people": draft.get("people", []),
                        "contacts": draft.get("contacts", [])}
            else:
                cid = step["companyId"]
                s, cmp = api.call("POST", "/intake/paste/company/compare",
                                  {"companyId": cid, "company": draft["company"],
                                   "people": draft.get("people", []), "contacts": draft.get("contacts", [])})
                if s != 200:
                    record(step, f"compare against {cid}", s, str(cmp)[:300])
                    file_case(step, item, {"http": s, "error": str(cmp)[:300]})
                    continue
                matched_people = cmp.get("people") or []
                matched_contacts = cmp.get("contacts") or []
                people = [{**p, "existingPersonId": (matched_people[i] or {}).get("existingPersonId")
                           if i < len(matched_people) else None}
                          for i, p in enumerate(draft.get("people", []))]
                contacts = [c for i, c in enumerate(draft.get("contacts", []))
                            if not (i < len(matched_contacts) and (matched_contacts[i] or {}).get("existingContactId"))]
                body = {"companyId": cid, "people": people, "contacts": contacts,
                        "companyChanges": step.get("companyChanges")}
            s, r = api.call("POST", f"/intake/items/{item_id}/company", body)
            record(step, "COMPANY " + ("create" if step.get("companyId") is None else f"onto {step['companyId']}"),
                   s, json.dumps(r, ensure_ascii=False)[:300])
            file_case(step, item, {"http": s, **(r if isinstance(r, dict) else {"error": str(r)[:300]})})
            continue

        body = {"action": action}
        for key in ("fields", "corrections", "vesselId", "note"):
            if step.get(key) is not None:
                body[key] = step[key]
        if "vesselOfItem" in step:
            body["vesselId"] = created.get(step["vesselOfItem"])
            if body["vesselId"] is None:
                record(step, f"{action} skipped: item {step['vesselOfItem']} created no hull", "-")
                file_case(step, item, {"skipped": "target hull not created"})
                continue
        s, r = api.call("POST", f"/intake/items/{item_id}/resolve", body)
        ok = s == 200 and isinstance(r, dict)
        record(step, f"{action} {json.dumps({k: v for k, v in body.items() if k != 'action'}, ensure_ascii=False)}", s,
               f"status={r.get('status')} vesselId={r.get('vesselId')}" if ok else str(r)[:300])
        outcome = {"http": s, "status": r.get("status"), "vesselId": r.get("vesselId")} if ok else {"http": s, "error": str(r)[:300]}
        if ok and r.get("vesselId"):
            created[item_id] = r["vesselId"]

        if ok and step.get("applyLookup") and r.get("vesselId"):
            _, detail = api.call("GET", f"/intake/items/{item_id}")
            proposals = ((detail.get("lookup") or {}).get("proposals")) or []
            fields = [p["field"] for p in proposals
                      if p["field"] in LOOKUP_FIELDS and p.get("current") in (None, "", 0)
                      and p.get("incoming") not in (None, "")]
            if fields:
                s2, r2 = api.call("POST", f"/intake/items/{item_id}/apply-lookup",
                                  {"fields": fields, "vesselId": r["vesselId"]})
                record(step, f"apply-lookup {fields} onto {r['vesselId']}", s2, "" if s2 == 200 else str(r2)[:300])
                outcome["lookupApplied"] = fields if s2 == 200 else f"HTTP {s2}"
        file_case(step, item, outcome)

    if api:
        _, status = api.call("GET", "/intake/status")
        print(f"now pending {status.get('pendingItems')} minor {status.get('minorItems')}")
    if cases:
        print(f"cases appended to {args.cases}")


if __name__ == "__main__":
    main()
