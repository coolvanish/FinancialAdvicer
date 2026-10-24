package kz.fearsom.financiallifev2.engine

import kz.fearsom.financiallifev2.i18n.StringKeys
import kz.fearsom.financiallifev2.i18n.Strings
import kz.fearsom.financiallifev2.model.*
import kz.fearsom.financiallifev2.scenarios.EraDefinition
import kz.fearsom.financiallifev2.scenarios.EraRegistry
import kz.fearsom.financiallifev2.scenarios.EventPoolSelector
import kz.fearsom.financiallifev2.scenarios.ScenarioGraph
import kz.fearsom.financiallifev2.scenarios.ScenarioGraphFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/** Safety bound on how many uneventful months a single choice may fast-forward. */
private const val MAX_FAST_FORWARD_MONTHS = 600

/**
 * Upper bound on retained chat history. Every CHARACTER message embeds a full
 * PlayerState snapshot, so an uncapped history makes the serialized GameState grow
 * O(months) — the server decodes/encodes the whole thing on every /game/choose.
 * 300 messages ≈ 100+ turns, far more than any screen scrolls back through.
 */
private const val MAX_MESSAGE_HISTORY = 300

/**
 * Core game FSM — 3-layer architecture:
 *
 * 1. NARRATIVE GRAPH  — events + options + branches, convergent paths
 * 2. STATE SYSTEM     — PlayerState (capital, income, debt, stress, knowledge, risk, flags)
 * 3. ECONOMIC SIM     — monthly tick: income − expenses − debt + investments ± random
 *
 * Turn flow:
 *   makeChoice(optionId)
 *     → applyEffects(option.effects)      ← applies deltas AND flag mutations
 *     → scheduleEvent if present          ← queues deferred consequences
 *     → if option.next == MONTHLY_TICK:
 *         runMonthlyTick()                ← economic simulation
 *         PRIORITY 1: era global event    ← world crises on specific dates
 *         PRIORITY 2: deferred scheduled  ← consequences from prior choices
 *         PRIORITY 3: conditional event   ← state-triggered (debt crisis, burnout…)
 *         PRIORITY 4: weighted pool       ← scam events, career events, normal life
 *     → else: navigate directly to option.next
 *     → emit new GameState via StateFlow
 */
class GameEngine(
    // Resolved through the factory so the default hits the graph cache instead of
    // building a fresh Kz2024ScenarioGraph per instance (the server constructs a
    // GameEngine on every /game/choose request and immediately loadState()s over it).
    private var graph: ScenarioGraph = ScenarioGraphFactory.forCharacter("asan", "kz_2024"),
    private var eraDefinition: EraDefinition? = null,
    private val random: Random = Random.Default,
    /**
     * Resolves the graph for a (characterId, eraId) at startGame/loadState time.
     * Default = built-in code graphs. The client overrides this to overlay
     * published DB stories, so publishing a story affects gameplay; the server
     * keeps the default (code graphs only), so its behaviour is unchanged.
     */
    private val resolveGraph: (characterId: String, eraId: String) -> ScenarioGraph =
        { characterId, eraId -> ScenarioGraphFactory.forCharacter(characterId, eraId) }
) {

    private val _state = MutableStateFlow<GameState?>(null)
    val state: StateFlow<GameState?> = _state.asStateFlow()

    // Tracks the current character name for template substitution in event messages
    private var currentCharacterName: String = ""

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start a new game. If [initialState] is provided (character-selection flow),
     * it overrides the default [ScenarioGraph.initialPlayerState].
     * If [characterName] is provided, it is used in messages and template substitution.
     */
    fun startGame(
        initialState: PlayerState? = null,
        characterName: String,
    ): GameState {
        currentCharacterName = characterName
        if (initialState != null) {
            graph         = resolveGraph(initialState.characterId, initialState.eraId)
            eraDefinition = EraRegistry.findById(initialState.eraId)
        }
        val ps    = initialState ?: graph.initialPlayerState
        val intro = graph.findEvent("intro") ?: error("No 'intro' event in graph")
        return GameState(
            playerState        = ps,
            currentEventId     = intro.id,
            characterName      = characterName,
            messages           = listOf(
                systemMsg(Strings.sysGameStart.replace("%s", characterName), StringKeys.SYS_GAME_START, listOf(characterName)),
                characterMsg(intro, ps)
            ),
            isWaitingForChoice = intro.options.isNotEmpty() && !intro.isEnding,
            gameOver           = intro.isEnding,
            endingType         = intro.endingType
        ).also { _state.value = it }
    }

    /**
     * Create a GameEngine wired to the correct ScenarioGraph and EraDefinition
     * for the given character + era combination.
     */
    companion object {
        fun forCharacterAndEra(characterId: String, eraId: String): GameEngine {
            val graph = ScenarioGraphFactory.forCharacter(characterId, eraId)
            val era   = EraRegistry.findById(eraId)
            return GameEngine(graph, era)
        }
    }

    /**
     * Process a player choice.
     * Handles MONTHLY_TICK sentinel, the full 4-tier event priority queue, and endings.
     */
    fun makeChoice(optionId: String): GameState {
        val current = _state.value ?: return startGame(characterName = Strings.sysDefaultCharacterName)
        val event   = graph.findEvent(current.currentEventId) ?: return current
        val option  = event.options.find { it.id == optionId } ?: return current

        // Apply stat effects and flag mutations
        var ps = applyEffects(current.playerState, option.effects)

        // Queue deferred consequence if the option requests one
        option.effects.scheduleEvent?.let { scheduled ->
            ps = addScheduledEvent(ps, scheduled)
        }

        val newMessages = mutableListOf(playerMsg(current.currentEventId, option))
        val nextEventId: String

        if (option.next == MONTHLY_TICK) {
            // ── Layer 3: monthly economic simulation + fast-forward ──────────
            // Run the first tick, then skip over "empty" months so the player is
            // never stuck watching the same filler ("Ordinary month", "watch DVD")
            // repeat for years. We only fast-forward while a concrete FUTURE beat
            // exists (a queued consequence or a dated era crisis); the loop stops
            // the moment ANY tier — including a conditional or a fresh pool draw —
            // becomes eligible, so capped filler still appears, just not forever.
            val firstTick = monthlyTick(ps)
            ps = firstTick.first
            var report = firstTick.second
            var beat = resolveTickBeat(ps, current.currentEventId)
            var monthsAdvanced = 1
            while (!beat.hasAny && monthsAdvanced < MAX_FAST_FORWARD_MONTHS && hasFutureDatedBeat(ps)) {
                val nextTick = monthlyTick(ps)
                ps = nextTick.first
                report = nextTick.second
                beat = resolveTickBeat(ps, current.currentEventId)
                monthsAdvanced++
            }

            // Condense any skipped months into a single "time passed" note.
            if (monthsAdvanced > 1) newMessages += timeAdvancedMsg(monthsAdvanced)
            newMessages += monthlyReportMsg(report)

            val nextEvent = beat.winnerEvent
            nextEventId   = nextEvent?.id ?: beat.poolPick ?: "normal_life"
            val selectedFromPool = nextEvent == null && beat.poolPick != null

            val winner = graph.findEvent(nextEventId)
            val singleUsePoolEvent = selectedFromPool && winner != null && winner.cooldownMonths <= 0

            // Pool events are one-shot by default unless they declare a cooldown.
            if (winner?.unique == true || singleUsePoolEvent) {
                ps = ps.copy(triggeredUniqueEvents = ps.triggeredUniqueEvents + nextEventId)
            }

            if (beat.scheduled?.event?.id == nextEventId) {
                ps = removeScheduledEvent(ps, beat.scheduled.pending)
            }

            if (winner != null && winner.cooldownMonths > 0) {
                ps = ps.copy(
                    eventCooldowns = ps.eventCooldowns +
                        (nextEventId to ps.absoluteMonth + winner.cooldownMonths)
                )
            }

            // Mark era event triggered so it doesn't fire again
            if (beat.eraEvent != null) {
                ps = ps.copy(triggeredUniqueEvents = ps.triggeredUniqueEvents + nextEventId)
            }

            // Count pool draws so per-game occurrence caps can retire over-used filler.
            if (selectedFromPool) {
                ps = ps.copy(
                    eventOccurrences = ps.eventOccurrences +
                        (nextEventId to (ps.eventOccurrences[nextEventId] ?: 0) + 1)
                )
            }

            graph.findEvent(nextEventId)?.let { newMessages += characterMsg(it, ps) }

        } else {
            // ── Direct graph navigation ──────────────────────────────────
            nextEventId = option.next
            graph.findEvent(nextEventId)?.let { newMessages += characterMsg(it, ps) }
        }

        val nextEvent = graph.findEvent(nextEventId)
        val ending    = nextEvent?.isEnding == true

        return current.copy(
            playerState        = ps,
            currentEventId     = nextEventId,
            // Cap retained history — see MAX_MESSAGE_HISTORY. takeLast keeps the
            // newest messages, so the visible chat tail is unaffected.
            messages           = (current.messages + newMessages).takeLast(MAX_MESSAGE_HISTORY),
            isWaitingForChoice = !ending,
            gameOver           = ending,
            endingType         = nextEvent?.endingType
        ).also { _state.value = it }
    }

    /** Rehydrate engine from a previously serialized [GameState] (e.g., loaded from DB). */
    fun loadState(state: GameState, characterName: String = "") {
        val resolvedCharacterName = characterName.ifEmpty { state.characterName }
        if (resolvedCharacterName.isNotEmpty()) currentCharacterName = resolvedCharacterName
        graph         = resolveGraph(state.playerState.characterId, state.playerState.eraId)
        eraDefinition = EraRegistry.findById(state.playerState.eraId)
        val localizedState = if (currentCharacterName.isNotEmpty()) {
            state.copy(
                characterName = currentCharacterName,
                messages = state.messages.map { localizeMessage(it, state.playerState) }
            )
        } else {
            state
        }
        // Advance the sequence counter past the already-stored messages so that
        // IDs generated after restore never collide with IDs in the loaded history.
        // Compose uses ChatMessage.id as LazyList keys; duplicates cause diff glitches.
        // Derived from the max numeric suffix (not list size): with MAX_MESSAGE_HISTORY
        // trimming, size plateaus while retained ids keep their original higher suffixes.
        msgSeq = localizedState.messages.maxOfOrNull { msg ->
            msg.id.substringAfterLast('_').toIntOrNull() ?: 0
        } ?: 0
        _state.value = localizedState
    }

    fun currentOptions(): List<GameOption> {
        val id = _state.value?.currentEventId ?: return emptyList()
        return graph.findEvent(id)?.options ?: emptyList()
    }

    fun relocalizeCurrentState(characterName: String = currentCharacterName) {
        val current = _state.value ?: return
        loadState(current, characterName)
    }

    fun reset() { _state.value = null }

    // ── Layer 3: Monthly Economic Simulator ───────────────────────────────────

    private fun monthlyTick(ps: PlayerState): Pair<PlayerState, MonthlyReport> {
        val investGain  = ps.monthlyInvestmentReturn
        val scheduledPrincipal = (ps.debtPaymentMonthly * 0.30).toLong()
        val principalPaid = minOf(scheduledPrincipal, ps.debt)
        val effectiveDebtPayment = when {
            ps.debt <= 0L -> 0L
            principalPaid == ps.debt -> ps.debt
            else -> ps.debtPaymentMonthly
        }
        val netFlow     = ps.income - ps.expenses - effectiveDebtPayment + investGain
        val capitalAfter = (ps.capital + netFlow).coerceAtLeast(0L)

        // 30% of monthly payment reduces principal until the final payoff month.
        val debtAfter     = (ps.debt - principalPaid).coerceAtLeast(0L)

        // Stress dynamics
        val rawDelta = when {
            capitalAfter == 0L           -> 15   // bankrupt stress spike
            netFlow < 0                  ->  6   // negative cash flow
            ps.debt > ps.income * 3      ->  3   // heavy debt burden
            netFlow > ps.expenses        -> -3   // comfortable surplus
            capitalAfter > ps.income * 6 -> -2   // 6-month cushion
            else                         ->  0
        }
        // Each 25 points of knowledge absorbs 1 stress point/month
        val stressDelta = rawDelta - (ps.financialKnowledge / 25)

        val report = MonthlyReport(
            month          = ps.month,
            year           = ps.year,
            currency       = ps.currency,
            incomeReceived = ps.income,
            expensesPaid   = ps.expenses,
            debtPayment    = effectiveDebtPayment,
            investmentGain = investGain,
            netFlow        = netFlow,
            capitalBefore  = ps.capital,
            capitalAfter   = capitalAfter,
            debtAfter      = debtAfter,
            stressDelta    = stressDelta
        )

        val next = ps.copy(
            capital  = capitalAfter,
            debt     = debtAfter,
            debtPaymentMonthly = if (debtAfter == 0L) 0L else ps.debtPaymentMonthly,
            stress   = (ps.stress + stressDelta).coerceIn(0, 100),
            month    = if (ps.month == 12) 1 else ps.month + 1,
            year     = if (ps.month == 12) ps.year + 1 else ps.year,
            eventCooldowns = ps.eventCooldowns.filterValues { it > ps.absoluteMonth + 1 }
        )

        return next to report
    }

    // ── Priority 1: Era global events ─────────────────────────────────────────

    private fun findEraEvent(ps: PlayerState): GameEvent? {
        val era = eraDefinition ?: return null
        return era.globalEvents
            .filter { it.year == ps.year && it.month == ps.month }
            .filter { it.eventId !in ps.triggeredUniqueEvents }
            .filter { it.probability >= 1.0f || random.nextFloat() < it.probability }
            .firstNotNullOfOrNull { graph.findEvent(it.eventId) }
    }

    // ── Priority 2: Deferred scheduled events ────────────────────────────────

    private data class ScheduledPick(val pending: PendingEvent, val event: GameEvent)

    private fun findScheduledEvent(ps: PlayerState): ScheduledPick? =
        ps.pendingScheduled
            .filter { it.absoluteMonth <= ps.absoluteMonth }
            .sortedBy { it.absoluteMonth }
            .firstNotNullOfOrNull { pending ->
                graph.findEvent(pending.eventId)?.let { ScheduledPick(pending, it) }
            }

    private fun addScheduledEvent(ps: PlayerState, scheduled: ScheduledEvent): PlayerState {
        val totalMonths = ps.year * 12 + ps.month + scheduled.afterMonths
        val targetYear  = (totalMonths - 1) / 12
        val targetMonth = ((totalMonths - 1) % 12) + 1
        return ps.copy(
            pendingScheduled = ps.pendingScheduled + PendingEvent(
                eventId    = scheduled.eventId,
                fireAtYear  = targetYear,
                fireAtMonth = targetMonth
            )
        )
    }

    private fun removeScheduledEvent(ps: PlayerState, selected: PendingEvent): PlayerState {
        var removed = false
        return ps.copy(
            pendingScheduled = ps.pendingScheduled.filter { pending ->
                if (!removed &&
                    pending.eventId == selected.eventId &&
                    pending.fireAtYear == selected.fireAtYear &&
                    pending.fireAtMonth == selected.fireAtMonth
                ) {
                    removed = true
                    false
                } else {
                    true
                }
            }
        )
    }

    // ── Priority 3: Conditional event injection ──────────────────────────────

    private fun findConditionalEvent(ps: PlayerState, excludeId: String): GameEvent? =
        graph.conditionalEvents
            .filter { it.id != excludeId && it.conditions.isNotEmpty() }
            .filter { it.id !in ps.triggeredUniqueEvents || !it.unique }
            .filter { (ps.eventCooldowns[it.id] ?: 0) <= ps.absoluteMonth }
            .sortedByDescending { it.priority }
            .firstOrNull { event -> event.conditions.all { it.check(ps) } }

    // ── Fast-forward over uneventful months ───────────────────────────────────

    /** Snapshot of the 4-tier priority queue for one month (no state mutation). */
    private data class TickBeat(
        val eraEvent: GameEvent?,
        val scheduled: ScheduledPick?,
        val conditional: GameEvent?,
        val poolPick: String?
    ) {
        val hasAny: Boolean
            get() = eraEvent != null || scheduled != null || conditional != null || poolPick != null

        /** Event that wins outright (era → scheduled → conditional). Pool is resolved separately. */
        val winnerEvent: GameEvent?
            get() = eraEvent ?: scheduled?.event ?: conditional
    }

    /** Evaluates the priority queue for [ps] without consuming or mutating anything. */
    private fun resolveTickBeat(ps: PlayerState, excludeConditionalId: String): TickBeat {
        val eraEvent = findEraEvent(ps)
        val scheduled = if (eraEvent == null) findScheduledEvent(ps) else null
        val conditional = if (eraEvent == null && scheduled == null)
            findConditionalEvent(ps, excludeConditionalId)
        else null
        val poolPick = if (eraEvent == null && scheduled == null && conditional == null)
            EventPoolSelector.selectNext(
                pool               = graph.eventPool,
                allEvents          = graph::findEvent,
                state              = ps,
                eraWeightModifiers = eraDefinition?.poolWeightModifiers ?: emptyMap(),
                random             = random
            )
        else null
        return TickBeat(eraEvent, scheduled, conditional, poolPick)
    }

    /**
     * True when a concrete dated beat still lies ahead: a queued consequence or an
     * era crisis with a future date. Conditionals and pool draws are intentionally
     * NOT counted here — they are re-checked every skipped month inside the loop, so
     * they end the skip the moment they become eligible. Without a future dated beat
     * we do not fast-forward, preserving the `normal_life` last-resort behaviour.
     */
    private fun hasFutureDatedBeat(ps: PlayerState): Boolean {
        val futureScheduled = ps.pendingScheduled.any { it.absoluteMonth > ps.absoluteMonth }
        val futureEra = eraDefinition?.globalEvents?.any { g ->
            (g.year > ps.year || (g.year == ps.year && g.month > ps.month)) &&
                g.eventId !in ps.triggeredUniqueEvents
        } ?: false
        return futureScheduled || futureEra
    }

    // ── Effect application ────────────────────────────────────────────────────

    private val PendingEvent.absoluteMonth: Int get() = fireAtYear * 12 + fireAtMonth

    private fun applyEffects(ps: PlayerState, e: Effect): PlayerState {
        val newFlags = (ps.flags + e.setFlags) - e.clearFlags
        val updated = ps.copy(
            capital            = (ps.capital            + e.capitalDelta).coerceAtLeast(0L),
            income             = (ps.income             + e.incomeDelta).coerceAtLeast(0L),
            expenses           = (ps.expenses           + e.expensesDelta).coerceAtLeast(0L),
            debt               = (ps.debt               + e.debtDelta).coerceAtLeast(0L),
            debtPaymentMonthly = (ps.debtPaymentMonthly + e.debtPaymentDelta).coerceAtLeast(0L),
            investments        = (ps.investments        + e.investmentsDelta).coerceAtLeast(0L),
            stress             = (ps.stress             + e.stressDelta).coerceIn(0, 100),
            financialKnowledge = (ps.financialKnowledge + e.knowledgeDelta).coerceIn(0, 100),
            riskLevel          = (ps.riskLevel          + e.riskDelta).coerceIn(0, 100),
            flags              = newFlags
        )
        return e.monetaryReform?.let { applyMonetaryReform(updated, it) } ?: updated
    }

    private fun applyMonetaryReform(ps: PlayerState, reform: MonetaryReform): PlayerState {
        if (ps.currency != reform.from || reform.numerator <= 0L || reform.denominator <= 0L) return ps

        // Use Double to avoid Long overflow when numerator is large (e.g. 500:1 redenomination).
        // Game amounts stay well within Double's ~15-digit precision.
        fun reprice(amount: Long): Long =
            (amount.toDouble() * reform.numerator / reform.denominator).toLong().coerceAtLeast(0L)

        return ps.copy(
            capital = reprice(ps.capital),
            income = reprice(ps.income),
            expenses = reprice(ps.expenses),
            debt = reprice(ps.debt),
            debtPaymentMonthly = reprice(ps.debtPaymentMonthly),
            investments = reprice(ps.investments),
            currency = reform.to
        )
    }

    // ── Message factories ─────────────────────────────────────────────────────

    /**
     * Create a chat message for a character event, substituting {token} placeholders
     * with actual values from the current [PlayerState].
     */
    private fun characterMsg(event: GameEvent, ps: PlayerState) = ChatMessage(
        id       = "char_${event.id}_${ts()}",
        sender   = MessageSender.CHARACTER,
        text     = substituteTemplate(event.message, ps),
        emoji    = event.flavor,
        sourceEventId = event.id,
        sourcePlayerState = ps,
        schemeExplanation = event.schemeExplanation?.let { substituteTemplate(it, ps) },
        sceneTag = primarySceneTag(event.tags)
    )

    /**
     * Maps a [GameEvent]'s tag set to a single scene category string consumed by the UI.
     *
     * Priority order matters: mortgage/presale events should keep their housing scene,
     * while a "scam + crisis" event should show the scam scene.
     * Returns null for routine events (salary, monthly tick, consequence-only) so
     * the diary card renders without an image and doesn't visually overload the feed.
     */
    private fun primarySceneTag(tags: Set<String>): String? = when {
        "mortgage" in tags                  -> "mortgage"
        tags.any { it.startsWith("scam") }  -> "scam"
        "crisis" in tags                    -> "crisis"
        "windfall" in tags                  -> "windfall"
        "career" in tags                    -> "career"
        "investment" in tags                -> "investment"
        "family" in tags                    -> "family"
        "world" in tags || "reflection" in tags -> "world"
        else                                -> null
    }

    /**
     * Replaces {token} placeholders in event messages with live PlayerState values.
     *
     * Supported tokens:
     *   {income}       — monthly income
     *   {expenses}     — monthly fixed expenses
     *   {capital}      — current savings/capital
     *   {debt}         — total outstanding debt
     *   {debtPayment}  — monthly debt repayment
     *   {investments}  — total portfolio value
     *   {passiveIncome}— monthly investment return
     *   {netFlow}      — net monthly cash flow
     *   {income3x}     — 3× income (emergency fund target)
     *   {knowledge}    — financial knowledge score
     *   {stress}       — stress score
     *   {name}         — character name
     */
    private fun substituteTemplate(text: String, ps: PlayerState): String =
        text
            .replace("{income}",        ps.income.moneyFormat(ps.currency))
            .replace("{expenses}",      ps.expenses.moneyFormat(ps.currency))
            .replace("{capital}",       ps.capital.moneyFormat(ps.currency))
            .replace("{debt}",          ps.debt.moneyFormat(ps.currency))
            .replace("{debtPayment}",   ps.debtPaymentMonthly.moneyFormat(ps.currency))
            .replace("{investments}",   ps.investments.moneyFormat(ps.currency))
            .replace("{passiveIncome}", ps.monthlyInvestmentReturn.moneyFormat(ps.currency))
            .replace("{netFlow}",       ps.netMonthlyFlow.moneyFormat(ps.currency))
            .replace("{income3x}",      (ps.income * 3).moneyFormat(ps.currency))
            .replace("{knowledge}",     ps.financialKnowledge.toString())
            .replace("{stress}",        ps.stress.toString())
            .replace("{name}",          currentCharacterName)
            .replace("{eraLabel}",      EraRegistry.findById(ps.eraId)?.name ?: ps.eraId)

    fun playerMsg(eventId: String, option: GameOption) = ChatMessage(
        id     = "player_${option.id}_${ts()}",
        sender = MessageSender.PLAYER,
        text   = "${option.emoji} ${option.text}",
        emoji  = option.emoji,
        sourceEventId = eventId,
        sourceOptionId = option.id
    )

    fun systemMsg(text: String, textKey: String? = null, textArgs: List<String> = emptyList()) = ChatMessage(
        id     = "sys_${ts()}",
        sender = MessageSender.SYSTEM,
        text   = text,
        textKey = textKey,
        textArgs = textArgs
    )

    fun monthlyReportMsg(report: MonthlyReport) = ChatMessage(
        id     = "report_${report.year}_${report.month}_${ts()}",
        sender = MessageSender.MONTHLY_REPORT,
        text   = report.toMessage(),
        emoji  = "📊",
        monthlyReport = report
    )

    /** Note shown when the engine fast-forwards over [months] uneventful months. */
    private fun timeAdvancedMsg(months: Int) = systemMsg(
        text     = Strings[StringKeys.SYS_TIME_ADVANCED].replaceFirst("%s", months.toString()),
        textKey  = StringKeys.SYS_TIME_ADVANCED,
        textArgs = listOf(months.toString())
    )

    private fun localizeMessage(message: ChatMessage, ps: PlayerState): ChatMessage = when (message.sender) {
        MessageSender.CHARACTER -> {
            val event = message.sourceEventId?.let { graph.findEvent(it) }
            if (event != null) {
                val sourceState = message.sourcePlayerState ?: ps
                message.copy(
                    text = substituteTemplate(event.message, sourceState),
                    emoji = event.flavor,
                    schemeExplanation = event.schemeExplanation?.let { substituteTemplate(it, sourceState) },
                    sceneTag = primarySceneTag(event.tags)
                )
            } else {
                message
            }
        }
        MessageSender.PLAYER -> {
            val option = message.sourceEventId
                ?.let { graph.findEvent(it) }
                ?.options
                ?.find { it.id == message.sourceOptionId }
            if (option != null) {
                message.copy(
                    text = "${option.emoji} ${option.text}",
                    emoji = option.emoji
                )
            } else {
                message
            }
        }
        MessageSender.MONTHLY_REPORT ->
            message.monthlyReport?.let { message.copy(text = it.toMessage(), emoji = "📊") } ?: message
        MessageSender.SYSTEM ->
            message.textKey?.let { key ->
                val args = if (key == StringKeys.SYS_GAME_START) {
                    listOf(currentCharacterName)
                } else {
                    message.textArgs
                }
                message.copy(text = args.fold(Strings[key]) { acc, arg -> acc.replaceFirst("%s", arg) })
            } ?: message
    }

    private var msgSeq = 0
    private fun ts() = (++msgSeq).toString()
}
