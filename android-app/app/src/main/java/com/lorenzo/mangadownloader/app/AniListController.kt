package com.lorenzo.mangadownloader.app

import com.lorenzo.mangadownloader.data.anilist.AniListAuth
import com.lorenzo.mangadownloader.data.anilist.AniListAuthException
import com.lorenzo.mangadownloader.data.anilist.AniListClient
import com.lorenzo.mangadownloader.data.anilist.AniListFavoritesSyncStore
import com.lorenzo.mangadownloader.data.anilist.AniListListStatus
import com.lorenzo.mangadownloader.data.anilist.AniListManga
import com.lorenzo.mangadownloader.data.anilist.AniListStore
import com.lorenzo.mangadownloader.data.anilist.AniListTracking
import com.lorenzo.mangadownloader.data.anilist.newAniListFavorites
import com.lorenzo.mangadownloader.data.anilist.realAniListFavoritesSynchronizer
import com.lorenzo.mangadownloader.data.library.LibraryRepository
import com.lorenzo.mangadownloader.data.model.ReaderChapter
import com.lorenzo.mangadownloader.data.sources.MangaSourceRegistry
import com.lorenzo.mangadownloader.data.store.FavoritesStore
import com.lorenzo.mangadownloader.data.store.SeriesLinksStore
import com.lorenzo.mangadownloader.domain.series.SeriesIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tracking AniList estratto da [MangaViewModel]: account (login OAuth, scollegamento, sessione
 * scaduta), riconciliazione dei preferiti, matching serie→media e push di stato/progresso a
 * fine capitolo. Condivide il [MutableStateFlow] del ViewModel.
 */
class AniListController(
    private val state: MutableStateFlow<MangaUiState>,
    private val scope: CoroutineScope,
    private val aniListClient: AniListClient,
    private val aniListStore: AniListStore,
    private val aniListFavoritesSyncStore: AniListFavoritesSyncStore,
    private val favoritesStore: FavoritesStore,
    private val seriesLinksStore: SeriesLinksStore,
    private val libraryRepository: LibraryRepository,
    private val sourceRegistry: MangaSourceRegistry,
    /** SeriesKey di (sourceId, mangaUrl, titolo), tenendo conto del link della scheda aperta. */
    private val seriesKeyFor: (sourceId: String, mangaUrl: String, title: String) -> String,
    private val skippedSourceIds: () -> Set<String>,
    /** Preferiti salvati dopo l'arrivo di quelli aggiunti su AniList: li porta nello stato. */
    private val onFavoritesImported: (List<FavoriteManga>) -> Unit,
) {
    private var matchJob: Job? = null
    private var favoritesSyncJob: Job? = null

    private inline fun updateState(transform: MangaUiState.() -> MangaUiState) {
        state.update(transform)
    }

    /** Avvia il login AniList: ricorda lo `state` monouso e restituisce l'URL da aprire. */
    fun beginLogin(): String {
        val loginState = AniListAuth.newLoginState()
        aniListStore.savePendingLoginState(loginState)
        return AniListAuth.authorizationUrl(loginState)
    }

    /**
     * Redirect OAuth dal browser (`mangapp://anilist-auth#access_token=…`): valida il token
     * recuperando il profilo, persiste l'account e riprova eventuali progressi in sospeso.
     */
    fun onAuthRedirect(fragment: String?) {
        // Si accetta solo il redirect che riporta lo `state` dell'ultimo login avviato qui, e
        // una volta sola. Uno `state` sbagliato non consuma quello in attesa: un link estraneo
        // aperto durante un login vero non deve farlo fallire.
        val pendingState = aniListStore.readPendingLoginState()
        if (pendingState == null || AniListAuth.extractState(fragment) != pendingState) {
            updateState { copy(errorMessage = "Collegamento AniList non richiesto dall'app: ignorato") }
            return
        }
        aniListStore.clearPendingLoginState()
        val token = AniListAuth.extractAccessToken(fragment)
        if (token == null) {
            updateState { copy(errorMessage = "Accesso ad AniList annullato o non riuscito") }
            return
        }
        updateState { copy(aniList = aniList.copy(isConnecting = true)) }
        scope.launch {
            try {
                val viewer = withContext(Dispatchers.IO) { aniListClient.fetchViewer(token) }
                aniListStore.persistAccount(token, viewer)
                updateState {
                    copy(
                        aniList = aniList.copy(viewer = viewer, isConnecting = false),
                        errorMessage = "AniList collegato come ${viewer.name}",
                    )
                }
                flushPendingSync()
                syncFavorites()
            } catch (e: CancellationException) {
                throw e
            } catch (_: AniListAuthException) {
                // Il token appena ricevuto e' stato rifiutato: e' un collegamento fallito, non
                // una sessione scaduta. Dirlo con le parole giuste evita il messaggio assurdo
                // "ricollega l'account" subito DOPO aver ricollegato l'account.
                updateState {
                    copy(
                        aniList = aniList.copy(isConnecting = false),
                        errorMessage = "AniList ha rifiutato l'accesso: riprova il collegamento",
                    )
                }
            } catch (exc: Exception) {
                updateState {
                    copy(
                        aniList = aniList.copy(isConnecting = false),
                        errorMessage = exc.message ?: "Impossibile collegare AniList",
                    )
                }
            }
        }
    }

    /**
     * Riallinea lo stato in memoria dell'account con quello persistito. E' esattamente cio' che
     * oggi si ottiene solo chiudendo e riaprendo l'app: chiamandolo al ritorno in primo piano
     * (cioe' al rientro dal browser dell'OAuth) un eventuale disallineamento si risolve da solo.
     * Non tocca nulla mentre un collegamento e' in corso, per non calpestarne l'esito.
     */
    fun syncAccountState() {
        if (state.value.aniList.isConnecting) return
        val viewer = aniListStore.readViewer().takeIf { aniListStore.readToken() != null }
        if (viewer == state.value.aniList.viewer) return
        updateState { copy(aniList = aniList.copy(viewer = viewer)) }
    }

    /**
     * Azione AniList richiesta senza un token valido: lo stato in memoria diceva "collegato"
     * ma su disco non c'e' piu' nulla. Prima era un `?: return` muto, cioe' un tap che non
     * faceva niente e non spiegava niente.
     */
    private fun reportNotConnected() {
        syncAccountState()
        updateState {
            copy(
                aniList = aniList.copy(
                    isSavingEntry = false,
                    match = aniList.match?.copy(isLinking = false),
                ),
                errorMessage = "Account AniList non collegato: ricollegalo dalle impostazioni",
            )
        }
    }

    /** Scollega l'account. I legami serie→media restano per un futuro nuovo login. */
    fun disconnect() {
        aniListStore.clearAccount()
        updateState {
            copy(aniList = aniList.copy(viewer = null, match = null, trackerKey = null))
        }
    }

    /**
     * Riconcilia i preferiti dell'app con i favourites AniList (vedi
     * [planAniListFavoritesSync]). No-op senza account collegato o con il sync disattivato.
     *
     * I nuovi arrivati vengono fusi con i preferiti **correnti**, non con lo snapshot con cui
     * il giro era partito: la riconciliazione dura quanto una ricerca su tutte le fonti, e nel
     * frattempo l'utente può aver toccato altre stelle che non vanno perse.
     */
    fun syncFavorites() {
        if (!state.value.settings.aniListFavoritesSyncEnabled) return
        val token = aniListStore.readToken() ?: return
        val viewerId = (state.value.aniList.viewer ?: aniListStore.readViewer())?.id ?: return
        val snapshot = state.value.favorites
        favoritesSyncJob?.cancel()
        favoritesSyncJob = scope.launch {
            try {
                val synchronizer = realAniListFavoritesSynchronizer(
                    client = aniListClient,
                    token = token,
                    viewerId = viewerId,
                    registry = sourceRegistry,
                    syncStore = aniListFavoritesSyncStore,
                    seriesLinksStore = seriesLinksStore,
                    disabledSourceIds = { state.value.settings.disabledSourceIds },
                    unavailableSourceIds = skippedSourceIds,
                )
                val imported = synchronizer.sync(snapshot)
                updateState {
                    copy(aniListUnmatchedFavorites = aniListFavoritesSyncStore.readUnmatchedFavorites())
                }
                if (imported.isEmpty()) return@launch
                // Il confronto è con i preferiti di ADESSO, non con lo snapshot di partenza:
                // il giro dura quanto una ricerca su tutte le fonti e nel frattempo l'utente
                // può aver aggiunto a mano proprio una di queste serie.
                if (newAniListFavorites(imported, state.value.favorites).isEmpty()) return@launch
                onFavoritesImported(
                    favoritesStore.update { stored -> newAniListFavorites(imported, stored) + stored },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: AniListAuthException) {
                handleAuthError(e.token)
            } catch (_: Exception) {
                // Best-effort: si riprova al prossimo avvio o al prossimo preferito toccato.
            }
        }
    }

    /** Apre il dialog di matching per la serie aperta nel dettaglio, cercandone il titolo. */
    fun openMatch() {
        val selected = state.value.selected ?: return
        val key = state.value.selectedSeriesKey
            ?: seriesKeyFor(selected.sourceId, selected.mangaUrl, selected.title)
        val query = selected.title.trim()
        updateState {
            copy(
                aniList = aniList.copy(
                    match = AniListMatchUiState(identityKey = key, query = query, isLoading = true),
                ),
            )
        }
        runMatchSearch(query)
    }

    fun onMatchQueryChange(query: String) {
        updateState {
            copy(aniList = aniList.copy(match = aniList.match?.copy(query = query)))
        }
    }

    fun submitMatchSearch() {
        val match = state.value.aniList.match ?: return
        val query = match.query.trim()
        if (query.isEmpty()) return
        updateState {
            copy(
                aniList = aniList.copy(
                    match = aniList.match?.copy(isLoading = true, errorMessage = null),
                ),
            )
        }
        runMatchSearch(query)
    }

    fun dismissMatch() {
        matchJob?.cancel()
        updateState { copy(aniList = aniList.copy(match = null)) }
    }

    private fun runMatchSearch(query: String) {
        matchJob?.cancel()
        matchJob = scope.launch {
            try {
                val candidates = withContext(Dispatchers.IO) { aniListClient.searchManga(query) }
                updateState {
                    copy(
                        aniList = aniList.copy(
                            match = aniList.match?.copy(
                                isLoading = false,
                                candidates = candidates,
                                errorMessage = null,
                            ),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (exc: Exception) {
                updateState {
                    copy(
                        aniList = aniList.copy(
                            match = aniList.match?.copy(
                                isLoading = false,
                                errorMessage = exc.message ?: "Ricerca su AniList non riuscita",
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * L'utente ha confermato il candidato: legge dal server capitoli totali ed eventuale entry
     * già esistente (per non azzerare un progresso fatto sul sito) e persiste il legame.
     */
    fun confirmMatch(media: AniListManga) {
        val match = state.value.aniList.match ?: return
        val token = aniListStore.readToken() ?: run {
            reportNotConnected()
            return
        }
        updateState {
            copy(aniList = aniList.copy(match = aniList.match?.copy(isLinking = true)))
        }
        scope.launch {
            try {
                val mediaEntry = withContext(Dispatchers.IO) {
                    aniListClient.fetchMediaEntry(media.id, token)
                }
                // Il legame vive SEMPRE sotto la chiave canonica del media: se il dialog era
                // stato aperto con una chiave title:/legacy, l'eventuale voce vecchia migra.
                val canonicalKey = SeriesIdentity.keyForAniList(media.id)
                if (match.identityKey != canonicalKey) {
                    saveTracking(match.identityKey, null)
                }
                saveTracking(
                    canonicalKey,
                    AniListTracking(
                        mediaId = media.id,
                        title = media.displayTitle(),
                        totalChapters = mediaEntry.totalChapters ?: media.chapters,
                        status = mediaEntry.entry?.status,
                        progress = mediaEntry.entry?.progress ?: 0,
                        score = mediaEntry.entry?.score,
                    ),
                )
                promoteSelectedSeries(media.id)
                updateState { copy(aniList = aniList.copy(match = null)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AniListAuthException) {
                handleAuthError(e.token)
            } catch (exc: Exception) {
                updateState {
                    copy(
                        aniList = aniList.copy(
                            match = aniList.match?.copy(
                                isLinking = false,
                                errorMessage = exc.message ?: "Collegamento non riuscito",
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * L'aggancio AniList è arrivato dopo: promuove il link `title:` della serie corrente a
     * `anilist:<id>` e migra il set dei progressi streaming sulla nuova chiave. La scheda
     * aperta viene riallineata (link + SeriesKey).
     */
    private fun promoteSelectedSeries(aniListId: Int) {
        val canonicalKey = SeriesIdentity.keyForAniList(aniListId)
        val currentKey = state.value.selectedSeriesKey
        if (currentKey != null && currentKey != canonicalKey) {
            libraryRepository.migrateStreamingSeriesKey(currentKey, canonicalKey)
        }
        val link = state.value.selectedSeriesLink
        val promoted = when {
            link == null -> null
            link.seriesKey == canonicalKey -> link
            link.seriesKey.startsWith(SeriesIdentity.TITLE_PREFIX) ->
                seriesLinksStore.promoteToAniList(link.seriesKey, aniListId)
            else -> link
        }
        updateState {
            copy(
                selectedSeriesLink = promoted ?: selectedSeriesLink,
                selectedSeriesKey = canonicalKey,
            )
        }
    }

    /** Apre il dialog del tracker (stato/progresso/voto) per la serie aperta nel dettaglio. */
    fun openTracker() {
        val selected = state.value.selected ?: return
        val key = state.value.selectedSeriesKey
            ?: seriesKeyFor(selected.sourceId, selected.mangaUrl, selected.title)
        if (state.value.aniList.trackings[key] == null) return
        updateState { copy(aniList = aniList.copy(trackerKey = key)) }
    }

    fun dismissTracker() {
        updateState { copy(aniList = aniList.copy(trackerKey = null, isSavingEntry = false)) }
    }

    /** Rimuove il legame della serie col tracker aperto. Non tocca la lista su AniList. */
    fun unlinkTracking() {
        val key = state.value.aniList.trackerKey ?: return
        saveTracking(key, null)
        updateState { copy(aniList = aniList.copy(trackerKey = null)) }
    }

    /** Salvataggio manuale dal dialog del tracker: una sola mutation con i campi modificati. */
    fun saveEntry(status: AniListListStatus?, progress: Int?, score: Double?) {
        val key = state.value.aniList.trackerKey ?: return
        val tracking = state.value.aniList.trackings[key] ?: return
        val token = aniListStore.readToken() ?: run {
            reportNotConnected()
            return
        }
        updateState { copy(aniList = aniList.copy(isSavingEntry = true)) }
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    aniListClient.saveListEntry(
                        token = token,
                        mediaId = tracking.mediaId,
                        status = status,
                        progress = progress,
                        score = score,
                    )
                }
                updateTracking(key) {
                    it.copy(
                        status = saved.status ?: it.status,
                        progress = saved.progress,
                        score = saved.score ?: it.score,
                        pendingProgress = null,
                    )
                }
                updateState { copy(aniList = aniList.copy(trackerKey = null, isSavingEntry = false)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AniListAuthException) {
                handleAuthError(e.token)
            } catch (exc: Exception) {
                updateState {
                    copy(
                        aniList = aniList.copy(isSavingEntry = false),
                        errorMessage = exc.message ?: "Salvataggio su AniList non riuscito",
                    )
                }
            }
        }
    }

    /**
     * Sync automatico a fine capitolo: risale alla serie (scaricata o streaming) e spinge il
     * numero di capitolo come progresso, se la serie è collegata e il sync è attivo.
     */
    fun onChapterRead(chapter: ReaderChapter) {
        val streaming = chapter.streamingChapter
        val downloaded = chapter.downloadedChapter
        val (seriesKey, chapterNumber) = when {
            streaming != null -> seriesKeyFor(
                streaming.sourceId,
                streaming.mangaUrl,
                state.value.selected?.title.orEmpty(),
            ) to streaming.chapter.numberValue
            downloaded != null -> {
                val series = sequenceOf(state.value.selectedDownloadedSeries)
                    .filterNotNull()
                    .plus(state.value.library)
                    .firstOrNull { s -> s.chapters.any { it.relativePath == chapter.relativePath } }
                    ?: return
                val url = series.mangaUrl?.takeIf(String::isNotBlank) ?: return
                seriesKeyFor(series.sourceId, url, series.title) to downloaded.numberValue
            }
            else -> return
        }
        val progress = chapterNumber?.toInt()?.takeIf { it > 0 } ?: return
        pushProgress(seriesKey, progress)
    }

    /**
     * Spinge [progress] su AniList per la serie [identityKey]. Mai regressioni: se il server
     * (o un pending) è già più avanti non invia nulla. A capitolo finale raggiunto marca
     * COMPLETED; le entry già COMPLETED non vengono toccate (rilettura ≠ nuovo progresso).
     * In caso di errore di rete il progresso resta in [AniListTracking.pendingProgress].
     */
    private fun pushProgress(identityKey: String, progress: Int) {
        if (!state.value.settings.aniListSyncEnabled) return
        val token = aniListStore.readToken() ?: run {
            // Push automatico: nessun messaggio all'utente, ma se la UI si crede ancora
            // collegata va rimessa in pari.
            syncAccountState()
            return
        }
        val tracking = state.value.aniList.trackings[identityKey] ?: return
        if (tracking.status == AniListListStatus.COMPLETED) return
        val target = maxOf(progress, tracking.pendingProgress ?: 0)
        if (target <= tracking.progress) {
            if (tracking.pendingProgress != null) {
                updateTracking(identityKey) { it.copy(pendingProgress = null) }
            }
            return
        }

        val completed = tracking.totalChapters?.let { target >= it } == true
        val newStatus = when {
            completed -> AniListListStatus.COMPLETED
            tracking.status == AniListListStatus.REPEATING -> null
            else -> AniListListStatus.CURRENT
        }
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    aniListClient.saveListEntry(
                        token = token,
                        mediaId = tracking.mediaId,
                        status = newStatus,
                        progress = target,
                    )
                }
                updateTracking(identityKey) {
                    it.copy(
                        status = saved.status ?: it.status,
                        progress = saved.progress,
                        pendingProgress = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AniListAuthException) {
                handleAuthError(e.token)
            } catch (_: Exception) {
                // Offline o errore transitorio: si riprova all'avvio o al prossimo capitolo.
                updateTracking(identityKey) { it.copy(pendingProgress = target) }
            }
        }
    }

    fun flushPendingSync() {
        val pending = state.value.aniList.trackings.filterValues { it.pendingProgress != null }
        pending.forEach { (key, tracking) ->
            pushProgress(key, tracking.pendingProgress ?: return@forEach)
        }
    }

    private fun saveTracking(identityKey: String, tracking: AniListTracking?) {
        val current = state.value.aniList.trackings
        val updated = if (tracking == null) current - identityKey else current + (identityKey to tracking)
        aniListStore.persistTrackings(updated)
        updateState { copy(aniList = aniList.copy(trackings = updated)) }
    }

    private fun updateTracking(
        identityKey: String,
        transform: (AniListTracking) -> AniListTracking,
    ) {
        val current = state.value.aniList.trackings[identityKey] ?: return
        saveTracking(identityKey, transform(current))
    }

    /**
     * Token rifiutato dal server: account scollegato, l'utente deve riautorizzare.
     *
     * [failedToken] e' il token con cui la richiesta era partita. Se nel frattempo l'utente si
     * e' gia' ricollegato, il token corrente e' un altro e questo 401 riguarda una sessione
     * ormai chiusa: ignorarlo e' l'unico modo perche' un login appena fatto sopravviva alle
     * risposte in ritardo di quella precedente.
     */
    private fun handleAuthError(failedToken: String?) {
        val currentToken = aniListStore.readToken()
        if (failedToken != null && currentToken != null && failedToken != currentToken) return
        aniListStore.clearAccount()
        updateState {
            copy(
                aniList = aniList.copy(
                    viewer = null,
                    match = null,
                    trackerKey = null,
                    isSavingEntry = false,
                ),
                errorMessage = "Sessione AniList scaduta: ricollega l'account dalle impostazioni",
            )
        }
    }
}
