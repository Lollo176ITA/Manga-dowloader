package com.lorenzo.mangadownloader

import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScannerTest {

    @Test
    fun buildChapterFileName_padsIntegerNumbers_andKeepsDecimals() {
        val integerChapter = ChapterEntry(
            numberText = "7",
            numberValue = BigDecimal("7"),
            url = "https://example.com/7",
            slug = "seven",
        )
        val decimalChapter = ChapterEntry(
            numberText = "10.5",
            numberValue = BigDecimal("10.5"),
            url = "https://example.com/10.5",
            slug = "ten-five",
        )

        assertEquals("chapter_007.cbz", DownloadStorage.buildChapterFileName(integerChapter))
        assertEquals("chapter_10.5.cbz", DownloadStorage.buildChapterFileName(decimalChapter))
    }

    @Test
    fun scan_readsMetadataCover_andReadState() {
        val root = createTempDirectory()
        val seriesDir = File(root, "berserk").apply { mkdirs() }
        File(seriesDir, "cover.jpg").writeText("cover")
        File(seriesDir, "chapter_010.cbz").writeText("chapter10")
        File(seriesDir, "chapter_011.cbz").writeText("chapter11")

        SeriesMetadataJson.write(
            File(seriesDir, DownloadStorage.SERIES_METADATA_FILE_NAME),
            SeriesMetadata(
                sourceId = MangaSourceIds.MANGAPILL,
                title = "Berserk",
                mangaUrl = "https://mangapill.com/manga/1/berserk",
                coverFileName = "cover.jpg",
                totalChapters = 2,
                readChapterIds = emptySet(),
                chapters = listOf(
                    SeriesMetadataChapter(
                        numberText = "10",
                        url = "https://mangapill.com/chapters/1/berserk-chapter-10",
                        slug = "berserk-chapter-10",
                        fileName = "chapter_010.cbz",
                        id = null,
                    ),
                    SeriesMetadataChapter(
                        numberText = "11",
                        url = "https://mangapill.com/chapters/1/berserk-chapter-11",
                        slug = "berserk-chapter-11",
                        fileName = "chapter_011.cbz",
                        id = null,
                    ),
                ),
            ),
        )

        val series = LibraryScanner.scan(root) { relativePath ->
            relativePath.endsWith("chapter_011.cbz")
        }

        assertEquals(1, series.size)
        assertEquals("Berserk", series.first().title)
        assertEquals(MangaSourceIds.MANGAPILL, series.first().sourceId)
        assertNotNull(series.first().coverFile)
        assertEquals(2, series.first().chapters.size)
        assertFalse(series.first().chapters.first().isRead)
        assertTrue(series.first().chapters.last().isRead)
    }

    @Test
    fun scan_supportsLegacyFoldersWithoutMetadata() {
        val root = createTempDirectory()
        val seriesDir = File(root, "my_series").apply { mkdirs() }
        File(seriesDir, "chapter_001.cbz").writeText("chapter1")
        File(seriesDir, "chapter_10.5.cbz").writeText("chapter10.5")

        val series = LibraryScanner.scan(root) { false }

        assertEquals(1, series.size)
        assertEquals("my series", series.first().title)
        assertEquals(MangaSourceIds.MANGAPILL, series.first().sourceId)
        assertEquals(listOf("1", "10.5"), series.first().chapters.map { it.numberText })
        assertTrue(series.first().chapters.none { it.isRead })
    }

    @Test
    fun parse_metadataWithoutSourceId_infersProviderFromUrl() {
        val parsed = SeriesMetadataJson.parse(
            """
            {
              "title": "Yotsuba",
              "mangaUrl": "https://reader.hastateam.com/comics/yotsuba",
              "chapters": []
            }
            """.trimIndent(),
        )

        assertNotNull(parsed)
        assertEquals(MangaSourceIds.HASTA_TEAM, parsed?.sourceId)
    }

    private fun createTempDirectory(): File {
        return Files.createTempDirectory("manga-library-test").toFile().apply {
            deleteOnExit()
        }
    }
}
