package com.lorenzo.mangadownloader.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.biometric.BiometricManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.lorenzo.mangadownloader.BuildConfig
import com.lorenzo.mangadownloader.DownloadWorker
import com.lorenzo.mangadownloader.FavoriteUpdatesScheduler
import com.lorenzo.mangadownloader.data.anilist.AniListClient
import com.lorenzo.mangadownloader.data.library.StreamingReaderCacheRepository
import com.lorenzo.mangadownloader.data.network.MangaNetworkClient
import com.lorenzo.mangadownloader.data.network.SharedHttpClient
import com.lorenzo.mangadownloader.data.store.SettingsStore
import com.lorenzo.mangadownloader.data.update.AppUpdateInfo
import com.lorenzo.mangadownloader.data.update.AppUpdateInstaller
import com.lorenzo.mangadownloader.data.update.AppUpdateRepository
import com.lorenzo.mangadownloader.platform.settings
import com.lorenzo.mangadownloader.sharedLibraryRepository
import com.lorenzo.mangadownloader.sharedSourceRegistry
import com.lorenzo.mangadownloader.ui.widget.ReadingWidget
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okio.IOException

/** Il [MangaViewModel] con i servizi Android dell'app: WorkManager, Coil, widget, APK da GitHub. */
fun MangaViewModel(
    application: Application,
    appUpdateRepository: AppUpdateRepository = AppUpdateRepository(application),
): MangaViewModel = MangaViewModel(androidAppContainer(application, appUpdateRepository))

/** Factory predefinita dell'Activity: `viewModel()` e `ViewModelProvider` passano da qui. */
val MangaViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
    initializer {
        MangaViewModel(checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]))
    }
}

fun androidAppContainer(
    application: Application,
    appUpdateRepository: AppUpdateRepository = AppUpdateRepository(application),
): AppContainer = AppContainer(
    settings = application.settings(SettingsStore.PREFS_NAME),
    sourceRegistry = sharedSourceRegistry(application),
    libraryRepository = sharedLibraryRepository(application),
    aniListClient = AniListClient(SharedHttpClient.ktor(application)),
    streamingCacheRepository = StreamingReaderCacheRepository(
        context = application,
        networkClient = MangaNetworkClient(SharedHttpClient.ktor(application)),
        // Le pagine dello streaming reader vengono già scaricate da Coil per mostrarle: se
        // sono nella sua disk-cache le copiamo invece di riscaricarle, così una pagina letta
        // non viaggia sulla rete due volte solo per finire in cache offline.
        reusablePageCopier = { url, target -> copyCoilCachedPage(application, url, target) },
    ),
    buildInfo = AppBuildInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
    downloadScheduler = WorkManagerDownloadScheduler(application),
    favoriteUpdatesScheduling = object : FavoriteUpdatesScheduling {
        override fun onAppStart() = FavoriteUpdatesScheduler.onAppStart(application)

        override fun setEnabled(enabled: Boolean) = FavoriteUpdatesScheduler.setEnabled(application, enabled)
    },
    imagePrefetcher = { urls, referer -> warmCoilCache(application, urls, referer) },
    refreshWidgets = { ReadingWidget.updateAll(application) },
    isBiometricAvailable = {
        BiometricManager.from(application)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    },
    appUpdater = ApkAppUpdater(application, appUpdateRepository),
)

/** La coda download su WorkManager: una catena per serie, vedi [DownloadWorker]. */
private class WorkManagerDownloadScheduler(private val context: Context) : DownloadScheduler {
    override fun enqueue(request: ChapterDownloadRequest) {
        DownloadWorker.enqueue(
            context = context,
            firstUrl = request.firstUrl,
            lastUrl = request.lastUrl,
            sourceId = request.sourceId,
            seriesTitle = request.seriesTitle,
            mangaUrl = request.mangaUrl,
            coverUrl = request.coverUrl,
        )
    }

    override val jobs: Flow<List<DownloadJob>> by lazy {
        DownloadWorker.observeAll(WorkManager.getInstance(context))
            .map { infos -> infos.map(WorkInfo::toDownloadJob) }
    }

    override fun stopAll() = DownloadWorker.stopAll(context)

    override fun stop(jobIds: Collection<String>) =
        DownloadWorker.stopWork(context, jobIds.map(UUID::fromString))
}

/**
 * Una richiesta WorkManager vista dalla UI. Ogni campo si legge prima dall'esito (download
 * concluso), poi dal progresso e infine dai tag messi all'accodamento: titolo, serie e
 * copertina ci sono da subito, i capitoli solo quando il worker parte.
 */
internal fun WorkInfo.toDownloadJob(): DownloadJob {
    fun field(key: String) = outputData.getString(key)?.takeIf(String::isNotBlank)
        ?: progress.getString(key)?.takeIf(String::isNotBlank)
    fun tag(prefix: String) = tags.firstOrNull { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.takeIf(String::isNotBlank)
    fun count(key: String) = progress.getInt(key, -1).takeIf { it >= 0 } ?: outputData.getInt(key, -1)
    return DownloadJob(
        id = id.toString(),
        state = when (state) {
            WorkInfo.State.ENQUEUED -> DownloadJobState.ENQUEUED
            WorkInfo.State.RUNNING -> DownloadJobState.RUNNING
            WorkInfo.State.BLOCKED -> DownloadJobState.BLOCKED
            WorkInfo.State.SUCCEEDED -> DownloadJobState.SUCCEEDED
            WorkInfo.State.FAILED -> DownloadJobState.FAILED
            WorkInfo.State.CANCELLED -> DownloadJobState.CANCELLED
        },
        waitingForTurn = progress.getBoolean(DownloadWorker.PROGRESS_WAITING, false),
        sourceId = field(DownloadWorker.PROGRESS_SOURCE_ID) ?: tag(DownloadWorker.TAG_SOURCE_ID_PREFIX),
        seriesTitle = field(DownloadWorker.PROGRESS_SERIES_TITLE) ?: tag(DownloadWorker.TAG_SERIES_TITLE_PREFIX),
        mangaUrl = field(DownloadWorker.PROGRESS_MANGA_URL) ?: tag(DownloadWorker.TAG_MANGA_URL_PREFIX),
        coverUrl = tag(DownloadWorker.TAG_COVER_URL_PREFIX),
        message = field(DownloadWorker.PROGRESS_MESSAGE),
        doneChapters = count(DownloadWorker.PROGRESS_DONE_CHAPTERS),
        totalChapters = count(DownloadWorker.PROGRESS_TOTAL_CHAPTERS),
        firstUrl = field(DownloadWorker.PROGRESS_FIRST_URL),
        lastUrl = field(DownloadWorker.PROGRESS_LAST_URL),
    )
}

/** Aggiornamento via APK pubblicato nelle release GitHub. */
private class ApkAppUpdater(
    private val context: Context,
    private val repository: AppUpdateRepository,
) : AppUpdater {
    override suspend fun checkForUpdate(includePreview: Boolean): AppUpdateInfo? =
        repository.checkForUpdate(includePreview = includePreview)

    override suspend fun install(update: AppUpdateInfo): Boolean {
        if (!AppUpdateInstaller.canInstallPackages(context)) {
            AppUpdateInstaller.openInstallPermissionSettings(context)
            return false
        }
        val apkFile = repository.downloadUpdateApk(update)
        AppUpdateInstaller.installApk(context, apkFile)
        return true
    }
}

/** Il documento scelto col selettore di sistema (Storage Access Framework). */
class UriBackupDocument(
    private val context: Context,
    private val uri: Uri,
) : BackupDocument {
    override suspend fun writeText(text: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("Stream di output nullo")
    }

    override suspend fun readText(): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Stream di input nullo")
    }
}

/**
 * Mette in coda a Coil le immagini indicate, così quando toccherà mostrarle saranno già
 * scaricate. La chiave di memoria può differire da quella che userà il reader (le pagine
 * doppie applicano trasformazioni), ma la cache su disco è per URL: il viaggio in rete si
 * risparmia comunque, che è la parte lenta.
 */
private fun warmCoilCache(context: Context, urls: List<String>, referer: String) {
    val headers = NetworkHeaders.Builder().set("Referer", referer).build()
    urls.forEach { url ->
        context.imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(url)
                .httpHeaders(headers)
                .build(),
        )
    }
}

/**
 * Copia su [target] i byte dell'immagine [url] dalla disk-cache di Coil, se presente (Coil
 * l'ha scaricata per mostrarla nel reader in streaming). Ritorna true se ha copiato. La
 * chiave della cache di Coil, con `ImageRequest.data(url)` senza diskCacheKey custom, è
 * l'URL grezzo. La copia avviene mentre lo snapshot è aperto, perché Coil può poi
 * rimuovere/rimpiazzare il file. Best-effort: qualsiasi errore ⇒ false (si riscarica).
 */
@OptIn(coil3.annotation.ExperimentalCoilApi::class)
private fun copyCoilCachedPage(context: Context, url: String, target: okio.Path): Boolean {
    return try {
        val diskCache = context.imageLoader.diskCache ?: return false
        diskCache.openSnapshot(url)?.use { snapshot ->
            val source = snapshot.data.toFile()
            if (source.isFile && source.length() > 0L) {
                target.toFile().outputStream().buffered().use { output ->
                    source.inputStream().buffered().use { it.copyTo(output) }
                }
                true
            } else {
                false
            }
        } ?: false
    } catch (_: Exception) {
        target.toFile().delete()
        false
    }
}
