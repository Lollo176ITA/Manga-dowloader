package com.lorenzo.mangadownloader.data.library

import android.content.Context
import com.lorenzo.mangadownloader.data.network.MangaNetworkClient
import com.lorenzo.mangadownloader.platform.AndroidTallPageNormalizer
import com.lorenzo.mangadownloader.platform.systemFileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.use

/**
 * La cache dello streaming reader nella `cacheDir` del dispositivo Android.
 *
 * @param reusablePageCopier prova a copiare su `target` una pagina già scaricata altrove
 *   (tipicamente dalla disk-cache di Coil, che l'ha scaricata per mostrarla nel reader) e
 *   ritorna `true` se ci è riuscita. Così una pagina già vista non viaggia sulla rete una
 *   seconda volta solo per finire in cache. Default: nessun riuso (sempre rete).
 */
fun StreamingReaderCacheRepository(
    context: Context,
    networkClient: MangaNetworkClient,
    reusablePageCopier: (url: String, target: Path) -> Boolean = { _, _ -> false },
): StreamingReaderCacheRepository = StreamingReaderCacheRepository(
    cacheRoot = context.cacheDir.toOkioPath() / StreamingReaderCacheRepository.CACHE_DIR_NAME,
    fetchPageToFile = { url, referer, target ->
        if (!reusablePageCopier(url, target)) {
            systemFileSystem.sink(target).use { output ->
                networkClient.fetchToSink(url, sink = output, referer = referer)
            }
        }
    },
    normalizePage = { source, outputDirectory, outputBaseName ->
        AndroidTallPageNormalizer.normalize(source, outputDirectory, outputBaseName).files
    },
)
