package com.sorted.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategorySuggestionTest {
    @Test
    fun `suggestion result rejects invalid confidence and invalid category`() {
        val category = SuggestedCategory("Food", "Food", TransactionType.EXPENSE)

        assertFails<IllegalArgumentException> {
            CategorySuggestionResult.Suggested(category, Double.NaN, CategorySuggestionSource.ON_DEVICE_MODEL)
        }
        assertFails<IllegalArgumentException> {
            CategorySuggestionResult.Suggested(category, 1.1, CategorySuggestionSource.ON_DEVICE_MODEL)
        }
        assertFails<IllegalArgumentException> {
            SuggestedCategory("", "Food", TransactionType.EXPENSE)
        }
        assertFails<IllegalArgumentException> {
            SuggestedCategory("Food", "Food", TransactionType.UNKNOWN)
        }
    }

    @Test
    fun `rule baseline suggests without modifying input and abstains on unfamiliar text`() {
        val input = CategorySuggestionInput(
            merchant = "Merchant Echo",
            descriptionHint = "pharmacy purchase",
            direction = Direction.DEBIT
        )

        val result = RuleBasedCategorySuggestionEngine.suggest(input)

        assertEquals("Health", (result as CategorySuggestionResult.Suggested).category.departmentCategory)
        assertEquals(CategorySuggestionSource.DETERMINISTIC_BASELINE, result.source)
        assertEquals("Merchant Echo", input.merchant)
        assertTrue(
            RuleBasedCategorySuggestionEngine.suggest(
                CategorySuggestionInput("Unfamiliar Merchant 482", direction = Direction.DEBIT)
            ) is CategorySuggestionResult.Abstained
        )
        assertEquals(
            CategorySuggestionAbstention.NO_USABLE_TEXT,
            (RuleBasedCategorySuggestionEngine.suggest(CategorySuggestionInput(null)) as CategorySuggestionResult.Abstained).reason
        )
    }

    @Test
    fun `separate device profiles learn only from their own corrections`() {
        val deviceOne = LocalCorrectionCategorySuggestionEngine(
            listOf(
                LocalCategoryCorrectionExample(
                    "Corner Store",
                    SuggestedCategory("Groceries", "Groceries", TransactionType.EXPENSE)
                )
            )
        )
        val deviceTwo = LocalCorrectionCategorySuggestionEngine(
            listOf(
                LocalCategoryCorrectionExample(
                    "Corner Store",
                    SuggestedCategory("Home supplies", "Shopping", TransactionType.EXPENSE)
                )
            )
        )
        val input = CategorySuggestionInput("UPI/Corner-Store@okaxis", direction = Direction.DEBIT)

        val first = deviceOne.suggest(input) as CategorySuggestionResult.Suggested
        val second = deviceTwo.suggest(input) as CategorySuggestionResult.Suggested

        assertEquals("Groceries", first.category.departmentCategory)
        assertEquals("Shopping", second.category.departmentCategory)
        assertEquals(CategorySuggestionSource.LOCAL_CORRECTION_EXAMPLES, first.source)
        assertEquals(CategorySuggestionSource.LOCAL_CORRECTION_EXAMPLES, second.source)
        assertEquals("Groceries", (deviceOne.suggest(input) as CategorySuggestionResult.Suggested).category.departmentCategory)
    }

    @Test
    fun `local correction learner abstains for missing or contradictory examples`() {
        val example = LocalCategoryCorrectionExample(
            "Corner Store",
            SuggestedCategory("Groceries", "Groceries", TransactionType.EXPENSE)
        )
        val input = CategorySuggestionInput("Corner Store")
        val emptyProfile = LocalCorrectionCategorySuggestionEngine(emptyList())
        val conflictingProfile = LocalCorrectionCategorySuggestionEngine(
            listOf(
                example,
                example.copy(category = SuggestedCategory("Home supplies", "Shopping", TransactionType.EXPENSE))
            )
        )

        assertEquals(
            CategorySuggestionAbstention.NO_CONFIDENT_CATEGORY,
            (emptyProfile.suggest(input) as CategorySuggestionResult.Abstained).reason
        )
        assertEquals(
            CategorySuggestionAbstention.NO_CONFIDENT_CATEGORY,
            (conflictingProfile.suggest(input) as CategorySuggestionResult.Abstained).reason
        )
    }

    @Test
    fun `related merchants need two independent correction examples`() {
        val category = SuggestedCategory("Groceries", "Groceries", TransactionType.EXPENSE)
        val input = CategorySuggestionInput("Bluebird Market Outlet", direction = Direction.DEBIT)
        val oneCorrection = LocalCorrectionCategorySuggestionEngine(
            listOf(LocalCategoryCorrectionExample("Bluebird Market Cafe", category))
        )
        val twoCorrections = LocalCorrectionCategorySuggestionEngine(
            listOf(
                LocalCategoryCorrectionExample("Bluebird Market Cafe", category),
                LocalCategoryCorrectionExample("Bluebird Market Kitchen", category)
            )
        )

        assertTrue(oneCorrection.suggest(input) is CategorySuggestionResult.Abstained)
        val suggestion = twoCorrections.suggest(input) as CategorySuggestionResult.Suggested
        assertEquals(category, suggestion.category)
        assertEquals(CategorySuggestionSource.LOCAL_CORRECTION_EXAMPLES, suggestion.source)
        assertTrue(suggestion.confidence in 0.78..0.92)
    }

    @Test
    fun `related merchant learner ignores a single generic token and competing categories`() {
        val groceries = SuggestedCategory("Groceries", "Groceries", TransactionType.EXPENSE)
        val conflictedExamples = listOf(
            LocalCategoryCorrectionExample("Bluebird Market Cafe", groceries),
            LocalCategoryCorrectionExample("Bluebird Market Kitchen", groceries),
            LocalCategoryCorrectionExample("Bluebird Market Outlet", SuggestedCategory("Home supplies", "Shopping", TransactionType.EXPENSE)),
            LocalCategoryCorrectionExample("Bluebird Market Store", SuggestedCategory("Home supplies", "Shopping", TransactionType.EXPENSE))
        )
        val learner = LocalCorrectionCategorySuggestionEngine(conflictedExamples)

        assertTrue(learner.suggest(CategorySuggestionInput("Cafe Downtown")) is CategorySuggestionResult.Abstained)
        assertTrue(learner.suggest(CategorySuggestionInput("Bluebird Market Hub")) is CategorySuggestionResult.Abstained)
    }

    @Test
    fun `similarity learning remains isolated across device profiles`() {
        val input = CategorySuggestionInput("Bluebird Market Outlet", direction = Direction.DEBIT)
        fun profile(category: SuggestedCategory) = LocalCorrectionCategorySuggestionEngine(
            listOf(
                LocalCategoryCorrectionExample("Bluebird Market Cafe", category),
                LocalCategoryCorrectionExample("Bluebird Market Kitchen", category)
            )
        )
        val firstProfile = profile(SuggestedCategory("Groceries", "Groceries", TransactionType.EXPENSE))
        val secondProfile = profile(SuggestedCategory("Home supplies", "Shopping", TransactionType.EXPENSE))

        val first = firstProfile.suggest(input) as CategorySuggestionResult.Suggested
        val second = secondProfile.suggest(input) as CategorySuggestionResult.Suggested

        assertEquals("Groceries", first.category.departmentCategory)
        assertEquals("Shopping", second.category.departmentCategory)
        assertEquals("Groceries", (firstProfile.suggest(input) as CategorySuggestionResult.Suggested).category.departmentCategory)
    }

    @Test
    fun `evaluator reports coverage selective accuracy and per-category precision`() {
        val food = SuggestedCategory("Food", "Food", TransactionType.EXPENSE)
        val examples = listOf(
            CategorySuggestionExample("good", CategorySuggestionInput("Good"), food),
            CategorySuggestionExample("wrong", CategorySuggestionInput("Wrong"), SuggestedCategory("Cab", "Transport", TransactionType.EXPENSE)),
            CategorySuggestionExample("abstain", CategorySuggestionInput("Unknown"), SuggestedCategory("OTT Subscription", "Subscriptions", TransactionType.SUBSCRIPTION))
        )
        val engine = CategorySuggestionEngine { input ->
            when (input.merchant) {
                "Good", "Wrong" -> CategorySuggestionResult.Suggested(food, 0.9, CategorySuggestionSource.ON_DEVICE_MODEL)
                else -> CategorySuggestionResult.Abstained(CategorySuggestionAbstention.NO_CONFIDENT_CATEGORY)
            }
        }

        val score = CategorySuggestionEvaluator.evaluate(examples, engine)

        assertEquals(3, score.total)
        assertEquals(2, score.covered)
        assertEquals(1, score.correct)
        assertEquals(1, score.incorrect)
        assertEquals(1, score.abstained)
        assertEquals(2.0 / 3.0, score.coverage, 0.0)
        assertEquals(0.5, score.precisionWhenSuggested!!, 0.0)
        assertEquals(0.5, score.perPredictedDepartment.getValue("Food").precision!!, 0.0)
        assertEquals(0.0, score.perExpectedDepartment.getValue("Transport").precision!!, 0.0)
        assertNull(score.perExpectedDepartment.getValue("Subscriptions").precision)
    }

    @Test
    fun `synthetic fixture corpus measures current baseline across category families`() {
        val examples = CategorySuggestionFixtures.examples
        val score = CategorySuggestionEvaluator.evaluate(examples, RuleBasedCategorySuggestionEngine)

        assertEquals(14, score.total)
        assertEquals(1, score.abstained)
        assertEquals(13, score.covered)
        assertEquals(13, score.correct)
        assertEquals(0, score.incorrect)
        assertTrue(score.perExpectedDepartment.containsKey("Investment"))
        assertTrue(score.perExpectedDepartment.containsKey("Self Transfer"))
        val sameMerchantCases = examples.filter { it.input.merchant == "Merchant Echo" }
        assertEquals(2, sameMerchantCases.size)
        assertEquals(setOf("Food", "Health"), sameMerchantCases.map { it.expected.departmentCategory }.toSet())
    }

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${error::class.java.simpleName}", error)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
