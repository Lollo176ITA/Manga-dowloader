package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core puro dei "Consigliati per te": scelta dei semi, aggregazione e parsing AniList. */
class RecommendationsTest {

    private fun favorite(title: String, addedAt: Long) =
        FavoriteManga("mangapill", title, "https://mangapill.com/manga/$title", null, addedAt)

    private fun memoryRecord(seriesTitle: String, lastReadAtMillis: Long) = ReadChapterMemory(
        seriesKey = seriesTitle,
        seriesTitle = seriesTitle,
        chapterLabel = "Capitolo 1",
        pagesRead = 10,
        pageCount = 10,
        isRead = true,
        lastReadAtMillis = lastReadAtMillis,
    )

    private fun manga(id: Int, title: String) = AniListManga(
        id = id,
        titleRomaji = title,
        titleEnglish = title,
        coverUrl = null,
        genres = emptyList(),
        averageScore = 80,
        description = null,
        status = MangaPublicationStatus.UNKNOWN,
    )

    // --- selectRecommendationSeeds ---

    @Test
    fun seeds_favoritesFirstThenRecentReads_dedupCaseInsensitive() {
        val favorites = listOf(favorite("Berserk", 2L), favorite("Vinland Saga", 5L))
        val memory = mapOf(
            "a/1.cbz" to memoryRecord("BERSERK", 9_000L), // dup del preferito: scartato
            "b/1.cbz" to memoryRecord("Vagabond", 8_000L),
            "c/1.cbz" to memoryRecord("Mai letta", 0L), // senza timestamp: fuori
        )
        assertEquals(
            listOf("Vinland Saga", "Berserk", "Vagabond"),
            selectRecommendationSeeds(favorites, memory),
        )
    }

    @Test
    fun seeds_respectsLimit_andSkipsBlanks() {
        val favorites = (1..10).map { favorite("Titolo $it", it.toLong()) } + favorite("  ", 99L)
        assertEquals(6, selectRecommendationSeeds(favorites, emptyMap()).size)
    }

    @Test
    fun seeds_emptyInputs_returnsEmpty() {
        assertTrue(selectRecommendationSeeds(emptyList(), emptyMap()).isEmpty())
    }

    // --- aggregateRecommendations ---

    @Test
    fun aggregate_ranksByHowManySeedsRecommend_thenByRating() {
        val recs = listOf(
            AniListRecommendation(seedMediaId = 1, rating = 5, manga = manga(10, "Doppio")),
            AniListRecommendation(seedMediaId = 2, rating = 1, manga = manga(10, "Doppio")),
            AniListRecommendation(seedMediaId = 1, rating = 100, manga = manga(20, "Singolo forte")),
            AniListRecommendation(seedMediaId = 2, rating = 3, manga = manga(30, "Singolo debole")),
        )
        assertEquals(
            listOf(10, 20, 30),
            aggregateRecommendations(recs).map { it.id },
        )
    }

    @Test
    fun aggregate_excludesSeedsAndOwnedTitles() {
        val recs = listOf(
            AniListRecommendation(1, 5, manga(10, "Berserk")), // già posseduto
            AniListRecommendation(1, 4, manga(2, "Seme stesso")), // è un seme
            AniListRecommendation(1, 3, manga(30, "Nuovo")),
        )
        val result = aggregateRecommendations(
            recommendations = recs,
            excludeIds = setOf(1, 2),
            excludeTitles = setOf(normalizedRecommendationTitle("berserk")),
        )
        assertEquals(listOf(30), result.map { it.id })
    }

    @Test
    fun aggregate_respectsLimit() {
        val recs = (1..20).map { AniListRecommendation(1, it, manga(it, "Titolo $it")) }
        assertEquals(12, aggregateRecommendations(recs).size)
    }

    // --- parseRecommendationsResponse ---

    @Test
    fun parseRecommendations_mapsSeedsAndSkipsAdultOrEmptyNodes() {
        val response = """
            {
              "data": {
                "Page": {
                  "media": [
                    {
                      "id": 1,
                      "recommendations": {
                        "nodes": [
                          {
                            "rating": 42,
                            "mediaRecommendation": {
                              "id": 100,
                              "title": { "romaji": "Consigliato", "english": null },
                              "coverImage": { "large": "https://img/c.jpg" },
                              "genres": ["Action"],
                              "averageScore": 84,
                              "description": "Trama",
                              "status": "FINISHED",
                              "isAdult": false
                            }
                          },
                          {
                            "rating": 99,
                            "mediaRecommendation": {
                              "id": 101,
                              "title": { "romaji": "Adulto" },
                              "isAdult": true
                            }
                          },
                          { "rating": 1, "mediaRecommendation": null }
                        ]
                      }
                    },
                    { "id": 2, "recommendations": { "nodes": [] } }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = AniListClient.parseRecommendationsResponse(response)
        assertEquals(1, result.size)
        assertEquals(1, result[0].seedMediaId)
        assertEquals(42, result[0].rating)
        assertEquals(100, result[0].manga.id)
        assertEquals("Consigliato", result[0].manga.displayTitle())
    }

    @Test
    fun parseRecommendations_emptyOnErrorResponse() {
        assertTrue(
            AniListClient.parseRecommendationsResponse("""{"errors":[{"message":"boom"}]}""").isEmpty(),
        )
    }
}
