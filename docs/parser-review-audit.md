# Parser review fixes — 2026-11-15

SMS parser revision 4 fixes missing-payee bank debits, executed autopay and
standing-instruction payments, confirmed card refunds, broker settlements,
dividend notices, and fixed-deposit sweeps. Explicit event meaning takes priority
over merchant category heuristics. Generated bills, promotional credits,
gift-card credentials, and mandate setup notices are excluded.

Unknown payees remain unknown. Dividend rates per share never supply a guessed
total. Provider confirmations and payment confirmations that could repeat an
account alert remain available for review; see [parser-contract.md](parser-contract.md).

## Validation

The local replay covered 3,584 SMS messages with zero parser errors:

- 288 previously unrecognized messages became parseable, including 279 bank
  debits without payee names. This is recognition, not automatic category approval.
- Six formerly parsed messages were excluded: three generated bills, a
  promotional voucher, a gift-card credential notice, and a mandate setup notice.
- All four newly supported payment confirmations matched account alerts and
  were retained for review by the reconciliation policy. Six provider
  confirmations also remained reviewable.

On the 50 user-reviewed cash-evidence examples:

| Completed-event recognition | Before | After |
| --- | ---: | ---: |
| Correctly recognized movement | 32 | 38 |
| Incorrectly recognized movement | 2 | 0 |
| Missed movement | 6 | 0 |
| Correctly excluded non-movement | 10 | 12 |

These examples guided the fixes, so this is a diagnostic regression result,
not an estimate of accuracy on unseen messages. Three dividend messages confirm
a credit without disclosing the total; their amounts stay null. Recognition of
an event is separate from confirming a bank posting or approving an import.

The unit suite passed 99 tests, including 38 new regressions; the optional corpus
harness was skipped because its environment variable was unset. The separate
local replay above exercised the full private corpus. The debug APK built with:

```sh
gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1536m
```

The additive database v6 → v7 migration was exercised on a local database copy:
all 1,524 transactions, six user corrections, two rules, and the remaining tables
were preserved, and SQLite integrity checking passed. This check used SQLite
locally; it was not an Android device migration test.

The subsequent connected-device update also completed the v6 → v7 migration.
SQLite integrity checking passed on the extracted device database, all saved
correction fields and rules were preserved. Inspection of excluded old rows found one genuine bank debit using
`HH:MM` rather than `HH:MM:SS`; revision 4 adds support and a regression for it.
The final device rescan retained 1,745 SMS records and recovered that debit. The
app's existing automatic Gmail refresh removed six duplicate email records with
matching payments still retained and imported three new email records, leaving
51 email records. All six saved corrections and both rules remained unchanged.

Private corpus, per-message before/after results, and database copies stay outside
the repository. Regression fixtures use synthetic messages. The original model
baseline remains frozen; rerun a new baseline against the updated parser before
using extracted merchant features to evaluate a student model. Installing this
build causes the next SMS import to rescan locally. The connected phone was
updated after backing up its database, preferences, and previously installed APK.
