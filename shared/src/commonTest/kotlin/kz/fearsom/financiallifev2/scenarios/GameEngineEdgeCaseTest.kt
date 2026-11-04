package kz.fearsom.financiallifev2.scenarios

import kz.fearsom.financiallifev2.engine.GameEngine
import kz.fearsom.financiallifev2.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for engine paths that have no dedicated coverage:
 *
 *  1. applyMonetaryReform — reprices all monetary fields + currency switch
 *  2. applyMonetaryReform — no-op when source currency does not match state
 *  3. Direct graph navigation (option.next ≠ MONTHLY_TICK) — month must not advance
 *  4. monthlyTick bankrupt branch — capital reaching zero spikes stress +15
 *  5. monthlyTick knowledge absorption — financialKnowledge / 25 absorbs stress
 *  6. addScheduledEvent timing — event fires at exactly the requested future month
 *  7. Condition.check coverage — Stat, HasFlag, NotFlag, InEra (+ normalisation), ForCharacter
 */
class GameEngineEdgeCaseTest {

    // ── 1. Monetary reform reprices everything ────────────────────────────────

    @Test
    fun monetary_reform_reprices_all_fields_and_switches_currency() {
        val engine = GameEngine(graph = MonetaryReformGraph(startCurrency = CurrencyCode.RUB))
        engine.startGame(characterName = "Test")

        val after = engine.makeChoice("reform_choice")
        val ps = after.playerState

        assertEquals(CurrencyCode.KZT, ps.currency)
        // All fields multiplied by numerator=500, divided by denominator=1
        assertEquals(50_000_000L,  ps.capital)             // 100_000 × 500
        assertEquals(225_000_000L, ps.income)              // 450_000 × 500
        assertEquals(100_000_000L, ps.expenses)            // 200_000 × 500
        assertEquals(60_000_000L,  ps.debt)                // 120_000 × 500
        assertEquals(9_000_000L,   ps.debtPaymentMonthly)  //  18_000 × 500
        assertEquals(25_000_000L,  ps.investments)         //  50_000 × 500
    }

    // ── 2. Reform is a no-op when state currency ≠ reform.from ───────────────

    @Test
    fun monetary_reform_is_noop_when_currency_does_not_match() {
        // Graph starts in KZT; reform declares from=RUB → mismatch → no change
        val engine = GameEngine(graph = MonetaryReformGraph(startCurrency = CurrencyCode.KZT))
        engine.startGame(characterName = "Test")

        val after = engine.makeChoice("reform_choice")

        assertEquals(CurrencyCode.KZT, after.playerState.currency)
        assertEquals(100_000L, after.playerState.capital)
    }

    // ── 3. Direct navigation does not trigger a monthly tick ─────────────────

    @Test
    fun direct_navigation_does_not_advance_month_or_run_tick() {
        val startMonth = 5
        val engine = GameEngine(graph = DirectNavGraph(startMonth = startMonth))
        engine.startGame(characterName = "Test")

        val after = engine.makeChoice("go_direct")

        assertEquals("destination_event", after.currentEventId)
        assertEquals(startMonth, after.playerState.month)   // month must not advance
        assertEquals(500_000L,   after.playerState.capital) // effect was applied
    }

    // ── 4. Bankrupt branch: capital reaching 0 spikes stress by 15 ───────────

    @Test
    fun bankrupt_monthly_tick_adds_15_stress_when_capital_hits_zero() {
        val startStress = 40
        // income=0, expenses=50 so capital 50 → 0 after tick
        val engine = GameEngine(graph = BankruptGraph(startStress = startStress))
        engine.startGame(characterName = "Test")

        val after = engine.makeChoice("tick")

        assertEquals(0L, after.playerState.capital)
        // rawDelta=15, financialKnowledge default=10 → absorption=10/25=0 → stressDelta=15
        assertEquals(startStress + 15, after.playerState.stress)
    }

    // ── 5. Knowledge absorbs stress at 1 point per 25 knowledge ──────────────

    @Test
    fun financial_knowledge_absorbs_stress_per_25_points() {
        // surplus branch: netFlow=50_001 > expenses=50_000 → rawDelta=−3
        // knowledge=50 → absorption=50/25=2 → stressDelta=−5
        val startStress = 40
        val engine = GameEngine(
            graph = KnowledgeStressGraph(
                income = 100_001L,
                expenses = 50_000L,
                knowledge = 50,
                startStress = startStress
            )
        )
        engine.startGame(characterName = "Test")

        val after = engine.makeChoice("tick")

        val expectedDelta  = -3 - (50 / 25)  // −5
        val expectedStress = (startStress + expectedDelta).coerceIn(0, 100)
        assertEquals(expectedStress, after.playerState.stress)
    }

    // ── 6. Scheduled event fires at exactly the right future month ────────────

    @Test
    fun scheduled_event_fires_at_exactly_the_requested_future_month() {
        val engine = GameEngine(graph = ScheduleTimingGraph())
        engine.startGame(characterName = "Test")

        // "schedule_it" (MONTHLY_TICK from month 1) queues future_event for month 4
        val s1 = engine.makeChoice("schedule_it")   // tick: month 1 → 2
        assertTrue(s1.playerState.pendingScheduled.any { it.eventId == "future_event" })
        assertFalse(s1.gameOver)

        // month 2 → 3: not yet due
        val s2 = engine.makeChoice("continue")
        assertFalse(s2.gameOver)
        assertTrue(s2.playerState.pendingScheduled.any { it.eventId == "future_event" })

        // month 3 → 4: future_event fires (absoluteMonth 2024×12+4 now ≤ current)
        val s3 = engine.makeChoice("continue")
        assertEquals("future_event", s3.currentEventId)
        assertFalse(s3.playerState.pendingScheduled.any { it.eventId == "future_event" })
    }

    // ── 7. Condition.check covers all condition variants ─────────────────────

    @Test
    fun condition_check_evaluates_all_types_correctly() {
        val ps = PlayerState(
            capital = 500_000L,
            income  = 200_000L,
            stress  = 50,
            financialKnowledge = 30,
            riskLevel = 25,
            month   = 6,
            year    = 2024,
            eraId   = "kz_2024",
            characterId = "asan",
            flags   = setOf("has_flag")
        )

        // Stat: GT / LT / EQ / GTE
        assertTrue( Condition.Stat(Condition.Stat.Field.CAPITAL,   Condition.Stat.Op.GT,  400_000L).check(ps))
        assertFalse(Condition.Stat(Condition.Stat.Field.CAPITAL,   Condition.Stat.Op.GT,  600_000L).check(ps))
        assertTrue( Condition.Stat(Condition.Stat.Field.STRESS,    Condition.Stat.Op.EQ,  50L).check(ps))
        assertTrue( Condition.Stat(Condition.Stat.Field.MONTH,     Condition.Stat.Op.GTE, 6L).check(ps))
        assertFalse(Condition.Stat(Condition.Stat.Field.MONTH,     Condition.Stat.Op.GTE, 7L).check(ps))
        assertTrue( Condition.Stat(Condition.Stat.Field.KNOWLEDGE, Condition.Stat.Op.LTE, 30L).check(ps))
        assertTrue( Condition.Stat(Condition.Stat.Field.RISK,      Condition.Stat.Op.NEQ, 99L).check(ps))

        // HasFlag / NotFlag
        assertTrue( Condition.HasFlag("has_flag").check(ps))
        assertFalse(Condition.HasFlag("missing_flag").check(ps))
        assertFalse(Condition.NotFlag("has_flag").check(ps))
        assertTrue( Condition.NotFlag("missing_flag").check(ps))

        // InEra — exact match and legacy alias normalisation
        assertTrue( Condition.InEra("kz_2024").check(ps))
        assertFalse(Condition.InEra("kz_90s").check(ps))
        val psLegacyEra = ps.copy(eraId = "modern_kz_2024")
        assertTrue( Condition.InEra("kz_2024").check(psLegacyEra), "modern_kz_2024 alias must normalise to kz_2024")

        // ForCharacter
        assertTrue( Condition.ForCharacter("asan").check(ps))
        assertFalse(Condition.ForCharacter("daniyar").check(ps))
    }

    // ── Test graphs ───────────────────────────────────────────────────────────

    private class MonetaryReformGraph(startCurrency: CurrencyCode) : ScenarioGraph() {
        override val initialPlayerState = PlayerState(
            characterId = "test", eraId = "test",
            month = 1, year = 2024,
            currency           = startCurrency,
            capital            = 100_000L,
            income             = 450_000L,
            expenses           = 200_000L,
            debt               = 120_000L,
            debtPaymentMonthly = 18_000L,
            investments        = 50_000L
        )

        override val events = mapOf(
            "intro" to GameEvent(
                id = "intro", message = "intro",
                options = listOf(
                    GameOption(
                        id = "reform_choice", text = "Reform", emoji = "",
                        effects = Effect(
                            monetaryReform = MonetaryReform(
                                from = CurrencyCode.RUB, to = CurrencyCode.KZT,
                                numerator = 500L, denominator = 1L
                            )
                        ),
                        next = "after_reform"
                    )
                )
            ),
            "after_reform" to GameEvent(
                id = "after_reform", message = "done",
                isEnding = true, endingType = EndingType.FINANCIAL_STABILITY,
                options = emptyList()
            )
        )

        override val conditionalEvents = emptyList<GameEvent>()
        override val eventPool         = emptyList<PoolEntry>()
    }

    private class DirectNavGraph(startMonth: Int) : ScenarioGraph() {
        override val initialPlayerState = PlayerState(
            characterId = "test", eraId = "test",
            month = startMonth, year = 2024,
            capital = 0L
        )

        override val events = mapOf(
            "intro" to GameEvent(
                id = "intro", message = "intro",
                options = listOf(
                    GameOption(
                        id = "go_direct", text = "Go", emoji = "",
                        effects = Effect(capitalDelta = 500_000L),
                        next = "destination_event"   // NOT MONTHLY_TICK
                    )
                )
            ),
            "destination_event" to GameEvent(
                id = "destination_event", message = "arrived",
                isEnding = true, endingType = EndingType.FINANCIAL_STABILITY,
                options = emptyList()
            )
        )

        override val conditionalEvents = emptyList<GameEvent>()
        override val eventPool         = emptyList<PoolEntry>()
    }

    private class BankruptGraph(startStress: Int) : ScenarioGraph() {
        override val initialPlayerState = PlayerState(
            characterId = "test", eraId = "test",
            month = 1, year = 2024,
            capital  = 50L,
            income   = 0L,
            expenses = 50L,
            debt     = 0L,
            debtPaymentMonthly = 0L,
            stress   = startStress
        )

        override val events = mapOf(
            "intro" to GameEvent(
                id = "intro", message = "intro",
                options = listOf(GameOption("tick", "Tick", "", Effect(), MONTHLY_TICK))
            ),
            "normal_life" to GameEvent(
                id = "normal_life", message = "normal",
                options = listOf(GameOption("tick_2", "Tick", "", Effect(), MONTHLY_TICK))
            )
        )

        override val conditionalEvents = emptyList<GameEvent>()
        override val eventPool         = emptyList<PoolEntry>()
    }

    private class KnowledgeStressGraph(
        income: Long,
        expenses: Long,
        knowledge: Int,
        startStress: Int
    ) : ScenarioGraph() {
        override val initialPlayerState = PlayerState(
            characterId = "test", eraId = "test",
            month = 1, year = 2024,
            capital            = 1_000_000L,
            income             = income,
            expenses           = expenses,
            debt               = 0L,
            debtPaymentMonthly = 0L,
            stress             = startStress,
            financialKnowledge = knowledge
        )

        override val events = mapOf(
            "intro" to GameEvent(
                id = "intro", message = "intro",
                options = listOf(GameOption("tick", "Tick", "", Effect(), MONTHLY_TICK))
            ),
            "normal_life" to GameEvent(
                id = "normal_life", message = "normal",
                options = listOf(GameOption("tick_2", "Tick", "", Effect(), MONTHLY_TICK))
            )
        )

        override val conditionalEvents = emptyList<GameEvent>()
        override val eventPool         = emptyList<PoolEntry>()
    }

    private class ScheduleTimingGraph : ScenarioGraph() {
        override val initialPlayerState = PlayerState(
            characterId = "test", eraId = "test",
            month = 1, year = 2024
        )

        override val events = mapOf(
            "intro" to GameEvent(
                id = "intro", message = "intro",
                options = listOf(
                    GameOption(
                        id = "schedule_it", text = "Schedule", emoji = "",
                        effects = Effect(scheduleEvent = ScheduledEvent("future_event", afterMonths = 3)),
                        next = MONTHLY_TICK
                    )
                )
            ),
            "normal_life" to GameEvent(
                id = "normal_life", message = "normal",
                options = listOf(GameOption("continue", "Continue", "", Effect(), MONTHLY_TICK))
            ),
            "future_event" to GameEvent(
                id = "future_event", message = "future arrived",
                isEnding = true, endingType = EndingType.FINANCIAL_STABILITY,
                options = emptyList()
            )
        )

        override val conditionalEvents = emptyList<GameEvent>()
        override val eventPool         = emptyList<PoolEntry>()
    }
}
