# Contribute data to Sorted

Help Sorted understand transaction alerts locally. Useful contributions include
unfamiliar bank formats, underrepresented categories, and messages whose correct
answer is **need review**. Start with a small batch of 5–20 varied examples.

Read the [interactive test report](reports/local-model-results.html) and
[benchmark notes](category-benchmark-refresh.md) for the current gaps. GitHub
shows HTML source; download/open the HTML locally to use the report.

## Quick start

1. Choose examples you are authorized to publish. Entirely synthetic examples are
   the easiest starting point. Sanitize real-derived examples locally **before**
   adding them to a Git working tree, issue, PR, or attachment.
2. Create a UTF-8 JSONL file under `data/contributions/`, for example
   `your-alias-utility-alerts.jsonl`. Use neutral example IDs, not phone/SMS IDs.
3. Copy and adapt the [synthetic examples](../data/contributions/examples/synthetic-samples.jsonl).
   Keep only your new examples; change their IDs and message content. One JSON
   object goes on each line. Encode message line breaks as `\n` inside the JSON
   string. Do not wrap the file in a JSON array or add comments/blank lines.
4. Label what the sanitized message supports, quote the supporting text, and
   declare review status and sharing/training permissions honestly.
5. Run the checks below and open a PR using the
   [data contribution template](../.github/PULL_REQUEST_TEMPLATE/data.md).
   Include counts, format coverage, and uncertainty notes, not a raw inbox dump.

From the repository root, using Python 3.9 or newer with no extra packages:

```sh
python3 scripts/validate_contribution_data.py data/contributions/your-alias-utility-alerts.jsonl
python3 scripts/validate_contribution_data.py
```

The second command also finds duplicate IDs and exact message bodies across
contribution files. Validation runs locally, changes no files, and uploads
nothing. It checks structure, basic label consistency, and obvious privacy red
flags. **Passing cannot prove that a message is anonymous or correctly labeled.**

## What helps most

The latest grouped experiment had no training examples for six categories that
appeared in its test: **Fees, Fuel, Income, Rent, Subscriptions, and Utilities**.
Health, Transfer, Entertainment, and Investment also need broader reviewed
coverage. These counts reflect a conservative split, not just total collection
volume; existing reviewed labels can enter a future training protocol.

Useful batches cover:

- Several merchants and banks for the same category, rather than many copies of
  one familiar alert.
- Debit, credit, confirmed refund, failed payment, upcoming mandate, mandate
  registration/cancellation, bill notice, promotion, and balance-only formats.
- Payee omitted; truncated/gateway descriptors; unknown amounts; misleading
  available balance or card limit; short times with optional seconds.
- Broker settlements, dividend notices, investment allocations, and provident-fund
  notices, with their different evidence for bank movement kept explicit.
- SMS and minimal email excerpts, regional languages, and transliterated text.
  Keep the original language; mark a translated reconstruction as synthetic.

An uncertain example is useful. Do not fill missing information to make a batch
look more complete. Rewording the same example creates a related variant, not an
independent evaluation example.

## Sanitize before anything becomes public

Publish only the minimal text needed to reproduce a format or category issue.
Keep original exports and working notes outside the repository. The ignored
`data/private/` and `data/raw/` folders are conveniences, not a privacy guarantee.

| Replace or remove | Safe form |
| --- | --- |
| Person names, signatures, employers, addresses, locations tied to a person | `RECIPIENT_A`, `SENDER_A`, or a neutral fictional equivalent |
| Account/card numbers and actual suffixes | `XX0000`, `XXXX0000` |
| Personal VPA/email, phone numbers | `RECIPIENT_A@example`, `person@example.test`, or `<PHONE>` |
| UTR/RRN/UPI references, order/mandate IDs, folios, PAN/UAN, ticket numbers | `REF_A`, `ORDER_A`, `<PAN>`, `<UAN>` |
| OTPs, PINs, passwords, recovery codes, access tokens, payment links | Remove; use `OTP_REDACTED` or an all-zero fictional code for a synthetic authentication test |
| Exact amounts, balances, limits, dates, times | Consistent fictional values preserving formatting and the distinction between each figure |
| Live URLs, tracking links, QR contents, personalized query parameters | `https://example.test/path` or `<URL>`; remove tokens entirely |
| Email routing headers, recipient addresses, quoted threads, attachments | Remove; retain only the minimum subject/body excerpt and a generic sender family |

Preserve punctuation, spacing, line breaks, word order, date/amount formats,
currency, failure/future wording, and nearby balance/limit labels when those are
the behavior being tested. Public bank/product names can be retained when needed
for the format. Public business names can remain when authorized and necessary
for a merchant-policy example; private payees must be fictionalized.

**Do not accidentally add evidence while sanitizing.** Replacing an opaque payee
with `EXAMPLE CAFE` would make Food easier to infer. Use a neutral name for an
opaque recipient. For a genuine missing-payee alert, keep the payee absent.
Removing identifiers does not by itself make a unique financial event anonymous.

Never submit raw SMS/email exports, databases, screenshots, credentials, APKs,
account histories, personal correction logs, or an entire inbox. Do not send raw
personal messages to an external AI service to sanitize or label them. A private
sharing channel is not established by this guide; use public-safe examples only.

Before staging, inspect your diff locally:

```sh
git diff -- data/contributions/
git diff --cached -- data/contributions/
```

If sensitive information is accidentally published, do not paste it again in a
follow-up comment. Contact a maintainer through an available private channel;
removing it in a later commit does not remove it from Git history. Revoke any
exposed credentials with their issuer.

## File contract

The authoritative structure is
[`transaction-example.schema.json`](../data/contributions/transaction-example.schema.json).
The validator applies that schema's supported structure plus semantic/red-flag
checks. All eight bundled examples are synthetic **drafts**, not human-verified
ground truth. The schema is a contribution format, not the Android import API.

| Field | What to put there |
| --- | --- |
| `schema_version` | `1` |
| `id` | Unique neutral slug, such as `alias-fee-001`; no original source ID |
| `origin` | `synthetic` or `sanitized_real`; do not relabel a real-derived example as fully synthetic |
| `source`, `language`, `sender_family` | `sms`/`email`, language tag such as `en`/`hi-Latn`, and public institution/product family or null; no personal sender address |
| `message` | Minimal public-safe source text |
| `groups` | Template family, merchant family if known, fictional same-event group if applicable, and related example ID in `variant_of` |
| `expected` | Separate movement evidence, status, extracted facts, transaction type, and category decision |
| `evidence` | Short literal quotes occurring in the sanitized message, each tied to an expected field |
| `review` | Draft or actual human review, method, and only the fields explicitly checked |
| `privacy` | Explicit confirmation that sanitization, secret removal, and personal-identifier removal were checked |
| `permissions` | Confirm public sharing; separately indicate whether model training is allowed |
| `notes` | Source-based reasoning, limitations, and broad merchant-policy assumptions; no private purchase context |

Use JSON `null` for unavailable values. Do not use empty strings, `N/A`, a guessed
merchant, or zero as a substitute for an unknown amount. Zero is valid only when
the source actually describes a zero-amount event, which does not establish a
positive bank cash movement. Preserve the source currency; do not invent INR
conversion or exchange rates. Amounts must be finite JSON numbers.

## Label the tasks separately

### 1. Does the source confirm bank cash movement?

`expected.bank_cash_movement` is:

- `confirmed`: the message states that a bank/card debit, credit, or adjustment
  already occurred.
- `not_confirmed`: the message is a request, future payment, failure, promotion,
  balance notice, allocation/contribution notice without a bank posting, or other
  text that does not confirm completed bank movement.
- `unclear`: the available text is insufficient to decide.

Keep `status` (`completed`, `pending`, `failed`, `ignored`, `unknown`) and
`direction` (`debit`, `credit`, `unknown`) separate. A future debit can have known
debit direction and pending status without a completed movement.

Only a transaction amount belongs in `amount`. Never substitute a balance,
available limit, amount due, financial year, dividend rate, or per-share value.
A credited dividend with an undisclosed total can have confirmed movement and
`amount: null` simultaneously. It still needs review before usable totals exist.

### 2. Where did the evidence come from?

`evidence_source` is `account_alert`, `payment_confirmation`,
`provider_confirmation`, or `unknown`. An executed autopay is different from
mandate consent. A broker's successful settlement notice differs from its bank
posting. Preserve both source examples and give them the same fictional
`event_group` if they could describe one payment; do not merge/delete them.

Recognition, amount availability, import approval, and duplicate reconciliation
are separate decisions. A provider can claim a completed credit while Sorted
still keeps its posting confirmation under review.

### 3. Can a broad category be justified?

| `category_status` | `category` | `category_basis` |
| --- | --- | --- |
| `category` | A value from the schema vocabulary | `source_explicit` or `merchant_policy` |
| `abstain` | null | `insufficient` |
| `not_applicable` | null | `not_applicable` |

Use a category decision for a confirmed, completed movement with enough evidence.
Use abstention when the payment is clear but its category is not. Use not
applicable for non-payment examples being collected for detection tests.

- A person name does not prove rent, salary, reimbursement, or a self transfer.
- A gateway or multipurpose merchant does not establish the purchased item.
- A clearly supported cafe merchant can use a broad Food policy; mark that as
  `merchant_policy`, rather than pretending the source literally says Food.
- A credited refund remains Refund even if the merchant usually sells software
  or food. A refund initiation/reference alone is not a completed credit.
- A broker settlement is a transfer, not automatically investment profit/income.
- Entertainment versus Subscriptions still needs consistent policy review for
  some services. Explain an existing policy if applicable; otherwise abstain and
  flag the policy question. Recurrence alone does not resolve the category.
- If a category requires personal ownership or purchase knowledge, set
  `context_required: true` and abstain. Do not publish that private context.

Transaction type and category do not determine the gross outgoing arithmetic.
Eligible completed outgoing debits still count regardless of type; incoming
refunds, rewards, and income remain separate and are never silently netted out.

## Review honestly

Use `review.status: draft` with `human_draft` or `assistant_draft` and an empty
`fields` array until a person has checked the answers. For actual review, use
`human_reviewed`, select `human_review` or `assistant_then_human`, and list only
the expected fields that person checked. Reviewing a category does not verify
the amount, status, or every other field.

AI suggestions and existing app predictions can assist annotation, but are not
ground truth. Check the original public-safe wording and preserve uncertainty.
The validator does not run an AI model or certify label correctness.

`permissions.public_share: true` is required for a public PR. Set
`training_allowed` explicitly; false examples can support permitted review/tests
but must not be added to model training. A contribution remains a candidate even
when its author allows training. Maintainers separately check provenance,
field-level review, label consistency, duplication, and split assignment.

No repository-wide data redistribution license has been selected by this guide.
The flags record stated permissions; they are not a blanket dataset license.
State any usage restrictions in the PR. Third-party datasets require their
applicable provenance and terms to be checked before import or redistribution.
Do not assume this guide grants rights over someone else's material.

## Group related examples before splitting

- Use `template_family` for a wording/format family, not the target category.
  Optional seconds, whitespace variants, and reordered balance footers usually
  remain related variants.
- Use `merchant_family` for the same business across aliases; use null when an
  identity is unknown. Generic gateways/masked fragments are not proven business
  identities.
- Use `event_group` for confirmations that may describe one payment. Values must
  be fictional local group names, never bank reference numbers.
- Use `variant_of` for a deliberately derived example. Both examples must be
  present in the public corpus, and related variants must stay in one split.

Contributors do not assign train/validation/test membership. Maintainers group
identities, exact inputs, templates where relevant, and event links before
assigning versioned splits. Fit vocabularies/models on training only, select
thresholds on validation, and score a reserved test only after independent label
review. Synthetic data is reported separately from real-derived evaluation;
many generated variations do not create many independent tests.

## Maintainer review and use

1. Inspect the public diff for privacy/provenance before quoting example text in
   a review. Check the contributor's stated permissions and origin.
2. Run the validator. Resolve duplicate IDs/bodies and group aliases/variants;
   do not silently discard possible duplicate payments.
3. Review source evidence and category policy. Preserve corrections with their
   author/review scope; resolve conflicting labels explicitly.
4. Keep drafts, reviewed examples, synthetic variants, private-context examples,
   and reserved evaluation examples distinguishable. Merging a PR is not blanket
   model-training approval.
5. Add a focused synthetic Kotlin regression for a parser fix when appropriate.
   A contributed JSONL example does not automatically become an Android test.
6. Record dataset/model/parser versions, partition membership, task eligibility,
   per-category support, precision/coverage, abstentions, and uncertainty. Publish
   aggregate results without private source text or identifiers.

Useful local checks:

```sh
python3 scripts/validate_contribution_data.py
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
python3 scripts/build_benchmark_report.py --check
```

For app changes, also run the Android unit tests/build described in the root
README. The current private experiment cannot be rerun from public aggregates
alone. The checked-in report JSON/template can regenerate the HTML without that
private corpus or any network access.
