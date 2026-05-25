package com.lorenzo.mangadownloader

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StorageSizeTest {

    @Test
    fun storageSizeBytes_sumsAllFilesRecursively() {
        val root = Files.createTempDirectory("series").toFile()
        try {
            File(root, "chapter_001.cbz").writeBytes(ByteArray(1000))
            File(root, "cover.jpg").writeBytes(ByteArray(500))
            val nested = File(root, "extra").apply { mkdirs() }
            File(nested, "page.bin").writeBytes(ByteArray(250))

            val series = downloadedSeries(root)

            assertEquals(1750L, series.storageSizeBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun storageSizeBytes_isZeroForEmptyDirectory() {
        val root = Files.createTempDirectory("series-empty").toFile()
        try {
            assertEquals(0L, downloadedSeries(root).storageSizeBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun seriesColor_isStableForSameKey() {
        assertEquals(seriesColor("one-piece"), seriesColor("one-piece"))
    }

    @Test
    fun seriesColor_differsForKeysWithDifferentHue() {
        // Chiavi scelte con hash che cadono su tinte diverse: colori distinti.
        assertNotEquals(seriesColor("naruto"), seriesColor("bleach"))
    }

    private fun downloadedSeries(directory: File): DownloadedSeries = DownloadedSeries(
        sourceId = MangaSourceIds.DEFAULT,
        title = directory.name,
        mangaUrl = null,
        coverFile = null,
        directory = directory,
        chapters = emptyList(),
        totalChapterCount = 0,
        readChapterIds = emptySet(),
    )
}
