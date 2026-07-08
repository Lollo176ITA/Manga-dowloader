package com.lorenzo.mangadownloader

import java.io.IOException
import java.io.OutputStream
import java.net.URI
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class MangaNetworkClient(
    private val httpClient: OkHttpClient,
) {
    fun fetchDocument(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Document {
        return Jsoup.parse(fetchString(url, headers), url)
    }

    fun fetchString(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header(
                "Accept",
                headers["Accept"] ?: "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            )
            .header("Accept-Language", headers["Accept-Language"] ?: "it,en;q=0.8")
            .apply {
                headers.forEach { (name, value) ->
                    if (name != "Accept" && name != "Accept-Language") {
                        header(name, value)
                    }
                }
            }
            .build()

        executeWithConnectionRetry(request).use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} su $url")
            }
            return response.body.string()
        }
    }

    fun fetchBytes(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply {
                referer?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { header("Referer", it) }
                headers.forEach { (name, value) -> header(name, value) }
            }
            .build()

        executeWithConnectionRetry(request).use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} scaricando $url")
            }
            return response.body.bytes()
        }
    }

    /**
     * Scarica [url] scrivendo il corpo della risposta direttamente su [sink] a blocchi, senza
     * mai materializzare l'intera immagine in memoria (a differenza di [fetchBytes]). Usato
     * dalla cache dello streaming reader, dove tenere in RAM un capitolo intero causava picchi
     * di heap. Sincrono: chiamato da thread IO. Non chiude [sink] (lo gestisce il chiamante).
     */
    fun fetchToStream(
        url: String,
        sink: OutputStream,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply {
                referer?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { header("Referer", it) }
                headers.forEach { (name, value) -> header(name, value) }
            }
            .build()

        executeWithConnectionRetry(request).use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} scaricando $url")
            }
            response.body.byteStream().use { it.copyTo(sink) }
        }
    }

    fun absolutize(baseUrl: String, value: String): String {
        return URI(baseUrl).resolve(value).toString()
    }

    /**
     * Esegue la richiesta ritentando **solo** gli errori di trasporto (timeout,
     * connessione, reset): le risposte HTTP non-2xx non passano di qui, quindi un
     * 404 non viene ritentato. Sincrono: chiamato da thread IO/worker, mai dal main.
     */
    private fun executeWithConnectionRetry(request: Request): Response {
        var lastError: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return httpClient.newCall(request).execute()
            } catch (e: IOException) {
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_MS * (attempt + 1))
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            }
        }
        throw lastError ?: IOException("Richiesta di rete fallita: ${request.url}")
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 400L

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
