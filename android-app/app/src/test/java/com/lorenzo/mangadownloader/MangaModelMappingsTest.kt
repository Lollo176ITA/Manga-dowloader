package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaModelMappingsTest {
    private val result = MangaSearchResult(
        sourceId = "source",
        title = "Title",
        mangaUrl = "https://example.test/manga",
        coverUrl = "https://example.test/cover.jpg",
    )

    @Test
    fun searchResult_preservesIdentityAcrossMappings() {
        val favorite = result.toFavoriteManga()
        assertEquals(result, favorite.toSearchResult())
        assertEquals(result, result.toDetailsStub().toSearchResult())
        assertEquals(
            MangaSourceCatalog.identityKey(result.sourceId, result.mangaUrl),
            favorite.identityKey(),
        )
    }

    @Test
    fun detailsAndUpdateEvent_mapToSearchResult() {
        val details = result.toDetailsStub()
        val event = FavoriteUpdateEvent(
            sourceId = result.sourceId,
            title = result.title,
            mangaUrl = result.mangaUrl,
            coverUrl = result.coverUrl,
        )

        assertEquals(result, details.toSearchResult())
        assertEquals(result, event.toSearchResult())
        assertEquals(details.toFavoriteManga(), result.toFavoriteManga())
    }
}
