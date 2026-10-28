package kz.fearsom.financiallifev2.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.fearsom.financiallifev2.auth.AuthRepository
import kz.fearsom.financiallifev2.data.AchievementsRepository
import kz.fearsom.financiallifev2.data.CatalogRepository
import kz.fearsom.financiallifev2.data.FeatureFlagRepository
import kz.fearsom.financiallifev2.data.GameSessionRepository
import kz.fearsom.financiallifev2.data.LocaleRepository
import kz.fearsom.financiallifev2.data.OnboardingRepository
import kz.fearsom.financiallifev2.data.PublishedStoriesRepository
import kz.fearsom.financiallifev2.engine.GameEngine
import kz.fearsom.financiallifev2.network.GameApiService
import kz.fearsom.financiallifev2.presentation.AchievementsPresenter
import kz.fearsom.financiallifev2.presentation.AsanPresenter
import kz.fearsom.financiallifev2.presentation.AuthPresenter
import kz.fearsom.financiallifev2.presentation.CharactersPresenter
import kz.fearsom.financiallifev2.presentation.GamePresenter
import kz.fearsom.financiallifev2.presentation.MainMenuPresenter
import kz.fearsom.financiallifev2.presentation.NewGamePresenter
import kz.fearsom.financiallifev2.presentation.SettingsPresenter
import kz.fearsom.financiallifev2.presentation.StatisticsPresenter
import kz.fearsom.financiallifev2.ui.screens.AchievementsScreen
import kz.fearsom.financiallifev2.ui.screens.AsanAdvisorScreen
import kz.fearsom.financiallifev2.ui.screens.ChatScreen
import kz.fearsom.financiallifev2.ui.screens.EraSelectionScreen
import kz.fearsom.financiallifev2.ui.screens.LoginScreen
import kz.fearsom.financiallifev2.ui.screens.MainMenuScreen
import kz.fearsom.financiallifev2.ui.screens.OnboardingScreen
import kz.fearsom.financiallifev2.ui.screens.SettingsScreen
import kz.fearsom.financiallifev2.ui.screens.SplashScreen
import kz.fearsom.financiallifev2.ui.screens.StatisticsScreen
import kz.fearsom.financiallifev2.ui.screens.character.CharacterDetailScreen
import kz.fearsom.financiallifev2.ui.screens.character.CharacterSelectionScreen
import kz.fearsom.financiallifev2.ui.screens.character.CharactersScreen
import kz.fearsom.financiallifev2.ui.theme.LocalAppColors
import org.koin.compose.koinInject

// ── Screen definitions ────────────────────────────────────────────────────────

sealed interface AppScreen {
    object Splash : AppScreen
    object Onboarding : AppScreen
    object Login : AppScreen
    object MainMenu : AppScreen
    object EraSelection : AppScreen
    data class CharacterSelection(val eraId: String) : AppScreen
    object Characters : AppScreen
    data class CharacterDetail(val characterId: String) : AppScreen
    object Achievements : AppScreen
    object Statistics : AppScreen
    object Settings : AppScreen
    data class Game(val sessionId: String) : AppScreen
    object AsanAdvisor : AppScreen
}

// Navigation depth — drives slide direction.
private fun AppScreen.depth(): Int = when (this) {
    AppScreen.Splash -> -1
    AppScreen.Onboarding -> 0
    AppScreen.MainMenu -> 1
    AppScreen.Login -> 2
    AppScreen.EraSelection -> 2
    is AppScreen.CharacterSelection -> 3
    AppScreen.Characters -> 2
    is AppScreen.CharacterDetail -> 3
    AppScreen.Achievements -> 2
    AppScreen.Statistics -> 2
    AppScreen.Settings -> 2
    is AppScreen.Game -> 4
    AppScreen.AsanAdvisor -> 5
}

// ── Navigation host ───────────────────────────────────────────────────────────

@Composable
fun AppNavigation() {
    val colors = LocalAppColors.current
    val authRepository: AuthRepository = koinInject()
    val gameEngine: GameEngine = koinInject()
    val sessionRepo: GameSessionRepository = koinInject()
    val gameApiService: GameApiService = koinInject()
    val catalogRepo: CatalogRepository = koinInject()
    val localeRepo: LocaleRepository = koinInject()
    val featureFlags: FeatureFlagRepository = koinInject()
    val achievementsRepo: AchievementsRepository = koinInject()
    val publishedStoriesRepo: PublishedStoriesRepository = koinInject()
    val onboardingRepo: OnboardingRepository = koinInject()

    val scope = rememberCoroutineScope()

    // Shared presenters — lifetime bound to this Composable's composition
    val authPresenter = remember { AuthPresenter(authRepository, scope) }
    val gamePresenter = remember { GamePresenter(gameEngine, sessionRepo, scope, gameApiService, achievementsRepo) }
    val achievementsPresenter = remember { AchievementsPresenter(achievementsRepo, scope) }
    val mainMenuPresenter = remember { MainMenuPresenter(sessionRepo, scope) }
    val newGamePresenter = remember { NewGamePresenter(sessionRepo, catalogRepo, scope) }
    val charsPresenter = remember { CharactersPresenter(sessionRepo, catalogRepo, scope) }
    val statsPresenter = remember { StatisticsPresenter(sessionRepo, scope, gameApiService) }
    val settingsPresenter = remember { SettingsPresenter(localeRepo, featureFlags, scope) }
    val asanPresenter = remember { AsanPresenter(scope) }

    val authUiState by authPresenter.uiState.collectAsStateWithLifecycle()
    val gameUiState by gamePresenter.uiState.collectAsStateWithLifecycle()
    val mainMenuUiState by mainMenuPresenter.uiState.collectAsStateWithLifecycle()
    val newGameUiState by newGamePresenter.uiState.collectAsStateWithLifecycle()
    val charsUiState by charsPresenter.uiState.collectAsStateWithLifecycle()
    val statsUiState by statsPresenter.uiState.collectAsStateWithLifecycle()
    val achievementsUiState by achievementsPresenter.uiState.collectAsStateWithLifecycle()
    val settingsUiState by settingsPresenter.uiState.collectAsStateWithLifecycle()
    val asanUiState by asanPresenter.uiState.collectAsStateWithLifecycle()

    // ── Back stack ────────────────────────────────────────────────────────────
    var backStack by remember { mutableStateOf(listOf<AppScreen>(AppScreen.Splash)) }
    var navForward by remember { mutableStateOf(true) }

    fun navigate(screen: AppScreen) {
        navForward = screen.depth() >= (backStack.lastOrNull()?.depth() ?: 0)
        backStack = backStack + screen
    }

    fun goBack() {
        if (backStack.size > 1) {
            navForward = false
            backStack = backStack.dropLast(1)
        }
    }

    // ── Cold start ────────────────────────────────────────────────────────────
    LaunchedEffect(Unit) { authPresenter.restoreSession() }
    // Warm the published-story cache so admin-published stories overlay gameplay
    // (the GameEngine resolver reads it synchronously at startGame).
    LaunchedEffect(Unit) { publishedStoriesRepo.refresh() }

    // ── Splash exit — fires once session restore completes ────────────────────
    LaunchedEffect(authUiState.isRestoringSession) {
        if (!authUiState.isRestoringSession && backStack.lastOrNull() == AppScreen.Splash) {
            navForward = true
            backStack = if (onboardingRepo.isCompleted()) {
                listOf(AppScreen.MainMenu)
            } else {
                listOf(AppScreen.Onboarding)
            }
        }
    }

    LaunchedEffect(settingsUiState.currentLocale) {
        mainMenuPresenter.refresh()
        newGamePresenter.refreshLocalizedData()
        charsPresenter.refreshLocalizedData()
        statsPresenter.refreshLocal()
        gamePresenter.refreshLocalizedData()
    }

    // ── Auth state — login returns to main; logout keeps guest mode available ─
    LaunchedEffect(authUiState.authState.isLoggedIn) {
        if (authUiState.authState.isLoggedIn) {
            if (backStack.lastOrNull() == AppScreen.Login) {
                // A successful login always means the user is past onboarding — mark it
                // complete so the splash exit never re-routes to Onboarding on cold start.
                onboardingRepo.markCompleted()
                navForward = true
                backStack = listOf(AppScreen.MainMenu)
            }
        }
    }

    val currentScreen = backStack.lastOrNull() ?: AppScreen.MainMenu

    // Re-pull the admin catalog when entering a catalog-driven screen, so changes
    // made in the admin panel show up without an app relaunch.
    LaunchedEffect(currentScreen, authUiState.authState.isLoggedIn) {
        when (currentScreen) {
            AppScreen.EraSelection -> newGamePresenter.refresh()
            AppScreen.Characters -> charsPresenter.refresh()
            AppScreen.Statistics -> if (authUiState.authState.isLoggedIn) {
                statsPresenter.refresh()
            } else {
                statsPresenter.refreshLocal()
            }

            AppScreen.Achievements -> if (authUiState.authState.isLoggedIn) {
                achievementsPresenter.refresh()
            }

            else -> {}
        }
    }

    SystemBackHandler(enabled = backStack.size > 1, onBack = ::goBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.backgroundDeep)
            .padding(bottom = 32.dp)
    ) {
        key(settingsUiState.currentLocale) {
            AnimatedContent(
                targetState = currentScreen,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val dir = if (navForward) 1 else -1
                    slideInHorizontally(initialOffsetX = { it * dir }, animationSpec = tween(320)) +
                            fadeIn(tween(320)) togetherWith
                            slideOutHorizontally(
                                targetOffsetX = { -it * dir },
                                animationSpec = tween(320)
                            ) +
                            fadeOut(tween(200))
                },
                label = "appNav"
            ) { screen ->
                when (screen) {

                    // ── Splash ────────────────────────────────────────────────────
                    AppScreen.Splash -> SplashScreen()

                    // ── Onboarding (first launch) ─────────────────────────────────
                    AppScreen.Onboarding -> OnboardingScreen(
                        onFinish = {
                            onboardingRepo.markCompleted()
                            navForward = true
                            backStack = listOf(AppScreen.MainMenu)
                        },
                        onLoginClick = {
                            // Do NOT mark completed here — the user hasn't logged in yet.
                            // markCompleted() is called only in onFinish (after finishing the
                            // flow) so that a user who dismisses login without signing in
                            // still sees onboarding on next cold-start.
                            navForward = true
                            backStack = listOf(AppScreen.MainMenu, AppScreen.Login)
                        }
                    )

                    // ── Login ─────────────────────────────────────────────────────
                    AppScreen.Login -> LoginScreen(
                        isLoading = authUiState.isLoading,
                        error = authUiState.error,
                        isRegisterMode = authUiState.isRegisterMode,
                        onLogin = authPresenter::login,
                        onRegister = authPresenter::register,
                        onToggleMode = authPresenter::toggleMode,
                        onContinueAsGuest = {
                            navForward = false
                            backStack = listOf(AppScreen.MainMenu)
                        },
                        onBack = ::goBack,
                    )

                    // ── Main Menu ─────────────────────────────────────────────────
                    AppScreen.MainMenu -> MainMenuScreen(
                        uiState = mainMenuUiState,
                        isAuthenticated = authUiState.authState.isLoggedIn,
                        username = authUiState.authState.username,
                        onContinue = {
                            val activeId = mainMenuUiState.activeSession?.id
                            if (activeId != null) {
                                gamePresenter.continueGame(activeId)
                                navigate(AppScreen.Game(activeId))
                            }
                        },
                        onNewGame = { navigate(AppScreen.EraSelection) },
                        onCharacters = { navigate(AppScreen.Characters) },
                        onAchievements = { navigate(AppScreen.Achievements) },
                        achievementsUnlocked = achievementsUiState.unlockedCount,
                        achievementsTotal = achievementsUiState.totalCount,
                        onStatistics = { navigate(AppScreen.Statistics) },
                        onSettings = { navigate(AppScreen.Settings) },
                        onLogin = { navigate(AppScreen.Login) },
                        onLogout = {
                            gamePresenter.saveAndPause()
                            authPresenter.logout()
                        }
                    )

                    // ── Era Selection ─────────────────────────────────────────────
                    AppScreen.EraSelection -> EraSelectionScreen(
                        uiState = newGameUiState,
                        isAuthenticated = authUiState.authState.isLoggedIn,
                        onEraSelected = { eraId ->
                            newGamePresenter.selectEra(eraId)
                            navigate(AppScreen.CharacterSelection(eraId))
                        },
                        onLoginRequired = { navigate(AppScreen.Login) },
                        onBack = ::goBack
                    )

                    // ── Character Selection ───────────────────────────────────────
                    is AppScreen.CharacterSelection -> CharacterSelectionScreen(
                        uiState = newGameUiState,
                        isAuthenticated = authUiState.authState.isLoggedIn,
                        onSelectPredefined = { charId ->
                            val sessionId = newGamePresenter.startWithPredefined(charId)
                            if (sessionId != null) {
                                gamePresenter.startNewGame(sessionId)
                                navForward = true
                                backStack = listOf(AppScreen.MainMenu, AppScreen.Game(sessionId))
                            }
                        },
                        onSelectBundle = { bundleId ->
                            val sessionId = newGamePresenter.startWithBundle(bundleId)
                            if (sessionId != null) {
                                gamePresenter.startNewGame(sessionId)
                                navForward = true
                                backStack = listOf(AppScreen.MainMenu, AppScreen.Game(sessionId))
                            }
                        },
                        onLoginRequired = { navigate(AppScreen.Login) },
                        onBack = ::goBack
                    )

                    // ── Characters ────────────────────────────────────────────────
                    AppScreen.Characters -> CharactersScreen(
                        uiState = charsUiState,
                        onCharacterClick = { charId -> navigate(AppScreen.CharacterDetail(charId)) },
                        onBack = ::goBack
                    )

                    // ── Character Detail ──────────────────────────────────────────
                    is AppScreen.CharacterDetail -> CharacterDetailScreen(
                        characterId = screen.characterId,
                        isAuthenticated = authUiState.authState.isLoggedIn,
                        onBack = ::goBack,
                        onLoginRequired = { navigate(AppScreen.Login) },
                        onStartGame = { charId ->
                            val sessionId = newGamePresenter.quickStartWithCharacter(charId)
                            if (sessionId != null) {
                                gamePresenter.startNewGame(sessionId)
                                navForward = true
                                backStack = listOf(AppScreen.MainMenu, AppScreen.Game(sessionId))
                            }
                        }
                    )

                    // ── Achievements ──────────────────────────────────────────────
                    AppScreen.Achievements -> AchievementsScreen(
                        uiState = achievementsUiState,
                        onSelect = achievementsPresenter::select,
                        onCloseDetail = achievementsPresenter::closeDetail,
                        onVote = achievementsPresenter::vote,
                        onBack = ::goBack
                    )

                    // ── Statistics ────────────────────────────────────────────────
                    AppScreen.Statistics -> StatisticsScreen(
                        uiState = statsUiState,
                        onBack = ::goBack
                    )

                    // ── Settings ─────────────────────────────────────────────────
                    AppScreen.Settings -> SettingsScreen(
                        uiState = settingsUiState,
                        onLocaleSelected = settingsPresenter::selectLocale,
                        onTypingAnimToggle = settingsPresenter::setTypingAnimationEnabled,
                        onTypingPaceChange = settingsPresenter::setTypingAnimationPace,
                        onBack = ::goBack
                    )

                    // ── Game (Chat) ───────────────────────────────────────────────
                    is AppScreen.Game -> ChatScreen(
                        uiState = gameUiState,
                        typingAnimationEnabled = settingsUiState.typingAnimationEnabled,
                        typingAnimationPace = settingsUiState.typingAnimationPace,
                        onChoiceSelected = gamePresenter::onChoiceSelected,
                        onToggleStats = gamePresenter::toggleStats,
                        onRestart = gamePresenter::restartGame,
                        onNavigateToMenu = {
                            gamePresenter.saveAndPause()
                            navForward = false
                            backStack = listOf(AppScreen.MainMenu)
                        },
                        onOpenAsan = {
                            asanPresenter.openConversation()
                            navigate(AppScreen.AsanAdvisor)
                        }
                    )

                    // ── Asan AI advisor (UI stub) ─────────────────────────────────
                    AppScreen.AsanAdvisor -> AsanAdvisorScreen(
                        uiState = asanUiState,
                        onSend = asanPresenter::sendMessage,
                        onToggleVoice = asanPresenter::toggleRecording,
                        onCancelVoice = asanPresenter::cancelRecording,
                        onBack = ::goBack
                    )
                }
            }
        }
    }
}
