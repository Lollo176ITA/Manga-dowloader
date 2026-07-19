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
    fun parseMediaResponse_leggeNativeESynonyms() {
        val json = """
            {"data":{"Page":{"media":[{
                "id":53390,
                "title":{"romaji":"Shingeki no Kyojin","english":"Attack on Titan","native":"進撃の巨人"},
                "synonyms":["L'attacco dei giganti","AoT"],
                "coverImage":{"large":"https://img/aot.jpg"},
                "genres":["Action"],"averageScore":84,"description":"...","status":"FINISHED"
            }]}}}
        """.trimIndent()
        val parsed = AniListClient.parseMediaResponse(json).single()
        assertEquals("進撃の巨人", parsed.titleNative)
        assertEquals(listOf("L'attacco dei giganti", "AoT"), parsed.synonyms)
        assertEquals(
            listOf("Attack on Titan", "Shingeki no Kyojin", "進撃の巨人", "L'attacco dei giganti", "AoT"),
            parsed.allTitles(),
        )
    }

    @Test
    fun parseMediaResponse_nativeESynonymsAssentiRestanoVuoti() {
        val json = """
            {"data":{"Page":{"media":[{
                "id":1,"title":{"romaji":"X","english":null}
            }]}}}
        """.trimIndent()
        val parsed = AniListClient.parseMediaResponse(json).single()
        assertNull(parsed.titleNative)
        assertTrue(parsed.synonyms.isEmpty())
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

    @Test
    fun parseMediaResponse_readsChaptersAndFormatWhenPresent() {
        val response = """
            {
              "data": { "Page": { "media": [ {
                "id": 7,
                "title": { "romaji": "Monster", "english": "Monster" },
                "coverImage": { "large": null },
                "genres": [],
                "averageScore": 90,
                "description": null,
                "status": "FINISHED",
                "chapters": 162,
                "format": "MANGA"
              } ] } }
            }
        """.trimIndent()

        val manga = AniListClient.parseMediaResponse(response).single()
        assertEquals(162, manga.chapters)
        assertEquals("MANGA", manga.format)
        // La query della Scopri non chiede questi campi: devono restare null senza errori.
        assertNull(AniListClient.parseMediaResponse(sampleResponse)[0].chapters)
        assertNull(AniListClient.parseMediaResponse(sampleResponse)[0].format)
    }

    @Test
    fun parseViewerResponse_mapsProfileAndScoreFormat() {
        val response = """
            {
              "data": {
                "Viewer": {
                  "id": 42,
                  "name": "Lollo",
                  "mediaListOptions": { "scoreFormat": "POINT_100" }
                }
              }
            }
        """.trimIndent()

        val viewer = AniListClient.parseViewerResponse(response)
        assertEquals(42, viewer?.id)
        assertEquals("Lollo", viewer?.name)
        assertEquals(AniListScoreFormat.POINT_100, viewer?.scoreFormat)

        assertNull(AniListClient.parseViewerResponse("""{ "data": { "Viewer": null } }"""))
    }

    @Test
    fun parseMediaEntryResponse_mapsEntryAndMissingEntry() {
        val withEntry = """
            {
              "data": { "Media": {
                "id": 30002,
                "chapters": 162,
                "mediaListEntry": { "status": "CURRENT", "progress": 12, "score": 8.5 }
              } }
            }
        """.trimIndent()

        val mediaEntry = AniListClient.parseMediaEntryResponse(withEntry)
        assertEquals(30002, mediaEntry?.mediaId)
        assertEquals(162, mediaEntry?.totalChapters)
        assertEquals(AniListListStatus.CURRENT, mediaEntry?.entry?.status)
        assertEquals(12, mediaEntry?.entry?.progress)
        assertEquals(8.5, mediaEntry?.entry?.score ?: 0.0, 0.001)

        val withoutEntry = """
            { "data": { "Media": { "id": 30002, "chapters": null, "mediaListEntry": null } } }
        """.trimIndent()
        val noEntry = AniListClient.parseMediaEntryResponse(withoutEntry)
        assertEquals(30002, noEntry?.mediaId)
        assertNull(noEntry?.totalChapters)
        assertNull(noEntry?.entry)
    }

    @Test
    fun parseSaveEntryResponse_mapsSavedEntryAndZeroScoreAsNone() {
        val response = """
            {
              "data": { "SaveMediaListEntry": { "status": "COMPLETED", "progress": 162, "score": 0 } }
            }
        """.trimIndent()

        val saved = AniListClient.parseSaveEntryResponse(response)
        assertEquals(AniListListStatus.COMPLETED, saved?.status)
        assertEquals(162, saved?.progress)
        // Su AniList 0 significa "nessun voto": non va riportato come voto reale.
        assertNull(saved?.score)
    }
}
