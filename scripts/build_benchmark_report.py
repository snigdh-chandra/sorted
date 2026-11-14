#!/usr/bin/env python3
"""Render the standalone report from the public, aggregate-only JSON snapshot."""
import argparse
import html
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "docs/reports/local-model-results.json"
TEMPLATE = ROOT / "scripts/templates/benchmark-report.html"
OUTPUT = ROOT / "docs/reports/local-model-results.html"


def build():
    data = json.loads(DATA.read_text(encoding="utf-8"))
    template = TEMPLATE.read_text(encoding="utf-8")
    rows = []
    for system in data["systems"]:
        m = system["versions"]["after"]["scopes"]["mixed"]["raw"]
        precision = f'{100 * m["correct_suggestions"] / m["suggestions"]:.1f}%' if m["suggestions"] else "—"
        rows.append(
            "<tr><th scope=\"row\" class=\"system\"><strong>" + html.escape(system["label"]) +
            "</strong><small>" + html.escape(system["inputs"]) + "</small></th>" +
            "".join('<td class="num">' + str(v) + "</td>" for v in [
                m["correct_suggestions"], m["wrong_suggestions"], m["records"] - m["suggestions"], precision
            ]) + "</tr>"
        )
    coverage = []
    for row in data["category_coverage"]:
        count = row["train"]
        coverage.append(
            '<tr><th scope="row">' + html.escape(row["category"]) + "</th>" +
            '<td class="training-cell"><div class="training-meter"><span>' + str(count) +
            '</span><span class="training-track" aria-hidden="true"><span style="width:' +
            str(100 * count / 11) + '%"></span></span></div></td>' +
            '<td class="num">' + str(row["validation"]) + '</td><td class="num">' + str(row["test"]) +
            '</td><td class="pill">' + html.escape(row["priority"]) + "</td></tr>"
        )
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    for source, target in [("&", "\\u0026"), ("<", "\\u003c"), (">", "\\u003e"), ("\u2028", "\\u2028"), ("\u2029", "\\u2029")]:
        payload = payload.replace(source, target)
    for marker, value in [
        ("<!-- REPORT_DATA -->", payload),
        ("<!-- STATIC_RESULTS -->", "\n".join(rows)),
        ("<!-- STATIC_COVERAGE -->", "\n".join(coverage)),
    ]:
        if template.count(marker) != 1:
            raise ValueError("Expected one template marker: " + marker)
        template = template.replace(marker, value)
    return template


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail if the committed HTML differs from the aggregate data/template.")
    args = parser.parse_args()
    expected = build()
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text(encoding="utf-8") != expected:
            parser.exit(1, "Report is stale. Run python3 scripts/build_benchmark_report.py\n")
        print("Report matches the aggregate data and template.")
    else:
        OUTPUT.write_text(expected, encoding="utf-8")
        print("Wrote " + str(OUTPUT.relative_to(ROOT)))


if __name__ == "__main__":
    main()
