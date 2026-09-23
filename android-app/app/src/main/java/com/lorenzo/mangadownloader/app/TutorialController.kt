package com.lorenzo.mangadownloader.app

import android.content.Context
import com.lorenzo.mangadownloader.DownloadWorker
import com.lorenzo.mangadownloader.data.library.LibraryRepository
import com.lorenzo.mangadownloader.data.model.matchKeys
import com.lorenzo.mangadownloader.data.sources.MangaSourceCatalog
import com.lorenzo.mangadownloader.data.sources.MangaSourceIds
import com.lorenzo.mangadownloader.data.sources.MangaSourceRegistry
import com.lorenzo.mangadownloader.data.store.FavoritesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TutorialPhase {
    Idle,
    Welcome,
    Preloading,
    AwaitingSearchBar,
    AwaitingResultTap,
    AwaitingFavorite,
    AwaitingDownload,
    AwaitingFavoritesTab,
    AwaitingLibraryTab,
    AwaitingSeriesTap,
    AwaitingChapterTap,
    InReader,
    AwaitingOverflow,
    Closing,
    FallbackShowcase,
    FallbackClosing,
}

data class TutorialSample(
    val sourceId: String,
    val mangaUrl: String,
    val title: String,
    val coverUrl: String?,
    val chapterUrl: String,
)

data class TutorialUiState(
    val phase: TutorialPhase = TutorialPhase.Idle,
    val sample: TutorialSample? = null,
)

/**
 * Tutorial guidato estratto da [MangaViewModel]: benvenuto, preload del manga di esempio
 * (ricerca + download del primo capitolo), avanzamento delle fasi, fallback senza rete e
 * pulizia dell'esempio a fine percorso. Condivide il [MutableStateFlow] del ViewModel.
 */
class TutorialController(
    private val state: MutableStateFlow<MangaUiState>,
    private val scope: CoroutineScope,
    private val context: Context,
    private val sourceRegistry: MangaSourceRegistry,
    private val favoritesStore: FavoritesStore,
    private val libraryRepository: LibraryRepository,
    private val updateSettings: ((AppSettings) -> AppSettings) -> Unit,
    private val refreshLibrary: (forceRefresh: Boolean) -> Unit,
) {
    private inline fun updateState(transform: MangaUiState.() -> MangaUiState) {
        state.update(transform)
    }

    fun markCompleted() {
        updateSettings { it.copy(tutorialCompleted = true) }
        updateState { copy(tutorialState = TutorialUiState(phase = TutorialPhase.Idle)) }
    }

    fun onWelcomeStart() {
        if (state.value.tutorialState.phase != TutorialPhase.Welcome) return
        updateState {
            copy(tutorialState = tutorialState.copy(phase = TutorialPhase.Preloading))
        }
        runPreload()
    }

    fun onWelcomeSkip() {
        markCompleted()
    }

    /** Chiude il percorso di fallback del tutorial, segnandolo come completato (permanente). */
    fun onFallbackCompleted() {
        markCompleted()
    }

    fun onFinish(keepSample: Boolean) {
        val sample = state.value.tutorialState.sample
        if (!keepSample && sample != null) {
            cleanupSample(sample)
        }
        markCompleted()
    }

    fun advancePhase(from: TutorialPhase, to: TutorialPhase) {
        val current = state.value.tutorialState.phase
        if (current != from) return
        updateState {
            copy(tutorialState = tutorialState.copy(phase = to))
        }
    }

    private fun runPreload() {
        scope.launch {
            try {
                // Fonte deterministica: `searchSourceId` sopravvive soltanto per migrare i dati
                // delle versioni che permettevano la ricerca su una singola fonte.
                val sourceId = MangaSourceIds.DEFAULT
                val source = sourceRegistry.requireById(sourceId)
                val results = withContext(Dispatchers.IO) { source.searchManga("One Piece") }
                val match = results.firstOrNull { it.title.contains("One Piece", ignoreCase = true) }
                    ?: results.firstOrNull()
                    ?: throw NoSuchElementException("Nessun risultato")
                val details = withContext(Dispatchers.IO) {
                    source.fetchMangaDetails(match.mangaUrl)
                }
                val chapter = details.chapters.firstOrNull()
                    ?: throw NoSuchElementException("Nessun capitolo")
                val sample = TutorialSample(
                    sourceId = match.sourceId,
                    mangaUrl = match.mangaUrl,
                    title = match.title,
                    coverUrl = match.coverUrl,
                    chapterUrl = chapter.url,
                )
                DownloadWorker.enqueue(
                    context = context,
                    firstUrl = chapter.url,
                    lastUrl = chapter.url,
                    sourceId = match.sourceId,
                    seriesTitle = match.title,
                    mangaUrl = match.mangaUrl,
                    coverUrl = match.coverUrl,
                )
                updateState {
                    copy(
                        query = "One Piece",
                        results = results,
                        isSearching = false,
                        tutorialState = tutorialState.copy(
                            phase = TutorialPhase.AwaitingSearchBar,
                            sample = sample,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateState {
                    copy(
                        tutorialState = tutorialState.copy(
                            phase = TutorialPhase.FallbackShowcase,
                        ),
                    )
                }
            }
        }
    }

    private fun cleanupSample(sample: TutorialSample) {
        val targetKey = MangaSourceCatalog.identityKey(sample.sourceId, sample.mangaUrl)
        val current = state.value.favorites.toMutableList()
        val removed = current.removeAll {
            MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl) == targetKey
        }
        if (removed) {
            favoritesStore.persist(current)
            updateState {
                copy(
                    favorites = current,
                    favoriteSeriesKeys = current.flatMapTo(linkedSetOf()) { it.matchKeys() },
                )
            }
        }
        scope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    libraryRepository.scanLibrary(forceRefresh = true)
                }
                val series = snapshot.firstOrNull {
                    MangaSourceCatalog.identityKey(it.sourceId, it.mangaUrl ?: "") == targetKey ||
                        it.title.equals(sample.title, ignoreCase = true)
                } ?: return@launch
                withContext(Dispatchers.IO) {
                    libraryRepository.deleteSeries(series)
                }
                refreshLibrary(true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silent — cleanup is best-effort.
            }
        }
    }

    /**
     * Rilancia il tutorial dall'inizio (usato da "Rivedi il tutorial" in Impostazioni). Riporta
     * anche su HOME: la card di benvenuto vive nella Home, quindi senza cambiare tab l'azione
     * sarebbe un no-op dalle altre schermate.
     */
    fun restart() {
        updateSettings { it.copy(tutorialCompleted = false, showHomeTab = true) }
        updateState {
            copy(
                showSettings = false,
                currentTab = AppTab.HOME,
                tutorialState = TutorialUiState(phase = TutorialPhase.Welcome),
            )
        }
    }
}
