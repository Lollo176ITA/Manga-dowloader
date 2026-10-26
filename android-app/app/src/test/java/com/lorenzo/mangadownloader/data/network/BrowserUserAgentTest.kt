package com.lorenzo.mangadownloader.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserUserAgentTest {

    @Test
    fun chromeMajor_isReadFromTheWebViewUserAgent() {
        val webView = "Mozilla/5.0 (Linux; Android 15; Pixel 8 Build/AP4A; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36"

        assertEquals(141, BrowserUserAgent.chromeMajorFrom(webView))
        assertNull(BrowserUserAgent.chromeMajorFrom("Dalvik/2.1.0"))
    }

    @Test
    fun build_staysDesktopAndNeverGoesBelowTheFallback() {
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
            BrowserUserAgent.build(141),
        )
        // WebView assente o vecchissima (telefono mai aggiornato): ripiego, mai una versione più vecchia.
        assertEquals(BrowserUserAgent.build(124), BrowserUserAgent.build(null))
        assertEquals(BrowserUserAgent.build(124), BrowserUserAgent.build(90))
    }
}
