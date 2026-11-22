package com.lorenzo.mangadownloader

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.compositionContext
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lorenzo.mangadownloader.app.AppSystemEffects
import com.lorenzo.mangadownloader.app.AppTab
import com.lorenzo.mangadownloader.app.MangaUiState
import com.lorenzo.mangadownloader.app.MangaViewModel
import com.lorenzo.mangadownloader.app.Screen
import com.lorenzo.mangadownloader.app.activeAdultKeys
import com.lorenzo.mangadownloader.app.canHandleBack
import com.lorenzo.mangadownloader.app.currentScreen
import com.lorenzo.mangadownloader.app.saveableScreenKey
import com.lorenzo.mangadownloader.app.tabPageIndex
import com.lorenzo.mangadownloader.app.visibleTabs
import com.lorenzo.mangadownloader.app.withoutRegisteredAdult
import com.lorenzo.mangadownloader.data.anilist.AniListAuth
import com.lorenzo.mangadownloader.data.model.MangaDetails
import com.lorenzo.mangadownloader.data.store.unseenCount
import com.lorenzo.mangadownloader.domain.series.LibraryMatching
import com.lorenzo.mangadownloader.domain.series.favoriteReadingStatesByKey
import com.lorenzo.mangadownloader.ui.app.AppDialogsHost
import com.lorenzo.mangadownloader.ui.app.AppScreenHost
import com.lorenzo.mangadownloader.ui.app.PendingCrashReportEffect
import com.lorenzo.mangadownloader.ui.app.rememberBackupActions
import com.lorenzo.mangadownloader.ui.app.rememberNotificationPermissions
import com.lorenzo.mangadownloader.ui.app.rememberStartDownload
import com.lorenzo.mangadownloader.ui.components.AppBottomBar
import com.lorenzo.mangadownloader.ui.components.AppTopBar
import com.lorenzo.mangadownloader.ui.components.LocalCardDensity
import com.lorenzo.mangadownloader.ui.home.HomeScreen
import com.lorenzo.mangadownloader.ui.library.rememberDownloadWorkUiState
import com.lorenzo.mangadownloader.ui.reader.SpreadPageMode
import com.lorenzo.mangadownloader.ui.theme.AnimationLevel
import com.lorenzo.mangadownloader.ui.theme.AppMotion
import com.lorenzo.mangadownloader.ui.theme.LocalAnimationLevel
import com.lorenzo.mangadownloader.ui.theme.MangaDownloaderTheme
import com.lorenzo.mangadownloader.ui.tutorial.TutorialAnchor
import com.lorenzo.mangadownloader.ui.tutorial.TutorialOverlay
import com.lorenzo.mangadownloader.ui.widget.ReadingWidget
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull


class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Recomposer della finestra con la nostra scala di durata al posto di quella di
        // sistema (che [AppMotion] comunque rispetta): è ciò che rende "Nessuna" istantaneo
        // per tutte le animazioni Compose, dialog e popup compresi.
        AppMotion.systemScale = AppMotion.readSystemScale(this)
        AppMotion.level = ViewModelProvider(this)[MangaViewModel::class.java]
            .state.value.settings.animationLevel
        window.decorView.compositionContext = window.decorView
            .createLifecycleAwareWindowRecomposer(AppMotion.durationScale, lifecycle)
        // Dopo un process death Android ricrea l'activity con l'intent originale: un redirect
        // già consumato tornerebbe qui e, con lo `state` ormai usato, mostrerebbe un avviso
        // fuori luogo. Un redirect nuovo in quel caso arriva da onNewIntent.
        val relaunched = savedInstanceState != null ||
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (!relaunched) {
            handleAniListRedirect(intent)
        }
        handleNotificationIntent(intent)

        setContent {
            MangaDownloaderApp()
        }
    }

    override fun onResume() {
        super.onResume()
        // "Rimuovi animazioni" può essere cambiata dalle impostazioni di Android mentre
        // l'app è in background.
        AppMotion.systemScale = AppMotion.readSystemScale(this)
    }

    override fun onStop() {
        super.onStop()
        // L'app va in background: è lì che il widget torna visibile, quindi è il momento di
        // ridisegnarlo con le letture appena fatte.
        ViewModelProvider(this)[MangaViewModel::class.java].onAppBackgrounded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // launchMode=singleTask: i nuovi intent (redirect OAuth, tap su notifica) arrivano qui.
        setIntent(intent)
        handleAniListRedirect(intent)
        handleNotificationIntent(intent)
    }

    /** Consuma il redirect OAuth di AniList (`mangapp://anilist-auth#access_token=…`). */
    private fun handleAniListRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != AniListAuth.REDIRECT_SCHEME || uri.host != AniListAuth.REDIRECT_HOST) {
            return
        }
        intent.data = null
        ViewModelProvider(this)[MangaViewModel::class.java]
            .onAniListAuthRedirect(uri.fragment ?: uri.encodedQuery)
    }

    /**
     * Consuma gli extras del tap su una notifica "nuovo capitolo": apre direttamente il
     * dettaglio del manga (notifica singola) o il feed Aggiornamenti (riepilogo), invece
     * della tab generica. Gli extras vengono rimossi così non riscattano alla ricomposizione.
     */
    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return
        val viewModel = ViewModelProvider(this)[MangaViewModel::class.java]
        if (intent.getBooleanExtra(ReadingWidget.EXTRA_RESUME_READING, false)) {
            intent.removeExtra(ReadingWidget.EXTRA_RESUME_READING)
            viewModel.resumeLatestReading()
            return
        }
        if (intent.getBooleanExtra(FavoriteUpdateNotifier.EXTRA_OPEN_UPDATES_FEED, false)) {
            intent.removeExtra(FavoriteUpdateNotifier.EXTRA_OPEN_UPDATES_FEED)
            viewModel.openUpdatesFromNotification()
            return
        }
        val mangaUrl = intent.getStringExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_URL) ?: return
        intent.removeExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_URL)
        viewModel.openMangaFromNotification(
            sourceId = intent.getStringExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_SOURCE_ID).orEmpty(),
            title = intent.getStringExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_TITLE).orEmpty(),
            mangaUrl = mangaUrl,
            coverUrl = intent.getStringExtra(FavoriteUpdateNotifier.EXTRA_OPEN_MANGA_COVER),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MangaDownloaderApp(viewModel: MangaViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MangaDownloaderTheme(
        themeMode = state.settings.themeMode,
        useDynamicColor = state.settings.useDynamicColor,
        animationLevel = state.settings.animationLevel,
    ) {
        val animationLevel = state.settings.animationLevel
        SideEffect { AppMotion.level = animationLevel }
        // Densità globale delle card (impostazione stile tema): fornita qui alla radice,
        // le card condivise la leggono via LocalCardDensity senza parametri da infilare.
        CompositionLocalProvider(
            LocalCardDensity provides state.settings.cardDensity,
            LocalAnimationLevel provides animationLevel,
            // Il ripple è un RippleDrawable di sistema, fuori dalla scala di durata: con
            // "Nessuna" va spento a parte (null = niente ripple nei componenti M3).
            LocalRippleConfiguration provides if (animationLevel == AnimationLevel.NONE) {
                null
            } else {
                LocalRippleConfiguration.current
            },
        ) {
            MangaDownloaderAppContent(state = rememberAdultFilteredState(state), viewModel = viewModel)
        }
    }
}

/**
 * Lo stato come lo vedono le schermate: col filtro adulti attivo, senza le serie del registro
 * (vedi AdultSeriesVisibility.kt). Ogni lista si ricalcola solo quando cambia lei o il
 * registro, così le `remember` a valle non ripartono a ogni emissione dello stato.
 */
@Composable
private fun rememberAdultFilteredState(state: MangaUiState): MangaUiState {
    val keys = state.activeAdultKeys()
    val favorites = remember(state.favorites, keys) { state.favorites.withoutRegisteredAdult(keys) }
    val library = remember(state.library, keys) { state.library.withoutRegisteredAdult(keys) }
    val memory = remember(state.readingMemory, keys) { state.readingMemory.withoutRegisteredAdult(keys) }
    val updates = remember(state.favoriteUpdates, keys) { state.favoriteUpdates.withoutRegisteredAdult(keys) }
    if (keys.isEmpty()) return state
    return state.copy(
        favorites = favorites,
        library = library,
        readingMemory = memory,
        favoriteUpdates = updates,
    )
}

/**
 * Radice dell'app: tiene lo stato che attraversa le schermate (pager delle tab, schermo intero
 * del reader, modalità modifica della Home) e monta barre, schermata corrente e dialog. I
 * pezzi stanno in `ui/app/`: [AppScreenHost] per le schermate, [AppDialogsHost] per i dialog,
 * più permessi notifiche, avvio dei download e backup.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MangaDownloaderAppContent(
    state: MangaUiState,
    viewModel: MangaViewModel,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val appContext = remember(context) { context.applicationContext }
    // Etichette di lettura automatiche dei preferiti, derivate dalla libreria scaricata.
    val favoriteReadingStates = remember(state.favorites, state.library) {
        favoriteReadingStatesByKey(state.favorites, state.library)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val downloadWorkUiState = rememberDownloadWorkUiState(context, viewModel, snackbarHostState)
    val scope = rememberCoroutineScope()
    // Conserva lo stato salvabile (posizione di scroll in testa) di ogni schermata quando
    // viene smontata navigando altrove, e lo ripristina al ritorno. Vive qui, alla radice
    // dei contenuti, così sopravvive a tutte le transizioni tra schermate.
    val screenStateHolder = rememberSaveableStateHolder()
    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage ?: return@LaunchedEffect
        // Dove il retry è naturale (fetch dei dettagli fallito) la snackbar offre "Riprova",
        // che rilancia lo stesso manga senza dover ripetere la ricerca.
        val retryResult = state.errorRetrySearchResult
        scope.launch {
            val result = snackbarHostState.showAutoDismissSnackbar(
                message = message,
                actionLabel = if (retryResult != null) "Riprova" else null,
            )
            viewModel.dismissError()
            if (result == SnackbarResult.ActionPerformed && retryResult != null) {
                viewModel.selectManga(retryResult)
            }
        }
    }

    val notifications = rememberNotificationPermissions(
        appContext = appContext,
        snackbarHostState = snackbarHostState,
        onFavoriteNotificationsGranted = { viewModel.setFavoriteNotificationsEnabled(true) },
    )
    // Il ritorno in primo piano è tipicamente il rientro dal browser dell'OAuth di AniList.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.syncAniListAccountState()
    }
    val backupActions = rememberBackupActions(viewModel)

    LaunchedEffect(Unit) {
        // Aperti dal tap su una notifica di download: porta direttamente alla Libreria.
        // L'extra viene consumato così non riscatta a ogni ricomposizione/rotazione.
        val intent = activity?.intent
        if (intent?.getStringExtra(DownloadWorker.EXTRA_OPEN_TAB) == DownloadWorker.OPEN_TAB_LIBRARY) {
            intent.removeExtra(DownloadWorker.EXTRA_OPEN_TAB)
            viewModel.clearSelection()
            viewModel.selectTab(AppTab.LIBRARY)
        }
        viewModel.checkForAppUpdate()
    }

    val onStartDownload = rememberStartDownload(
        appContext = appContext,
        snackbarHostState = snackbarHostState,
        notifications = notifications,
        onQueued = viewModel::onDownloadQueued,
        // Chiude il dettaglio e porta dove si monitora la coda appena creata.
        onOpenLibrary = {
            viewModel.clearSelection()
            viewModel.selectTab(AppTab.LIBRARY)
        },
    )

    val visibleTabs = state.visibleTabs()
    val pagerState = rememberPagerState(
        initialPage = state.tabPageIndex(state.currentTab),
        // Il set di tab è fisso (Home·Cerca·Preferiti·Libreria): il conteggio è costante.
        pageCount = { visibleTabs.size },
    )
    val showPager = state.currentScreen() == Screen.Tabs
    // derivedStateOf: lo stato di scorrimento del pager cambia a ogni gesto, anche verticale;
    // letto qui direttamente ricomponeva tutta la radice due volte per swipe. Così la radice si
    // ricompone solo quando cambia davvero la tab visibile.
    val currentTab = state.currentTab
    val visiblePagerTab by remember(pagerState, visibleTabs, showPager, currentTab) {
        derivedStateOf {
            when {
                !showPager -> currentTab
                pagerState.isScrollInProgress -> visibleTabs.getOrElse(pagerState.targetPage) { currentTab }
                else -> visibleTabs.getOrElse(pagerState.currentPage) { currentTab }
            }
        }
    }
    val canHandleBack = state.canHandleBack()

    // Quando si arriva sulla tab Preferiti (dove è visibile il badge "Aggiornamenti"), rilegge
    // il feed così il conteggio riflette gli eventi scritti dal worker mentre l'app era aperta.
    LaunchedEffect(visiblePagerTab) {
        when (visiblePagerTab) {
            AppTab.FAVORITES -> viewModel.refreshUpdatesFeed()
            AppTab.HOME -> {
                // La Home mostra ripresa lettura e novità: entrambe vanno rinfrescate quando
                // diventa visibile (la libreria si ri-scansiona solo entrando in Libreria).
                viewModel.refreshLibrary()
                viewModel.refreshUpdatesFeed()
            }
            else -> {}
        }
    }

    val privacyDimAlpha = readerPrivacyDimAlpha(
        enabled = state.readerChapter != null && state.settings.privacyBrightnessEnabled,
        brightness = state.settings.readerBrightness,
    )
    // Keyed sull'apertura del reader (null → non-null), non sulla singola relativePath:
    // così il fullscreen si azzera solo quando apri il reader da fuori, mentre resta
    // invariato passando a capitolo successivo/precedente.
    var isReaderFullscreen by remember(state.readerChapter != null) { mutableStateOf(false) }

    // Modalità modifica della Home: vive qui (non in HomeScreen) perché la top bar la comanda.
    var homeEditMode by rememberSaveable { mutableStateOf(false) }

    // Nascondere la tab Home chiude la modalità modifica: al ritorno la Home riparte normale.
    LaunchedEffect(state.settings.showHomeTab) {
        if (!state.settings.showHomeTab) homeEditMode = false
    }

    // Vero schermo intero: quando il reader è in fullscreen nascondiamo anche le barre
    // di sistema (status + navigation), così la pagina occupa davvero tutto lo schermo.
    // Si esce con un tap (toggle) o con lo swipe dal bordo, che le ripristina da solo.
    // Durante la lettura i tocchi sono rari (pagine lunghe, tavole dense): senza questo
    // flag il timeout di sistema spegne lo schermo a metà pagina. Attivo solo a reader
    // aperto (e con l'impostazione dedicata accesa); onDispose lo ripulisce sempre.
    // Con le pagine doppie ruotate il telefono si gira per leggerle: se ruotasse anche
    // l'app, l'immagine tornerebbe sdraiata e la modalità non servirebbe a niente.
    val readerLocksPortrait = state.readerChapter != null &&
        state.readerSpreadPageMode == SpreadPageMode.ROTATE
    AppSystemEffects(
        activity = activity,
        allowLandscapeRotation = state.settings.allowLandscapeRotation && !readerLocksPortrait,
        biometricRequest = state.biometricPromptRequest,
        readerOpen = state.readerChapter != null,
        readerFullscreen = isReaderFullscreen,
        keepReaderScreenOn = state.settings.keepScreenOnEnabled,
        onBiometricSucceeded = viewModel.parental::onBiometricSucceeded,
        onUsePinInstead = viewModel.parental::usePinInsteadOfBiometric,
        onBiometricCancelled = viewModel.parental::cancelBiometric,
    )

    // Porta l'utente alla ricerca dagli stati vuoti (es. Libreria/Preferiti vuoti):
    // trasforma il vicolo cieco in un passo successivo chiaro. Rispetta il lock parentale.
    val goToSearchTab: () -> Unit = {
        viewModel.selectTab(AppTab.SEARCH)
        val requiresSearchUnlock = state.settings.parentalControlEnabled &&
            state.currentTab != AppTab.SEARCH
        if (!requiresSearchUnlock) {
            scope.launch {
                pagerState.animateScrollToPage(state.tabPageIndex(AppTab.SEARCH))
            }
        }
    }

    BackHandler(enabled = canHandleBack) {
        if (isReaderFullscreen) {
            isReaderFullscreen = false
        } else {
            viewModel.handleBack()
        }
    }

    LaunchedEffect(
        state.currentTab,
        state.pendingSearchAccessReturnTab,
        showPager,
    ) {
        if (
            showPager &&
            !pagerState.isScrollInProgress &&
            state.pendingSearchAccessReturnTab == null &&
            pagerState.currentPage != state.tabPageIndex(state.currentTab)
        ) {
            pagerState.animateScrollToPage(state.tabPageIndex(state.currentTab))
        }
    }

    // Pagina e scorrimento letti in snapshotFlow, non come chiavi dell'effetto: come chiavi
    // ricomponevano la radice a ogni inizio e fine di scorrimento.
    val latestVisibleTabs by rememberUpdatedState(visibleTabs)
    val latestCurrentTab by rememberUpdatedState(state.currentTab)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                if (!scrolling) {
                    val newTab = latestVisibleTabs.getOrElse(page) { latestCurrentTab }
                    if (latestCurrentTab != newTab) {
                        viewModel.selectTab(newTab)
                    }
                }
            }
    }

    TutorialOverlay(
        state = state,
        onFallbackCompleted = {
            viewModel.tutorial.onFallbackCompleted()
            // A tour appena concluso il valore delle notifiche è chiaro: è il momento
            // giusto per chiedere il permesso, con la spiegazione del perché.
            notifications.askWithRationale()
        },
        onAdvancePhase = viewModel.tutorial::advancePhase,
        onTargetTap = { anchor ->
            when (anchor) {
                TutorialAnchor.SEARCH_RESULT_FIRST -> {
                    state.results.firstOrNull()?.let(viewModel::selectManga)
                }
                TutorialAnchor.DETAIL_FAVORITE -> {
                    viewModel.toggleFavoriteSelectedManga()
                }
                TutorialAnchor.FAVORITES_TAB -> {
                    viewModel.selectTab(AppTab.FAVORITES)
                    scope.launch {
                        pagerState.animateScrollToPage(state.tabPageIndex(AppTab.FAVORITES))
                    }
                }
                TutorialAnchor.LIBRARY_TAB -> {
                    viewModel.selectTab(AppTab.LIBRARY)
                    scope.launch {
                        pagerState.animateScrollToPage(state.tabPageIndex(AppTab.LIBRARY))
                    }
                }
                TutorialAnchor.LIBRARY_SERIES_FIRST -> {
                    LibraryMatching.tutorialSampleSeries(state.tutorialState.sample, state.library)
                        ?.let(viewModel::selectDownloadedSeries)
                }
                TutorialAnchor.DOWNLOADED_CHAPTER_FIRST -> {
                    state.selectedDownloadedSeries
                        ?.chapters
                        ?.firstOrNull()
                        ?.let(viewModel::openReader)
                }
                TutorialAnchor.READER_FULLSCREEN -> {
                    viewModel.closeReader()
                }
                TutorialAnchor.SEARCH_TAB,
                TutorialAnchor.SEARCH_BAR,
                TutorialAnchor.SETTINGS,
                TutorialAnchor.DETAIL_DOWNLOAD -> Unit
            }
        },
        onFinish = { keepSample ->
            viewModel.tutorial.onFinish(keepSample)
            notifications.askWithRationale()
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    if (!(state.readerChapter != null && isReaderFullscreen)) {
                        AppTopBar(
                            state = state,
                            visibleTab = visiblePagerTab,
                            onBack = viewModel::handleBack,
                            onToggleFavorite = viewModel::toggleFavoriteSelectedManga,
                            onToggleFavoriteSeries = viewModel::toggleFavoriteSelectedSeries,
                            onOpenSettings = viewModel::openSettings,
                            onReaderBrightnessChange = viewModel::previewReaderBrightness,
                            onReaderBrightnessChangeFinished = viewModel::commitReaderBrightness,
                            onSelectReadingMode = viewModel::setReaderReadingMode,
                            onSelectSpreadPageMode = viewModel::setReaderSpreadPageMode,
                            // Leggendo in streaming si può tenere il capitolo: le pagine sono già
                            // state scaricate per mostrarlo, quindi salvarlo è a un tocco invece che
                            // a un giro completo (esci → dettaglio → selettore intervallo).
                            onSaveStreamingChapter = state.readerChapter?.streamingChapter?.let { streaming ->
                                {
                                    onStartDownload(
                                        MangaDetails(
                                            sourceId = streaming.sourceId,
                                            title = streaming.mangaTitle,
                                            // La copertina correda la serie salvata: si prende dal
                                            // dettaglio da cui siamo entrati, e solo se è la stessa serie.
                                            coverUrl = state.selected
                                                ?.takeIf { it.mangaUrl == streaming.mangaUrl }
                                                ?.coverUrl,
                                            mangaUrl = streaming.mangaUrl,
                                            chapters = streaming.chapters,
                                        ),
                                        streaming.chapter,
                                        streaming.chapter,
                                    )
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Capitolo aggiunto ai download")
                                    }
                                }
                            },
                            unseenUpdatesCount = unseenCount(state.favoriteUpdates),
                            onOpenUpdates = viewModel::openUpdates,
                            onMarkAllUpdatesSeen = viewModel::markAllUpdatesSeen,
                            homeEditMode = homeEditMode,
                            onToggleHomeEdit = { homeEditMode = !homeEditMode },
                        )
                    }
                },
                bottomBar = {
                    if (showPager) {
                        AppBottomBar(
                            currentTab = visiblePagerTab,
                            favoritesBadgeCount = unseenCount(state.favoriteUpdates),
                            showHomeTab = state.settings.showHomeTab,
                            onSelect = { tab ->
                                viewModel.selectTab(tab)
                                val requiresSearchUnlock = tab == AppTab.SEARCH &&
                                    state.settings.parentalControlEnabled &&
                                    state.currentTab != AppTab.SEARCH
                                if (!requiresSearchUnlock) {
                                    scope.launch {
                                        pagerState.animateScrollToPage(state.tabPageIndex(tab))
                                    }
                                }
                            },
                        )
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
            ) { innerPadding ->
                // Avvolge la schermata corrente: ne preserva/ripristina lo stato salvabile (scroll)
                // tra una navigazione e l'altra. La chiave distingue le schermate (vedi saveableScreenKey).
                screenStateHolder.SaveableStateProvider(state.saveableScreenKey()) {
                    AppScreenHost(
                        state = state,
                        viewModel = viewModel,
                        padding = innerPadding,
                        snackbarHostState = snackbarHostState,
                        pagerState = pagerState,
                        showPager = showPager,
                        readerFullscreen = isReaderFullscreen,
                        onToggleReaderFullscreen = { isReaderFullscreen = !isReaderFullscreen },
                        homeEditMode = homeEditMode,
                        favoriteReadingStates = favoriteReadingStates,
                        downloadStatuses = downloadWorkUiState.statuses,
                        notifications = notifications,
                        backupActions = backupActions,
                        onStartDownload = onStartDownload,
                        goToSearchTab = goToSearchTab,
                    )
                }
            }
            if (privacyDimAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = privacyDimAlpha)),
                )
            }
        }
    }

    PendingCrashReportEffect(appContext)
    AppDialogsHost(state = state, viewModel = viewModel, snackbarHostState = snackbarHostState)
}

private fun readerPrivacyDimAlpha(enabled: Boolean, brightness: Float): Float {
    if (!enabled) return 0f
    return (1f - brightness.coerceIn(0f, 1f)) * ReaderPrivacyMaxDimAlpha
}

private const val ReaderPrivacyMaxDimAlpha = 0.86f

/** Permanenza massima delle snackbar con azione prima di sparire da sole (come le Short). */
private const val SnackbarWithActionTimeoutMs = 4_000L

/**
 * Come [SnackbarHostState.showSnackbar], ma le snackbar con azione spariscono da sole dopo
 * [SnackbarWithActionTimeoutMs], allineate ai 4s delle Short: il default Material 3 con
 * `actionLabel` è `Indefinite` (restano finché non le tocchi). Allo scadere il risultato
 * è [SnackbarResult.Dismissed], come per uno swipe. Senza azione, comportamento
 * standard (Short).
 */
internal suspend fun SnackbarHostState.showAutoDismissSnackbar(
    message: String,
    actionLabel: String? = null,
): SnackbarResult {
    if (actionLabel == null) {
        return showSnackbar(message)
    }
    return withTimeoutOrNull(SnackbarWithActionTimeoutMs) {
        showSnackbar(message, actionLabel, duration = SnackbarDuration.Indefinite)
    } ?: SnackbarResult.Dismissed
}

