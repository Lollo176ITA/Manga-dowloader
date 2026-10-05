package com.lorenzo.mangadownloader.app

import com.lorenzo.mangadownloader.data.anilist.AniListClient
import com.lorenzo.mangadownloader.data.library.LibraryRepository
import com.lorenzo.mangadownloader.data.library.StreamingReaderCacheRepository
import com.lorenzo.mangadownloader.data.sources.MangaSourceRegistry
import com.lorenzo.mangadownloader.data.update.AppUpdateInfo
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Tutto ciò che il [MangaViewModel] riceve dalla piattaforma: repository già costruiti sulle
 * cartelle e sulle preferenze del dispositivo, più i servizi che esistono solo lì (download in
 * background, notifiche, widget, aggiornamento dell'APK). Lo costruisce una funzione per
 * piattaforma; nessun framework di DI.
 *
 * I servizi nullable mancano su qualche piattaforma: `null` significa "funzione assente" e la UI
 * nasconde le voci relative.
 */
class AppContainer(
    /** Le preferenze principali (`SettingsStore.PREFS_NAME`). */
    val settings: Settings,
    val sourceRegistry: MangaSourceRegistry,
    val libraryRepository: LibraryRepository,
    val aniListClient: AniListClient,
    val streamingCacheRepository: StreamingReaderCacheRepository,
    val buildInfo: AppBuildInfo,
    val downloadScheduler: DownloadScheduler,
    val favoriteUpdatesScheduling: FavoriteUpdatesScheduling,
    val imagePrefetcher: ImagePrefetcher,
    /** Ridisegna i widget della schermata Home dopo che la memoria di lettura è su disco. */
    val refreshWidgets: (suspend () -> Unit)? = null,
    /** Il dispositivo ha uno sblocco biometrico utilizzabile per il controllo parentale. */
    val isBiometricAvailable: () -> Boolean = { false },
    val appUpdater: AppUpdater? = null,
)

/** Versione dell'app installata. */
data class AppBuildInfo(
    val versionName: String,
    val versionCode: Int,
)

/** Un intervallo di capitoli da scaricare, con quanto serve a mostrarlo nella coda. */
@Serializable
data class ChapterDownloadRequest(
    val firstUrl: String,
    val lastUrl: String? = null,
    val sourceId: String? = null,
    val seriesTitle: String? = null,
    val mangaUrl: String? = null,
    val coverUrl: String? = null,
    /** Solo un gesto esplicito dell'utente può richiedere BGContinuedProcessingTask su iOS. */
    val userInitiated: Boolean = true,
)

/** La coda dei download della piattaforma, che li porta a termine anche ad app chiusa. */
interface DownloadScheduler {
    fun enqueue(request: ChapterDownloadRequest)

    /** Tutte le richieste note alla piattaforma, attive e concluse, aggiornate a ogni progresso. */
    val jobs: Flow<List<DownloadJob>>

    fun stopAll()

    fun stop(jobIds: Collection<String>)
}

enum class DownloadJobState { ENQUEUED, RUNNING, BLOCKED, SUCCEEDED, FAILED, CANCELLED }

/**
 * Una richiesta della coda download vista dalla UI. I campi testuali sono `null` finché la
 * piattaforma non li conosce (il titolo arriva subito, i capitoli solo quando parte).
 */
data class DownloadJob(
    val id: String,
    val state: DownloadJobState,
    /** In esecuzione ma ferma ad aspettare che finisca il download di un'altra serie. */
    val waitingForTurn: Boolean = false,
    val sourceId: String? = null,
    val seriesTitle: String? = null,
    val mangaUrl: String? = null,
    val coverUrl: String? = null,
    val message: String? = null,
    val doneChapters: Int = -1,
    val totalChapters: Int = -1,
    /** L'intervallo richiesto, conservato per "Riprova" quando il download fallisce. */
    val firstUrl: String? = null,
    val lastUrl: String? = null,
) {
    val isActive: Boolean
        get() = state == DownloadJobState.RUNNING || state == DownloadJobState.ENQUEUED ||
            state == DownloadJobState.BLOCKED

    val isTerminal: Boolean get() = !isActive
}

/** Il controllo periodico dei nuovi capitoli dei preferiti. */
interface FavoriteUpdatesScheduling {
    /** All'avvio dell'app, con le notifiche attive: (ri)programma il controllo se serve. */
    fun onAppStart()

    fun setEnabled(enabled: Boolean)
}

/** Scarica in anticipo nella cache immagini le pagine che il reader mostrerà a breve. */
fun interface ImagePrefetcher {
    fun prefetch(urls: List<String>, referer: String)
}

/** Aggiornamento dell'app fuori dagli store (APK da GitHub su Android). */
interface AppUpdater {
    /** La versione più recente se è più nuova di quella installata, altrimenti `null`. */
    suspend fun checkForUpdate(includePreview: Boolean): AppUpdateInfo?

    /**
     * Scarica e avvia l'installazione di [update]. `false` se prima l'utente deve concedere
     * il permesso di installare (la piattaforma ha già aperto la schermata giusta).
     */
    suspend fun install(update: AppUpdateInfo): Boolean
}

/** Il documento scelto dall'utente per esportare o importare un backup. */
interface BackupDocument {
    suspend fun writeText(text: String)

    suspend fun readText(): String
}
