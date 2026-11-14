package com.sorted.app.engine

import org.junit.Assert.*
import org.junit.Test

/** Synthetic shapes from the local audit; no private messages, balances, IDs, or payees. */
class ReviewedSmsRegressionTest {
    @Test fun `upi debit without a named payee remains reviewable`() {
        val p = SmsParser.parse("A/c XX1234 debited INR 125.00 Dt 09-09-26 07:25:53 thru UPI:111111111111.Bal INR 900.00 Not u?Fwd this SMS to 9000000000 to block UPI.Download PNB ONE-PNB")
        assertTrue(p.isTransaction)
        assertEquals(125.0, p.amount!!, 0.0)
        assertEquals(Direction.DEBIT, p.direction)
        assertEquals("2026-09-09", p.transactionDate)
        assertEquals("07:25:53", p.transactionTime)
        assertNull(p.merchantRaw)
        assertNull(p.merchantNormalized)
        assertEquals(ImportDecision.REVIEW, ImportDecisionPolicy.assess(p).decision)
    }

    @Test fun `missing debit amount never falls through to the balance`() {
        val p = SmsParser.parse("A/c XX1234 debited INR Dt 09-09-26 07:25:53 thru UPI:111111111111.Bal INR 900.00")
        assertFalse(p.isTransaction)
        assertNull(p.amount)
    }

    @Test fun `upi debit time can omit seconds`() {
        val p = SmsParser.parse("A/c XX1234 debited INR 124.00 Dt 09-09-26 02:50 thru UPI:111111111111. Not u?Fwd this SMS to 9000000000 to block UPI.")
        assertTrue(p.isTransaction)
        assertEquals(124.0, p.amount!!, 0.0)
        assertEquals(Direction.DEBIT, p.direction)
        assertEquals("2026-09-09", p.transactionDate)
        assertEquals("02:50", p.transactionTime)
        assertNull(p.merchantNormalized)
        assertEquals(ImportDecision.REVIEW, ImportDecisionPolicy.assess(p).decision)
    }

    @Test fun `credited promotional voucher without shop now is ignored`() {
        val p = SmsParser.parse("CREDITED: Rs 250, your Festive Treat from Example Store!\nRedeem on Min Purchase of 1200\nCode: EXAMPLE\nOnline Exclusive. Only today.\nexample.test/offer", "VM-EXAMPLE-P")
        assertFalse(p.isTransaction)
    }

    @Test fun `genuine bank credit survives a promotional footer`() {
        val p = SmsParser.parse("Credit Alert!\nRs.250.00 credited to HDFC Bank A/c XX1234 on 09-09-26 from VPA example@bank (UPI 111111111111)\nUse code SAVE on your next purchase.")
        assertTrue(p.isTransaction)
        assertEquals(Direction.CREDIT, p.direction)
        assertEquals(250.0, p.amount!!, 0.0)
    }

    @Test fun `generated bill with card and cashback language is not income`() {
        val p = SmsParser.parse("Hi Customer,\nBill for your Example plan has been generated.\nCurrent Month Payable Amount: Rs. 250.00\nDue By: 15-Sep-2026\nBill Summary\nPlan Rental: Rs. 200.00\nTax: Rs. 50.00\nView your bill and get up to 25% cashback on payments with a Bank Credit Card.")
        assertFalse(p.isTransaction)
        assertEquals("bill_due", p.ignoreReason)
    }

    @Test fun `completed debit mentioning the remaining bill is still a payment`() {
        val p = SmsParser.parse("Rs.250.00 debited from your account for electricity bill payment on 09-Sep-26. Remaining total amount due Rs. 50.00.")
        assertTrue(p.isTransaction)
        assertEquals(250.0, p.amount!!, 0.0)
        assertEquals(Direction.DEBIT, p.direction)
    }

    @Test fun `executed autopay exposes merchant amount date and card`() {
        val p = SmsParser.parse("AutoPay (E-mandate) Success!\nFor EXAMPLE VIDEO\nTxn Amt:INR149.00\nDt:09/09/2026\nVia:HDFC Bank CC 1234\nMandate ID: EXAMPLE\nNot You? To Block+Reissue Call 9000000000")
        assertTrue(p.isTransaction)
        assertEquals(TransactionStatus.COMPLETED, p.status)
        assertEquals("EXAMPLE VIDEO", p.merchantRaw)
        assertEquals(Direction.DEBIT, p.direction)
        assertEquals(149.0, p.amount!!, 0.0)
        assertEquals("2026-09-09", p.transactionDate)
        assertTrue(p.accountHint!!.endsWith("1234"))
    }

    @Test fun `autopay success without a transaction amount cannot harvest the limit`() {
        val p = SmsParser.parse("AutoPay (E-mandate) Success!\nFor EXAMPLE VIDEO\nTxn Amt:INR\nCurrent Limit: INR9999.00\nDt:09/09/2026\nVia:HDFC Bank CC 1234")
        assertFalse(p.isTransaction)
        assertNull(p.amount)
    }

    @Test fun `standing instruction payment success is a debit to its named merchant`() {
        val p = SmsParser.parse("We have successfully processed payment of INR 249.00 to Merchant Example Software, as per Standing Instruction EXAMPLE on 09/09/2026 for ICICI Bank Credit Card 1234. Call 9000000000 for queries.")
        assertTrue(p.isTransaction)
        assertEquals("Example Software", p.merchantRaw)
        assertEquals(249.0, p.amount!!, 0.0)
        assertEquals(Direction.DEBIT, p.direction)
        assertEquals("2026-09-09", p.transactionDate)
    }

    @Test fun `created standing instruction is an authorization rather than a charge`() {
        val p = SmsParser.parse("We have activated Standing Instruction on ICICI Bank Credit Card 1234. Merchant: Example Software, Maximum Amount: INR 75000.00, Frequency: As Presented, Start Date: 09/09/2026.")
        assertFalse(p.isTransaction)
    }

    @Test fun `upcoming mandate remains pending rather than a completed debit`() {
        val p = SmsParser.parse("For the upcoming mandate set for 09-09-26, your account will be debited with Rs. 199.00 towards Example Video. If mandate is paused the execution will not happen.")
        assertTrue(p.isTransaction)
        assertEquals(TransactionStatus.PENDING, p.status)
        assertEquals(ImportDecision.REVIEW, ImportDecisionPolicy.assess(p).decision)
    }

    @Test fun `compact bank card and currency formatting is supported`() {
        val p = SmsParser.parse("We have successfully processed payment of INR249.00 to Merchant Example Software, as per Standing Instruction EXAMPLE on 09/09/2026 for ICICI Bank Credit Card1234.")
        assertTrue(p.isTransaction)
        assertEquals(249.0, p.amount!!, 0.0)
        assertEquals("XX1234", p.accountHint)
        val card = SmsParser.parse("Spent Rs.250 On HDFC Bank Card1234 At ..EXAMPLE HEALTH_ On 2026-09-09:09:56:34.Not You?")
        assertEquals("Example Health", card.merchantNormalized)
    }

    @Test fun `actual debit survives an offer to earn cashback`() {
        val p = SmsParser.parse("Rs.250.00 debited from your account for electricity bill payment on 09-Sep-26. Earn Rs.50 cashback on your next purchase.")
        assertTrue(p.isTransaction)
        assertEquals(250.0, p.amount!!, 0.0)
        assertEquals(Direction.DEBIT, p.direction)
    }

    @Test fun `failed autopay and consent request stay outside completed payments`() {
        listOf(
            "Autopay Failed:\nINR.249.00 on 09/09/2026 via HDFC Bank Card 1234 for Example Software",
            "AutoPay (E-mandate) Consent Requested! For Example Software Amt Due: INR249.00 authenticate via OTP"
        ).forEach { assertFalse(SmsParser.parse(it).isTransaction) }
    }

    @Test fun `successful provider settlement is a transfer credit not investment income`() {
        val p = SmsParser.parse("Monthly settlement: Transfer successful from Groww\n\nAmount: Rs. 25.50\nInitiated on: 2026-09-09\nUTR no.: EXAMPLE11111111\nBank: HDFC BANK (XXXX1234)")
        assertTrue(p.isTransaction)
        assertEquals(25.50, p.amount!!, 0.0)
        assertEquals(Direction.CREDIT, p.direction)
        assertEquals("Groww", p.merchantRaw)
        assertEquals(TransactionType.TRANSFER, p.transactionType)
        assertEquals("Transfer", p.departmentCategory)
        assertTrue(p.accountHint!!.endsWith("1234"))
    }

    @Test fun `provider settlement format works without a known merchant whitelist`() {
        val p = SmsParser.parse("Quarterly settlement: Transfer successful from Example Broker\nAmount: INR 225.50\nInitiated on: 2026-09-09\nUTR no.: EXAMPLE11111111\nBank: Example Bank (XXXX1234)")
        assertTrue(p.isTransaction)
        assertEquals("Example Broker", p.merchantRaw)
        assertEquals(225.50, p.amount!!, 0.0)
    }

    @Test fun `settlement initiated or portfolio balance is not a completed credit`() {
        listOf(
            "Monthly settlement: Transfer initiated from Example Broker\nAmount: Rs. 25.50\nBank: Example Bank (XXXX1234)",
            "Example Broker reported your Fund bal Rs.25.50 and Securities bal 0. This excludes your Bank balance."
        ).forEach { assertFalse(SmsParser.parse(it).isTransaction) }
    }

    @Test fun `dividend per share confirms credit but never supplies a total`() {
        val p = SmsParser.parse("4th Interim Dividend of 50 paise per share for FY 2025-26 by Example Finance Ltd has been credited into your bank account on 09/09/2026. Team Registrar.")
        assertTrue(p.isTransaction)
        assertEquals(Direction.CREDIT, p.direction)
        assertNull(p.amount)
        assertEquals("Example Finance Ltd", p.merchantRaw)
        assertEquals(TransactionType.INCOME, p.transactionType)
        assertEquals("2026-09-09", p.transactionDate)
        assertEquals(ImportDecision.REVIEW, ImportDecisionPolicy.assess(p).decision)
    }

    @Test fun `rupee dividend rate is not mistaken for the amount received`() {
        val p = SmsParser.parse("1st Interim Dividend of Rs.1.00 per share for FY 2026-27 by Example Ltd has been credited into your bank account on 09.09.2026.")
        assertTrue(p.isTransaction)
        assertNull(p.amount)
        assertEquals("2026-09-09", p.transactionDate)
    }

    @Test fun `explicit dividend total can be extracted`() {
        val p = SmsParser.parse("Dividend amount of INR 125.50 by Example Ltd has been credited into your bank account on 09/09/2026.")
        assertTrue(p.isTransaction)
        assertEquals(125.50, p.amount!!, 0.0)
        assertEquals(TransactionType.INCOME, p.transactionType)
    }

    @Test fun `fixed deposit sweep uses its amount rather than savings balance`() {
        val p = SmsParser.parse("Multi Option Dep (FD) of Rs 1,500.00 created on 09/09/26 by Auto Sweep out of your SB A/c XXXXX1234. Avl SB Bal Rs 9,999.94-SBI")
        assertTrue(p.isTransaction)
        assertEquals(1500.0, p.amount!!, 0.0)
        assertEquals(Direction.DEBIT, p.direction)
        assertEquals(TransactionType.INVESTMENT, p.transactionType)
        assertEquals("2026-09-09", p.transactionDate)
    }

    @Test fun `bank card refund retains the payee and explicit refund meaning`() {
        val p = SmsParser.parse("GOOGLE CLOUD refund of Rs 2.00 credited to ICICI Bank Credit Card XX1234 on 09-SEP-26. Revised total due Rs 0, minimum due Rs .00")
        assertTrue(p.isTransaction)
        assertEquals("GOOGLE CLOUD", p.merchantRaw)
        assertEquals(Direction.CREDIT, p.direction)
        assertEquals(2.0, p.amount!!, 0.0)
        assertEquals(TransactionType.REFUND, p.transactionType)
        assertEquals("Refund", p.departmentCategory)
    }

    @Test fun `refund adjustment beats the generic credit category`() {
        val p = SmsParser.parse("Alert! Rs. 25.50 refunded by UPI-Example Store on 09/SEP/2026 & adjusted against HDFC Bank Credit Card 1234 View updated balance here: https://example.test")
        assertTrue(p.isTransaction)
        assertEquals(TransactionType.REFUND, p.transactionType)
        assertEquals("UPI-Example Store", p.merchantRaw)
        assertEquals(25.50, p.amount!!, 0.0)
    }

    @Test fun `refund initiation and reference alone are not completed credits`() {
        listOf(
            "Refund of Rs 25.50 has been initiated for Example order ABC. Updated balance should reflect in 4-7 days.",
            "Dear customer, your refund reference number for Rs 25.50 is EXAMPLE111111."
        ).forEach { message ->
            val p = SmsParser.parse(message)
            assertFalse(p.isTransaction && p.status == TransactionStatus.COMPLETED)
        }
    }

    @Test fun `dated card merchant can start with punctuation`() {
        val p = SmsParser.parse("Spent Rs.250 On HDFC Bank Card 1234 At ..EXAMPLE HEALTH_ On 2026-09-09:09:56:34.Not You? To Block+Reissue Call 9000000000")
        assertTrue(p.isTransaction)
        assertEquals("..EXAMPLE HEALTH_", p.merchantRaw)
        assertEquals("Example Health", p.merchantNormalized)
        assertEquals("2026-09-09", p.transactionDate)
        assertEquals("09:56:34", p.transactionTime)
    }

    @Test fun `dated card spend does not require a transaction time`() {
        val p = SmsParser.parse("Spent Rs.250 On HDFC Bank Card1234 At EXAMPLE HEALTH On 2026-09-09. Not You?")
        assertTrue(p.isTransaction)
        assertEquals("Example Health", p.merchantNormalized)
        assertEquals("2026-09-09", p.transactionDate)
        assertNull(p.transactionTime)
    }

    @Test fun `autopay merchant after colon does not require an extra space`() {
        val p = SmsParser.parse("AutoPay (E-mandate) Success!\nFor:EXAMPLE VIDEO\nTxn Amt:INR149.00\nDt:09/09/2026\nVia:HDFC Bank CC 1234")
        assertTrue(p.isTransaction)
        assertEquals("EXAMPLE VIDEO", p.merchantRaw)
    }

    @Test fun `single use codes are excluded even without the word otp`() {
        val p = SmsParser.parse("Example account single-use code: 111111")
        assertFalse(p.isTransaction)
        assertEquals("otp", p.ignoreReason)
    }

    @Test fun `numeric vpa remains unknown instead of inventing a merchant`() {
        val p = SmsParser.parse("Credit Alert!\nRs.250.00 credited to HDFC Bank A/c XX1234 on 09-09-26 from VPA 9000000000@example (UPI 111111111111)")
        assertTrue(p.isTransaction)
        assertEquals("9000000000@example", p.merchantRaw)
        assertNull(p.merchantNormalized)
        assertEquals(ImportDecision.REVIEW, ImportDecisionPolicy.assess(p).decision)
    }
}
