package com.lorenzo.mangadownloader.data.network

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.IOException
import okio.Sink
import okio.buffer

class MangaNetworkClient(
    private val httpClient: HttpClient,
) {
    suspend fun fetchDocument(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Document {
        return Ksoup.parse(fetchString(url, headers), url)
    }

    suspend fun fetchString(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String = executeSuccessful(
        url = url,
        defaultHeaders = DEFAULT_DOCUMENT_HEADERS,
        headers = headers,
        errorContext = "su $url",
    ) { response -> response.bodyAsText() }

    suspend fun fetchBytes(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray = executeSuccessful(
        url = url,
        referer = referer,
        headers = headers,
        errorContext = "scaricando $url",
    ) { response -> response.body<ByteArray>() }

    /**
     * Scarica [url] scrivendo il corpo della risposta direttamente su [sink] a blocchi, senza
     * mai materializzare l'intera immagine in memoria (a differenza di [fetchBytes]). Usato
     * dai download e dalla cache dello streaming reader, dove tenere in RAM un capitolo intero
     * causava picchi di heap. Non chiude [sink] (lo gestisce il chiamante).
     */
    suspend fun fetchToSink(
        url: String,
        sink: Sink,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ) {
        executeSuccessful(
            url = url,
            referer = referer,
            headers = headers,
            errorContext = "scaricando $url",
        ) { response ->
            val channel = response.bodyAsChannel()
            val chunk = ByteArray(COPY_BUFFER_BYTES)
            val output = sink.buffer()
            while (true) {
                val read = channel.readAvailable(chunk, 0, chunk.size)
                if (read == -1) break
                if (read > 0) withContext(Dispatchers.IO) { output.write(chunk, 0, read) }
            }
            withContext(Dispatchers.IO) { output.flush() }
        }
    }

    private suspend fun <T> executeSuccessful(
        url: String,
        referer: String? = null,
        defaultHeaders: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        errorContext: String,
        readBody: suspend (HttpResponse) -> T,
    ): T {
        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            // Si ritentano **solo** gli errori di trasporto (timeout, connessione, reset) prima di
            // avere una risposta: un 404 o un corpo troncato non passano di qui. L'attesa tra i
            // tentativi è sospendibile, quindi annullare la coroutine interrompe subito i retry.
            var responded = false
            try {
                return httpClient.prepareGet(url) {
                    header("User-Agent", USER_AGENT)
                    referer?.trim()?.takeIf(String::isNotBlank)?.let { header("Referer", it) }
                    defaultHeaders.forEach { (name, value) -> if (name !in headers) header(name, value) }
                    headers.forEach { (name, value) -> header(name, value) }
                }.execute { response ->
                    responded = true
                    if (!response.status.isSuccess()) {
                        throw IOException("HTTP ${response.status.value} $errorContext")
                    }
                    readBody(response)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (responded || !error.isTransportError()) throw error
                lastError = error
                if (attempt < MAX_ATTEMPTS - 1) {
                    delay(RETRY_BACKOFF_MS * (attempt + 1))
                }
            }
        }
        throw lastError ?: IOException("Richiesta di rete fallita: $url")
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 400L
        private const val COPY_BUFFER_BYTES = 64 * 1024

        private val DEFAULT_DOCUMENT_HEADERS = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "it,en;q=0.8",
        )

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}

/** Errore di I/O di rete (anche quelli di Ktor, che su iOS non sono `okio.IOException`). */
fun Throwable.isTransportError(): Boolean = this is IOException || this is kotlinx.io.IOException
