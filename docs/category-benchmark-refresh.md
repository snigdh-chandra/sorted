# Category benchmark refresh — 2026-11-15

The 593 curated annotations were refreshed with SMS parser revision 4, preserving
the reviewed labels and their field-level review scope byte for byte. The
previous baseline and its 39 recorded artifacts remain unchanged. Detailed data,
reports, predictions, and experimental models remain outside Git in the private
dataset workspace (`baseline-v2` and `fresh-test-v1`). No application change or
model deployment was made during this refresh.

## Dataset and split

The existing task pool contains 146 category candidates and 163 abstention
candidates. Missing merchant inputs fell from 20 to 10; all ten remaining cases
already require abstention. No named-category candidate lacks a merchant.
Three dividend notices correctly retain unknown total amounts.

Recovered identities exposed overlap in the previous partitions. Five training
and two validation rows were quarantined using identity/input/event links.
Both feature versions were compared on the same 101 training, 104 validation,
and 97 test records. Original test membership was retained. Effective merchant
families, exact inputs, and known same-event groups do not cross partitions.

This inherited protocol holds all user-reviewed targets out of training. The
usable training set therefore contains only 31 named-category and 70 abstention
examples, all with draft targets. Fees, Fuel, Income, Rent, Subscriptions, and
Utilities have no training support. Most evaluation targets are also drafts.
These reused diagnostic results are not an independent accuracy estimate.

## Results

Unthresholded suggestions on the same 97 test messages:

| Input/system | Correct suggestions | Wrong suggestions |
| --- | ---: | ---: |
| Merchant + direction, small character classifier | 5 | 7 |
| Merchant + direction, deterministic suggestion rules | 40 | 17 |
| Merchant only, small character classifier | 5 | 22 |
| Merchant only, deterministic suggestion rules | 40 | 8 |
| Parser with source-message context, before fixes | 40 | 30 |
| Parser with source-message context, after fixes | 45 | 23 |

The source-aware parser has evidence absent from merchant-only inputs, including
refund wording. Its results are a separate diagnostic, not a matched-input
comparison with the small classifiers.

Neither classifier nor the merchant suggestion rules met the inherited 90%
validation precision gate with at least five suggestions; their gated output is
abstention. The source-aware parser selected a validation threshold of 0.8 and
made 37 correct and five wrong suggestions on the reused test. No system met a
98% validation point-precision gate with five suggestions. App thresholds were
not changed. The small classifier has not demonstrated a reason for deployment.

## Fresh review and next experiment

Fifty additional source examples were reserved outside the original 593,
duplicate bodies, exact model inputs, and known event groups. Their prefilled
answers are assistant proposals awaiting user review. They were not used for
fitting, calibration, or scoring. Forty-nine are proposed category-evaluation
examples; one provident-fund contribution notice needs separate movement review.

These examples come from the existing extraction. Familiar bank templates may
overlap, and actual business identity still needs review for masked/gateway
descriptors. This is not a new collection date or an unseen-template benchmark.

After review, preserve this set for evaluation. A new training protocol can use
existing reviewed labels and targeted examples from unsupported categories,
while keeping the fresh set separate. Distillation, pruning, quantization, and
Android model integration remain subsequent experiments.

Independent checks verified 84 metric blocks, saved-model predictions, training
vocabulary provenance, label integrity, and reserved-set separation. No messages
were sent to an external inference service.
