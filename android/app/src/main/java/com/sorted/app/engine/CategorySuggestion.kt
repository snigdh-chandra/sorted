package com.sorted.app.engine

import kotlin.math.ln

/** Small, local-only input contract for category suggestions. This is never transaction evidence. */
data class CategorySuggestionInput(
    val merchant: String?,
    val descriptionHint: String? = null,
    val direction: Direction = Direction.UNKNOWN,
    val paymentMode: PaymentMode = PaymentMode.UNKNOWN
)

data class SuggestedCategory(
    val miscCategory: String,
    val departmentCategory: String,
    val transactionType: TransactionType
) {
    init {
        require(miscCategory.isNotBlank()) { "A suggestion needs a user-facing category" }
        require(departmentCategory.isNotBlank()) { "A suggestion needs a category group" }
        require(transactionType != TransactionType.UNKNOWN) { "Unknown is not a category suggestion" }
    }
}

enum class CategorySuggestionSource {
    DETERMINISTIC_BASELINE,
    ON_DEVICE_MODEL,
    LOCAL_CORRECTION_EXAMPLES
}

enum class CategorySuggestionAbstention {
    NO_USABLE_TEXT,
    NO_CONFIDENT_CATEGORY,
    UNSUPPORTED_INPUT
}

sealed class CategorySuggestionResult {
    data class Suggested(
        val category: SuggestedCategory,
        val confidence: Double,
        val source: CategorySuggestionSource
    ) : CategorySuggestionResult() {
        init {
            require(confidence.isFinite() && confidence in 0.0..1.0) {
                "Suggestion confidence must be finite and between 0 and 1"
            }
        }
    }

    data class Abstained(val reason: CategorySuggestionAbstention) : CategorySuggestionResult()
}

/**
 * Suggestion providers only return a proposal. Callers must keep imports, corrections, and totals
 * authoritative and must require explicit user acceptance before applying a proposal.
 */
fun interface CategorySuggestionEngine {
    fun suggest(input: CategorySuggestionInput): CategorySuggestionResult
}

/** Wraps the current deterministic categorizer as the measurable baseline for future models. */
object RuleBasedCategorySuggestionEngine : CategorySuggestionEngine {
    override fun suggest(input: CategorySuggestionInput): CategorySuggestionResult {
        val text = listOf(input.merchant, input.descriptionHint)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .joinToString(" ")
        if (text.isBlank() && input.paymentMode == PaymentMode.UNKNOWN) {
            return CategorySuggestionResult.Abstained(CategorySuggestionAbstention.NO_USABLE_TEXT)
        }

        val result = Categorizer.categorize(
            ParserFacts(
                amount = null,
                currency = null,
                direction = input.direction,
                merchantRaw = text.ifBlank { null },
                paymentMode = input.paymentMode,
                accountHint = null,
                transactionDate = null,
                transactionTime = null
            )
        )
        if (
            result.categorySource == CategorySource.FALLBACK ||
            result.categorySource == CategorySource.NONE ||
            result.miscCategory.isNullOrBlank() ||
            result.miscCategory.equals("Uncategorized", ignoreCase = true) ||
            result.departmentCategory.isNullOrBlank() ||
            result.transactionType == TransactionType.UNKNOWN
        ) {
            return CategorySuggestionResult.Abstained(CategorySuggestionAbstention.NO_CONFIDENT_CATEGORY)
        }

        return CategorySuggestionResult.Suggested(
            category = SuggestedCategory(
                miscCategory = result.miscCategory.orEmpty(),
                departmentCategory = result.departmentCategory.orEmpty(),
                transactionType = result.transactionType
            ),
            confidence = result.confidence,
            source = CategorySuggestionSource.DETERMINISTIC_BASELINE
        )
    }
}

/** One explicit category correction stored locally for the current app profile. */
data class LocalCategoryCorrectionExample(
    val merchant: String,
    val category: SuggestedCategory
)

/**
 * Learns merchant preferences from this profile's accepted corrections only. Exact matches use
 * one unambiguous correction; related merchants need support from at least two distinct corrected
 * merchants. It keeps no global state and abstains when local evidence conflicts or is sparse.
 */
class LocalCorrectionCategorySuggestionEngine(
    examples: List<LocalCategoryCorrectionExample>
) : CategorySuggestionEngine {
    private val localExamples = examples.toList()

    override fun suggest(input: CategorySuggestionInput): CategorySuggestionResult {
        val merchantKey = MerchantNormalizer.key(input.merchant)
        if (merchantKey.isBlank()) {
            return CategorySuggestionResult.Abstained(CategorySuggestionAbstention.NO_USABLE_TEXT)
        }

        val examplesByMerchant = localExamples
            .mapNotNull { example ->
                MerchantNormalizer.key(example.merchant)
                    .takeIf(String::isNotBlank)
                    ?.let { it to example.category }
            }
            .groupBy({ it.first }, { it.second })
        val exactCategories = examplesByMerchant[merchantKey].orEmpty().distinct()
        if (exactCategories.size > 1) {
            return CategorySuggestionResult.Abstained(CategorySuggestionAbstention.NO_CONFIDENT_CATEGORY)
        }
        exactCategories.singleOrNull()?.let { exact ->
            return suggested(exact, confidence = 0.98)
        }

        val evidenceByMerchant = examplesByMerchant.mapNotNull { (key, categories) ->
            val distinctCategories = categories.distinct()
            if (distinctCategories.size != 1) return@mapNotNull null
            MerchantEvidence(tokens(key), distinctCategories.single())
        }.filter { it.tokens.isNotEmpty() }
        val candidateTokens = tokens(merchantKey)
        if (candidateTokens.isEmpty() || evidenceByMerchant.size < MinimumRelatedExamples) {
            return CategorySuggestionResult.Abstained(CategorySuggestionAbstention.NO_CONFIDENT_CATEGORY)
        }

        val documentFrequency = evidenceByMerchant
            .flatMap { it.tokens }
            .groupingBy { it }
            .eachCount()
        val supported = evidenceByMerchant.mapNotNull { evidence ->
            val overlap = candidateTokens intersect evidence.tokens
            if (overlap.size < MinimumSharedTokens) return@mapNotNull null
            val similarity = weightedJaccard(candidateTokens, evidence.tokens, documentFrequency, evidenceByMerchant.size)
            if (similarity < MinimumRelatedSimilarity) return@mapNotNull null
            evidence.category to similarity
        }
        val rankings = supported.groupBy({ it.first }, { it.second })
            .mapValues { (_, similarities) ->
                CategoryEvidence(
                    support = similarities.size,
                    averageSimilarity = similarities.average()
                )
            }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<SuggestedCategory, CategoryEvidence>> { it.value.averageSimilarity }
                    .thenByDescending { it.value.support }
                    .thenBy { it.key.departmentCategory.lowercase() }
            )
        val best = rankings.firstOrNull()?.value
        val second = rankings.getOrNull(1)?.value
        if (
            best == null || best.support < MinimumRelatedExamples ||
            (second != null && best.averageSimilarity - second.averageSimilarity < MinimumCategoryMargin)
        ) {
            return CategorySuggestionResult.Abstained(CategorySuggestionAbstention.NO_CONFIDENT_CATEGORY)
        }

        val confidence = (
            MinimumRelatedConfidence +
                ((best.averageSimilarity - MinimumRelatedSimilarity) / (1.0 - MinimumRelatedSimilarity)) * SimilarityConfidenceRange +
                ((best.support - MinimumRelatedExamples).coerceAtMost(MaximumExtraSupport) * ExtraSupportConfidence)
            ).coerceIn(MinimumRelatedConfidence, MaximumRelatedConfidence)
        return suggested(rankings.first().key, confidence)
    }

    private fun suggested(category: SuggestedCategory, confidence: Double) =
        CategorySuggestionResult.Suggested(
            category = category,
            confidence = confidence,
            source = CategorySuggestionSource.LOCAL_CORRECTION_EXAMPLES
        )

    private fun tokens(merchantKey: String): Set<String> =
        merchantKey.uppercase()
            .split(' ')
            .filter { it.isNotBlank() && it.any(Char::isLetter) }
            .toSet()

    private fun weightedJaccard(
        left: Set<String>,
        right: Set<String>,
        documentFrequency: Map<String, Int>,
        merchantCount: Int
    ): Double {
        val union = left union right
        val intersection = left intersect right
        fun weight(token: String): Double = ln((merchantCount + 1.0) / ((documentFrequency[token] ?: 0) + 1.0)) + 1.0
        return intersection.sumOf(::weight) / union.sumOf(::weight)
    }

    private data class MerchantEvidence(
        val tokens: Set<String>,
        val category: SuggestedCategory
    )

    private data class CategoryEvidence(
        val support: Int,
        val averageSimilarity: Double
    )

    private companion object {
        const val MinimumRelatedExamples = 2
        const val MinimumSharedTokens = 2
        const val MinimumRelatedSimilarity = 0.35
        const val MinimumCategoryMargin = 0.15
        const val MinimumRelatedConfidence = 0.78
        const val MaximumRelatedConfidence = 0.92
        const val SimilarityConfidenceRange = 0.10
        const val ExtraSupportConfidence = 0.02
        const val MaximumExtraSupport = 2
    }
}
