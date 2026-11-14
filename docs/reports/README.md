# Local intelligence report

Open [local-model-results.html](local-model-results.html) in a browser. It is a
standalone offline HTML file with light/dark appearance, printable output,
before/after features, raw/gated suggestions, and reviewed-target filters.
GitHub displays the source; download the file to use the interactive view.

The public [aggregate JSON](local-model-results.json) contains counts and metrics
only. It excludes private message text, original example IDs, row-level
predictions, user corrections, and personal paths. The underlying private corpus
is not distributed, so these aggregates cannot reproduce the original training
experiment by themselves.

From the repository root:

~~~sh
python3 scripts/build_benchmark_report.py
python3 scripts/build_benchmark_report.py --check
~~~

The HTML is rendered from the aggregate JSON and
<code>scripts/templates/benchmark-report.html</code>. This is the 15 November 2026
snapshot, not a live dashboard. When publishing a new experiment, review the
template's narrative/denominators and methodology notes as well as replacing
metrics. Keep different input contracts and label scopes distinguishable.

Methodology:

- [Category benchmark refresh](../category-benchmark-refresh.md)
- [Parser review audit](../parser-review-audit.md)
- [Data contribution guide](../data-contributions.md)

The original 50-message parser audit informed fixes and is a regression check.
The separate fresh 50-example pack is awaiting human review and remains unscored.
The report does not claim independent accuracy or a deployed on-device model.
