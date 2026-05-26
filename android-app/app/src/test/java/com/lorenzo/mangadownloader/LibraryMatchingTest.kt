package com.lorenzo.mangadownloader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logica di matching serie/capitoli estratta da MainActivity: pura, quindi testabile su JVM
 * (niente Robolectric). Copre match per identità (sourceId+URL), fallback sul titolo,
 * assenza di match, e le chiavi capitolo/letti derivate.
 */
class LibraryMatchingTest {

    @Test
    fun matchingDownloadedSeries_matchesByIdentityIgnoringSlug() {
        val details = details(
            mangaUrl = "https://mangapill.com/manga/12345/berserk",
            title = "Berserk",
        )
        val library = listOf(
            series(title = "Altro", mangaUrl = "https://mangapill.com/manga/999"),
            series(title = "Berserk", mangaUrl = "https://mangapill.com/manga/12345"),
        )

        val match = LibraryMatching.matchingDownloadedSeries(details, library)

        assertEquals("https://mangapill.com/manga/12345", match?.mangaUrl)
    }

    @Test
    fun matchingDownloadedSeries_fallsBackToTitleWhenUrlIdentityDiffers() {
        val details = details(
            mangaUrl = "https://mangapill.com/manga/12345/berserk",
            title = "Berserk",
        )
        // URL con id diverso → identità diversa, ma stesso titolo.
        val library = listOf(
            series(title = "Berserk", mangaUrl = "https://mangapill.com/manga/67890"),
        )

        val match = LibraryMatching.matchingDownloadedSeries(details, library)

        assertEquals("https://mangapill.com/manga/67890", match?.mangaUrl)
    }

    @Test
    fun matchingDownloadedSeries_returnsNullWhenNothingMatches() {
        val details = details(mangaUrl = "https://mangapill.com/manga/12345", title = "Berserk")
        val library = listOf(
            series(title = "Naruto", mangaUrl = "https://mangapill.com/manga/777"),
        )

        assertNull(LibraryMatching.matchingDownloadedSeries(details, library))
    }

    @Test
    fun downloadedChapterKeys_includeChapterIdAndNormalizedNumberKey() {
        val details = details(mangaUrl = "https://mangapill.com/manga/12345", title = "Berserk")
        val library = listOf(
            series(
                title = "Berserk",
                mangaUrl = "https://mangapill.com/manga/12345",
                chapters = listOf(
                    downloadedChapter(number = "1.0", chapterId = "id-1"),
                    downloadedChapter(number = "2", chapterId = "id-2"),
                ),
            ),
        )

        val keys = LibraryMatching.downloadedChapterKeys(details, library)

        assertTrue("id-1" in keys)
        assertTrue("id-2" in keys)
        assertTrue("number:1" in keys) // "1.0" normalizzato a "1"
        assertTrue("number:2" in keys)
    }

    @Test
    fun downloadedChapterKeys_emptyWhenSeriesNotInLibrary() {
        val details = details(mangaUrl = "https://mangapill.com/manga/12345", title = "Berserk")

        assertEquals(emptySet<String>(), LibraryMatching.downloadedChapterKeys(details, emptyList()))
    }

    @Test
    fun downloadedReadChapterIds_combineMetadataIdsAndReadChapters() {
        val details = details(mangaUrl = "https://mangapill.com/manga/12345", title = "Berserk")
        val library = listOf(
            series(
                title = "Berserk",
                mangaUrl = "https://mangapill.com/manga/12345",
                chapters = listOf(
                    downloadedChapter(number = "1", chapterId = "id-1", isRead = true),
                    downloadedChapter(number = "2", chapterId = "id-2", isRead = false),
                ),
                readChapterIds = setOf("meta-read"),
            ),
        )

        val read = LibraryMatching.downloadedReadChapterIds(details, library)

        assertEquals(setOf("meta-read", "id-1"), read)
    }

    @Test
    fun tutorialSampleSeries_matchesSampleThenFallsBackToFirst() {
        val first = series(title = "Primo", mangaUrl = "https://mangapill.com/manga/1")
        val target = series(title = "Berserk", mangaUrl = "https://mangapill.com/manga/12345")
        val library = listOf(first, target)
        val sample = TutorialSample(
            sourceId = MangaSourceIds.MANGAPILL,
            mangaUrl = "https://mangapill.com/manga/12345/berserk",
            title = "Berserk",
            coverUrl = null,
            chapterUrl = "https://mangapill.com/chapters/12345-1/berserk-chapter-1",
        )

        assertEquals(target.mangaUrl, LibraryMatching.tutorialSampleSeries(sample, library)?.mangaUrl)
        // Nessun sample → prima serie.
        assertEquals(first.mangaUrl, LibraryMatching.tutorialSampleSeries(null, library)?.mangaUrl)
        // Libreria vuota → null.
        assertNull(LibraryMatching.tutorialSampleSeries(sample, emptyList()))
    }

    // ---- builder ----

    private fun details(mangaUrl: String, title: String) = MangaDetails(
        sourceId = MangaSourceIds.MANGAPILL,
        title = title,
        coverUrl = null,
        mangaUrl = mangaUrl,
        chapters = emptyList(),
    )

    private fun series(
        title: String,
        mangaUrl: String,
        chapters: List<DownloadedChapter> = emptyList(),
        readChapterIds: Set<String> = emptySet(),
    ) = DownloadedSeries(
        sourceId = MangaSourceIds.MANGAPILL,
        title = title,
        mangaUrl = mangaUrl,
        coverFile = null,
        directory = File(title),
        chapters = chapters,
        totalChapterCount = chapters.size,
        readChapterIds = readChapterIds,
    )

    private fun downloadedChapter(
        number: String,
        chapterId: String,
        isRead: Boolean = false,
    ) = DownloadedChapter(
        title = "Capitolo $number",
        numberText = number,
        numberValue = number.toBigDecimalOrNull(),
        volumeText = null,
        labelPrefix = "Capitolo",
        file = File("$number.cbz"),
        relativePath = "series/$number.cbz",
        chapterId = chapterId,
        isRead = isRead,
        readerPageIndex = null,
        readerPageCount = null,
    )
}
