package com.lorenzo.mangadownloader.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.IOException

class KtorMangaNetworkClientTest {

    private val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()

    private fun client(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
        io.ktor.client.request.HttpRequestData,
    ) -> io.ktor.client.request.HttpResponseData) = MangaNetworkClient(
        HttpClient(
            MockEngine { request ->
                requests += request
                handler(request)
            },
        ),
    )

    @Test
    fun transportErrorsAreRetriedThreeTimesThenRethrown() = runTest {
        val network = client { throw IOException("connessione rifiutata") }

        assertFailsWith<IOException> { network.fetchString("https://example.test/") }
        assertEquals(3, requests.size)
    }

    @Test
    fun cancellingDuringBackoffStopsFurtherAttempts() = runTest {
        val firstAttempt = CompletableDeferred<Unit>()
        val network = client {
            firstAttempt.complete(Unit)
            throw IOException("connessione rifiutata")
        }

        val job = launch { runCatching { network.fetchString("https://example.test/") } }
        firstAttempt.await()
        job.cancel()
        advanceTimeBy(5_000)

        assertEquals(1, requests.size)
    }

    @Test
    fun httpErrorsAreNotRetriedAndCarryTheStatusInTheMessage() = runTest {
        val network = client { respondError(HttpStatusCode.NotFound) }

        val error = assertFailsWith<IOException> { network.fetchString("https://example.test/x") }
        assertEquals("HTTP 404 su https://example.test/x", error.message)
        assertEquals(1, requests.size)
    }

    @Test
    fun documentRequestsSendBrowserHeadersAndCustomOnesWin() = runTest {
        val network = client { respond("<html><a href='/p'>p</a></html>") }

        val document = network.fetchDocument("https://example.test/page", headers = mapOf("Accept" to "application/json"))

        val headers = requests.single().headers
        assertTrue(headers[HttpHeaders.UserAgent]!!.startsWith("Mozilla/5.0"))
        assertEquals("application/json", headers[HttpHeaders.Accept])
        assertEquals("it,en;q=0.8", headers[HttpHeaders.AcceptLanguage])
        assertEquals("https://example.test/p", document.selectFirst("a")!!.absUrl("href"))
    }

    @Test
    fun imagesAreStreamedToTheSinkWithTheReferer() = runTest {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val network = client { respond(bytes, headers = headersOf(HttpHeaders.ContentType, "image/jpeg")) }
        val sink = Buffer()

        network.fetchToSink("https://img.test/1.jpg", sink, referer = "https://site.test/ch-1")

        assertContentEquals(bytes, sink.readByteArray())
        assertEquals("https://site.test/ch-1", requests.single().headers[HttpHeaders.Referrer])
        assertEquals(null, requests.single().headers[HttpHeaders.AcceptLanguage])
    }

    @Test
    fun bytesAreReturnedWhole() = runTest {
        val network = client { respond(byteArrayOf(1, 2, 3)) }

        assertContentEquals(byteArrayOf(1, 2, 3), network.fetchBytes("https://img.test/c.png"))
    }
}
