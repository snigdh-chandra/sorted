# Contributing to Sorted

Sorted keeps financial interpretation local. Contributions should preserve
source evidence, user corrections, visible uncertainty, and checkable totals.

## Contribute examples

Start with the **[data contribution guide](docs/data-contributions.md)**. It
covers sanitization, labeling, review, permissions, grouping, and the PR process.

- [Current test results — standalone HTML](docs/reports/local-model-results.html)
- [Benchmark methodology and limits](docs/category-benchmark-refresh.md)
- [Synthetic JSONL examples](data/contributions/examples/synthetic-samples.jsonl)
- [Contribution schema](data/contributions/transaction-example.schema.json)
- [Data PR template](.github/PULL_REQUEST_TEMPLATE/data.md)

Only public-safe examples belong in a PR. Do not submit raw inbox exports,
databases, screenshots, personal correction logs, credentials, or financial
histories. Keep originals outside Git and sanitize locally before staging.
Prefer a few varied synthetic examples over a large unreviewed dump.

Run from the repository root with Python 3.9+; no extra packages are needed:

```sh
python3 scripts/validate_contribution_data.py
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
python3 scripts/build_benchmark_report.py --check
```

The validator checks structure, consistency, duplicates, and common privacy red
flags. It cannot certify anonymity, permission to share, or label correctness.
Drafts remain drafts, and merged examples are not automatically training data.

## Contribute code or documentation

- Describe the concrete behavior being fixed and how it was checked.
- Keep parser/source evidence separate from merchant categorization and totals.
- Preserve corrections across imports. Keep incoming refunds separate from gross
  outgoing totals; category/type changes do not change debit eligibility.
- Use fictional fixtures in tests and avoid merchant-specific false-positive
  blacklists when a source-evidence rule can explain the difference.
- Before UI/design changes, read the guidance in [AGENTS.md](AGENTS.md).
- Use the [root README](README.md) for Android setup and build instructions.

Do not upload personal messages to an external model service to debug or label
them. No repository-wide data redistribution license is established here;
record provenance and permissions as described in the data guide.
