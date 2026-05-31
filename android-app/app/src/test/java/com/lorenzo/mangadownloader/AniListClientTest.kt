package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniListClientTest {

    private val sampleResponse = """
        {
          "data": {
            "Page": {
              "media": [
                {
                  "id": 1,
                  "title": { "romaji": "Berserk", "english": "Berserk" },
                  "coverImage": { "large": "https://img/berserk.jpg" },
                  "genres": ["Action", "Horror"],
                  "averageScore": 93,
                  "description": "A dark <b>fantasy</b>.<br><br>Guts fights.",
                  "status": "RELEASING"
                },
                {
                  "id": 2,
                  "title": { "romaji": "Yotsuba to!", "english": null },
                  "coverImage": { "large": null },
                  "genres": ["Comedy"],
                  "averageScore": null,
                  "description": null,
                  "status": "FINISHED"
                },
                {
                  "id": 3,
                  "title": { "romaji": null, "english": null },
                  "coverImage": { "large": null },
                  "genres": [],
                  "averageScore": 50,
                  "description": "senza titolo, da saltare",
                  "status": "FINISHED"
                }
              ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun parseMediaResponse_skipsEntriesWithoutTitleAndMapsFields() {
        val result = AniListClient.parseMediaResponse(sampleResponse)

        // La terza voce (senza alcun titolo) viene scartata.
        assertEquals(2, result.size)

        val first = result[0]
        assertEquals(1, first.id)
        assertEquals("Berserk", first.titleEnglish)
        assertEquals("Berserk", first.titleRomaji)
        assertEquals("https://img/berserk.jpg", first.coverUrl)
        assertEquals(listOf("Action", "Horror"), first.genres)
        assertEquals(93, first.averageScore)
        assertEquals(MangaPublicationStatus.ONGOING, first.status)

        val second = result[1]
        assertEquals(2, second.id)
        assertNull(second.titleEnglish)
        assertEquals("Yotsuba to!", second.titleRomaji)
        assertNull(second.coverUrl)
        assertNull(second.averageScore)
        assertNull(second.description)
        assertEquals(MangaPublicationStatus.COMPLETED, second.status)
    }

    @Test
    fun parseMediaResponse_stripsHtmlFromDescription() {
        val description = AniListClient.parseMediaResponse(sampleResponse)[0].description
        assertEquals("A dark fantasy.\n\nGuts fights.", description)
    }

    @Test
    fun parseMediaResponse_emptyOnErrorResponse() {
        val errorResponse = """{ "errors": [ { "message": "Boom" } ] }"""
        assertTrue(AniListClient.parseMediaResponse(errorResponse).isEmpty())
    }

    @Test
    fun searchTitle_prefersEnglishThenRomaji() {
        val englishAndRomaji = AniListManga(
            id = 1,
            titleRomaji = "Shingeki no Kyojin",
            titleEnglish = "Attack on Titan",
            coverUrl = null,
            genres = emptyList(),
            averageScore = null,
            description = null,
            status = MangaPublicationStatus.UNKNOWN,
        )
        assertEquals("Attack on Titan", englishAndRomaji.searchTitle())

        val romajiOnly = englishAndRomaji.copy(titleEnglish = null)
        assertEquals("Shingeki no Kyojin", romajiOnly.searchTitle())
        assertEquals("Shingeki no Kyojin", romajiOnly.displayTitle())
    }

    @Test
    fun cleanDescription_handlesNullAndEntities() {
        assertNull(AniListClient.cleanDescription(null))
        assertNull(AniListClient.cleanDescription("   "))
        assertEquals(
            "Tom & Jerry \"say\" hi",
            AniListClient.cleanDescription("Tom &amp; Jerry &quot;say&quot; hi"),
        )
    }
}
