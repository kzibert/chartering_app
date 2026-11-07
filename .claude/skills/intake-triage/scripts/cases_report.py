"""Summarise the case log by the part of the code each situation points at.

    python -I cases_report.py [--cases local/notes/intake-cases.jsonl] [--since 2026-10-01] [--out report.md]

Per area, per situation: how many decisions and rows, over how many triages, and every case with
its reason and its source (mail message / board post / parse rows) - the list to re-run the parser
on, or to turn into corpus examples, when that area is worked on.
"""
import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from apply_decisions import DEFAULT_CASES  # noqa: E402
from situations import SITUATIONS  # noqa: E402

sys.stdout.reconfigure(encoding="utf-8")


def source(case):
    s = case.get("source") or {}
    refs = [f"mail {s['mailMessageId']}" if s.get("mailMessageId") else None,
            f"post {s['feedItemId']}" if s.get("feedItemId") else None]
    refs += [f"parse {x['parsedEmailId']}" for x in s.get("sources") or [] if x.get("parsedEmailId")]
    return ", ".join(r for r in refs if r) or "-"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", default=str(DEFAULT_CASES))
    ap.add_argument("--since", help="only triages on or after this date (YYYY-MM-DD)")
    ap.add_argument("--out")
    args = ap.parse_args()

    cases = [json.loads(line) for line in Path(args.cases).read_text(encoding="utf-8").splitlines() if line.strip()]
    if args.since:
        cases = [c for c in cases if c.get("triage", "") >= args.since]

    # One entry per (situation, case); a VESSEL_FIELDS case contributes each row's own situation too.
    hits = defaultdict(list)
    for c in cases:
        hits[c["situation"]].append((c, None))
        for field, code in (c.get("rows") or {}).items():
            if code != c["situation"]:
                hits[code].append((c, field))

    by_area = defaultdict(list)
    for code, entries in hits.items():
        by_area[SITUATIONS.get(code, ("?", "unknown"))[1]].append((code, entries))

    lines = [f"# Intake cases: {len(cases)} decisions over {len({c.get('triage') for c in cases})} triage(s)", ""]
    for area, codes in sorted(by_area.items(), key=lambda a: -sum(len(e) for _, e in a[1])):
        lines += [f"## {area} - {sum(len(e) for _, e in codes)}", ""]
        for code, entries in sorted(codes, key=lambda c: -len(c[1])):
            lines += [f"### {code} ({len(entries)}) - {SITUATIONS.get(code, ('?',))[0]}", ""]
            for c, field in entries:
                what = f"{c.get('kind')} {c['item']} {c.get('subject') or ''}" + (f" [{field}]" if field else "")
                lines.append(f"- {c.get('triage')} {what} -> {c['action']}: {c['reason']} ({source(c)})")
            lines.append("")
    text = "\n".join(lines)
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
        print(f"wrote {args.out}")
    else:
        print(text)


if __name__ == "__main__":
    main()
