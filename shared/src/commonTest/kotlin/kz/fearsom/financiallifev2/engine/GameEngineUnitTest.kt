package kz.fearsom.financiallifev2.engine

import kz.fearsom.financiallifev2.model.CurrencyCode
import kz.fearsom.financiallifev2.model.Effect
import kz.fearsom.financiallifev2.model.GameEvent
import kz.fearsom.financiallifev2.model.GameOption
import kz.fearsom.financiallifev2.model.MONTHLY_TICK
import kz.fearsom.financiallifev2.model.MonetaryReform
import kz.fearsom.financiallifev2.model.PlayerState
import kz.fearsom.financiallifev2.model.PoolEntry
import kz.fearsom.financiallifev2.scenarios.ScenarioGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for GameEngine behaviors not covered by the simulation harness.
 * Each test builds a minimal ScenarioGraph to isolate the specific logic path.
 */
class GameEngineUnitTest {

    // ── Monetary reform ───────────────────────────────────────────────────────

    @Test
    fun `monetary reform rescales all currency fields and updates currency code`() {
        val engine = GameEngine(graph = MonetaryReformGraph())
        engine.startGame(characterName = "Test")

        val after = engine.makeChoice("reform")

        assertEquals(CurrencyCode.USD, after.playerState.currency)
        // 1_000_000 KZT * (1/500) = 2_000 USD (integer division)
        assertEquals(2_000L, after.playerState.capital)
        assertEquals(1_000L, after.playerState.income)
        assertEquals(400L,   after.playerState.expenses)
        assertEquals(240L,   after.playerState.debt)
    }

    @Test
    fun `monetary reform is a no-op when currency does not match`() {
        val engine = GameEngine(graph = MonetaryReformGraph())
        engine.startGame(characterName = "Test")

        // Apply a reform from RUB on a KZT-denominated state — should be skipped
        val after = engine.makeChoice("wrong_currency_reform")

        assertEquals(CurrencyCode.KZT, after.playerState.currency)
        assertEquals(1_000_000L, after.playerState.capital)
    }

    // ── Stat clamping ─────────────────────────────────────────────────────────

    @Test
    fun `stress clamped at 0 when large negative delta applied`() {
        val engine = GameEngine(graph = StatClampGraph(initialStress = 5))
        engine.startGame(characterName = "Test")

        val after = engine.makeChoice("reduce_stress_big")

        assertEquals(0, after.playerState.stress)
    }

    @Test
    fun `knowledge clamped at 100 when large positive delta applied`() {
        val engine = GameEngine(graph = StatClampGraph(initialKnowledge = 95))
        engine.startGame(characterName = "Test")

        val after = engine.makeChoice("boost_knowledge_big")

        assertEquals(100, after.playerState.financialKnowledge)
    }

    // ── Flag operations ───────────────────────────────────────────────────────

    @Test
    fun `setFlags adds and clearFlags removes in the same effect`() {
        val engine = GameEngine(graph = FlagOperationGraph(preloadFlags = setOf("old_flag", "remove_me")))
        engine.startGame(characterName = "Test")

        val after = engine.makeChoice("mutate_flags")

        assertTrue("new_flag" in after.playerState.flags, "new_flag should be added")
        assertFalse("remove_me" in after.playerState.flags, "remove_me should be cleared")
        assertTrue("old_flag" in after.playerState.flags, "old_flag should be untouched")
    }

    // ── Monthly tick capital floor ────────────────────────────────────────────

    @Test
    fun `capital floored at 0 when monthly expenses exceed capital`() {
        val engine = GameEngine(
            graph = TickGraph(
                initialCapital  = 1_000L,
                income          = 0L,
                expenses        = 5_000L,
                debt            = 0L,
                debtPayment     = 0L
            )
        )
        engine.startGame(characterName = "Test")

        val after = engine.makeChoice("tick")

        assertEquals(0L, after.playerState.capital)
        val report = after.messages.last { it.monthlyReport != null }.monthlyReport!!
        assertEquals(0L, report.capitalAfter)
    }

    // ── loadState msgSeq collision guard ─────────────────────────────────────

    @Test
    fun `loadState advances msgSeq so new message ids do not collide with restored ones`() {
        val engine = GameEngine(graph = SimpleGraph())
        val initial = engine.startGame(characterName = "Test")

        // Play a few turns to produce messages with non-trivial sequence suffixes
        val afterChoice = engine.makeChoice("go_choice")

        // Save state then restore it; new messages after restore must have higher IDs
        engine.loadState(afterChoice, "Test")
        val afterRestore = engine.makeChoice("go_choice")

        val restoredIds = afterChoice.messages.map { it.id }.toSet()
        val newMessages = afterRestore.messages.filter { it.id !in restoredIds }
        assertTrue(newMessages.isNotEmpty(), "Expected at least one new message after restore")

        // All new message IDs should not collide with any ID in the restored history
        newMessages.forEach { msg ->
            assertFalse(msg.id in restoredIds, "New message id '${msg.id}' collides with restored history")
        }
    }

    // ── Minimal graph helpers ─────────────────────────────────────────────────

    private class MonetaryReformGraph : ScenarioGraph() {
        override val initialPlayerState = PlayerState(
            characterId = "test", eraId = "test",
            capital  = 1_000_000L, income = 500_000L, expenses = 200_000L,
            debt     = 120_000L,   debtPaymentMonthly = 0L,
            currency = CurrencyCode.KZT
        )

        override val events = mapOf(
            "intro" to GameEvent(
                id = "intro", message = "intro", options = listOf(
                    GameOption("reform", "Reform KZT→USD", "",
                        Effect(monetaryReform = MonetaryReform(CurrencyCode.KZT, CurrencyCode.USD, 1L, 500L)),
                        "next"),
                    GameOption("wrong_currency_reform", "Wrong currency", "",
                        Effect(monetaryReform = MonetaryReform(CurrencyCode.RUB, CurrencyCode.USD, 1L, 500L)),
                        "next")
                )
            ),
            "next" to GameEvent("next", "done", options = listOf(GameOption("tick", "Tick", "", Effect(), MONTHLY_TICK)))
        )

        override val conditionalEvents = emptyList<GameEvent>()
        override val eventPool = emptyList<PoolEntry>()
    }

    private class StatClampGraph(
        private val initialStress: Int = 50,
        private val initialKnowledge: Int = 50
    ) : ScenarioGraph() {
        override val initialPlayerState = PlayerState(
            characterId = "test", eraId = "test",
            stress = initialStress, financialKnowledge = initialKnowledge
        )

        override val events = mapOf(
            "intro" to GameEvent(
                id = "intro", message = "intro", options = listOf(
                    GameOption("reduce_stress_big", "Reduce stress", "",
                        Effect(stressDelta = -50), "next"),
                    GameOption("boost_knowledge_big", "Boost knowledge", "",
                        Effect(knowledgeDelta = 50), "next")
                )
            ),
            "next" to GameEvent("next", "done", options = listOf(GameOption("tick", "Tick", "", Effect(), MONTHLY_TICK)))
        )

        override val conditionalEvents = emptyList<GameEvent>()
        override val eventPool = emptyList<PoolEntry>()
    }

    private class FlagOperationGraph(private val preloadFlags: Set<String>) : ScenarioGraph() {
        override val initialPlayerState = PlayerState(
            characterId = "test", eraId = "test",
            flags = preloadFlags
        )

        override val events = mapOf(
            "intro" to GameEvent(
                id = "intro", message = "intro", options = listOf(
                    GameOption(
                        id = "mutate_flags", text = "Mutate", emoji = "",
                        effects = Effect(setFlags = setOf("new_flag"), clearFlags = setOf("remove_me")),
                        next = "next"
                    )
                )
            ),
            "next" to GameEvent("next", "done", options = listOf(GameOption("tick", "Tick", "", Effect(), MONTHLY_TICK)))
        )

        override val conditionalEvents = emptyList<GameEvent>()
        override val eventPool = emptyList<PoolEntry>()
    }

    private class TickGraph(
        private val initialCapital: Long,
        private val income: Long,
        private val expenses: Long,
        private val debt: Long,
        private val debtPayment: Long
    ) : ScenarioGraph() {
        override val initialPlayerState = PlayerState(
            characterId = "test", eraId = "test",
            capital = initialCapital, income = income,
            expenses = expenses, debt = debt, debtPaymentMonthly = debtPayment
        )

        override val events = mapOf(
            "intro" to GameEvent("intro", "intro", options = listOf(GameOption("tick", "Tick", "", Effect(), MONTHLY_TICK))),
            "normal_life" to GameEvent("normal_life", "normal", options = listOf(GameOption("tick", "Tick", "", Effect(), MONTHLY_TICK)))
        )

        override val conditionalEvents = emptyList<GameEvent>()
        override val eventPool = emptyList<PoolEntry>()
    }

    private class SimpleGraph : ScenarioGraph() {
        override val initialPlayerState = PlayerState(characterId = "test", eraId = "test")

        override val events = mapOf(
            "intro" to GameEvent("intro", "intro", options = listOf(GameOption("go_choice", "Go", "", Effect(), "next"))),
            "next"  to GameEvent("next",  "next",  options = listOf(GameOption("go_choice", "Go", "", Effect(), "intro")))
        )

        override val conditionalEvents = emptyList<GameEvent>()
        override val eventPool = emptyList<PoolEntry>()
    }
}
