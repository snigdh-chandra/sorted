# Public candidate examples

See the **[data contribution guide](../../docs/data-contributions.md)** before
adding a file. Keep personal originals outside Git. This folder accepts UTF-8
JSONL candidates, not raw exports or databases.

- `examples/synthetic-samples.jsonl`: eight entirely synthetic draft examples.
- `transaction-example.schema.json`: contribution format, version 1.
- New batches: `<neutral-alias>-<topic>.jsonl`, with globally unique neutral IDs.

Examples are not automatically approved for training. Origin, permissions,
field-level human review, task eligibility, related groups, and final dataset
membership remain explicit. Existing app predictions are not gold labels.

From the repository root:

```sh
python3 scripts/validate_contribution_data.py
```

Passing validation does not establish privacy or correctness. Public sharing
and training permission are separate fields. This folder does not establish a
general data redistribution license.
