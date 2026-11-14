package com.sorted.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmationReconciliationTest {
    private val confirmation = ConfirmationEvidence(
        "confirmation", TransactionEvidenceSource.PAYMENT_CONFIRMATION,
        TransactionStatus.COMPLETED, 149.0, "INR", Direction.DEBIT, "XX1234", "2026-09-09"
    )
    private val alert = confirmation.copy(sourceHash = "account", evidenceSource = TransactionEvidenceSource.ACCOUNT_ALERT)

    @Test fun `executed payment without an account alert remains completed`() {
        assertEquals(TransactionStatus.COMPLETED, ConfirmationReconciliation.statusForImport(confirmation, emptyList()))
    }

    @Test fun `matching account alert sends secondary confirmation to review`() {
        assertEquals(TransactionStatus.UNKNOWN, ConfirmationReconciliation.statusForImport(confirmation, listOf(alert)))
        assertEquals(TransactionStatus.COMPLETED, ConfirmationReconciliation.statusForImport(alert, listOf(confirmation)))
    }

    @Test fun `different financial identities do not match`() {
        listOf(
            alert.copy(amount = 150.0), alert.copy(currency = "USD"),
            alert.copy(direction = Direction.CREDIT), alert.copy(accountHint = "XX5678"),
            alert.copy(transactionDate = "2026-09-10"), alert.copy(status = TransactionStatus.PENDING),
            alert.copy(sourceHash = confirmation.sourceHash),
            alert.copy(evidenceSource = TransactionEvidenceSource.PAYMENT_CONFIRMATION)
        ).forEach {
            assertEquals(TransactionStatus.COMPLETED, ConfirmationReconciliation.statusForImport(confirmation, listOf(it)))
        }
    }

    @Test fun `account masking and currency case do not prevent a match`() {
        assertEquals(TransactionStatus.UNKNOWN, ConfirmationReconciliation.statusForImport(
            confirmation, listOf(alert.copy(accountHint = "XXXX1234", currency = "inr"))
        ))
    }

    @Test fun `missing or invalid payment identity stays reviewable`() {
        listOf(
            confirmation.copy(amount = null), confirmation.copy(amount = 0.0),
            confirmation.copy(amount = Double.NaN), confirmation.copy(currency = null),
            confirmation.copy(direction = Direction.UNKNOWN), confirmation.copy(accountHint = null),
            confirmation.copy(accountHint = "XX123"), confirmation.copy(transactionDate = null)
        ).forEach {
            assertEquals(TransactionStatus.UNKNOWN, ConfirmationReconciliation.statusForImport(it, emptyList()))
        }
    }

    @Test fun `provider settlement never silently adds a second bank posting`() {
        val provider = confirmation.copy(evidenceSource = TransactionEvidenceSource.PROVIDER_CONFIRMATION)
        assertEquals(TransactionStatus.UNKNOWN, ConfirmationReconciliation.statusForImport(provider, emptyList()))
        assertEquals(TransactionStatus.UNKNOWN, ConfirmationReconciliation.statusForImport(provider, listOf(alert)))
    }

    @Test fun `explicit user status correction wins over reconciliation`() {
        assertEquals(TransactionStatus.COMPLETED, ConfirmationReconciliation.statusForImport(confirmation, listOf(alert), true))
        assertEquals(TransactionStatus.COMPLETED, ConfirmationReconciliation.statusForImport(
            confirmation.copy(evidenceSource = TransactionEvidenceSource.PROVIDER_CONFIRMATION), emptyList(), true
        ))
    }

    @Test fun `pending failed and reviewed records do not become completed`() {
        listOf(TransactionStatus.PENDING, TransactionStatus.FAILED, TransactionStatus.UNKNOWN).forEach {
            assertEquals(it, ConfirmationReconciliation.statusForImport(confirmation.copy(status = it), emptyList()))
        }
    }
}
