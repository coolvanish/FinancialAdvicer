package kz.fearsom.financiallifev2.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kz.fearsom.financiallifev2.data.AchievementsRepository
import kz.fearsom.financiallifev2.data.GameSessionRepository
import kz.fearsom.financiallifev2.data.SeedData
import kz.fearsom.financiallifev2.engine.GameEngine
import kz.fearsom.financiallifev2.i18n.Strings
import kz.fearsom.financiallifev2.model.EndingType
import kz.fearsom.financiallifev2.model.GameOption
import kz.fearsom.financiallifev2.model.GameState
import kz.fearsom.financiallifev2.model.toGameEnding
import kz.fearsom.financiallifev2.model.toPlayerState
import kz.fearsom.financiallifev2.network.GameApiService
import kz.fearsom.financiallifev2.scenarios.ScenarioGraphFactory

data class GameUiState(
    val gameState: GameState? = null,
    val currentOptions: List<GameOption> = emptyList(),
    val isTyping: Boolean = false,
    val showStats: Boolean = false,
    // Dynamic character display info
    val characterName: String  = Strings.sysDefaultCharacterName,
    val characterEmoji: String = "🛒",
    val characterTitle: String = "",
    val gameStartYear: Int? = null,
    val gameStartMonth: Int? = null
)

/**
 * Pure Kotlin presenter — iOS + Android compatible (no Android ViewModel).
 *
 * Session-aware: supports both the legacy default start (Асан) and the
 * new session-based flow (character-selection → ChatScreen).
 */
class GamePresenter(
    private val engine: GameEngine,
    private val sessionRepo: GameSessionRepository,
    private val scope: CoroutineScope,
    private val gameApiService: GameApiService? = null,
    private val achievementsRepo: AchievementsRepository? = null
) {
    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    // Tracks which session is currently being played (for save-on-back)
    private var activeSessionId: String? = null

    // Signature of the last state persisted to the repo; saves are skipped when it is
    // unchanged (e.g. on relocalization, which re-emits the same logical state).
    private var lastPersistedSignature: String? = null

    // Session ids whose completion has already been recorded — guards against
    // double-recording statistics when a game-over state is re-emitted.
    private val recordedCompletions = mutableSetOf<String>()

    init {
        scope.launch {
            engine.state.collect { gameState ->
                _uiState.value = _uiState.value.copy(
                    gameState      = gameState,
                    currentOptions = if (gameState?.isWaitingForChoice == true)
                        engine.currentOptions()
                    else
                        emptyList()
                )
                val state = gameState ?: return@collect
                val sessionId = activeSessionId ?: return@collect

                // Achievement unlock detection — evaluates on every state advance;
                // repo dedupes internally, sync is fire-and-forget (offline-safe).
                achievementsRepo?.let { repo ->
                    val newlyUnlocked = repo.onGameState(state)
                    if (newlyUnlocked.isNotEmpty()) {
                        launch { repo.sync() }
                    }
                }

                // Persist only when the logical state actually advanced. Re-localization
                // re-emits the same event id and message count, so its signature is
                // unchanged and the redundant save is skipped (replaces the old
                // suppressNextStateSideEffects flag).
                // absoluteMonth is included because the engine caps message history:
                // once the cap is hit, size stops growing, and a repeated filler event
                // (same eventId) would otherwise produce an identical signature and
                // silently skip the save.
                val signature = "$sessionId:${state.currentEventId}:${state.messages.size}:${state.playerState.absoluteMonth}"
                if (signature != lastPersistedSignature) {
                    lastPersistedSignature = signature
                    sessionRepo.saveGameState(sessionId, state)
                }

                // Complete + record the session at most once, even if the game-over state
                // is re-emitted (e.g. by relocalization).
                val endingType = state.endingType
                if (state.gameOver && endingType != null && recordedCompletions.add(sessionId)) {
                    val gameEnding = endingType.toGameEnding()
                    sessionRepo.completeSession(sessionId, gameEnding)
                    // Sync completed session to server (fire-and-forget)
                    val session = sessionRepo.getSession(sessionId)
                    if (session != null && gameApiService != null) {
                        launch {
                            gameApiService.recordCompletedSession(session, gameEnding)
                        }
                    }
                }
            }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Start a brand-new game for [session] created by the new-game flow.
     * Initialises the engine with the character's stats from the era's start year.
     */
    fun startNewGame(sessionId: String) {
        val session = sessionRepo.getSession(sessionId) ?: run {
            startDefaultGame(); return
        }
        activeSessionId = sessionId
        val scenarioStart = ScenarioGraphFactory.forCharacter(session.characterId, session.eraId).initialPlayerState
        achievementsRepo?.startSessionTracking(sessionId, session.characterId, session.eraId, freshRun = true)
        // Fresh playthrough — clear any persistence guards left from a previous run of this session.
        recordedCompletions.remove(sessionId)
        lastPersistedSignature = null
        // Set character info synchronously so the first render already shows the correct character
        _uiState.value = _uiState.value.copy(
            characterName  = session.characterName,
            characterEmoji = session.characterEmoji,
            characterTitle = session.characterTitle,
            gameStartYear  = scenarioStart.year,
            gameStartMonth = scenarioStart.month
        )
        scope.launch {
            _uiState.value = _uiState.value.copy(isTyping = true)
            delay(800)
            val initialState = session.initialStats.toPlayerState(
                year        = scenarioStart.year,
                month       = scenarioStart.month,
                characterId = session.characterId,
                eraId       = session.eraId,
                currency    = scenarioStart.currency
            )
            engine.startGame(initialState, session.characterName)
            delay(300)
            _uiState.value = _uiState.value.copy(isTyping = false)
        }
    }

    /**
     * Continue an existing [session] — restores the saved engine state if available,
     * otherwise restarts from the session's initial stats.
     */
    fun continueGame(sessionId: String) {
        val session = sessionRepo.getSession(sessionId) ?: run {
            startDefaultGame(); return
        }
        activeSessionId = sessionId
        val savedState = sessionRepo.getSavedGameState(sessionId)
        val scenarioStart = ScenarioGraphFactory.forCharacter(session.characterId, session.eraId).initialPlayerState
        achievementsRepo?.startSessionTracking(sessionId, session.characterId, session.eraId)
        // Reset persistence guards on (re)entry; if the save is already a finished game,
        // pre-seed the completion guard so merely loading it doesn't re-record statistics.
        lastPersistedSignature = null
        if (savedState?.gameOver == true) recordedCompletions.add(sessionId)
        else recordedCompletions.remove(sessionId)
        // Set character info synchronously so the first render already shows the correct character
        _uiState.value = _uiState.value.copy(
            characterName  = session.characterName,
            characterEmoji = session.characterEmoji,
            characterTitle = session.characterTitle,
            gameStartYear  = scenarioStart.year,
            gameStartMonth = scenarioStart.month
        )
        scope.launch {
            _uiState.value = _uiState.value.copy(isTyping = true)
            delay(800)
            if (savedState != null) {
                engine.loadState(savedState, session.characterName)
            } else {
                val initialState = session.initialStats.toPlayerState(
                    year        = session.currentGameYear,
                    month       = session.currentGameMonth,
                    characterId = session.characterId,
                    eraId       = session.eraId,
                    currency    = scenarioStart.currency
                )
                engine.startGame(initialState, session.characterName)
            }
            delay(300)
            _uiState.value = _uiState.value.copy(isTyping = false)
        }
    }

    /** Legacy default start — used when no session is selected. Loads Amir (kz_2024). */
    fun startDefaultGame() {
        activeSessionId = null
        scope.launch {
            val defaultStart = ScenarioGraphFactory.forCharacter("era_kz_2024", "kz_2024").initialPlayerState
            val defaultChar = SeedData.predefinedCharacters.find { it.id == "asan" }
            _uiState.value = _uiState.value.copy(
                isTyping       = true,
                characterName  = Strings.sysDefaultCharacterName,
                characterEmoji = defaultChar?.emoji ?: "🛒",
                characterTitle = defaultChar?.profession ?: "",
                gameStartYear  = defaultStart.year,
                gameStartMonth = defaultStart.month
            )
            delay(800)
            engine.startGame(characterName = Strings.sysDefaultCharacterName)
            delay(300)
            _uiState.value = _uiState.value.copy(isTyping = false)
        }
    }

    fun onChoiceSelected(optionId: String) {
        // Guard against double-submission: drop the call if a choice is already being processed.
        if (_uiState.value.isTyping) return
        scope.launch {
            _uiState.value = _uiState.value.copy(isTyping = true, currentOptions = emptyList())
            delay(1400)
            engine.makeChoice(optionId)
            delay(500)
            _uiState.value = _uiState.value.copy(isTyping = false)
        }
    }

    fun toggleStats() {
        _uiState.value = _uiState.value.copy(showStats = !_uiState.value.showStats)
    }

    fun refreshLocalizedData() {
        val session = activeSessionId?.let { sessionRepo.getSession(it) }
        if (session != null) {
            _uiState.value = _uiState.value.copy(
                characterName = session.characterName,
                characterEmoji = session.characterEmoji,
                characterTitle = session.characterTitle
            )
            engine.relocalizeCurrentState(session.characterName)
        } else {
            val defaultChar = SeedData.predefinedCharacters.find { it.id == "asan" }
            _uiState.value = _uiState.value.copy(
                characterName  = Strings.sysDefaultCharacterName,
                characterEmoji = defaultChar?.emoji ?: "🛒",
                characterTitle = defaultChar?.profession ?: ""
            )
            engine.relocalizeCurrentState(Strings.sysDefaultCharacterName)
        }
    }

    /** Restart from the beginning of the same session (same character + era). */
    fun restartGame() {
        engine.reset()
        _uiState.value = _uiState.value.copy(
            gameState      = null,
            currentOptions = emptyList(),
            isTyping       = false
        )
        val sessionId = activeSessionId
        if (sessionId != null) {
            startNewGame(sessionId)
        } else {
            startDefaultGame()
        }
    }

    /** Save the current engine state before navigating away (back to MainMenu). */
    fun saveAndPause() {
        val sessionId = activeSessionId ?: return
        val state = _uiState.value.gameState ?: return
        sessionRepo.saveGameState(sessionId, state)
    }

    fun getEndingTitle(type: EndingType?): String = when (type) {
        EndingType.BANKRUPTCY            -> Strings.endingBankruptcy
        EndingType.PAYCHECK_TO_PAYCHECK  -> Strings.endingPaycheck
        EndingType.FINANCIAL_STABILITY   -> Strings.endingStability
        EndingType.FINANCIAL_FREEDOM     -> Strings.endingFreedom
        EndingType.WEALTH                -> Strings.endingWealth
        null                             -> Strings.endingGameOver
    }
}
