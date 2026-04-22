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

enum class AppTab {
    SEARCH,
    DOWNLOADS,
}

data class MangaUiState(
    val currentTab: AppTab = AppTab.SEARCH,
    val query: String = "",
    val results: List<MangaSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val selected: MangaDetails? = null,
    val isLoadingDetails: Boolean = false,
    val library: List<DownloadedSeries> = emptyList(),
    val isLoadingLibrary: Boolean = false,
    val selectedDownloadedSeries: DownloadedSeries? = null,
    val selectedChapterPaths: Set<String> = emptySet(),
    val readerChapter: DownloadedChapter? = null,
    val readerPreviousChapter: DownloadedChapter? = null,
    val readerNextChapter: DownloadedChapter? = null,
    val readerPages: List<File> = emptyList(),
    val isLoadingReader: Boolean = false,
    val availableUpdate: AppUpdateInfo? = null,
    val isCheckingUpdate: Boolean = false,
    val isInstallingUpdate: Boolean = false,
    val errorMessage: String? = null,
)

class MangaViewModel(application: Application) : AndroidViewModel(application) {

    private val client = MangapillClient(application)
    private val libraryRepository = LibraryRepository(application)
    private val appUpdateRepository = AppUpdateRepository(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        MangaUiState(query = prefs.getString(KEY_LAST_QUERY, "").orEmpty()),
    )
    val state: StateFlow<MangaUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var libraryJob: Job? = null
    private var readerJob: Job? = null
    private var updateJob: Job? = null

    init {
        observeQueryChanges()
        refreshLibrary()
        if (_state.value.query.trim().length >= MIN_QUERY_LENGTH) {
            runSearch(_state.value.query.trim())
        }
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
                            _state.value = _state.value.copy(
                                results = emptyList(),
                                isSearching = false,
                                errorMessage = null,
                            )
                        }
                        q.length >= MIN_QUERY_LENGTH -> runSearch(q)
                        else -> {
                            searchJob?.cancel()
                            _state.value = _state.value.copy(
                                results = emptyList(),
                                isSearching = false,
                            )
                        }
                    }
                }
        }
    }

    fun onQueryChange(text: String) {
        _state.value = _state.value.copy(query = text)
    }

    fun submitSearch() {
        val q = _state.value.query.trim()
        if (q.length >= MIN_QUERY_LENGTH) {
            runSearch(q)
        }
    }

    fun selectTab(tab: AppTab) {
        _state.value = _state.value.copy(currentTab = tab)
        if (tab == AppTab.DOWNLOADS) {
            refreshLibrary()
        }
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

    fun refreshLibrary() {
        libraryJob?.cancel()
        _state.value = _state.value.copy(isLoadingLibrary = true)
        libraryJob = viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { libraryRepository.scanLibrary() }
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
        _state.value = _state.value.copy(
            selectedDownloadedSeries = series,
            selectedChapterPaths = emptySet(),
            currentTab = AppTab.DOWNLOADS,
            errorMessage = null,
        )
    }

    fun clearDownloadedSelection() {
        readerJob?.cancel()
        _state.value = _state.value.copy(
            selectedDownloadedSeries = null,
            selectedChapterPaths = emptySet(),
            readerChapter = null,
            readerPreviousChapter = null,
            readerNextChapter = null,
            readerPages = emptyList(),
            isLoadingReader = false,
            errorMessage = null,
        )
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
    }

    fun closeReader() {
        readerJob?.cancel()
        _state.value = _state.value.copy(
            readerChapter = null,
            readerPreviousChapter = null,
            readerNextChapter = null,
            readerPages = emptyList(),
            isLoadingReader = false,
        )
    }

    fun dismissError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    fun toggleChapterSelection(chapter: DownloadedChapter) {
        val current = _state.value.selectedChapterPaths.toMutableSet()
        if (!current.add(chapter.relativePath)) {
            current.remove(chapter.relativePath)
        }
        _state.value = _state.value.copy(selectedChapterPaths = current)
    }

    fun startChapterSelection(chapter: DownloadedChapter) {
        val current = _state.value.selectedChapterPaths
        _state.value = _state.value.copy(
            selectedChapterPaths = if (chapter.relativePath in current) current else current + chapter.relativePath,
        )
    }

    fun clearChapterSelection() {
        _state.value = _state.value.copy(selectedChapterPaths = emptySet())
    }

    fun setAllChaptersSelected(selected: Boolean) {
        val series = _state.value.selectedDownloadedSeries ?: return
        _state.value = _state.value.copy(
            selectedChapterPaths = if (selected) {
                series.chapters.mapTo(linkedSetOf()) { it.relativePath }
            } else {
                emptySet()
            },
        )
    }

    fun deleteSelectedChapters() {
        val currentState = _state.value
        val series = currentState.selectedDownloadedSeries ?: return
        val selectedPaths = currentState.selectedChapterPaths
        if (selectedPaths.isEmpty()) {
            return
        }

        libraryJob?.cancel()
        _state.value = _state.value.copy(isLoadingLibrary = true, errorMessage = null)
        libraryJob = viewModelScope.launch {
            try {
                val chaptersToDelete = series.chapters.filter { it.relativePath in selectedPaths }
                withContext(Dispatchers.IO) {
                    libraryRepository.deleteChapters(series, chaptersToDelete)
                }
                val snapshot = withContext(Dispatchers.IO) { libraryRepository.scanLibrary() }
                _state.value = _state.value.copy(selectedChapterPaths = emptySet())
                    .withLibrarySnapshot(snapshot)
                    .copy(isLoadingLibrary = false)
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                _state.value = _state.value.copy(
                    isLoadingLibrary = false,
                    errorMessage = exc.message ?: "Errore eliminazione capitoli",
                )
            }
        }
    }

    fun deleteDownloadedSeries() {
        val series = _state.value.selectedDownloadedSeries ?: return

        libraryJob?.cancel()
        readerJob?.cancel()
        _state.value = _state.value.copy(isLoadingLibrary = true, errorMessage = null)
        libraryJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    libraryRepository.deleteSeries(series)
                }
                val snapshot = withContext(Dispatchers.IO) { libraryRepository.scanLibrary() }
                _state.value = _state.value.copy(
                    selectedChapterPaths = emptySet(),
                    readerChapter = null,
                    readerPreviousChapter = null,
                    readerNextChapter = null,
                    readerPages = emptyList(),
                    isLoadingReader = false,
                ).withLibrarySnapshot(snapshot)
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
        _state.value = _state.value.copy(availableUpdate = null)
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
        prefs.edit().putString(KEY_LAST_QUERY, q).apply()
        searchJob?.cancel()
        _state.value = _state.value.copy(isSearching = true, errorMessage = null)
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
        val updatedSelectedPaths = selectedChapterPaths.filterTo(linkedSetOf()) { relativePath ->
            updatedSelected?.chapters?.any { it.relativePath == relativePath } == true
        }
        val readerPath = readerChapter?.relativePath
        val updatedReader = updatedSelected?.chapters?.firstOrNull { it.relativePath == readerPath }
            ?: snapshot.asSequence()
                .flatMap { it.chapters.asSequence() }
                .firstOrNull { it.relativePath == readerPath }

        return copy(
            library = snapshot,
            selectedDownloadedSeries = updatedSelected,
            selectedChapterPaths = updatedSelectedPaths,
            readerChapter = updatedReader ?: readerChapter,
        ).withReaderAdjacency((updatedReader ?: readerChapter)?.relativePath)
    }

    private fun MangaUiState.withReadChapter(relativePath: String): MangaUiState {
        fun DownloadedChapter.markIfSame(): DownloadedChapter {
            return if (this.relativePath == relativePath) copy(isRead = true) else this
        }

        val updatedLibrary = library.map { series ->
            series.copy(chapters = series.chapters.map { chapter -> chapter.markIfSame() })
        }
        val updatedSelected = selectedDownloadedSeries?.copy(
            chapters = selectedDownloadedSeries.chapters.map { chapter -> chapter.markIfSame() },
        )
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

    companion object {
        private const val PREFS_NAME = "manga_downloader_prefs"
        private const val KEY_LAST_QUERY = "last_query"
        private const val MIN_QUERY_LENGTH = 3
        private const val DEBOUNCE_MS = 350L
    }
}
