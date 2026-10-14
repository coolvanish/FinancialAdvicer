package kz.fearsom.financiallifev2.engine

import kz.fearsom.financiallifev2.model.Condition
import kz.fearsom.financiallifev2.model.CurrencyCode
import kz.fearsom.financiallifev2.model.Effect
import kz.fearsom.financiallifev2.model.GameEvent
import kz.fearsom.financiallifev2.model.GameOption
import kz.fearsom.financiallifev2.model.MONTHLY_TICK
import kz.fearsom.financiallifev2.model.MonetaryReform
import kz.fearsom.financiallifev2.model.PlayerState
import kz.fearsom.financiallifev2.model.PoolEntry
import kz.fearsom.financiallifev2.model.ScheduledEvent
import kz.fearsom.financiallifev2.scenarios.EventPoolSelector
import kz.fearsom.financiallifev2.scenarios.ScenarioGraph
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GameEngineEffectsTest {

    // ── Monetary reform ───────────────────────────────────────────────────────

    @Test
    fun `monetary_reform converts capital income debt and switches currency`() {
        val engine = GameEngine(graph = MonetaryReformGraph())
        engine.startGame(characterName = "Test")

        // The graph's intro option applies a 1:2 KZT→USD reform (doubles everything)
        val state = engine.makeChoice("reform")

        assertEquals(CurrencyCode.USD, state.playerState.currency)
        assertEquals(2_000_000L, state.playerState.capital)      // 1_000_000 × 2/1
        assertEquals(200_000L,  state.playerState.income)        // 100_000 × 2/1
        assertEquals(50_000L,   state.playerState.debt)          // 25_000 × 2/1
    }

    @Test
    fun `monetary_reform is a no-op when player currency does not match reform from-currency`() {
        val engine = GameEngine(graph = MonetaryReformMismatchGraph())
        engine.startGame(characterName = "Test")

        val before = engine.state.value!!.playerState
        val state = engine.makeChoice("reform_mismatch")

        // Reform says from=USD but player is in KZT — nothing should change
        assertEquals(before.capital, state.playerState.capital)
        assertEquals(before.currency, state.playerState.currency)
        assertEquals(CurrencyCode.KZT, state.playerState.currency)
    }

    // ── EventPoolSelector weight modifiers ────────────────────────────────────

    @Test
    fun `event_pool knowledge above 50 reduces scam event weight`() {
        val scamEvent = GameEvent(
            id = "scam_event",
            message = "scam",
            options = emptyList(),
            tags = setOf("scam", "scam.pyramid"),
            poolWeight = 100
        )
        val pool = listOf(PoolEntry("scam_event", 100))
        val allEvents: (String) -> GameEvent? = { if (it == "scam_event") scamEvent else null }

        // Low knowledge: scam appears freely
        val lowKnowledgeState = PlayerState(financialKnowledge = 30)
        var scamDraws = 0
        repeat(100) {
            if (EventPoolSelector.selectNext(pool, allEvents, lowKnowledgeState, random = Random(it)) == "scam_event")
                scamDraws++
        }

        // High knowledge: scam probability drops
        val highKnowledgeState = PlayerState(financialKnowledge = 75)
        var scamDrawsHighKnowledge = 0
        repeat(100) {
            if (EventPoolSelector.selectNext(pool, allEvents, highKnowledgeState, random = Random(it)) == "scam_event")
                scamDrawsHighKnowledge++
        }

        // With only one candidate, it always wins regardless of weight. Add a non-scam competitor.
        val safe = GameEvent(id = "safe", message = "safe", options = emptyList(), poolWeight = 100)
        val competingPool = listOf(PoolEntry("scam_event", 100), PoolEntry("safe", 100))
        val competingAllEvents: (String) -> GameEvent? = { id ->
            when (id) { "scam_event" -> scamEvent; "safe" -> safe; else -> null }
        }

        var competingScamLow = 0
        var competingScamHigh = 0
        val trials = 200
        repeat(trials) {
            if (EventPoolSelector.selectNext(competingPool, competingAllEvents, lowKnowledgeState, random = Random(it)) == "scam_event")
                competingScamLow++
            if (EventPoolSelector.selectNext(competingPool, competingAllEvents, highKnowledgeState, random = Random(it)) == "scam_event")
                competingScamHigh++
        }

        // With equal base weights (100 each), low-knowledge should draw scam ~50% of the time.
        // High-knowledge gets scam weight reduced to 60, so ~37.5% — clearly less than low-knowledge.
        assertTrue(competingScamHigh < competingScamLow,
            "High knowledge ($competingScamHigh) should draw scam less often than low knowledge ($competingScamLow)")
    }

    @Test
    fun `event_pool learned scam flag nearly eliminates specific scam subtype`() {
        val scamPyramid = GameEvent(
            id = "scam_pyramid",
            message = "pyramid",
            options = emptyList(),
            tags = setOf("scam", "scam.pyramid"),
            poolWeight = 100
        )
        val safe = GameEvent(id = "safe", message = "safe", options = emptyList(), poolWeight = 100)
        val pool = listOf(PoolEntry("scam_pyramid", 100), PoolEntry("safe", 100))
        val allEvents: (String) -> GameEvent? = { id ->
            when (id) { "scam_pyramid" -> scamPyramid; "safe" -> safe; else -> null }
        }

        val naiveState  = PlayerState()
        val educatedState = PlayerState(flags = setOf("learned.scam.pyramid"))

        var pyramidCountNaive = 0
        var pyramidCountEducated = 0
        val trials = 300
        repeat(trials) {
            if (EventPoolSelector.selectNext(pool, allEvents, naiveState, random = Random(it)) == "scam_pyramid")
                pyramidCountNaive++
            if (EventPoolSelector.selectNext(pool, allEvents, educatedState, random = Random(it)) == "scam_pyramid")
                pyramidCountEducated++
        }

        // Educated player has scam.pyramid weight ×0.15 of base → ~13% vs naive ~50%.
        // Use < naive/2 (not naive/3) to keep the bound stable across JVM random seeds.
        assertTrue(pyramidCountEducated < pyramidCountNaive / 2,
            "Educated player ($pyramidCountEducated) should draw pyramid scam far less than naive ($pyramidCountNaive)")
    }

    // ── Scheduled event year boundary ─────────────────────────────────────────

    @Test
    fun `scheduled_event fires correctly when scheduled from month 12 (year boundary)`() {
        val engine = GameEngine(graph = DecemberScheduleGraph())
        engine.startGame(characterName = "Test")

        // tick_december schedules new_year_event for +1 month (Jan 2025) and triggers a monthly
        // tick. The engine advances from month=12 to month=1 of the next year, and since the
        // pending event is due at that same month=1/year=2025, it fires in the same tick.
        val state = engine.makeChoice("tick_december")
        assertEquals("new_year_event", state.currentEventId,
            "Scheduled event should fire in January within the December→January tick (year-boundary wrap)")
        assertTrue(state.playerState.pendingScheduled.none { it.eventId == "new_year_event" },
            "Fired scheduled event should be removed from pending list")
    }

    // ── Condition.InEra normalization ─────────────────────────────────────────

    @Test
    fun `condition_in_era normalizes modern_kz_2024 alias to kz_2024`() {
        val condition = Condition.InEra("kz_2024")

        val stateWithAlias    = PlayerState(eraId = "modern_kz_2024")
        val stateWithCanonical = PlayerState(eraId = "kz_2024")
        val stateWithDifferent = PlayerState(eraId = "kz_90s")

        assertTrue(condition.check(stateWithAlias),
            "InEra(kz_2024) should match legacy alias modern_kz_2024")
        assertTrue(condition.check(stateWithCanonical),
            "InEra(kz_2024) should match canonical kz_2024")
        assertNotEquals(true, condition.check(stateWithDifferent),
            "InEra(kz_2024) should not match unrelated era kz_90s")
    }

    // ── applyEffects capital coercion ─────────────────────────────────────────

    @Test
    fun `apply_effects capital cannot go below zero from large negative delta`() {
        val engine = GameEngine(graph = LargeNegativeCapitalGraph())
        engine.startGame(characterName = "Test")

        // The option subtracts more capital than the player has
        val state = engine.makeChoice("lose_all")

        assertEquals(0L, state.playerState.capital,
            "Capital should be coerced to 0, not negative, after a large capital loss")
    }

    // ── Internal graph helpers ────────────────────────────────────────────────

    private class MonetaryReformGraph : ScenarioGraph() {
        override val initialPlayerState = PlayerState(
            characterId = "test", eraId = "test",
            capital = 1_000_000L, income = 100_000L, debt = 25_000L,
            currency = CurrencyCode.KZT
        )
        override val events = mapOf(
            "intro" to GameEvent(
                id = "intro", message = "intro",
                options = listOf(
                    GameOption(
                        id = "reform", text = "Reform", emoji = "",
                        effects = Effect(monetaryReform = MonetaryReform(
                            from = CurrencyCode.KZT, to = CurrencyCode.USD,
                            numerator = 2L, denominator = 1L
                        )),
                        next = "after_reform"
                    )
                )
            ),
            "after_reform" to GameEvent(
                id = "after_reform", message = "done",
                options = listOf(GameOption("end", "End", "", Effect(), MONTHLY_TICK))
            ),
            "normal_life" to GameEvent("normal_life", "normal",
                options = listOf(GameOption("tick", "Tick", "", Effect(), MONTHLY_TICK)))
        )
        override val conditionalEvents = emptyList<GameEvent>()
        override val eventPool = emptyList<PoolEntry>()
    }

    private class MonetaryReformMismatchGraph : ScenarioGraph() {
        override val initialPlayerState = PlayerState(
            characterId = "test", eraId = "test",
            capital = 500_000L, currency = CurrencyCode.KZT
        )
        override val events = mapOf(
            "intro" to GameEvent(
                id = "intro", message = "intro",
                options = listOf(
                    GameOption(
                        id = "reform_mismatch", text = "Reform", emoji = "",
                        effects = Effect(monetaryReform = MonetaryReform(
                            from = CurrencyCode.USD, to = CurrencyCode.RUB, // mismatch: player is in KZT
                            numerator = 100L, denominator = 1L
                        )),
                        next = "after_reform"
                    )
                )
            ),
            "after_reform" to GameEvent("after_reform", "done",
                options = listOf(GameOption("end", "End", "", Effect(), MONTHLY_TICK))),
            "normal_life" to GameEvent("normal_life", "normal",
                options = listOf(GameOption("tick", "Tick", "", Effect(), MONTHLY_TICK)))
        )
        override val conditionalEvents = emptyList<GameEvent>()
        override val eventPool = emptyList<PoolEntry>()
    }

    private class DecemberScheduleGraph : ScenarioGraph() {
        override val initialPlayerState = PlayerState(
            characterId = "test", eraId = "test",
            month = 12, year = 2024
        )
        override val events = mapOf(
            "intro" to GameEvent(
                id = "intro", message = "intro",
                options = listOf(
                    GameOption(
                        id = "tick_december", text = "Tick Dec", emoji = "",
                        effects = Effect(scheduleEvent = ScheduledEvent("new_year_event", afterMonths = 1)),
                        next = MONTHLY_TICK
                    )
                )
            ),
            "new_year_event" to GameEvent(
                id = "new_year_event", message = "Happy New Year!",
                options = listOf(GameOption("tick_january", "Continue", "", Effect(), MONTHLY_TICK))
            ),
            "normal_life" to GameEvent("normal_life", "normal",
                options = listOf(GameOption("tick_january", "Tick", "", Effect(), MONTHLY_TICK)))
        )
        override val conditionalEvents = emptyList<GameEvent>()
        override val eventPool = emptyList<PoolEntry>()
    }

    private class LargeNegativeCapitalGraph : ScenarioGraph() {
        override val initialPlayerState = PlayerState(
            characterId = "test", eraId = "test",
            capital = 10_000L
        )
        override val events = mapOf(
            "intro" to GameEvent(
                id = "intro", message = "intro",
                options = listOf(
                    GameOption(
                        id = "lose_all", text = "Lose All", emoji = "",
                        effects = Effect(capitalDelta = -999_999_999L),
                        next = "after_loss"
                    )
                )
            ),
            "after_loss" to GameEvent("after_loss", "broke",
                options = listOf(GameOption("end", "End", "", Effect(), MONTHLY_TICK))),
            "normal_life" to GameEvent("normal_life", "normal",
                options = listOf(GameOption("tick", "Tick", "", Effect(), MONTHLY_TICK)))
        )
        override val conditionalEvents = emptyList<GameEvent>()
        override val eventPool = emptyList<PoolEntry>()
    }
}
