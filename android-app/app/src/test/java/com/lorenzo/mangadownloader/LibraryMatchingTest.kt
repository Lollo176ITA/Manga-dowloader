package com.lorenzo.mangadownloader

import java.io.File
import java.math.BigDecimal
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
    fun matchingDownloadedSeries_matchaTramiteBindingDelLink() {
        // Scheda aperta su VyManga con titolo diverso; la serie era stata scaricata da
        // Mangapill: né identità né titolo combaciano, ma i binding del SeriesLink sì.
        val details = MangaDetails(
            sourceId = MangaSourceIds.VYMANGA,
            title = "Titolo VY",
            coverUrl = null,
            mangaUrl = "https://vymanga.com/manga/x",
            chapters = emptyList(),
        )
        val downloaded = series(title = "Altro Titolo", mangaUrl = "https://mangapill.com/manga/1")
        val bindings = listOf(
            SeriesSourceBinding(MangaSourceIds.MANGAPILL, "https://mangapill.com/manga/1"),
            SeriesSourceBinding(MangaSourceIds.VYMANGA, "https://vymanga.com/manga/x"),
        )

        assertEquals(
            downloaded,
            LibraryMatching.matchingDownloadedSeries(details, listOf(downloaded), extraBindings = bindings),
        )
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

    @Test
    fun downloadedChapterFor_trovaLaCopiaScaricataTramiteIdStabile() {
        val entry = chapterEntry(number = 1)
        val details = details(mangaUrl = "https://mangapill.com/manga/12345", title = "Berserk")
        val downloaded = downloadedChapter(
            number = "1",
            chapterId = DownloadStorage.stableChapterId(entry),
        )
        val library = listOf(
            series(
                title = "Berserk",
                mangaUrl = "https://mangapill.com/manga/12345",
                chapters = listOf(downloadedChapter(number = "2", chapterId = "id-2"), downloaded),
            ),
        )

        assertEquals(downloaded, LibraryMatching.downloadedChapterFor(details, entry, library))
    }

    /**
     * Stesso capitolo scaricato da un URL diverso (o prima di un cambio di URL della fonte):
     * la lista lo marca "scaricato" tramite la chiave per numero, quindi anche l'apertura
     * deve risolverlo, altrimenti si riscaricherebbe in streaming un capitolo già in libreria.
     */
    @Test
    fun downloadedChapterFor_ripiegaSullaChiavePerNumero() {
        val entry = chapterEntry(number = 1)
        val details = details(mangaUrl = "https://mangapill.com/manga/12345", title = "Berserk")
        val downloaded = downloadedChapter(number = "1.0", chapterId = "url:vecchio-indirizzo")
        val library = listOf(
            series(
                title = "Berserk",
                mangaUrl = "https://mangapill.com/manga/12345",
                chapters = listOf(downloaded),
            ),
        )

        assertEquals(downloaded, LibraryMatching.downloadedChapterFor(details, entry, library))
    }

    @Test
    fun downloadedChapterFor_nullSenzaCopiaInLibreria() {
        val details = details(mangaUrl = "https://mangapill.com/manga/12345", title = "Berserk")
        val library = listOf(
            series(
                title = "Berserk",
                mangaUrl = "https://mangapill.com/manga/12345",
                chapters = listOf(downloadedChapter(number = "2", chapterId = "id-2")),
            ),
        )

        // Capitolo non scaricato in una serie presente…
        assertNull(LibraryMatching.downloadedChapterFor(details, chapterEntry(number = 1), library))
        // …e serie non presente affatto.
        assertNull(LibraryMatching.downloadedChapterFor(details, chapterEntry(number = 1), emptyList()))
    }

    /**
     * Invariante che tiene insieme badge e apertura: ogni capitolo che la lista marca come
     * scaricato dev'essere anche risolvibile in un file, e viceversa.
     */
    @Test
    fun downloadedChapterFor_eCoerenteConDownloadedChapterKeys() {
        val details = details(mangaUrl = "https://mangapill.com/manga/12345", title = "Berserk")
        val entries = (1..3).map { chapterEntry(number = it) }
        val library = listOf(
            series(
                title = "Berserk",
                mangaUrl = "https://mangapill.com/manga/12345",
                chapters = listOf(
                    downloadedChapter(number = "1", chapterId = DownloadStorage.stableChapterId(entries[0])),
                    downloadedChapter(number = "3", chapterId = "url:altro"),
                ),
            ),
        )
        val keys = LibraryMatching.downloadedChapterKeys(details, library)

        entries.forEach { entry ->
            val markedDownloaded = DownloadStorage.stableChapterId(entry) in keys ||
                DownloadStorage.chapterNumberKey(entry.displayNumber(), entry.variantTag) in keys
            val resolved = LibraryMatching.downloadedChapterFor(details, entry, library)
            assertEquals(
                "Capitolo ${entry.numberText}: badge e apertura devono concordare",
                markedDownloaded,
                resolved != null,
            )
        }
    }

    // ---- builder ----

    private fun details(mangaUrl: String, title: String) = MangaDetails(
        sourceId = MangaSourceIds.MANGAPILL,
        title = title,
        coverUrl = null,
        mangaUrl = mangaUrl,
        chapters = emptyList(),
    )

    private fun chapterEntry(number: Int) = ChapterEntry(
        numberText = number.toString(),
        numberValue = BigDecimal(number),
        url = "https://mangapill.com/chapters/$number/berserk-chapter-$number",
        slug = "berserk-chapter-$number",
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
