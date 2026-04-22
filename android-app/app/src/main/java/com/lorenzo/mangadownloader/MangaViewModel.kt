package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
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
    val title: String,
    val mangaUrl: String,
    val coverUrl: String?,
)

data class AppSettings(
    val autoDownloadEnabled: Boolean = false,
    val autoDownloadTriggerChapters: Int = 3,
    val autoDownloadBatchSize: Int = 3,
    val smartCleanupEnabled: Boolean = false,
    val smartCleanupKeepPreviousChapters: Int = 3,
)

data class MangaUiState(
    val currentTab: AppTab = AppTab.SEARCH,
    val query: String = "",
    val favoritesQuery: String = "",
    val libraryQuery: String = "",
    val results: List<MangaSearchResult> = emptyList(),
    val favorites: List<FavoriteManga> = emptyList(),
    val favoriteMangaUrls: Set<String> = emptySet(),
    val isSearching: Boolean = false,
    val selected: MangaDetails? = null,
    val isLoadingDetails: Boolean = false,
    val library: List<DownloadedSeries> = emptyList(),
    val isLoadingLibrary: Boolean = false,
    val selectedDownloadedSeries: DownloadedSeries? = null,
    val readerChapter: DownloadedChapter? = null,
    val readerPreviousChapter: DownloadedChapter? = null,
    val readerNextChapter: DownloadedChapter? = null,
    val readerPages: List<File> = emptyList(),
    val isLoadingReader: Boolean = false,
    val availableUpdate: AppUpdateInfo? = null,
    val isCheckingUpdate: Boolean = false,
    val isInstallingUpdate: Boolean = false,
    val showSettings: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val errorMessage: String? = null,
)

class MangaViewModel(application: Application) : AndroidViewModel(application) {

    private val client = MangapillClient(application)
    private val libraryRepository = LibraryRepository(application)
    private val appUpdateRepository = AppUpdateRepository(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val initialFavorites = readFavorites()
    private val initialSettings = readSettings()

    private val _state = MutableStateFlow(
        MangaUiState(
            favorites = initialFavorites,
            favoriteMangaUrls = initialFavorites.mapTo(linkedSetOf()) { it.mangaUrl },
            settings = initialSettings,
        ),
    )
    val state: StateFlow<MangaUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var libraryJob: Job? = null
    private var readerJob: Job? = null
    private var updateJob: Job? = null
    private var autoDownloadJob: Job? = null
    private var smartCleanupJob: Job? = null

    init {
        observeQueryChanges()
        refreshLibrary()
    }

    @OptIn(FlowPreview::class)
    private fun observeQueryChanges() {
        viewModelScope.launch {
            _state
                .map { it.query.trim() }
                .distinctUntilChanged()
                .debounce(DEBOUNCE_MS)
                .collect { q ->
                    when {
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
                        q.length >= MIN_QUERY_LENGTH -> runSearch(q)
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
        if (q.length >= MIN_QUERY_LENGTH) {
            runSearch(q)
        }
    }

    fun selectTab(tab: AppTab) {
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

    fun closeSettings() {
        updateState { copy(showSettings = false) }
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

    fun toggleFavoriteFromResult(result: MangaSearchResult) {
        toggleFavorite(
            FavoriteManga(
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
                title = result.title,
                coverUrl = result.coverUrl,
                mangaUrl = result.mangaUrl,
                chapters = emptyList(),
            ),
        )
        detailJob = viewModelScope.launch {
            try {
                val details = withContext(Dispatchers.IO) { client.fetchMangaDetails(result.mangaUrl) }
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

    fun clearSelection() {
        detailJob?.cancel()
        _state.value = _state.value.copy(selected = null, isLoadingDetails = false, errorMessage = null)
    }

    fun toggleFavorite(manga: FavoriteManga) {
        val current = _state.value.favorites.toMutableList()
        val existingIndex = current.indexOfFirst { it.mangaUrl == manga.mangaUrl }
        if (existingIndex >= 0) {
            current.removeAt(existingIndex)
        } else {
            current.add(0, manga)
        }
        persistFavorites(current)
        updateState {
            copy(
                favorites = current,
                favoriteMangaUrls = current.mapTo(linkedSetOf()) { it.mangaUrl },
            )
        }
    }

    fun toggleFavoriteSelectedManga() {
        val selected = _state.value.selected ?: return
        toggleFavorite(
            FavoriteManga(
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
        libraryRepository.markChapterRead(chapter)
        _state.value = _state.value.copy(
            readerChapter = chapter.copy(isRead = true),
            readerPreviousChapter = null,
            readerNextChapter = null,
            readerPages = emptyList(),
            isLoadingReader = true,
            errorMessage = null,
        ).withReadChapter(chapter.relativePath)
            .withReaderAdjacency(chapter.relativePath)

        readerJob = viewModelScope.launch {
            try {
                val pages = libraryRepository.extractReaderPages(chapter)
                val updated = _state.value.readerChapter?.copy(isRead = true) ?: chapter.copy(isRead = true)
                _state.value = _state.value.copy(
                    readerChapter = updated,
                    readerPages = pages,
                    isLoadingReader = false,
                ).withReaderAdjacency(updated.relativePath)
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
                val details = withContext(Dispatchers.IO) { client.fetchMangaDetails(mangaUrl) }
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
                val update = appUpdateRepository.checkForUpdate()
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
                val results = withContext(Dispatchers.IO) { client.searchManga(q) }
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
        )
    }

    private fun persistSettings(settings: AppSettings) {
        prefs.edit()
            .putBoolean(KEY_AUTO_DOWNLOAD_ENABLED, settings.autoDownloadEnabled)
            .putInt(KEY_AUTO_DOWNLOAD_TRIGGER, settings.autoDownloadTriggerChapters)
            .putInt(KEY_AUTO_DOWNLOAD_BATCH, settings.autoDownloadBatchSize)
            .putBoolean(KEY_SMART_CLEANUP_ENABLED, settings.smartCleanupEnabled)
            .putInt(KEY_SMART_CLEANUP_KEEP_PREVIOUS, settings.smartCleanupKeepPreviousChapters)
            .apply()
    }

    private fun MangaUiState.clearedReaderState(): MangaUiState {
        return copy(
            readerChapter = null,
            readerPreviousChapter = null,
            readerNextChapter = null,
            readerPages = emptyList(),
            isLoadingReader = false,
        )
    }

    companion object {
        private const val PREFS_NAME = "manga_downloader_prefs"
        private const val KEY_FAVORITES_JSON = "favorites_json"
        private const val KEY_AUTO_DOWNLOAD_ENABLED = "auto_download_enabled"
        private const val KEY_AUTO_DOWNLOAD_TRIGGER = "auto_download_trigger"
        private const val KEY_AUTO_DOWNLOAD_BATCH = "auto_download_batch"
        private const val KEY_SMART_CLEANUP_ENABLED = "smart_cleanup_enabled"
        private const val KEY_SMART_CLEANUP_KEEP_PREVIOUS = "smart_cleanup_keep_previous"
        private const val MIN_QUERY_LENGTH = 3
        private const val DEBOUNCE_MS = 350L
    }
}
