"""Download the Intake review queue and condense it into one evidence file per kind.

Read-only: it logs in, reads, and writes files under --out. Nothing is answered here.

    python -I fetch_queue.py --out <dir> [--base http://localhost:8081/api/v1] [--minor]

Credentials: CHARTERING_USER / CHARTERING_PASSWORD, else AUTH_USERNAME / AUTH_PASSWORD from
the repository's .env (the seeded administrator).
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
REPO = Path(__file__).resolve().parents[4]


def credentials():
    user, password = os.environ.get("CHARTERING_USER"), os.environ.get("CHARTERING_PASSWORD")
    if user and password:
        return user, password
    env = {}
    for line in (REPO / ".env").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip()
    return env.get("AUTH_USERNAME", "admin"), env["AUTH_PASSWORD"]


class Api:
    def __init__(self, base, token=None):
        self.base, self.token = base.rstrip("/"), token

    def call(self, method, path, body=None):
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        req = urllib.request.Request(self.base + path, method=method, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.loads(r.read() or b"null")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace")

    def login(self):
        user, password = credentials()
        status, body = self.call("POST", "/auth/login", {"username": user, "password": password})
        if status != 200:
            sys.exit(f"login failed: HTTP {status} {body}")
        self.token = body["token"]
        return self.token


def dash(v):
    return "-" if v in (None, "", []) else str(v).replace("|", "/").replace("\n", " ")


def senders(item):
    names = [item.get("senderCompanyName") or item.get("fromName") or item.get("feedSourceName")]
    names += [s.get("senderCompanyName") or s.get("fromName") for s in item.get("sources") or []]
    return "; ".join(dict.fromkeys(n for n in names if n)) or "-"


def lookup_line(lk):
    if not lk:
        return "no lookup"
    m = lk.get("matched") or {}
    cands = ", ".join(f"{c.get('imo')} {c.get('name')} {c.get('deadweightTonnage')}t {c.get('yearBuilt')} {c.get('vesselType')}"
                      for c in (lk.get("candidates") or [])[:4])
    return (f"{lk.get('status')} conf {dash(lk.get('confidence'))} corroborated {dash(lk.get('corroborated'))}; "
            f"matched IMO {dash(m.get('imo'))} {dash(m.get('name'))} DWT {dash(m.get('deadweightTonnage'))} "
            f"built {dash(m.get('yearBuilt'))} flag {dash(m.get('flag'))} type {dash(m.get('vesselType'))}; "
            f"reasons: {dash('; '.join(lk.get('reasons') or []))}; disagreements: {dash('; '.join(lk.get('disagreements') or []))}; "
            f"candidates: {dash(cands)}")


def new_vessel(item, imo_hits):
    v = item["payload"].get("vessel") or {}
    sugg = " / ".join(f"{s.get('vesselId')} {s.get('name')} (IMO {dash(s.get('imoNumber'))}) - {s.get('reason')}"
                      for s in item["payload"].get("suggestions") or [])
    on_file = imo_hits.get(str(item["id"]))
    return "\n".join([
        f"### {item['id']} NEW_VESSEL - {item['subjectLabel']} - from {senders(item)} ({item.get('sourceKind')}, {item.get('receivedAt')})",
        f"- email: IMO {dash(v.get('imo'))}, DWT {dash(v.get('dwt'))}, DWCC {dash(v.get('dwcc'))}, built {dash(v.get('built'))}, "
        f"flag {dash(v.get('flag'))}, draft {dash(v.get('draft'))}, type {dash(v.get('vesselType'))}, "
        f"open {dash(v.get('openPort'))} {dash(v.get('openText'))}",
        f"- notes: {dash((v.get('notes') or '')[:240])}",
        f"- lookup: {lookup_line(item.get('lookup'))}",
        f"- lookup IMO already on file: {dash(on_file)}",
        f"- similar hulls on file: {dash(sugg)}",
        "",
    ])


def vessel_fields(item):
    p = item["payload"]
    rows = "\n".join(f"| {d.get('field')} | {dash(d.get('current'))} | {dash(d.get('incoming'))} | "
                     f"{'minor: ' + dash(d.get('note')) if d.get('minor') else 'no'} |" for d in p.get("diffs") or [])
    return "\n".join([
        f"### {item['id']} VESSEL_FIELDS - {p.get('vesselName')} (vessel {p.get('vesselId')}, matched by {p.get('matchedBy')}) "
        f"- email name {dash((p.get('vessel') or {}).get('name'))} - from {senders(item)} ({item.get('sourceKind')}, {item.get('receivedAt')})",
        f"- lookup: {lookup_line(item.get('lookup'))}",
        f"- email capacity unit {dash((p.get('vessel') or {}).get('capacityUnit'))}; already gap-filled: {dash(', '.join(p.get('filled') or []))}",
        "",
        "| field | on file | email | minor |",
        "|---|---|---|---|",
        rows,
        "",
    ])


def company(item):
    p = item["payload"]
    draft = p.get("draft") or {}
    cmp = p.get("comparison") or {}
    fields = "; ".join(f"{f.get('field')}: {dash(f.get('current'))} -> {dash(f.get('parsed'))}" for f in cmp.get("fields") or [])
    matches = " / ".join(f"{m.get('companyId')} {m.get('name')} by {m.get('how')} ({'; '.join(m.get('reasons') or [])})"
                         for m in draft.get("matches") or [])
    on_file_contacts = [c for c in cmp.get("contacts") or []]
    contacts = "; ".join(
        f"{c.get('kind')} {c.get('value')} [{dash(c.get('label'))}] {dash(c.get('personName'))}"
        + (f" (on file #{on_file_contacts[i].get('existingContactId')})" if i < len(on_file_contacts) and on_file_contacts[i].get('existingContactId') else "")
        for i, c in enumerate(draft.get("contacts") or []))
    people = "; ".join(f"{pp.get('fullName')} ({dash(pp.get('jobTitle'))})" for pp in draft.get("people") or [])
    return "\n".join([
        f"### {item['id']} COMPANY_DETAILS - signed \"{dash((draft.get('company') or {}).get('name'))}\" - on file: "
        f"{dash(p.get('companyId'))} {dash(p.get('companyName'))} (by {dash(p.get('matchedBy'))}) - from {senders(item)} ({item.get('sourceKind')})",
        f"- summary: {item.get('summary')}",
        f"- differing fields: {dash(fields)}",
        f"- people: {dash(people)}; people on file: {dash(', '.join(str(x.get('personId')) + ' ' + str(x.get('fullName')) for x in cmp.get('peopleOnFile') or []))}",
        f"- contacts: {dash(contacts)}",
        f"- candidate firms: {dash(matches)}",
        "",
    ])


def other(item):
    return f"### {item['id']} {item['kind']} - {item.get('subjectLabel')} - {item.get('summary')}\n- payload: {json.dumps(item.get('payload'), ensure_ascii=False)[:1500]}\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--base", default="http://localhost:8081/api/v1")
    ap.add_argument("--minor", action="store_true", help="also fetch minor items")
    args = ap.parse_args()
    out = Path(args.out)
    (out / "items").mkdir(parents=True, exist_ok=True)

    api = Api(args.base)
    (out / "token").write_text(api.login())
    _, status = api.call("GET", "/intake/status")
    (out / "status.json").write_text(json.dumps(status, indent=1))

    query = "size=1000" + ("" if args.minor else "&minor=false")
    _, page = api.call("GET", f"/intake/items?{query}")
    listed = page.get("content", [])
    items = []
    for row in listed:
        _, item = api.call("GET", f"/intake/items/{row['id']}")
        (out / "items" / row["kind"]).mkdir(exist_ok=True)
        (out / "items" / row["kind"] / f"{row['id']}.json").write_text(json.dumps(item, ensure_ascii=False, indent=1), encoding="utf-8")
        items.append(item)

    # A number the search found that is already on a hull here makes the item a link, not a create.
    imo_hits = {}
    for item in items:
        if item["kind"] != "NEW_VESSEL":
            continue
        imo = ((item.get("lookup") or {}).get("matched") or {}).get("imo") or (item["payload"].get("vessel") or {}).get("imo")
        if imo:
            _, found = api.call("GET", f"/vessels?includeBanned=true&imoNumber={urllib.parse.quote(str(imo))}")
            hits = [f"{v['id']} {v['name']}" for v in (found or {}).get("content", [])]
            if hits:
                imo_hits[str(item["id"])] = f"IMO {imo}: " + ", ".join(hits)

    render = {"NEW_VESSEL": lambda i: new_vessel(i, imo_hits), "VESSEL_FIELDS": vessel_fields, "COMPANY_DETAILS": company}
    for kind in sorted({i["kind"] for i in items}):
        chunk = [render.get(kind, other)(i) for i in sorted(items, key=lambda i: (i.get("subjectLabel") or "").upper())
                 if i["kind"] == kind]
        (out / f"evidence_{kind}.md").write_text(f"# {kind} ({len(chunk)})\n\n" + "\n".join(chunk), encoding="utf-8")

    print(f"pending {status.get('pendingItems')} minor {status.get('minorItems')}; fetched {len(items)}:",
          dict(Counter(i["kind"] for i in items)))
    print(f"evidence files: {sorted(str(p) for p in out.glob('evidence_*.md'))}")


if __name__ == "__main__":
    main()
