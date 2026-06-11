package com.lorenzo.mangadownloader

import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logica pura di ordinamento/filtro dei preferiti. JVM, niente Android. */
class FavoritesOrganizationTest {

    private fun fav(title: String, n: Int, addedAt: Long = 0L) =
        FavoriteManga("mangapill", title, "https://mangapill.com/manga/$n", null, addedAt)

    private fun key(f: FavoriteManga) = MangaSourceCatalog.identityKey(f.sourceId, f.mangaUrl)

    @Test
    fun sortByDateAdded_descWithLegacyZerosLast() {
        val r = fav("R", 1, addedAt = 0L)
        val s = fav("S", 2, addedAt = 0L)
        val p = fav("P", 3, addedAt = 300L)
        val q = fav("Q", 4, addedAt = 100L)
        val sorted = sortFavorites(listOf(r, s, p, q), FavoriteSort.DATE_ADDED, emptyMap(), emptyMap())
        assertEquals(listOf("P", "Q", "R", "S"), sorted.map { it.title })
    }

    @Test
    fun sortByTitle_caseInsensitive() {
        val sorted = sortFavorites(
            listOf(fav("banana", 1), fav("Apple", 2), fav("cherry", 3)),
            FavoriteSort.TITLE_ASC,
            emptyMap(),
            emptyMap(),
        )
        assertEquals(listOf("Apple", "banana", "cherry"), sorted.map { it.title })
    }

    @Test
    fun sortByPublicationStatus_ongoingUnknownCompletedDropped() {
        val a = fav("A", 1)
        val b = fav("B", 2)
        val c = fav("C", 3)
        val d = fav("D", 4)
        val statusByKey = mapOf(
            key(a) to MangaPublicationStatus.ONGOING,
            key(b) to MangaPublicationStatus.COMPLETED,
            key(d) to MangaPublicationStatus.DROPPED,
            // c: assente → UNKNOWN
        )
        val sorted = sortFavorites(listOf(b, d, c, a), FavoriteSort.PUBLICATION_STATUS, statusByKey, emptyMap())
        assertEquals(listOf("A", "C", "B", "D"), sorted.map { it.title })
    }

    @Test
    fun sortByLastUpdate_higherChapterFirstMissingLast() {
        val x = fav("X", 1)
        val y = fav("Y", 2)
        val z = fav("Z", 3)
        val seen = mapOf(
            key(x) to FavoriteSeenState("5"),
            key(y) to FavoriteSeenState("12"),
            // z: nessuno → in fondo
        )
        val sorted = sortFavorites(listOf(x, y, z), FavoriteSort.LAST_UPDATE, emptyMap(), seen)
        assertEquals(listOf("Y", "X", "Z"), sorted.map { it.title })
    }

    @Test
    fun filterByText_isCaseInsensitive() {
        val list = listOf(fav("Berserk", 1), fav("Naruto", 2))
        assertEquals(listOf("Berserk"), filterFavorites(list, "ber").map { it.title })
    }

    @Test
    fun filterByReadingState_nullIsAll_missingKeyCountsAsToStart() {
        val a = fav("A", 1)
        val b = fav("B", 2)
        val list = listOf(a, b)
        val states = mapOf(key(a) to FavoriteReadingState.IN_PROGRESS)

        assertEquals(2, filterFavorites(list, "", null, states).size)
        assertEquals(
            listOf("A"),
            filterFavorites(list, "", FavoriteReadingState.IN_PROGRESS, states).map { it.title },
        )
        // B non è nella mappa → conta come "Da iniziare".
        assertEquals(
            listOf("B"),
            filterFavorites(list, "", FavoriteReadingState.TO_START, states).map { it.title },
        )
    }

    // --- Etichette di lettura automatiche ---

    private fun dlChapter(number: String, readerPageIndex: Int? = null) = DownloadedChapter(
        title = "Cap $number",
        numberText = number,
        numberValue = number.toBigDecimalOrNull(),
        volumeText = null,
        labelPrefix = "Capitolo",
        file = File("$number.cbz"),
        relativePath = "s/$number.cbz",
        chapterId = "id-$number",
        isRead = false,
        readerPageIndex = readerPageIndex,
        readerPageCount = if (readerPageIndex != null) 10 else null,
    )

    private fun dlSeries(n: Int, chapters: List<DownloadedChapter>, total: Int, readIds: Set<String>) =
        DownloadedSeries(
            sourceId = "mangapill",
            title = "T$n",
            mangaUrl = "https://mangapill.com/manga/$n",
            coverFile = null,
            directory = File("T$n"),
            chapters = chapters,
            totalChapterCount = total,
            readChapterIds = readIds,
        )

    @Test
    fun readingState_nullSeriesIsToStart() {
        assertEquals(FavoriteReadingState.TO_START, favoriteReadingState(null))
    }

    @Test
    fun readingState_fullyReadIsCompleted() {
        val series = dlSeries(1, listOf(dlChapter("1"), dlChapter("2")), total = 2, readIds = setOf("id-1", "id-2"))
        assertEquals(FavoriteReadingState.COMPLETED, favoriteReadingState(series))
    }

    @Test
    fun readingState_someReadIsInProgress() {
        val series = dlSeries(1, listOf(dlChapter("1"), dlChapter("2"), dlChapter("3")), total = 3, readIds = setOf("id-1"))
        assertEquals(FavoriteReadingState.IN_PROGRESS, favoriteReadingState(series))
    }

    @Test
    fun readingState_downloadedButUnreadIsToStart() {
        val series = dlSeries(1, listOf(dlChapter("1"), dlChapter("2")), total = 2, readIds = emptySet())
        assertEquals(FavoriteReadingState.TO_START, favoriteReadingState(series))
    }

    @Test
    fun readingState_readerProgressCountsAsInProgress() {
        val series = dlSeries(1, listOf(dlChapter("1", readerPageIndex = 3), dlChapter("2")), total = 2, readIds = emptySet())
        assertEquals(FavoriteReadingState.IN_PROGRESS, favoriteReadingState(series))
    }

    @Test
    fun readingStatesByKey_matchesFavoritesToLibrary() {
        val a = fav("A", 1)
        val b = fav("B", 2)
        val library = listOf(dlSeries(1, listOf(dlChapter("1"), dlChapter("2")), total = 2, readIds = setOf("id-1", "id-2")))
        val states = favoriteReadingStatesByKey(listOf(a, b), library)
        assertEquals(FavoriteReadingState.COMPLETED, states[key(a)])
        assertEquals(FavoriteReadingState.TO_START, states[key(b)])
    }

    // --- Shortcut "Leggi": selezione primi capitoli ---

    private fun chEntry(n: String) =
        ChapterEntry(numberText = n, numberValue = BigDecimal(n), url = "https://x/$n", slug = n)

    @Test
    fun firstChaptersForReading_picksLowestThreeRegardlessOfSourceOrder() {
        val chapters = listOf("5", "4", "3", "2", "1").map { chEntry(it) } // elencati newest-first
        assertEquals(listOf("1", "2", "3"), firstChaptersForReading(chapters, 3).map { it.numberText })
    }

    @Test
    fun firstChaptersForReading_handlesFewerThanCountAndEmpty() {
        assertEquals(listOf("1", "2"), firstChaptersForReading(listOf(chEntry("2"), chEntry("1")), 3).map { it.numberText })
        assertTrue(firstChaptersForReading(emptyList(), 3).isEmpty())
    }
}
