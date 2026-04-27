package com.lorenzo.mangadownloader

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/**
 * Singleton OkHttpClient used across HTML scraping (MangaNetworkClient),
 * GitHub release polling (AppUpdateRepository) and Coil image loading.
 *
 * Sharing the client lets all calls reuse the same connection pool and HTTP
 * cache, which cuts redundant TCP/TLS handshakes and keeps the cellular radio
 * idle longer.
 */
object SharedHttpClient {
    private const val CACHE_DIR_NAME = "http"
    private const val CACHE_SIZE_BYTES = 20L * 1024L * 1024L

    @Volatile
    private var instance: OkHttpClient? = null

    fun get(context: Context): OkHttpClient {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(appContext: Context): OkHttpClient {
        val cacheDir = File(appContext.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .cache(Cache(cacheDir, CACHE_SIZE_BYTES))
            .retryOnConnectionFailure(true)
            .build()
    }
}
