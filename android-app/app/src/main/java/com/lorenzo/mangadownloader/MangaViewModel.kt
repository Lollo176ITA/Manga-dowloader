package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class AppTab {
    SEARCH,
    FAVORITES,
    LIBRARY,
}

data class FavoriteManga(
    val sourceId: String,
    val title: String,
    val mangaUrl: String,
    val coverUrl: String?,
)

data class AppSettings(
    val searchSourceId: String = MangaSourceIds.DEFAULT,
    val autoDownloadEnabled: Boolean = false,
    val autoDownloadTriggerChapters: Int = 3,
    val autoDownloadBatchSize: Int = 3,
    val smartCleanupEnabled: Boolean = false,
    val smartCleanupKeepPreviousChapters: Int = 3,
    val parentalControlEnabled: Boolean = false,
    val parentalPinConfigured: Boolean = false,
    val parentalBiometricEnabled: Boolean = false,
    val parentalPinSalt: String? = null,
    val parentalPinHash: String? = null,
    val labsEnabled: Boolean = false,
    val downloadDevUpdates: Boolean = false,
    val autoReaderSpeed: AutoReaderSpeed = AutoReaderSpeed.OFF,
    val privacyBrightnessEnabled: Boolean = false,
    val readerBrightness: Float = 1f,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    val useDynamicColor: Boolean = false,
)

enum class AutoReaderSpeed(val pauseSeconds: Int) {
    OFF(0),
    CALM(30),
    NORMAL(20),
    FAST(10),
}

enum class ParentalAction {
    OPEN_SEARCH,
    CHANGE_PIN,
    DISABLE_PARENTAL_CONTROL,
    ENABLE_BIOMETRIC,
    DISABLE_BIOMETRIC,
}

enum class ParentalPinSetupMode {
    CREATE,
    CHANGE,
}

data class ParentalPinSetupState(
    val mode: ParentalPinSetupMode,
    val pin: String = "",
    val confirmPin: String = "",
    val errorMessage: String? = null,
    val completionAction: ParentalAction? = null,
)

data class ParentalPinEntryState(
    val action: ParentalAction,
    val pin: String = "",
    val errorMessage: String? = null,
)

data class ParentalBiometricPromptRequest(
    val requestId: Long,
    val action: ParentalAction,
    val title: String,
    val subtitle: String,
)

data class MangaUiState(
    val currentTab: AppTab = AppTab.SEARCH,
    val pendingSearchAccessReturnTab: AppTab? = null,
    val query: String = "",
    val favoritesQuery: String = "",
    val libraryQuery: String = "",
    val results: List<MangaSearchResult> = emptyList(),
    val favorites: List<FavoriteManga> = emptyList(),
    val favoriteMangaKeys: Set<String> = emptySet(),
    val isSearching: Boolean = false,
    val selected: MangaDetails? = null,
    val isLoadingDetails: Boolean = false,
    val mangaInfoDialog: MangaInfoDialogState? = null,
    val library: List<DownloadedSeries> = emptyList(),
    val isLoadingLibrary: Boolean = false,
    val selectedDownloadedSeries: DownloadedSeries? = null,
    val readerChapter: DownloadedChapter? = null,
    val readerPreviousChapter: DownloadedChapter? = null,
    val readerNextChapter: DownloadedChapter? = null,
    val readerPages: List<File> = emptyList(),
    val readerInitialPageIndex: Int = 0,
    val isLoadingReader: Boolean = false,
    val availableUpdate: AppUpdateInfo? = null,
    val isCheckingUpdate: Boolean = false,
    val isInstallingUpdate: Boolean = false,
    val showSettings: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val isBiometricAvailable: Boolean = false,
    val isParentalAuthInProgress: Boolean = false,
    val parentalPinSetupState: ParentalPinSetupState? = null,
    val parentalPinEntryState: ParentalPinEntryState? = null,
    val biometricPromptRequest: ParentalBiometricPromptRequest? = null,
    val errorMessage: String? = null,
)

data class MangaInfoDialogState(
    val sourceId: String,
    val title: String,
    val mangaUrl: String,
    val coverUrl: String?,
    val description: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class MangaViewModel internal constructor(
    application: Application,
    private val appUpdateRepository: AppUpdateRepository,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(application, AppUpdateRepository(application))

    private val sourceRegistry = MangaSourceRegistry(application)
    private val libraryRepository = LibraryRepository(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val initialFavorites = readFavorites()
    private val initialSettings = readSettings()

    private val _state = MutableStateFlow(
        MangaUiState(
            currentTab = if (initialSettings.parentalControlEnabled) AppTab.LIBRARY else AppTab.SEARCH,
            favorites = initialFavorites,
            favoriteMangaKeys = initialFavorites.mapTo(linkedSetOf()) {
                MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl)
            },
            settings = initialSettings,
            isBiometricAvailable = isBiometricAvailable(application),
        ),
    )
    val state: StateFlow<MangaUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var infoJob: Job? = null
    private var libraryJob: Job? = null
    private var readerJob: Job? = null
    private var updateJob: Job? = null
    private var autoDownloadJob: Job? = null
    private var smartCleanupJob: Job? = null
    private var nextBiometricRequestId = 1L

    init {
        observeQueryChanges()
        refreshLibrary()
    }

    @OptIn(FlowPreview::class)
    private fun observeQueryChanges() {
        viewModelScope.launch {
            _state
                .map { it.query.trim() to it.settings.searchSourceId }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .collect { (q, sourceId) ->
                    val searchConfig = MangaSourceCatalog.searchConfig(sourceId)
                    when {
                        q.isEmpty() && searchConfig.showAllOnEmptyQuery -> runSearch("")
                        q.isEmpty() -> {
                            searchJob?.cancel()
                            updateState {
                                copy(
                                    results = emptyList(),
                                    isSearching = false,
                                    errorMessage = null,
                                )
                            }
                        }
                        q.length >= searchConfig.minQueryLength -> runSearch(q)
                        else -> {
                            searchJob?.cancel()
                            updateState {
                                copy(
                                    results = emptyList(),
                                    isSearching = false,
                                )
                            }
                        }
                    }
                }
        }
    }

    fun onQueryChange(text: String) {
        updateState { copy(query = text) }
    }

    fun submitSearch() {
        val q = _state.value.query.trim()
        val searchConfig = MangaSourceCatalog.searchConfig(_state.value.settings.searchSourceId)
        if (q.isEmpty() && searchConfig.showAllOnEmptyQuery) {
            runSearch("")
        } else if (q.length >= searchConfig.minQueryLength) {
            runSearch(q)
        }
    }

    fun selectTab(tab: AppTab) {
        if (tab == _state.value.currentTab) {
            if (tab == AppTab.LIBRARY) {
                refreshLibrary()
            }
            return
        }
        if (tab == AppTab.SEARCH && _state.value.settings.parentalControlEnabled) {
            requestSearchAccess()
            return
        }
        updateState { copy(currentTab = tab) }
        if (tab == AppTab.LIBRARY) {
            refreshLibrary()
        }
    }

    fun onFavoritesQueryChange(text: String) {
        updateState { copy(favoritesQuery = text) }
    }

    fun onLibraryQueryChange(text: String) {
        updateState { copy(libraryQuery = text) }
    }

    fun openSettings() {
        updateState { copy(showSettings = true) }
    }

    fun selectSearchSource(sourceId: String) {
        val resolvedSourceId = MangaSourceCatalog.resolveSourceId(sourceId)
        val query = _state.value.query.trim()
        val searchConfig = MangaSourceCatalog.searchConfig(resolvedSourceId)
        updateSettings { it.copy(searchSourceId = resolvedSourceId) }
        updateState {
            copy(
                errorMessage = null,
            )
        }
        when {
            query.isEmpty() && searchConfig.showAllOnEmptyQuery -> {
                updateState {
                    copy(
                        results = emptyList(),
                        isSearching = true,
                    )
                }
            }
            query.length >= searchConfig.minQueryLength -> {
                updateState {
                    copy(
                        results = emptyList(),
                        isSearching = true,
                    )
                }
            }
            else -> {
                searchJob?.cancel()
                updateState {
                    copy(
                        results = emptyList(),
                        isSearching = false,
                    )
                }
            }
        }
    }

    fun closeSettings() {
        updateState { copy(showSettings = false) }
    }

    fun setParentalControlEnabled(enabled: Boolean) {
        val currentSettings = _state.value.settings
        if (enabled) {
            if (currentSettings.parentalControlEnabled) return
            startParentalPinSetup(mode = ParentalPinSetupMode.CREATE)
            return
        }

        if (!currentSettings.parentalControlEnabled) return
        if (!currentSettings.parentalPinConfigured) {
            disableParentalControl(clearCredentials = true)
        } else {
            requestParentalAuthentication(ParentalAction.DISABLE_PARENTAL_CONTROL)
        }
    }

    fun requestChangeParentalPin() {
        val settings = _state.value.settings
        if (!settings.parentalControlEnabled) return
        if (!settings.parentalPinConfigured) {
            startParentalPinSetup(mode = ParentalPinSetupMode.CREATE)
        } else {
            requestParentalAuthentication(ParentalAction.CHANGE_PIN)
        }
    }

    fun setParentalBiometricEnabled(enabled: Boolean) {
        val settings = _state.value.settings
        if (!settings.parentalControlEnabled || !settings.parentalPinConfigured) return
        val action = if (enabled) {
            ParentalAction.ENABLE_BIOMETRIC
        } else {
            ParentalAction.DISABLE_BIOMETRIC
        }
        requestParentalAuthentication(action)
    }

    fun onParentalPinSetupChange(pin: String? = null, confirmPin: String? = null) {
        val setupState = _state.value.parentalPinSetupState ?: return
        updateState {
            copy(
                parentalPinSetupState = setupState.copy(
                    pin = pin?.let(::sanitizeParentalPin) ?: setupState.pin,
                    confirmPin = confirmPin?.let(::sanitizeParentalPin) ?: setupState.confirmPin,
                    errorMessage = null,
                ),
            )
        }
    }

    fun dismissParentalPinSetup() {
        val setupState = _state.value.parentalPinSetupState ?: return
        if (setupState.mode == ParentalPinSetupMode.CREATE && !_state.value.settings.parentalPinConfigured) {
            disableParentalControl(clearCredentials = true)
            return
        }
        updateState {
            copy(
                parentalPinSetupState = null,
                isParentalAuthInProgress = false,
                pendingSearchAccessReturnTab = if (setupState.completionAction == ParentalAction.OPEN_SEARCH) {
                    null
                } else {
                    pendingSearchAccessReturnTab
                },
            )
        }
    }

    fun confirmParentalPinSetup() {
        val setupState = _state.value.parentalPinSetupState ?: return
        when {
            setupState.pin.length != PARENTAL_PIN_LENGTH -> {
                updateState {
                    copy(
                        parentalPinSetupState = setupState.copy(
                            errorMessage = "Il PIN deve avere 6 cifre",
                        ),
                    )
                }
            }
            setupState.confirmPin.length != PARENTAL_PIN_LENGTH -> {
                updateState {
                    copy(
                        parentalPinSetupState = setupState.copy(
                            errorMessage = "Conferma il PIN di 6 cifre",
                        ),
                    )
                }
            }
            setupState.pin != setupState.confirmPin -> {
                updateState {
                    copy(
                        parentalPinSetupState = setupState.copy(
                            errorMessage = "I due PIN non coincidono",
                        ),
                    )
                }
            }
            else -> {
                val salt = generateParentalPinSalt()
                val hash = hashParentalPin(setupState.pin, salt)
                updateSettings {
                    it.copy(
                        parentalControlEnabled = true,
                        parentalPinConfigured = true,
                        parentalBiometricEnabled = _state.value.isBiometricAvailable,
                        parentalPinSalt = salt,
                        parentalPinHash = hash,
                    )
                }
                updateState {
                    copy(
                        currentTab = if (
                            setupState.completionAction == null &&
                            currentTab == AppTab.SEARCH
                        ) {
                            AppTab.LIBRARY
                        } else {
                            currentTab
                        },
                        parentalPinSetupState = null,
                        isParentalAuthInProgress = false,
                    )
                }
                setupState.completionAction?.let(::completeParentalAction)
            }
        }
    }

    fun onParentalPinEntryChange(pin: String) {
        val pinEntryState = _state.value.parentalPinEntryState ?: return
        updateState {
            copy(
                parentalPinEntryState = pinEntryState.copy(
                    pin = sanitizeParentalPin(pin),
                    errorMessage = null,
                ),
            )
        }
    }

    fun dismissParentalPinEntry() {
        val pinEntryState = _state.value.parentalPinEntryState ?: return
        updateState {
            copy(
                parentalPinEntryState = null,
                isParentalAuthInProgress = false,
                pendingSearchAccessReturnTab = if (pinEntryState.action == ParentalAction.OPEN_SEARCH) {
                    null
                } else {
                    pendingSearchAccessReturnTab
                },
            )
        }
    }

    fun confirmParentalPinEntry() {
        val pinEntryState = _state.value.parentalPinEntryState ?: return
        val settings = _state.value.settings
        if (pinEntryState.pin.length != PARENTAL_PIN_LENGTH) {
            updateState {
                copy(
                    parentalPinEntryState = pinEntryState.copy(
                        errorMessage = "Inserisci un PIN di 6 cifre",
                    ),
                )
            }
            return
        }

        val salt = settings.parentalPinSalt
        val expectedHash = settings.parentalPinHash
        if (salt.isNullOrBlank() || expectedHash.isNullOrBlank()) {
            updateState {
                copy(
                    parentalPinEntryState = null,
                    isParentalAuthInProgress = false,
                    errorMessage = "Configura di nuovo il parental control",
                )
            }
            disableParentalControl(clearCredentials = true)
            return
        }

        val providedHash = hashParentalPin(pinEntryState.pin, salt)
        if (providedHash != expectedHash) {
            updateState {
                copy(
                    parentalPinEntryState = pinEntryState.copy(
                        pin = "",
                        errorMessage = "PIN non corretto",
                    ),
                )
            }
            return
        }

        updateState {
            copy(
                parentalPinEntryState = null,
                isParentalAuthInProgress = false,
            )
        }
        completeParentalAction(pinEntryState.action)
    }

    fun onBiometricAuthenticationSucceeded(requestId: Long) {
        val request = _state.value.biometricPromptRequest ?: return
        if (request.requestId != requestId) return
        updateState {
            copy(
                biometricPromptRequest = null,
                isParentalAuthInProgress = false,
            )
        }
        completeParentalAction(request.action)
    }

    fun usePinInsteadOfBiometric(requestId: Long) {
        val request = _state.value.biometricPromptRequest ?: return
        if (request.requestId != requestId) return
        showPinEntryForAction(request.action)
    }

    fun cancelBiometricAuthentication(requestId: Long, message: String? = null) {
        val request = _state.value.biometricPromptRequest ?: return
        if (request.requestId != requestId) return
        updateState {
            copy(
                biometricPromptRequest = null,
                isParentalAuthInProgress = false,
                pendingSearchAccessReturnTab = if (request.action == ParentalAction.OPEN_SEARCH) {
                    null
                } else {
                    pendingSearchAccessReturnTab
                },
                errorMessage = message ?: errorMessage,
            )
        }
    }

    fun setAutoDownloadEnabled(enabled: Boolean) {
        updateSettings { it.copy(autoDownloadEnabled = enabled) }
    }

    fun setAutoDownloadTriggerChapters(value: Int) {
        updateSettings { it.copy(autoDownloadTriggerChapters = value.coerceAtLeast(1)) }
    }

    fun setAutoDownloadBatchSize(value: Int) {
        updateSettings { it.copy(autoDownloadBatchSize = value.coerceAtLeast(1)) }
    }

    fun setSmartCleanupEnabled(enabled: Boolean) {
        updateSettings { it.copy(smartCleanupEnabled = enabled) }
    }

    fun setSmartCleanupKeepPreviousChapters(value: Int) {
        updateSettings { it.copy(smartCleanupKeepPreviousChapters = value.coerceAtLeast(0)) }
    }

    fun setLabsEnabled(enabled: Boolean) {
        updateSettings {
            if (enabled) it.copy(labsEnabled = true)
            else it.copy(
                labsEnabled = false,
                downloadDevUpdates = false,
                autoReaderSpeed = AutoReaderSpeed.OFF,
                privacyBrightnessEnabled = false,
                readerBrightness = 1f,
            )
        }
    }

    fun setDownloadDevUpdates(enabled: Boolean) {
        updateSettings { it.copy(downloadDevUpdates = enabled) }
        if (enabled) {
            checkForAppUpdate(force = true)
        }
    }

    fun setAutoReaderSpeed(speed: AutoReaderSpeed) {
        updateSettings { it.copy(autoReaderSpeed = speed) }
    }

    fun setPrivacyBrightnessEnabled(enabled: Boolean) {
        updateSettings { it.copy(privacyBrightnessEnabled = enabled) }
    }

    fun setReaderBrightness(brightness: Float) {
        updateSettings { it.copy(readerBrightness = brightness.coerceIn(0f, 1f)) }
    }

    fun setThemeMode(mode: ThemeMode) {
        updateSettings { it.copy(themeMode = mode) }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        updateSettings { it.copy(useDynamicColor = enabled) }
    }

    fun toggleFavoriteFromResult(result: MangaSearchResult) {
        toggleFavorite(
            FavoriteManga(
                sourceId = result.sourceId,
                title = result.title,
                mangaUrl = result.mangaUrl,
                coverUrl = result.coverUrl,
            ),
        )
    }

    fun selectManga(result: MangaSearchResult) {
        detailJob?.cancel()
        _state.value = _state.value.copy(
            isLoadingDetails = true,
            errorMessage = null,
            selected = MangaDetails(
                sourceId = result.sourceId,
                title = result.title,
                coverUrl = result.coverUrl,
                mangaUrl = result.mangaUrl,
                chapters = emptyList(),
            ),
        )
        detailJob = viewModelScope.launch {
            try {
                val details = withContext(Dispatchers.IO) {
                    sourceRegistry.resolve(result.sourceId, result.mangaUrl).fetchMangaDetails(result.mangaUrl)
                }
                _state.value = _state.value.copy(selected = details, isLoadingDetails = false)
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                _state.value = _state.value.copy(
                    isLoadingDetails = false,
                    errorMessage = exc.message ?: "Errore caricamento manga",
                )
            }
        }
    }

    fun showMangaInfo(result: MangaSearchResult) {
        infoJob?.cancel()
        _state.value = _state.value.copy(
            mangaInfoDialog = MangaInfoDialogState(
                sourceId = result.sourceId,
                title = result.title,
                mangaUrl = result.mangaUrl,
                coverUrl = result.coverUrl,
                isLoading = true,
            ),
            errorMessage = null,
        )
        infoJob = viewModelScope.launch {
            try {
                val details = withContext(Dispatchers.IO) {
                    sourceRegistry.resolve(result.sourceId, result.mangaUrl).fetchMangaDetails(result.mangaUrl)
                }
                _state.value = _state.value.copy(
                    mangaInfoDialog = MangaInfoDialogState(
                        sourceId = details.sourceId,
                        title = details.title,
                        mangaUrl = details.mangaUrl,
                        coverUrl = details.coverUrl,
                        description = details.description,
                        isLoading = false,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                _state.value = _state.value.copy(
                    mangaInfoDialog = MangaInfoDialogState(
                        sourceId = result.sourceId,
                        title = result.title,
                        mangaUrl = result.mangaUrl,
                        coverUrl = result.coverUrl,
                        isLoading = false,
                        errorMessage = exc.message ?: "Errore caricamento trama",
                    ),
                )
            }
        }
    }

    fun dismissMangaInfo() {
        infoJob?.cancel()
        _state.value = _state.value.copy(mangaInfoDialog = null)
    }

    fun clearSelection() {
        detailJob?.cancel()
        _state.value = _state.value.copy(selected = null, isLoadingDetails = false, errorMessage = null)
    }

    fun toggleFavorite(manga: FavoriteManga) {
        val current = _state.value.favorites.toMutableList()
        val targetKey = MangaSourceCatalog.identityKey(manga.sourceId, manga.mangaUrl)
        val existingIndex = current.indexOfFirst {
            MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) == targetKey
        }
        if (existingIndex >= 0) {
            current.removeAt(existingIndex)
        } else {
            current.add(0, manga)
        }
        persistFavorites(current)
        updateState {
            copy(
                favorites = current,
                favoriteMangaKeys = current.mapTo(linkedSetOf()) {
                    MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl)
                },
            )
        }
    }

    fun toggleFavoriteSelectedManga() {
        val selected = _state.value.selected ?: return
        toggleFavorite(
            FavoriteManga(
                sourceId = selected.sourceId,
                title = selected.title,
                mangaUrl = selected.mangaUrl,
                coverUrl = selected.coverUrl,
            ),
        )
    }

    fun refreshLibrary() {
        libraryJob?.cancel()
        updateState { copy(isLoadingLibrary = true) }
        libraryJob = viewModelScope.launch {
            try {
                val snapshot = scanLibrarySnapshot()
                _state.value = _state.value.withLibrarySnapshot(snapshot).copy(isLoadingLibrary = false)
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                _state.value = _state.value.copy(
                    isLoadingLibrary = false,
                    errorMessage = exc.message ?: "Errore caricamento libreria",
                )
            }
        }
    }

    fun selectDownloadedSeries(series: DownloadedSeries) {
        updateState {
            copy(
                selectedDownloadedSeries = series,
                currentTab = AppTab.LIBRARY,
                errorMessage = null,
            )
        }
    }

    fun clearDownloadedSelection() {
        readerJob?.cancel()
        smartCleanupJob?.cancel()
        updateState {
            copy(
                selectedDownloadedSeries = null,
                errorMessage = null,
            ).clearedReaderState()
        }
    }

    fun openReader(chapter: DownloadedChapter) {
        readerJob?.cancel()
        val initialPageIndex = libraryRepository.readerPagePosition(chapter.relativePath)?.pageIndex
            ?: chapter.readerPageIndex
            ?: 0
        libraryRepository.saveReaderPagePosition(
            relativePath = chapter.relativePath,
            pageIndex = initialPageIndex,
            pageCount = chapter.readerPageCount,
        )
        _state.value = _state.value.copy(
            readerChapter = chapter.copy(
                readerPageIndex = initialPageIndex,
            ),
            readerPreviousChapter = null,
            readerNextChapter = null,
            readerPages = emptyList(),
            readerInitialPageIndex = initialPageIndex,
            isLoadingReader = true,
            errorMessage = null,
        ).withReaderPosition(
            relativePath = chapter.relativePath,
            pageIndex = initialPageIndex,
            pageCount = chapter.readerPageCount,
        )
            .withReaderAdjacency(chapter.relativePath)

        readerJob = viewModelScope.launch {
            try {
                val pages = libraryRepository.extractReaderPages(chapter)
                val restoredPageIndex = initialPageIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
                libraryRepository.saveReaderPagePosition(
                    relativePath = chapter.relativePath,
                    pageIndex = restoredPageIndex,
                    pageCount = pages.size,
                )
                val completed = restoredPageIndex >= pages.lastIndex
                if (completed) {
                    libraryRepository.markChapterRead(chapter)
                }
                val updated = (_state.value.readerChapter ?: chapter).copy(
                    isRead = (_state.value.readerChapter ?: chapter).isRead || completed,
                    readerPageIndex = restoredPageIndex,
                    readerPageCount = pages.size,
                )
                val nextState = _state.value.copy(
                    readerChapter = updated,
                    readerPages = pages,
                    readerInitialPageIndex = restoredPageIndex,
                    isLoadingReader = false,
                ).withReaderPosition(
                    relativePath = updated.relativePath,
                    pageIndex = restoredPageIndex,
                    pageCount = pages.size,
                ).let { state ->
                    if (completed) state.withReadChapter(updated.relativePath) else state
                }.withReaderAdjacency(updated.relativePath)
                _state.value = nextState
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                _state.value = _state.value.copy(
                    isLoadingReader = false,
                    errorMessage = exc.message ?: "Impossibile aprire il reader",
                )
            }
        }

        maybeTriggerAutoDownload(chapter)
        maybePerformSmartCleanup(chapter)
    }

    fun saveReaderPagePosition(pageIndex: Int, pageCount: Int) {
        val chapter = _state.value.readerChapter ?: return
        val safePageCount = pageCount.coerceAtLeast(1)
        val safePageIndex = pageIndex.coerceIn(0, safePageCount - 1)
        val currentPageIndex = chapter.readerPageIndex ?: -1
        val nextPageIndex = maxOf(currentPageIndex, safePageIndex)
        if (
            chapter.readerPageIndex == nextPageIndex &&
            chapter.readerPageCount == safePageCount
        ) {
            return
        }

        libraryRepository.saveReaderPagePosition(
            relativePath = chapter.relativePath,
            pageIndex = nextPageIndex,
            pageCount = safePageCount,
        )
        val completed = nextPageIndex >= safePageCount - 1
        if (completed && !chapter.isRead) {
            libraryRepository.markChapterRead(chapter)
        }
        updateState {
            val positionedState = withReaderPosition(
                relativePath = chapter.relativePath,
                pageIndex = nextPageIndex,
                pageCount = safePageCount,
            )
            if (completed) positionedState.withReadChapter(chapter.relativePath) else positionedState
        }
    }

    private fun maybeTriggerAutoDownload(chapter: DownloadedChapter) {
        val settings = _state.value.settings
        if (!settings.autoDownloadEnabled) return
        val series = _state.value.selectedDownloadedSeries ?: return
        val mangaUrl = series.mangaUrl?.takeIf { it.isNotBlank() } ?: return

        val chapters = series.chapters
        val currentIndex = chapters.indexOfFirst { it.relativePath == chapter.relativePath }
        if (currentIndex < 0) return
        val chaptersAfter = chapters.size - 1 - currentIndex
        if (chaptersAfter > settings.autoDownloadTriggerChapters) return

        if (autoDownloadJob?.isActive == true) return

        val downloadedNumbers = chapters.mapNotNull { it.numberValue }.toSet()
        val highestDownloaded = downloadedNumbers.maxOrNull() ?: return
        val batchSize = settings.autoDownloadBatchSize

        autoDownloadJob = viewModelScope.launch {
            try {
                val details = withContext(Dispatchers.IO) {
                    sourceRegistry.resolve(series.sourceId, mangaUrl).fetchMangaDetails(mangaUrl)
                }
                val missing = details.chapters
                    .asSequence()
                    .filter { remote ->
                        remote.numberValue > highestDownloaded &&
                            remote.numberValue !in downloadedNumbers
                    }
                    .sortedBy { it.numberValue }
                    .take(batchSize)
                    .toList()
                if (missing.isEmpty()) return@launch
                withContext(Dispatchers.IO) {
                    DownloadWorker.enqueue(
                        getApplication<Application>(),
                        missing.first().url,
                        missing.last().url,
                        sourceId = series.sourceId,
                        series.title,
                        mangaUrl,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silent: auto-download is best-effort
            }
        }
    }

    private fun maybePerformSmartCleanup(chapter: DownloadedChapter) {
        val settings = _state.value.settings
        if (!settings.smartCleanupEnabled) return
        if (smartCleanupJob?.isActive == true) return

        val series = _state.value.selectedDownloadedSeries ?: return
        val currentIndex = series.chapters.indexOfFirst { it.relativePath == chapter.relativePath }
        if (currentIndex <= 0) return

        val keepPrevious = settings.smartCleanupKeepPreviousChapters.coerceAtLeast(0)
        val deleteUntilIndex = (currentIndex - keepPrevious).coerceAtLeast(0)
        if (deleteUntilIndex <= 0) return

        val chaptersToDelete = series.chapters
            .take(deleteUntilIndex)
            .filter { it.isRead }

        if (chaptersToDelete.isEmpty()) return

        smartCleanupJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    libraryRepository.deleteChapters(series, chaptersToDelete)
                }
                val snapshot = scanLibrarySnapshot()
                _state.value = _state.value.withLibrarySnapshot(snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silent: automatic cleanup is best-effort
            }
        }
    }

    fun closeReader() {
        readerJob?.cancel()
        updateState { clearedReaderState() }
    }

    fun dismissError() {
        updateState { copy(errorMessage = null) }
    }

    fun deleteDownloadedChapter(chapter: DownloadedChapter) {
        val series = _state.value.selectedDownloadedSeries ?: return

        libraryJob?.cancel()
        smartCleanupJob?.cancel()
        updateState { copy(isLoadingLibrary = true, errorMessage = null) }
        libraryJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    libraryRepository.deleteChapters(series, listOf(chapter))
                }
                val snapshot = scanLibrarySnapshot()
                _state.value = _state.value
                    .withLibrarySnapshot(snapshot)
                    .copy(isLoadingLibrary = false)
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                _state.value = _state.value.copy(
                    isLoadingLibrary = false,
                    errorMessage = exc.message ?: "Errore eliminazione capitolo",
                )
            }
        }
    }

    fun deleteDownloadedSeries(series: DownloadedSeries? = _state.value.selectedDownloadedSeries) {
        val targetSeries = series ?: return

        libraryJob?.cancel()
        readerJob?.cancel()
        smartCleanupJob?.cancel()
        updateState { copy(isLoadingLibrary = true, errorMessage = null) }
        libraryJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    libraryRepository.deleteSeries(targetSeries)
                }
                val snapshot = scanLibrarySnapshot()
                _state.value = _state.value
                    .clearedReaderState()
                    .withLibrarySnapshot(snapshot)
                    .copy(isLoadingLibrary = false)
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                _state.value = _state.value.copy(
                    isLoadingLibrary = false,
                    errorMessage = exc.message ?: "Errore eliminazione manga",
                )
            }
        }
    }

    fun openPreviousReaderChapter() {
        _state.value.readerPreviousChapter?.let(::openReader)
    }

    fun openNextReaderChapter() {
        _state.value.readerNextChapter?.let(::openReader)
    }

    fun checkForAppUpdate(force: Boolean = false) {
        if (updateJob?.isActive == true) {
            return
        }
        if (!force && _state.value.availableUpdate != null) {
            return
        }

        _state.value = _state.value.copy(isCheckingUpdate = true)
        updateJob = viewModelScope.launch {
            try {
                val update = appUpdateRepository.checkForUpdate(
                    includePreview = _state.value.settings.downloadDevUpdates,
                )
                _state.value = _state.value.copy(
                    availableUpdate = update,
                    isCheckingUpdate = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                _state.value = if (force) {
                    _state.value.copy(
                        isCheckingUpdate = false,
                        errorMessage = exc.message ?: "Errore controllo aggiornamenti",
                    )
                } else {
                    _state.value.copy(isCheckingUpdate = false)
                }
            }
        }
    }

    fun dismissAvailableUpdate() {
        updateState { copy(availableUpdate = null) }
    }

    fun installAvailableUpdate() {
        val update = _state.value.availableUpdate ?: return
        updateJob?.cancel()
        _state.value = _state.value.copy(isInstallingUpdate = true, errorMessage = null)
        updateJob = viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                if (!AppUpdateInstaller.canInstallPackages(context)) {
                    AppUpdateInstaller.openInstallPermissionSettings(context)
                    _state.value = _state.value.copy(
                        isInstallingUpdate = false,
                        errorMessage = "Abilita l'installazione da questa app e riprova",
                    )
                    return@launch
                }

                val apkFile = appUpdateRepository.downloadUpdateApk(update)
                AppUpdateInstaller.installApk(context, apkFile)
                _state.value = _state.value.copy(
                    isInstallingUpdate = false,
                    availableUpdate = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                _state.value = _state.value.copy(
                    isInstallingUpdate = false,
                    errorMessage = exc.message ?: "Errore installazione aggiornamento",
                )
            }
        }
    }

    private fun runSearch(q: String) {
        searchJob?.cancel()
        updateState { copy(isSearching = true, errorMessage = null) }
        searchJob = viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    sourceRegistry.requireById(_state.value.settings.searchSourceId).searchManga(q)
                }
                _state.value = _state.value.copy(results = results, isSearching = false)
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                _state.value = _state.value.copy(
                    isSearching = false,
                    errorMessage = exc.message ?: "Errore di ricerca",
                )
            }
        }
    }

    private fun requestSearchAccess() {
        val settings = _state.value.settings
        val originTab = _state.value.currentTab
        if (!settings.parentalControlEnabled) {
            updateState { copy(currentTab = AppTab.SEARCH) }
            return
        }
        updateState { copy(pendingSearchAccessReturnTab = originTab) }
        if (!settings.parentalPinConfigured) {
            startParentalPinSetup(
                mode = ParentalPinSetupMode.CREATE,
                completionAction = ParentalAction.OPEN_SEARCH,
            )
            return
        }
        requestParentalAuthentication(ParentalAction.OPEN_SEARCH)
    }

    private fun requestParentalAuthentication(action: ParentalAction) {
        if (_state.value.isParentalAuthInProgress) return
        val settings = _state.value.settings
        if (!settings.parentalPinConfigured) {
            startParentalPinSetup(mode = ParentalPinSetupMode.CREATE, completionAction = action)
            return
        }
        if (settings.parentalBiometricEnabled && _state.value.isBiometricAvailable) {
            val requestId = nextBiometricRequestId++
            updateState {
                copy(
                    isParentalAuthInProgress = true,
                    parentalPinEntryState = null,
                    biometricPromptRequest = ParentalBiometricPromptRequest(
                        requestId = requestId,
                        action = action,
                        title = "Parental control",
                        subtitle = when (action) {
                            ParentalAction.OPEN_SEARCH -> "Autenticati per aprire Cerca"
                            ParentalAction.CHANGE_PIN -> "Autenticati per cambiare il PIN"
                            ParentalAction.DISABLE_PARENTAL_CONTROL ->
                                "Autenticati per disattivare il parental control"
                            ParentalAction.ENABLE_BIOMETRIC,
                            ParentalAction.DISABLE_BIOMETRIC ->
                                "Autenticati per aggiornare la biometria"
                        },
                    ),
                )
            }
        } else {
            showPinEntryForAction(action)
        }
    }

    private fun startParentalPinSetup(
        mode: ParentalPinSetupMode,
        completionAction: ParentalAction? = null,
    ) {
        updateState {
            copy(
                isParentalAuthInProgress = true,
                parentalPinEntryState = null,
                biometricPromptRequest = null,
                parentalPinSetupState = ParentalPinSetupState(
                    mode = mode,
                    completionAction = completionAction,
                ),
            )
        }
    }

    private fun showPinEntryForAction(action: ParentalAction) {
        updateState {
            copy(
                biometricPromptRequest = null,
                isParentalAuthInProgress = true,
                parentalPinEntryState = ParentalPinEntryState(action = action),
            )
        }
    }

    private fun completeParentalAction(action: ParentalAction) {
        when (action) {
            ParentalAction.OPEN_SEARCH -> updateState {
                copy(
                    currentTab = AppTab.SEARCH,
                    pendingSearchAccessReturnTab = null,
                )
            }
            ParentalAction.CHANGE_PIN -> startParentalPinSetup(mode = ParentalPinSetupMode.CHANGE)
            ParentalAction.DISABLE_PARENTAL_CONTROL -> disableParentalControl(clearCredentials = true)
            ParentalAction.ENABLE_BIOMETRIC -> updateSettings { it.copy(parentalBiometricEnabled = true) }
            ParentalAction.DISABLE_BIOMETRIC -> updateSettings { it.copy(parentalBiometricEnabled = false) }
        }
    }

    private fun disableParentalControl(clearCredentials: Boolean) {
        updateSettings {
            if (clearCredentials) {
                it.copy(
                    parentalControlEnabled = false,
                    parentalPinConfigured = false,
                    parentalBiometricEnabled = false,
                    parentalPinSalt = null,
                    parentalPinHash = null,
                )
            } else {
                it.copy(parentalControlEnabled = false, parentalBiometricEnabled = false)
            }
        }
        updateState {
            copy(
                currentTab = if (currentTab == AppTab.SEARCH) AppTab.LIBRARY else currentTab,
                pendingSearchAccessReturnTab = null,
                parentalPinSetupState = null,
                parentalPinEntryState = null,
                biometricPromptRequest = null,
                isParentalAuthInProgress = false,
            )
        }
    }

    private fun isBiometricAvailable(context: Context): Boolean {
        return BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun MangaUiState.withLibrarySnapshot(snapshot: List<DownloadedSeries>): MangaUiState {
        val selectedDirectory = selectedDownloadedSeries?.directory?.absolutePath
        val updatedSelected = snapshot.firstOrNull { it.directory.absolutePath == selectedDirectory }
        val readerPath = readerChapter?.relativePath
        val updatedReader = updatedSelected?.chapters?.firstOrNull { it.relativePath == readerPath }
            ?: snapshot.asSequence()
                .flatMap { it.chapters.asSequence() }
                .firstOrNull { it.relativePath == readerPath }

        return copy(
            library = snapshot,
            selectedDownloadedSeries = updatedSelected,
            readerChapter = updatedReader ?: readerChapter,
        ).withReaderAdjacency((updatedReader ?: readerChapter)?.relativePath)
    }

    private fun MangaUiState.withReadChapter(relativePath: String): MangaUiState {
        fun DownloadedChapter.markIfSame(): DownloadedChapter {
            return if (this.relativePath == relativePath) copy(isRead = true) else this
        }

        val readChapterId = selectedDownloadedSeries
            ?.chapters
            ?.firstOrNull { it.relativePath == relativePath }
            ?.chapterId
            ?: library
                .asSequence()
                .flatMap { it.chapters.asSequence() }
                .firstOrNull { it.relativePath == relativePath }
                ?.chapterId

        val updatedLibrary = library.map { series ->
            val updatedChapters = series.chapters.map { chapter -> chapter.markIfSame() }
            val includesReadChapter = updatedChapters.any { it.relativePath == relativePath }
            series.copy(
                chapters = updatedChapters,
                readChapterIds = if (includesReadChapter && readChapterId != null) {
                    series.readChapterIds + readChapterId
                } else {
                    series.readChapterIds
                },
            )
        }
        val updatedSelected = selectedDownloadedSeries?.let { series ->
            series.copy(
                chapters = series.chapters.map { chapter -> chapter.markIfSame() },
                readChapterIds = if (readChapterId != null && series.chapters.any { it.relativePath == relativePath }) {
                    series.readChapterIds + readChapterId
                } else {
                    series.readChapterIds
                },
            )
        }
        val updatedReader = if (readerChapter?.relativePath == relativePath) {
            readerChapter.copy(isRead = true)
        } else {
            readerChapter
        }
        return copy(
            library = updatedLibrary,
            selectedDownloadedSeries = updatedSelected,
            readerChapter = updatedReader,
        ).withReaderAdjacency(updatedReader?.relativePath)
    }

    private fun MangaUiState.withReaderPosition(
        relativePath: String,
        pageIndex: Int,
        pageCount: Int?,
    ): MangaUiState {
        fun DownloadedChapter.updateIfSame(): DownloadedChapter {
            return if (this.relativePath == relativePath) {
                copy(
                    readerPageIndex = pageIndex,
                    readerPageCount = pageCount ?: readerPageCount,
                )
            } else {
                this
            }
        }

        val updatedLibrary = library.map { series ->
            series.copy(chapters = series.chapters.map { chapter -> chapter.updateIfSame() })
        }
        val updatedSelected = selectedDownloadedSeries?.let { series ->
            series.copy(chapters = series.chapters.map { chapter -> chapter.updateIfSame() })
        }
        val updatedReader = readerChapter?.updateIfSame()
        return copy(
            library = updatedLibrary,
            selectedDownloadedSeries = updatedSelected,
            readerChapter = updatedReader,
        )
    }

    private fun MangaUiState.withReaderAdjacency(relativePath: String?): MangaUiState {
        val chapters = selectedDownloadedSeries?.chapters.orEmpty()
        val currentIndex = relativePath?.let { path ->
            chapters.indexOfFirst { it.relativePath == path }
        } ?: -1

        if (currentIndex < 0) {
            return copy(
                readerPreviousChapter = null,
                readerNextChapter = null,
            )
        }

        return copy(
            readerPreviousChapter = chapters.getOrNull(currentIndex - 1),
            readerNextChapter = chapters.getOrNull(currentIndex + 1),
        )
    }

    private fun readFavorites(): List<FavoriteManga> {
        val raw = prefs.getString(KEY_FAVORITES_JSON, null).orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }
        return try {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val item = element.jsonObject
                val title = item["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val mangaUrl = item["mangaUrl"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (title.isBlank() || mangaUrl.isBlank()) {
                    null
                } else {
                    FavoriteManga(
                        sourceId = MangaSourceCatalog.resolveSourceId(
                            sourceId = item["sourceId"]?.jsonPrimitive?.contentOrNull,
                            url = mangaUrl,
                        ),
                        title = title,
                        mangaUrl = mangaUrl,
                        coverUrl = item["coverUrl"]?.jsonPrimitive?.contentOrNull,
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistFavorites(favorites: List<FavoriteManga>) {
        val payload = buildJsonArray {
            favorites.forEach { manga ->
                add(
                    buildJsonObject {
                        put("sourceId", JsonPrimitive(manga.sourceId))
                        put("title", JsonPrimitive(manga.title))
                        put("mangaUrl", JsonPrimitive(manga.mangaUrl))
                        manga.coverUrl?.let { put("coverUrl", JsonPrimitive(it)) }
                    },
                )
            }
        }
        prefs.edit()
            .putString(KEY_FAVORITES_JSON, json.encodeToString(JsonArray.serializer(), payload))
            .apply()
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val current = _state.value.settings
        val updated = transform(current)
        if (updated == current) return
        updateState { copy(settings = updated) }
        persistSettings(updated)
    }

    private inline fun updateState(transform: MangaUiState.() -> MangaUiState) {
        _state.value = _state.value.transform()
    }

    private suspend fun scanLibrarySnapshot(): List<DownloadedSeries> {
        return withContext(Dispatchers.IO) { libraryRepository.scanLibrary() }
    }

    private fun readSettings(): AppSettings {
        return AppSettings(
            searchSourceId = MangaSourceCatalog.resolveSourceId(
                prefs.getString(KEY_SEARCH_SOURCE_ID, null),
            ),
            autoDownloadEnabled = prefs.getBoolean(KEY_AUTO_DOWNLOAD_ENABLED, false),
            autoDownloadTriggerChapters = prefs
                .getInt(KEY_AUTO_DOWNLOAD_TRIGGER, 3)
                .coerceAtLeast(1),
            autoDownloadBatchSize = prefs
                .getInt(KEY_AUTO_DOWNLOAD_BATCH, 3)
                .coerceAtLeast(1),
            smartCleanupEnabled = prefs.getBoolean(KEY_SMART_CLEANUP_ENABLED, false),
            smartCleanupKeepPreviousChapters = prefs
                .getInt(KEY_SMART_CLEANUP_KEEP_PREVIOUS, 3)
                .coerceAtLeast(0),
            parentalControlEnabled = prefs.getBoolean(KEY_PARENTAL_CONTROL_ENABLED, false),
            parentalPinConfigured = prefs.getBoolean(KEY_PARENTAL_PIN_CONFIGURED, false),
            parentalBiometricEnabled = prefs.getBoolean(KEY_PARENTAL_BIOMETRIC_ENABLED, false),
            parentalPinSalt = prefs.getString(KEY_PARENTAL_PIN_SALT, null),
            parentalPinHash = prefs.getString(KEY_PARENTAL_PIN_HASH, null),
            labsEnabled = prefs.getBoolean(KEY_LABS_ENABLED, false),
            downloadDevUpdates = prefs.getBoolean(KEY_DOWNLOAD_DEV_UPDATES, false),
            autoReaderSpeed = runCatching {
                AutoReaderSpeed.valueOf(
                    prefs.getString(KEY_AUTO_READER_SPEED, AutoReaderSpeed.OFF.name)
                        ?: AutoReaderSpeed.OFF.name,
                )
            }.getOrDefault(AutoReaderSpeed.OFF),
            privacyBrightnessEnabled = prefs.getBoolean(KEY_PRIVACY_BRIGHTNESS_ENABLED, false),
            readerBrightness = prefs.getFloat(KEY_READER_BRIGHTNESS, 1f).coerceIn(0f, 1f),
            themeMode = runCatching {
                ThemeMode.valueOf(
                    prefs.getString(KEY_THEME_MODE, ThemeMode.AUTO.name)
                        ?: ThemeMode.AUTO.name,
                )
            }.getOrDefault(ThemeMode.AUTO),
            useDynamicColor = prefs.getBoolean(KEY_USE_DYNAMIC_COLOR, false),
        )
    }

    private fun persistSettings(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_SEARCH_SOURCE_ID, settings.searchSourceId)
            .putBoolean(KEY_AUTO_DOWNLOAD_ENABLED, settings.autoDownloadEnabled)
            .putInt(KEY_AUTO_DOWNLOAD_TRIGGER, settings.autoDownloadTriggerChapters)
            .putInt(KEY_AUTO_DOWNLOAD_BATCH, settings.autoDownloadBatchSize)
            .putBoolean(KEY_SMART_CLEANUP_ENABLED, settings.smartCleanupEnabled)
            .putInt(KEY_SMART_CLEANUP_KEEP_PREVIOUS, settings.smartCleanupKeepPreviousChapters)
            .putBoolean(KEY_PARENTAL_CONTROL_ENABLED, settings.parentalControlEnabled)
            .putBoolean(KEY_PARENTAL_PIN_CONFIGURED, settings.parentalPinConfigured)
            .putBoolean(KEY_PARENTAL_BIOMETRIC_ENABLED, settings.parentalBiometricEnabled)
            .putString(KEY_PARENTAL_PIN_SALT, settings.parentalPinSalt)
            .putString(KEY_PARENTAL_PIN_HASH, settings.parentalPinHash)
            .putBoolean(KEY_LABS_ENABLED, settings.labsEnabled)
            .putBoolean(KEY_DOWNLOAD_DEV_UPDATES, settings.downloadDevUpdates)
            .putString(KEY_AUTO_READER_SPEED, settings.autoReaderSpeed.name)
            .putBoolean(KEY_PRIVACY_BRIGHTNESS_ENABLED, settings.privacyBrightnessEnabled)
            .putFloat(KEY_READER_BRIGHTNESS, settings.readerBrightness.coerceIn(0f, 1f))
            .putString(KEY_THEME_MODE, settings.themeMode.name)
            .putBoolean(KEY_USE_DYNAMIC_COLOR, settings.useDynamicColor)
            .apply()
    }

    private fun MangaUiState.clearedReaderState(): MangaUiState {
        return copy(
            readerChapter = null,
            readerPreviousChapter = null,
            readerNextChapter = null,
            readerPages = emptyList(),
            readerInitialPageIndex = 0,
            isLoadingReader = false,
        )
    }

    companion object {
        private const val PREFS_NAME = "manga_downloader_prefs"
        private const val KEY_FAVORITES_JSON = "favorites_json"
        private const val KEY_SEARCH_SOURCE_ID = "search_source_id"
        private const val KEY_AUTO_DOWNLOAD_ENABLED = "auto_download_enabled"
        private const val KEY_AUTO_DOWNLOAD_TRIGGER = "auto_download_trigger"
        private const val KEY_AUTO_DOWNLOAD_BATCH = "auto_download_batch"
        private const val KEY_SMART_CLEANUP_ENABLED = "smart_cleanup_enabled"
        private const val KEY_SMART_CLEANUP_KEEP_PREVIOUS = "smart_cleanup_keep_previous"
        private const val KEY_PARENTAL_CONTROL_ENABLED = "parental_control_enabled"
        private const val KEY_PARENTAL_PIN_CONFIGURED = "parental_pin_configured"
        private const val KEY_PARENTAL_BIOMETRIC_ENABLED = "parental_biometric_enabled"
        private const val KEY_PARENTAL_PIN_SALT = "parental_pin_salt"
        private const val KEY_PARENTAL_PIN_HASH = "parental_pin_hash"
        private const val KEY_LABS_ENABLED = "labs_enabled"
        private const val KEY_DOWNLOAD_DEV_UPDATES = "download_dev_updates"
        private const val KEY_AUTO_READER_SPEED = "auto_reader_speed"
        private const val KEY_PRIVACY_BRIGHTNESS_ENABLED = "privacy_brightness_enabled"
        private const val KEY_READER_BRIGHTNESS = "reader_brightness"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_USE_DYNAMIC_COLOR = "use_dynamic_color"
        private const val PARENTAL_PIN_LENGTH = 6
        private const val DEBOUNCE_MS = 350L
    }
}
