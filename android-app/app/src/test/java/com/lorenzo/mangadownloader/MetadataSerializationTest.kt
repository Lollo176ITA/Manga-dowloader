package com.lorenzo.mangadownloader

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataSerializationTest {

    @Test
    fun seriesMetadata_readsLegacyFixtureAndPreservesNormalization() {
        val metadata = SeriesMetadataJson.parse(fixture("legacy-series-metadata.json"))

        assertNotNull(metadata)
        assertEquals(MangaSourceIds.HASTA_TEAM, metadata?.sourceId)
        assertEquals("Yotsuba", metadata?.title)
        assertEquals(setOf("chapter:2", "chapter:1"), metadata?.readChapterIds)
        assertEquals(2, metadata?.chapters?.size)
        assertEquals("1", metadata?.chapters?.first()?.numberText)
        assertEquals("chapter_001.cbz", metadata?.chapters?.first()?.fileName)
        assertEquals("Capitolo", metadata?.chapters?.first()?.labelPrefix)
        assertEquals("Episodio", metadata?.chapters?.last()?.labelPrefix)
    }

    @Test
    fun seriesMetadata_roundTripsWithHistoricalOptionalFieldShape() {
        val directory = createTempDirectory("series-metadata")
        val target = File(directory, DownloadStorage.SERIES_METADATA_FILE_NAME)
        val expected = SeriesMetadata(
            sourceId = MangaSourceIds.MANGAPILL,
            title = "Berserk",
            mangaUrl = null,
            coverFileName = null,
            totalChapters = null,
            readChapterIds = linkedSetOf("chapter:2", "chapter:1"),
            chapters = listOf(
                SeriesMetadataChapter(
                    numberText = "1",
                    fileName = "chapter_001.cbz",
                    id = null,
                ),
            ),
        )

        SeriesMetadataJson.write(target, expected)

        val raw = target.readText()
        assertFalse(raw.contains("\"mangaUrl\""))
        assertFalse(raw.contains("\"coverFileName\""))
        assertTrue(raw.indexOf("chapter:1") < raw.indexOf("chapter:2"))
        assertEquals(expected, SeriesMetadataJson.read(target))
    }

    @Test
    fun seriesMetadata_rejectsBlankTitle() {
        assertNull(SeriesMetadataJson.parse("""{"title":"   ","chapters":[]}"""))
    }

    @Test
    fun streamingMetadata_readsLegacyFixtureAndPreservesNormalization() {
        val directory = createTempDirectory("legacy-streaming-metadata")
        File(directory, "metadata.json").writeText(fixture("legacy-streaming-reader-cache-metadata.json"))

        val metadata = StreamingReaderCacheMetadata.read(directory)

        assertNotNull(metadata)
        assertEquals(MangaSourceIds.MANGAPILL, metadata?.sourceId)
        assertEquals(listOf("https://cdn.example/1.jpg", "https://cdn.example/2.jpg"), metadata?.pageUrls)
        assertEquals(listOf("001.jpg", "002.jpg"), metadata?.pages)
        assertEquals(emptyList<StreamingReaderCachedPageMetadata>(), metadata?.cachedPages)
        assertEquals(1_700_000_000_000L, metadata?.lastAccessAtMs)
    }

    @Test
    fun streamingMetadata_roundTripsAndOmitsNullOptionalFields() {
        val directory = createTempDirectory("streaming-metadata")
        val expected = StreamingReaderCacheMetadata(
            title = "Capitolo 1",
            pageUrls = listOf("https://cdn.example/1.jpg"),
            pages = listOf("001.jpg"),
            referer = "https://example.test/chapter-1",
            lastAccessAtMs = 42L,
        )

        StreamingReaderCacheMetadata.write(directory, expected)

        val raw = File(directory, "metadata.json").readText()
        assertFalse(raw.contains("\"sourceId\""))
        assertFalse(raw.contains("\"mangaUrl\""))
        assertFalse(raw.contains("\"chapterUrl\""))
        assertEquals(expected, StreamingReaderCacheMetadata.read(directory))
    }

    @Test
    fun streamingMetadata_roundTripsSplitPageOrigins() {
        val directory = createTempDirectory("streaming-split-metadata")
        val sourceUrl = "https://cdn.example/tall.jpg"
        val cachedPages = (0..1).map { segmentIndex ->
            StreamingReaderCachedPageMetadata(
                fileName = "001__part_${(segmentIndex + 1).toString().padStart(4, '0')}.png",
                sourceUrl = sourceUrl,
                originalPageIndex = 0,
                segmentIndex = segmentIndex,
                segmentCount = 2,
            )
        }
        val expected = StreamingReaderCacheMetadata(
            title = "Capitolo lungo",
            pageUrls = listOf(sourceUrl),
            pages = cachedPages.map(StreamingReaderCachedPageMetadata::fileName),
            cachedPages = cachedPages,
            referer = "https://example.test/chapter",
        )

        StreamingReaderCacheMetadata.write(directory, expected)

        assertEquals(expected, StreamingReaderCacheMetadata.read(directory))
    }

    private fun fixture(name: String): String {
        return checkNotNull(javaClass.getResource("/fixtures/$name")) { "Fixture mancante: $name" }
            .readText()
    }

    private fun createTempDirectory(prefix: String): File {
        return Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }
    }
}
