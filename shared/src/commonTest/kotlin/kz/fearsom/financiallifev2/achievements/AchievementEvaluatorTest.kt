package kz.fearsom.financiallifev2.achievements

import kz.fearsom.financiallifev2.model.ChatMessage
import kz.fearsom.financiallifev2.model.EndingType
import kz.fearsom.financiallifev2.model.GameState
import kz.fearsom.financiallifev2.model.MessageSender
import kz.fearsom.financiallifev2.model.MonthlyReport
import kz.fearsom.financiallifev2.model.CurrencyCode
import kz.fearsom.financiallifev2.model.PlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AchievementEvaluatorTest {

    private fun state(
        ps: PlayerState = PlayerState(),
        messages: List<ChatMessage> = emptyList(),
        gameOver: Boolean = false,
        endingType: EndingType? = null
    ) = GameState(
        playerState = ps,
        currentEventId = "evt",
        characterName = "Тест",
        messages = messages,
        gameOver = gameOver,
        endingType = endingType
    )

    private fun evaluate(
        state: GameState,
        facts: AchievementSessionFacts = AchievementSessionFacts().update(state),
        already: Set<String> = emptySet()
    ) = AchievementEvaluator.newlyUnlocked(state, facts, already)

    // ── FirstMonthlyReport ────────────────────────────────────────────────────

    @Test
    fun first_steps_unlocks_after_first_monthly_report() {
        val report = MonthlyReport(
            month = 1, year = 2024, currency = CurrencyCode.KZT,
            incomeReceived = 0, expensesPaid = 0, debtPayment = 0, investmentGain = 0,
            netFlow = 0, capitalBefore = 0, capitalAfter = 0, debtAfter = 0, stressDelta = 0
        )
        val withReport = state(
            ps = neutralState(),
            messages = listOf(
                ChatMessage(id = "r1", sender = MessageSender.MONTHLY_REPORT, monthlyReport = report)
            )
        )

        assertTrue(AchievementCatalog.FIRST_STEPS in evaluate(withReport))
        assertFalse(AchievementCatalog.FIRST_STEPS in evaluate(state(ps = neutralState())))
    }

    // ── EmergencyFund ─────────────────────────────────────────────────────────

    @Test
    fun cushion_unlocks_at_six_months_of_expenses() {
        val below = neutralState().copy(capital = 1_199_999L, expenses = 200_000L)
        val exact = neutralState().copy(capital = 1_200_000L, expenses = 200_000L)

        assertFalse(AchievementCatalog.CUSHION in evaluate(state(below)))
        assertTrue(AchievementCatalog.CUSHION in evaluate(state(exact)))
    }

    @Test
    fun cushion_requires_positive_expenses() {
        // Guard against div-by-zero-style trivial unlock when expenses are 0.
        val zeroExpenses = neutralState().copy(capital = 10_000_000L, expenses = 0L)
        assertFalse(AchievementCatalog.CUSHION in evaluate(state(zeroExpenses)))
    }

    // ── DebtFree ──────────────────────────────────────────────────────────────

    @Test
    fun debt_free_requires_having_carried_debt() {
        // Character that never had debt: no unlock.
        val neverDebt = state(neutralState().copy(debt = 0L))
        assertFalse(AchievementCatalog.DEBT_FREE in evaluate(neverDebt))

        // Carried debt, then paid it off across two emissions.
        var facts = AchievementSessionFacts()
        val inDebt = state(neutralState().copy(debt = 500_000L))
        facts = facts.update(inDebt)
        assertFalse(AchievementCatalog.DEBT_FREE in AchievementEvaluator.newlyUnlocked(inDebt, facts, emptySet()))

        val paidOff = state(neutralState().copy(debt = 0L))
        facts = facts.update(paidOff)
        assertTrue(AchievementCatalog.DEBT_FREE in AchievementEvaluator.newlyUnlocked(paidOff, facts, emptySet()))
    }

    // ── AnyFlag (scam collection) ─────────────────────────────────────────────

    @Test
    fun scam_dossiers_unlock_via_learned_flags() {
        val ponzi = state(neutralState().copy(flags = setOf("learned.scam.pyramid")))
        val unlocked = evaluate(ponzi)

        assertTrue(AchievementCatalog.SCAM_PONZI in unlocked)
        assertFalse(AchievementCatalog.SCAM_CRYPTO_MOON in unlocked)
    }

    @Test
    fun scam_condition_accepts_any_of_alternative_flags() {
        // Both historical spellings used by scenario graphs must count.
        val a = state(neutralState().copy(flags = setOf("learned.scam.infocoach")))
        val b = state(neutralState().copy(flags = setOf("learned.infocoach.scam")))

        assertTrue(AchievementCatalog.SCAM_GURU in evaluate(a))
        assertTrue(AchievementCatalog.SCAM_GURU in evaluate(b))
    }

    // ── StoryCompleted ────────────────────────────────────────────────────────

    @Test
    fun new_life_unlocks_on_any_ending() {
        val ended = state(neutralState(), gameOver = true, endingType = EndingType.BANKRUPTCY)
        val running = state(neutralState())

        assertTrue(AchievementCatalog.NEW_LIFE in evaluate(ended))
        assertFalse(AchievementCatalog.NEW_LIFE in evaluate(running))
    }

    // ── CalmThroughCrisis ─────────────────────────────────────────────────────

    @Test
    fun iron_nerves_unlocks_after_calm_crisis_year() {
        val crisisPs = neutralState().copy(stress = 20, month = 1, year = 2024)
        val crisisMsg = ChatMessage(
            id = "char_crisis_1",
            sender = MessageSender.CHARACTER,
            sceneTag = "crisis",
            sourcePlayerState = crisisPs
        )

        var facts = AchievementSessionFacts()
        var current = state(crisisPs, messages = listOf(crisisMsg))
        facts = facts.update(current)
        assertFalse(AchievementCatalog.IRON_NERVES in AchievementEvaluator.newlyUnlocked(current, facts, emptySet()))

        // Advance 12 calm months (stress stays below 30).
        for (m in 2..13) {
            val ps = crisisPs.copy(
                stress = 22,
                month = ((m - 1) % 12) + 1,
                year = 2024 + (m - 1) / 12
            )
            current = state(ps, messages = listOf(crisisMsg))
            facts = facts.update(current)
        }

        assertTrue(AchievementCatalog.IRON_NERVES in AchievementEvaluator.newlyUnlocked(current, facts, emptySet()))
    }

    @Test
    fun iron_nerves_fails_when_stress_spikes_mid_crisis() {
        val crisisPs = neutralState().copy(stress = 20, month = 1, year = 2024)
        val crisisMsg = ChatMessage(
            id = "char_crisis_1",
            sender = MessageSender.CHARACTER,
            sceneTag = "crisis",
            sourcePlayerState = crisisPs
        )

        var facts = AchievementSessionFacts()
        var current = state(crisisPs, messages = listOf(crisisMsg))
        facts = facts.update(current)

        for (m in 2..13) {
            val stress = if (m == 5) 45 else 22   // one bad month ruins the run
            val ps = crisisPs.copy(
                stress = stress,
                month = ((m - 1) % 12) + 1,
                year = 2024 + (m - 1) / 12
            )
            current = state(ps, messages = listOf(crisisMsg))
            facts = facts.update(current)
        }

        assertFalse(AchievementCatalog.IRON_NERVES in AchievementEvaluator.newlyUnlocked(current, facts, emptySet()))
    }

    // ── Dedup ─────────────────────────────────────────────────────────────────

    @Test
    fun already_unlocked_ids_are_never_returned_again() {
        val ponzi = state(neutralState().copy(flags = setOf("learned.scam.pyramid")))
        val second = evaluate(ponzi, already = setOf(AchievementCatalog.SCAM_PONZI))
        assertFalse(AchievementCatalog.SCAM_PONZI in second)
    }

    // ── CalmThroughCrisis exact boundary ─────────────────────────────────────

    @Test
    fun iron_nerves_does_not_unlock_when_stress_equals_stressBelow_exactly() {
        // The condition uses strict < for stressBelow=30, so stress=30 must NOT unlock.
        val crisisPs = neutralState().copy(stress = 10, month = 1, year = 2024)
        val crisisMsg = ChatMessage(
            id = "char_crisis_boundary",
            sender = MessageSender.CHARACTER,
            sceneTag = "crisis",
            sourcePlayerState = crisisPs
        )

        var facts = AchievementSessionFacts()
        facts = facts.update(state(crisisPs, messages = listOf(crisisMsg)))

        // Advance 12 months with stress sitting exactly at 30 (== stressBelow, not below it).
        for (m in 2..13) {
            val ps = crisisPs.copy(
                stress = 30,
                month = ((m - 1) % 12) + 1,
                year = 2024 + (m - 1) / 12
            )
            val current = state(ps, messages = listOf(crisisMsg))
            facts = facts.update(current)
            if (m == 13) {
                assertFalse(
                    AchievementCatalog.IRON_NERVES in AchievementEvaluator.newlyUnlocked(current, facts, emptySet()),
                    "stress == stressBelow (30) must not unlock iron_nerves (strict < required)"
                )
            }
        }
    }

    @Test
    fun iron_nerves_window_resets_when_new_crisis_arrives() {
        // First crisis at month 1 with high stress recorded — would normally block unlock.
        val firstCrisisPs = neutralState().copy(stress = 50, month = 1, year = 2024)
        val firstCrisisMsg = ChatMessage(
            id = "char_crisis_first",
            sender = MessageSender.CHARACTER,
            sceneTag = "crisis",
            sourcePlayerState = firstCrisisPs
        )

        var facts = AchievementSessionFacts()
        facts = facts.update(state(firstCrisisPs, messages = listOf(firstCrisisMsg)))
        // maxStressSinceCrisis = 50 → old window is "dirty"

        // Second crisis arrives; window should reset and stress tracking restart.
        val secondCrisisPs = neutralState().copy(stress = 10, month = 2, year = 2024)
        val secondCrisisMsg = ChatMessage(
            id = "char_crisis_second",
            sender = MessageSender.CHARACTER,
            sceneTag = "crisis",
            sourcePlayerState = secondCrisisPs
        )
        facts = facts.update(state(secondCrisisPs, messages = listOf(firstCrisisMsg, secondCrisisMsg)))
        // After reset: crisisStartAbsMonth = 2024*12+2, maxStress = 10

        // Advance 12 calm months from the second crisis.
        for (m in 3..14) {
            val ps = secondCrisisPs.copy(
                stress = 15,
                month = ((m - 1) % 12) + 1,
                year = 2024 + (m - 1) / 12
            )
            facts = facts.update(state(ps, messages = listOf(firstCrisisMsg, secondCrisisMsg)))
        }

        val finalPs = secondCrisisPs.copy(stress = 15, month = 2, year = 2025)
        val finalState = state(finalPs, messages = listOf(firstCrisisMsg, secondCrisisMsg))
        facts = facts.update(finalState)

        assertTrue(
            AchievementCatalog.IRON_NERVES in AchievementEvaluator.newlyUnlocked(finalState, facts, emptySet()),
            "iron_nerves should unlock after 12 calm months from the second crisis (window reset)"
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * A state that unlocks nothing by itself: modest capital, no debt history,
     * no flags, no messages, game running.
     */
    private fun neutralState() = PlayerState(
        capital = 100_000L,
        income = 450_000L,
        expenses = 200_000L,
        debt = 0L,
        stress = 50,
        characterId = "test_char",
        eraId = "kz_2024"
    )

    @Test
    fun neutral_state_unlocks_nothing() {
        assertEquals(emptySet(), evaluate(state(neutralState())))
    }
}
