package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

class MangaApplication : Application(), SingletonImageLoader.Factory {

    /**
     * Istanze applicative uniche: condivise tra ViewModel e DownloadWorker così che
     * la TTL-cache di [LibraryRepository] sia coerente e non si ricreino registry/repo
     * a ogni uso. Lazy: costruite al primo accesso, non all'avvio del processo.
     */
    val libraryRepository: LibraryRepository by lazy { LibraryRepository(this) }
    val sourceRegistry: MangaSourceRegistry by lazy { MangaSourceRegistry(this, libraryRepository) }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }

    override fun newImageLoader(context: Context): ImageLoader {
        // newBuilder() preserves the parent connection pool and HTTP cache,
        // so image requests reuse the same pool as the rest of the app.
        val okHttpClient = SharedHttpClient.get(this).newBuilder()
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                    .header("User-Agent", COIL_USER_AGENT)
                    .header("Accept", "image/avif,image/webp,image/png,image/jpeg,*/*;q=0.8")
                // Non sovrascrivere un Referer già impostato per-immagine (es. le pagine
                // del reader in streaming, che usano l'URL della loro fonte). Solo come
                // fallback usiamo mangapill, dove serve per l'hotlink protection.
                if (original.header("Referer") == null) {
                    builder.header("Referer", "https://mangapill.com/")
                }
                chain.proceed(builder.build())
            }
            .build()

        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }
            .crossfade(true)
            .build()
    }

    companion object {
        private const val COIL_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}

private fun Context.mangaApp(): MangaApplication? = applicationContext as? MangaApplication

/**
 * Registry condiviso dell'app, con fallback a un'istanza nuova se il [Context] non
 * appartiene a [MangaApplication] (es. test che usano un Application generico).
 */
fun sharedSourceRegistry(context: Context): MangaSourceRegistry =
    context.mangaApp()?.sourceRegistry ?: MangaSourceRegistry(context)

/** Repository di libreria condiviso dell'app, con lo stesso fallback. */
fun sharedLibraryRepository(context: Context): LibraryRepository =
    context.mangaApp()?.libraryRepository ?: LibraryRepository(context)
