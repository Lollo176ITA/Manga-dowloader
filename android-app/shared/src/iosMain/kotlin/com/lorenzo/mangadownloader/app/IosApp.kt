package com.lorenzo.mangadownloader.app

import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import com.lorenzo.mangadownloader.data.anilist.AniListAuth
import com.lorenzo.mangadownloader.data.anilist.AniListClient
import com.lorenzo.mangadownloader.data.download.ChapterDownloader
import com.lorenzo.mangadownloader.data.library.AppPaths
import com.lorenzo.mangadownloader.data.library.LibraryRepository
import com.lorenzo.mangadownloader.data.library.StreamingReaderCacheRepository
import com.lorenzo.mangadownloader.data.network.MangaNetworkClient
import com.lorenzo.mangadownloader.data.sources.MangaSourceRegistry
import com.lorenzo.mangadownloader.data.store.FavoriteUpdateEvent
import com.lorenzo.mangadownloader.data.store.SettingsStore
import com.lorenzo.mangadownloader.domain.updates.FavoriteUpdatesChecker
import com.lorenzo.mangadownloader.domain.updates.NewChapterNotifier
import com.lorenzo.mangadownloader.platform.IosImageOps
import com.lorenzo.mangadownloader.platform.systemFileSystem
import com.lorenzo.mangadownloader.ui.App
import com.lorenzo.mangadownloader.ui.IosPlatformHost
import com.lorenzo.mangadownloader.ui.IosPlatformServices
import com.lorenzo.mangadownloader.ui.reader.TallPageNormalizer
import com.russhwolf.settings.NSUserDefaultsSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import okio.use
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController

/** Una sola istanza per processo: sopravvive alle transizioni UIKit e al ritorno dal browser. */
@OptIn(coil3.annotation.ExperimentalCoilApi::class)
class IosApp(private val services: IosPlatformServices, versionName: String, versionCode: Int) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults)
    private val paths = AppPaths({ services.documentsDirectory.toPath() }, services.cacheDirectory.toPath())
    private val httpClient = HttpClient(Darwin) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 45_000
            requestTimeoutMillis = 90_000
        }
    }
    private val network = MangaNetworkClient(httpClient)
    private val library = LibraryRepository(paths, prefs)
    private val sources = MangaSourceRegistry(prefs, network, library)
    private val aniList = AniListClient(httpClient)
    private val scheduler = ForegroundDownloadScheduler(scope) { request, progress ->
        ChapterDownloader(sources::resolve).download(request, isStopped = { false }, onStatus = progress)
        library.invalidateCache()
    }
    private val updates = IosFavoriteUpdatesScheduling(scope, prefs, sources, aniList, services)
    private val host = IosPlatformHost(services)
    private val viewModel: MangaViewModel

    init {
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context).components { add(KtorNetworkFetcherFactory(httpClient)) }.build()
        }
        val normalizer = TallPageNormalizer(IosImageOps)
        viewModel = MangaViewModel(AppContainer(
            settings = prefs,
            sourceRegistry = sources,
            libraryRepository = library,
            aniListClient = aniList,
            streamingCacheRepository = StreamingReaderCacheRepository(
                cacheRoot = paths.cacheRoot / StreamingReaderCacheRepository.CACHE_DIR_NAME,
                fetchPageToFile = { url, referer, target ->
                    systemFileSystem.sink(target).use { network.fetchToSink(url, it, referer) }
                },
                normalizePage = { source, directory, name -> normalizer.normalize(source, directory, name).files },
            ),
            buildInfo = AppBuildInfo(versionName, versionCode),
            downloadScheduler = scheduler,
            favoriteUpdatesScheduling = updates,
            imagePrefetcher = { urls, referer ->
                val context = PlatformContext.INSTANCE
                val loader = SingletonImageLoader.get(context)
                val headers = NetworkHeaders.Builder().set("Referer", referer).build()
                urls.forEach { loader.enqueue(ImageRequest.Builder(context).data(it).httpHeaders(headers).build()) }
            },
            isBiometricAvailable = services::biometricAvailable,
        ))
    }

    fun makeViewController(): UIViewController = ComposeUIViewController { App(viewModel, host) }
    fun updateNotificationPermission(granted: Boolean) = host.updateNotificationPermission(granted)

    fun handleUrl(url: String): Boolean {
        val parsed = runCatching { Url(url) }.getOrNull() ?: return false
        if (parsed.protocol.name != AniListAuth.REDIRECT_SCHEME || parsed.host != AniListAuth.REDIRECT_HOST) return false
        viewModel.onAniListAuthRedirect(parsed.fragment)
        return true
    }

    fun openUpdates() = viewModel.openUpdates()
}

/** Nessuna promessa di esecuzione a processo chiuso: scheduler foreground del primo host iOS. */
private class IosFavoriteUpdatesScheduling(
    private val scope: CoroutineScope,
    private val prefs: NSUserDefaultsSettings,
    sources: MangaSourceRegistry,
    aniList: AniListClient,
    services: IosPlatformServices,
) : FavoriteUpdatesScheduling {
    private var polling: Job? = null
    private val checker = FavoriteUpdatesChecker(prefs, sources, aniList, object : NewChapterNotifier {
        override fun notifyNewChapter(favorite: FavoriteManga, seriesKey: String, chapterLabel: String) {
            services.notify("chapter:$seriesKey", favorite.title, chapterLabel)
        }
        override fun notifySummary(unseenEvents: List<FavoriteUpdateEvent>) = Unit
    })

    override fun onAppStart() = setEnabled(SettingsStore(prefs).read().favoriteNewChapterNotificationsEnabled)

    override fun setEnabled(enabled: Boolean) {
        polling?.cancel()
        polling = if (!enabled) null else scope.launch {
            while (isActive) {
                try { checker.run() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Il prossimo controllo ritenta gli errori temporanei. */ }
                delay(30 * 60 * 1_000L)
            }
        }
    }
}
