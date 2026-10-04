package com.lorenzo.mangadownloader.data.network

import java.net.URI
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Gli URL delle richieste devono restare identici a quelli composti con OkHttp prima della migrazione. */
class HttpUrlsOkHttpParityTest {

    private val alphabet = "abcXYZ019 +-_.~!*'();:@&=$,/?#[]%\"<>\\^`{|}àèéìòù€日本語\u0001\u007f"

    private val samples: List<String> = listOf(
        "", " ", "one piece", "One Piece: Red", "a+b", "100%", "dr. stone", "l'attacco dei giganti",
        "naruto/shippuden", "q?x=1&y=2", "#hash", "[x]", "città", "日本", "emoji 😀",
    ) + Random(11).let { rnd ->
        List(500) { String(CharArray(rnd.nextInt(0, 12)) { alphabet[rnd.nextInt(alphabet.length)] }) }
    }

    @Test
    fun queryParametersAreEncodedLikeOkHttp() {
        for (value in samples) {
            val expected = "https://mangapill.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", value)
                .addQueryParameter("exclude_genre[]", value)
                .build().toString()
            val actual = buildHttpUrl(
                "https://mangapill.com/search",
                query = listOf("q" to value, "exclude_genre[]" to value),
            )
            assertEquals(expected, actual, "value '$value'")
        }
    }

    @Test
    fun pathSegmentsAreEncodedLikeOkHttp() {
        for (value in samples.filter { it != "." && it != ".." }) {
            val expected = "https://hastateam.com".toHttpUrl().newBuilder()
                .addPathSegment("api").addPathSegment("search").addPathSegment(value)
                .build().toString()
            val actual = buildHttpUrl("https://hastateam.com", pathSegments = listOf("api", "search", value))
            assertEquals(expected, actual, "segment '$value'")
        }
    }

    @Test
    fun baseUrlsWithPathAndQueryKeepOkHttpShape() {
        assertEquals(
            "https://www.mangaworld.mx/archive?keyword=one%20piece",
            buildHttpUrl("https://www.mangaworld.mx", listOf("archive"), listOf("keyword" to "one piece")),
        )
        assertEquals(
            "https://demonicscans.org/search.php?manga=x".toHttpUrl().toString(),
            buildHttpUrl("https://demonicscans.org/search.php", query = listOf("manga" to "x")),
        )
        assertEquals("https://hastateam.com/".toHttpUrl().toString(), buildHttpUrl("https://hastateam.com"))
    }

    @Test
    fun relativeReferencesResolveLikeJavaUri() {
        val base = "https://hastateam.com"
        listOf("/comics/x", "//cdn.example.com/a.jpg", "/a/./b/../c.jpg", "https://other.org/z")
            .forEach { relative ->
                assertEquals(URI(base).resolve(relative).toString(), resolveUrl(base, relative), "relative '$relative'")
            }
        listOf("https://hastateam.com/comics/", "https://hastateam.com/a/b.html").forEach { pathBase ->
            listOf("x.jpg", "../y.jpg", "./z.jpg", "?page=2", "img/a.jpg").forEach { relative ->
                assertEquals(URI(pathBase).resolve(relative).toString(), resolveUrl(pathBase, relative), "$pathBase + $relative")
            }
        }
    }

    @Test
    fun relativePathOnBaseWithoutPathGetsTheSlashThatJavaUriForgets() {
        // java.net.URI("https://host").resolve("x") restituisce "https://hostx" (URL rotto):
        // qui vale la RFC 3986, quindi gli URL relativi senza "/" iniziale tornano validi.
        assertEquals("https://hastateam.comcomics/y", URI("https://hastateam.com").resolve("comics/y").toString())
        assertEquals("https://hastateam.com/comics/y", resolveUrl("https://hastateam.com", "comics/y"))
    }
}
