package com.lorenzo.mangadownloader

import java.math.BigDecimal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBarsTest {

    @Test
    fun showReaderLoadingIcon_onlyWhileStreamingReaderIsLoading() {
        val streamingChapter = StreamingReaderChapter(
            sourceId = MangaSourceIds.MANGAPILL,
            mangaTitle = "Manga",
            mangaUrl = "https://example.test/manga",
            chapter = ChapterEntry(
                numberText = "1",
                numberValue = BigDecimal.ONE,
                url = "https://example.test/manga/chapter-1",
                slug = "chapter-1",
            ),
            chapters = emptyList(),
        ).toReaderChapter()

        assertTrue(showReaderLoadingIcon(streamingChapter, isLoadingReader = true))
        assertFalse(showReaderLoadingIcon(streamingChapter, isLoadingReader = false))
        assertFalse(showReaderLoadingIcon(streamingChapter.copy(streamingChapter = null), isLoadingReader = true))
    }
}
