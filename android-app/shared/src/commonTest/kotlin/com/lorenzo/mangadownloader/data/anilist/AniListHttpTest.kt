package com.lorenzo.mangadownloader.data.anilist

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.IOException

class AniListHttpTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun client(status: HttpStatusCode, body: String = "") = AniListClient(
        HttpClient(
            MockEngine { request ->
                requests += request
                if (status == HttpStatusCode.OK) respond(body) else respondError(status)
            },
        ),
    )

    @Test
    fun rejectedTokensBecomeAuthExceptionsCarryingThatToken() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            val error = assertFailsWith<AniListAuthException> { client(status).fetchViewer("tok-1") }
            assertEquals("tok-1", error.token)
        }
    }

    @Test
    fun rateLimitAndServerErrorsAreIoExceptionsWithReadableMessages() = runTest {
        assertEquals(
            "AniList sta limitando le richieste, riprova tra poco",
            assertFailsWith<IOException> { client(HttpStatusCode.TooManyRequests).fetchViewer("t") }.message,
        )
        assertEquals(
            "HTTP 502 da AniList",
            assertFailsWith<IOException> { client(HttpStatusCode.BadGateway).fetchViewer("t") }.message,
        )
    }

    @Test
    fun queriesArePostedAsJsonWithTheBearerToken() = runTest {
        val viewer = """{"data":{"Viewer":{"id":7,"name":"Lorenzo"}}}"""

        client(HttpStatusCode.OK, viewer).fetchViewer("tok-2")

        val request = requests.single()
        assertEquals("https://graphql.anilist.co", request.url.toString())
        assertEquals("Bearer tok-2", request.headers[HttpHeaders.Authorization])
        assertEquals("application/json", request.headers[HttpHeaders.Accept])
        val body = request.body as TextContent
        assertTrue(body.contentType.toString().startsWith("application/json"))
        assertTrue(body.text.contains("Viewer"))
    }
}
