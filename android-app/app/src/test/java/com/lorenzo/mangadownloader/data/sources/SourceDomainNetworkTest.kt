package com.lorenzo.mangadownloader.data.sources

import com.lorenzo.mangadownloader.data.network.MangaNetworkClient
import com.lorenzo.mangadownloader.data.network.isWorthRetrying
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `sources.json` applicato alla rete vera dell'app: interceptor + [MangaNetworkClient]. */
class SourceDomainNetworkTest {

    private val sent = mutableListOf<Request>()

    /** Client come quello dell'app, con un sito finto in fondo che risponde [body]. */
    private fun client(body: String) = MangaNetworkClient(
        OkHttpClient.Builder()
            .addInterceptor(SourceDomainInterceptor())
            .addInterceptor(
                Interceptor { chain ->
                    sent += chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("text/html".toMediaType()))
                        .build()
                },
            )
            .build(),
    )

    @After
    fun tearDown() {
        RemoteSourceConfig.replaceForTest(null)
    }

    @Test
    fun movedSource_isFetchedFromTheNewDomain_andReadWithTheSavedOne() {
        RemoteSourceConfig.replaceForTest(
            parseSourceOverrides("""{"sources":{"manga_world":{"domain":"mangaworld.nz"}}}"""),
        )

        val html = client("""<a href="https://www.mangaworld.nz/manga/1/x/read/abc">Capitolo 1</a>""")
            .fetchString(
                "https://www.mangaworld.mx/manga/1/x",
                headers = mapOf("Referer" to "https://www.mangaworld.mx/"),
            )

        val request = sent.single()
        assertEquals("https://www.mangaworld.nz/manga/1/x", request.url.toString())
        assertEquals("https://www.mangaworld.nz/", request.header("Referer"))
        // Il link del capitolo torna nella forma che la fonte riconosce e che viene salvata.
        assertEquals("""<a href="https://www.mangaworld.mx/manga/1/x/read/abc">Capitolo 1</a>""", html)
        assertTrue(MangaWorldSource.handlesUrl("https://www.mangaworld.mx/manga/1/x/read/abc"))
    }

    @Test
    fun otherSites_areLeftAlone() {
        RemoteSourceConfig.replaceForTest(
            parseSourceOverrides("""{"sources":{"manga_world":{"domain":"mangaworld.nz"}}}"""),
        )

        client("ok").fetchString("https://mangapill.com/search?q=x")

        assertEquals("https://mangapill.com/search?q=x", sent.single().url.toString())
    }

    @Test
    fun sourceInMaintenance_isNotContacted_andIsNotRetried() {
        RemoteSourceConfig.replaceForTest(
            parseSourceOverrides("""{"sources":{"tcb_scans":{"maintenance":"Torna presto."}}}"""),
        )

        val error = assertThrows(SourceMaintenanceException::class.java) {
            client("ok").fetchString("https://tcbonepiecechapters.com/projects")
        }

        assertEquals("TCB Scans è in manutenzione: Torna presto.", error.message)
        assertTrue(sent.isEmpty())
        assertFalse(isWorthRetrying(error))
    }
}
