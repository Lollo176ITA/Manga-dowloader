package com.lorenzo.mangadownloader.app

import com.lorenzo.mangadownloader.data.store.FavoriteUpdateEvent
import com.lorenzo.mangadownloader.domain.adultRegistryKeys
import com.lorenzo.mangadownloader.domain.reading.ReadChapterMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AdultSeriesVisibilityTest {

    private val adult = FavoriteManga("mangapill", "Serie Spinta", "https://mangapill/1", null, seriesKey = "anilist:9")
    private val safe = FavoriteManga("mangapill", "One Piece", "https://mangapill/2", null, seriesKey = "anilist:21")
    private val keys = adultRegistryKeys(adult.title, adult.sourceId, adult.mangaUrl, adult.seriesKey)

    @Test
    fun favoritesAndFeed_loseOnlyTheRegisteredSeries() {
        assertEquals(listOf(safe), listOf(adult, safe).withoutRegisteredAdult(keys))

        val events = listOf(
            FavoriteUpdateEvent(title = adult.title, seriesKey = adult.seriesKey),
            FavoriteUpdateEvent(title = safe.title, seriesKey = safe.seriesKey),
        )
        assertEquals(listOf(safe.title), events.withoutRegisteredAdult(keys).map { it.title })
    }

    @Test
    fun readingMemory_isMatchedByTitleEvenWithoutSource() {
        // Capitolo scaricato: il record conosce solo la cartella e il titolo.
        val memory = mapOf(
            "dl:1" to memory(seriesTitle = "Serie Spinta"),
            "dl:2" to memory(seriesTitle = "One Piece"),
        )

        assertEquals(setOf("dl:2"), memory.withoutRegisteredAdult(keys).keys)
    }

    @Test
    fun filterOff_keepsTheSameListInstances() {
        val favorites = listOf(adult, safe)
        assertSame(favorites, favorites.withoutRegisteredAdult(emptySet()))

        val state = MangaUiState(adultSeriesKeys = keys)
        assertEquals(emptySet<String>(), state.activeAdultKeys())
        assertEquals(keys, state.copy(settings = AppSettings(hideAdultContent = true)).activeAdultKeys())
        assertEquals(keys, state.copy(settings = AppSettings(parentalControlEnabled = true)).activeAdultKeys())
    }

    private fun memory(seriesTitle: String) = ReadChapterMemory(
        seriesKey = "folder/$seriesTitle",
        seriesTitle = seriesTitle,
        chapterLabel = "Capitolo 1",
        pagesRead = 3,
        pageCount = 20,
        isRead = false,
        lastReadAtMillis = 1L,
    )
}
