package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `DownloadStorage.imageExtension` è una funzione pura (niente Android): testabile su JVM.
 * Era duplicata tra `BaseMangaSource` e `StreamingReaderCacheRepository`; ora è la sorgente unica.
 */
class DownloadStorageImageExtensionTest {

    @Test
    fun returnsExtension_forSimpleUrl() {
        assertEquals("jpg", DownloadStorage.imageExtension("https://example.com/page.jpg"))
        assertEquals("webp", DownloadStorage.imageExtension("https://cdn.example.com/a/b/page.webp"))
    }

    @Test
    fun ignoresQueryString() {
        assertEquals("png", DownloadStorage.imageExtension("https://example.com/page.png?w=700&v=2"))
    }

    @Test
    fun isLowercased() {
        assertEquals("jpg", DownloadStorage.imageExtension("https://example.com/PAGE.JPG"))
    }

    @Test
    fun fallsBackToJpg_whenNoUsableExtension() {
        // Nessun punto nell'URL → default.
        assertEquals("jpg", DownloadStorage.imageExtension("imagewithoutdot"))
        // Solo caratteri non alfanumerici dopo l'ultimo punto → default.
        assertEquals("jpg", DownloadStorage.imageExtension("https://example.com/page.~!"))
    }

    @Test
    fun stripsNonAlphanumericChars() {
        assertEquals("jpeg", DownloadStorage.imageExtension("https://example.com/page.jp@eg"))
    }
}
