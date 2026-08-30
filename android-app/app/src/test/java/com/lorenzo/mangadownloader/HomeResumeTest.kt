package com.lorenzo.mangadownloader

import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selettore del blocco "Riprendi" della Home: prima guardava solo la libreria scaricata, così
 * chi legge in streaming non lo vedeva mai comparire pur avendo un progresso registrato.
 * Funzione pura → test su JVM.
 */
class HomeResumeTest {

    @Test
    fun computeHomeResume_senzaLettureNonProponeNulla() {
        assertNull(computeHomeResume(emptyList(), emptyMap()))
    }

    @Test
    fun computeHomeResume_proponeIlCapitoloScaricatoInCorso() {
        val library = listOf(series("Berserk", listOf(inProgressDownloadedChapter())))

        val resume = computeHomeResume(library, emptyMap())

        assertEquals("Berserk", resume?.seriesTitle)
        assertTrue(resume?.target is ResumeTarget.Downloaded)
    }

    /** Il caso che questo blocco esisteva per non coprire: nessun download, solo streaming. */
    @Test
    fun computeHomeResume_proponeLaLetturaInStreaming() {
        val memory = mapOf(STREAMING_PATH to streamingRecord(lastReadAtMillis = 1_000L))

        val resume = computeHomeResume(emptyList(), memory)

        assertEquals("Solo Leveling", resume?.seriesTitle)
        assertEquals("Capitolo 5", resume?.chapterLabel)
        // pagesRead è un conteggio (7 pagine viste), la card ragiona per indice.
        assertEquals(6, resume?.pageIndex)
        assertEquals(20, resume?.pageCount)
        assertEquals("https://cdn/cover.jpg", resume?.coverModel)
        assertEquals(ResumeTarget.Streaming(streamingRecord(lastReadAtMillis = 1_000L)), resume?.target)
    }

    @Test
    fun computeHomeResume_vinceLaLetturaPiuRecente() {
        val library = listOf(
            series("Berserk", listOf(inProgressDownloadedChapter(lastReadAtMillis = 5_000L))),
        )
        val recentStreaming = mapOf(STREAMING_PATH to streamingRecord(lastReadAtMillis = 9_000L))
        val oldStreaming = mapOf(STREAMING_PATH to streamingRecord(lastReadAtMillis = 1_000L))

        assertEquals("Solo Leveling", computeHomeResume(library, recentStreaming)?.seriesTitle)
        assertEquals("Berserk", computeHomeResume(library, oldStreaming)?.seriesTitle)
    }

    @Test
    fun computeHomeResume_ignoraLeLettureStreamingFinite() {
        val memory = mapOf(STREAMING_PATH to streamingRecord(isRead = true))

        assertNull(computeHomeResume(emptyList(), memory))
    }

    /**
     * Un record salvato prima che si annotassero le coordinate non è riapribile: proporlo
     * darebbe una card che non porta da nessuna parte.
     */
    @Test
    fun computeHomeResume_ignoraIRecordSenzaCoordinatePerRiaprire() {
        val memory = mapOf(
            STREAMING_PATH to streamingRecord().copy(mangaUrl = "", chapterUrl = ""),
        )

        assertNull(computeHomeResume(emptyList(), memory))
    }

    /** I record dei capitoli scaricati passano dalla libreria, non da questo ramo. */
    @Test
    fun computeHomeResume_ignoraIRecordNonStreaming() {
        val memory = mapOf(
            "serie/chapter_001.cbz" to streamingRecord().copy(seriesTitle = "Da libreria"),
        )

        assertNull(computeHomeResume(emptyList(), memory))
    }

    // ---- builder ----

    private fun streamingRecord(
        isRead: Boolean = false,
        lastReadAtMillis: Long = 1_000L,
    ) = ReadChapterMemory(
        seriesKey = "st:${MangaSourceIds.MANGAPILL}::https://mangapill.com/manga/1",
        seriesTitle = "Solo Leveling",
        chapterLabel = "Capitolo 5",
        pagesRead = 7,
        pageCount = 20,
        isRead = isRead,
        lastReadAtMillis = lastReadAtMillis,
        sourceId = MangaSourceIds.MANGAPILL,
        mangaUrl = "https://mangapill.com/manga/1",
        chapterUrl = "https://mangapill.com/chapters/1-5/solo-leveling-chapter-5",
        coverUrl = "https://cdn/cover.jpg",
    )

    private fun inProgressDownloadedChapter(lastReadAtMillis: Long = 5_000L) = DownloadedChapter(
        title = "Capitolo 1",
        numberText = "1",
        numberValue = BigDecimal.ONE,
        volumeText = null,
        labelPrefix = "Capitolo",
        file = File("1.cbz"),
        relativePath = "berserk/1.cbz",
        chapterId = "id-1",
        isRead = false,
        readerPageIndex = 3,
        readerPageCount = 30,
        lastReadAtMillis = lastReadAtMillis,
    )

    private fun series(title: String, chapters: List<DownloadedChapter>) = DownloadedSeries(
        sourceId = MangaSourceIds.MANGAPILL,
        title = title,
        mangaUrl = "https://mangapill.com/manga/${title.lowercase()}",
        coverFile = null,
        directory = File(title),
        chapters = chapters,
        totalChapterCount = chapters.size,
        readChapterIds = emptySet(),
    )

    private companion object {
        const val STREAMING_PATH = "streaming:abc123"
    }
}
