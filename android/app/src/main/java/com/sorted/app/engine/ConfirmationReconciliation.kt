package com.sorted.app.engine

/** Import evidence only: a match triggers review, never deletion or automatic merging. */
data class ConfirmationEvidence(
    val sourceHash: String,
    val evidenceSource: TransactionEvidenceSource,
    val status: TransactionStatus,
    val amount: Double?,
    val currency: String?,
    val direction: Direction,
    val accountHint: String?,
    val transactionDate: String?
)

object ConfirmationReconciliation {
    fun statusForImport(
        record: ConfirmationEvidence,
        accountAlerts: List<ConfirmationEvidence>,
        hasUserStatusCorrection: Boolean = false
    ): TransactionStatus {
        if (hasUserStatusCorrection || record.status != TransactionStatus.COMPLETED) return record.status
        return when (record.evidenceSource) {
            TransactionEvidenceSource.ACCOUNT_ALERT -> record.status
            // Provider notices can precede or duplicate a bank posting. Retain them for
            // review rather than adding another confirmed cash transaction automatically.
            TransactionEvidenceSource.PROVIDER_CONFIRMATION -> TransactionStatus.UNKNOWN
            TransactionEvidenceSource.PAYMENT_CONFIRMATION -> {
                if (!hasUsableIdentity(record) || accountAlerts.any { couldDescribeSamePayment(record, it) }) {
                    TransactionStatus.UNKNOWN
                } else record.status
            }
        }
    }

    private fun hasUsableIdentity(record: ConfirmationEvidence): Boolean =
        record.amount != null && record.amount > 0.0 && record.amount.isFinite() &&
            !record.currency.isNullOrBlank() && record.direction != Direction.UNKNOWN &&
            accountSuffix(record.accountHint) != null && !record.transactionDate.isNullOrBlank()

    private fun couldDescribeSamePayment(a: ConfirmationEvidence, b: ConfirmationEvidence): Boolean =
        a.sourceHash != b.sourceHash &&
            b.evidenceSource == TransactionEvidenceSource.ACCOUNT_ALERT &&
            b.status == TransactionStatus.COMPLETED && hasUsableIdentity(b) &&
            a.amount == b.amount && a.currency.equals(b.currency, ignoreCase = true) &&
            a.direction == b.direction && a.transactionDate == b.transactionDate &&
            accountSuffix(a.accountHint) == accountSuffix(b.accountHint)

    private fun accountSuffix(value: String?): String? =
        value.orEmpty().filter(Char::isDigit).takeIf { it.length >= 4 }?.takeLast(4)
}
