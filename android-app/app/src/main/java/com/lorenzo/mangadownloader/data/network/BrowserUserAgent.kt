package com.lorenzo.mangadownloader.data.network

import android.content.Context
import android.webkit.WebSettings

/**
 * User-Agent "da browser" di pagine, download e copertine: Chrome desktop su Windows, con la
 * versione di Chrome presa dalla WebView del telefono, che si aggiorna da sola dal Play Store.
 * Uno UA fermo a una versione vecchia è un segnale per Cloudflare, e con molti utenti i blocchi
 * pesano di più. Resta desktop perché i parser delle fonti sono scritti sull'HTML desktop.
 *
 * La WebView si interroga una volta per processo, al primo uso (che avviene su un thread IO):
 * caricarla costa e all'avvio non serve.
 */
object BrowserUserAgent {

    @Volatile
    private var appContext: Context? = null

    val value: String by lazy {
        build(appContext?.let(::webViewChromeMajor))
    }

    /** Da chiamare in `Application.onCreate`: senza, si usa la versione di ripiego. */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    private fun webViewChromeMajor(context: Context): Int? =
        // Lancia se la WebView manca o si sta aggiornando: in quel caso basta il ripiego.
        runCatching { chromeMajorFrom(WebSettings.getDefaultUserAgent(context)) }.getOrNull()

    /** Major di `Chrome/<n>` nello UA della WebView. Pura. */
    internal fun chromeMajorFrom(webViewUserAgent: String): Int? =
        Regex("""Chrome/(\d+)""").find(webViewUserAgent)?.groupValues?.get(1)?.toIntOrNull()

    /** Formato ridotto di Chrome (`<major>.0.0.0`), come lo manda oggi il browser vero. Pura. */
    internal fun build(chromeMajor: Int?): String {
        val major = chromeMajor?.takeIf { it >= FALLBACK_CHROME_MAJOR } ?: FALLBACK_CHROME_MAJOR
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }

    private const val FALLBACK_CHROME_MAJOR = 124
}
