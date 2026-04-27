package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaSourcesTest {

    @Test
    fun mangapillCanonicalSeriesUrl_normalizesChapterUrl() {
        val normalized = MangapillSource.canonicalSeriesUrl(
            "https://mangapill.com/chapters/12345/berserk-chapter-10",
        )

        assertEquals("https://mangapill.com/manga/12345", normalized)
    }

    @Test
    fun mangapillCanonicalSeriesUrl_stripsMangaSlugForStableIdentity() {
        val normalized = MangapillSource.canonicalSeriesUrl(
            "https://mangapill.com/manga/12345/berserk",
        )

        assertEquals("https://mangapill.com/manga/12345", normalized)
    }

    @Test
    fun sourceCatalog_mangapillIdentityKeyMatchesSluggedAndChapterUrls() {
        val sluggedKey = MangaSourceCatalog.identityKey(
            sourceId = MangaSourceIds.MANGAPILL,
            mangaUrl = "https://mangapill.com/manga/12345/berserk",
        )
        val chapterKey = MangaSourceCatalog.identityKey(
            sourceId = MangaSourceIds.MANGAPILL,
            mangaUrl = "https://mangapill.com/chapters/12345/berserk-chapter-10",
        )

        assertEquals("mangapill::https://mangapill.com/manga/12345", sluggedKey)
        assertEquals(sluggedKey, chapterKey)
    }

    @Test
    fun hastaSearchResponse_mapsAbsoluteUrlsAndSourceId() {
        val results = HastaTeamSource.parseSearchResponse(
            """
            {
              "comics": [
                {
                  "title": "Yotsuba&!",
                  "thumbnail": "https://reader.hastateam.com/storage/comics/yotsuba.jpg",
                  "url": "/comics/yotsuba"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, results.size)
        assertEquals(MangaSourceIds.HASTA_TEAM, results.first().sourceId)
        assertEquals("https://reader.hastateam.com/comics/yotsuba", results.first().mangaUrl)
    }

    @Test
    fun hastaMangaDetails_sortsChaptersAscending_andHandlesSubchapter() {
        val details = HastaTeamSource.parseMangaDetails(
            """
            {
              "comic": {
                "title": "Yotsuba&!",
                "thumbnail": "https://reader.hastateam.com/storage/comics/yotsuba.jpg",
                "url": "/comics/yotsuba",
                "chapters": [
                  {
                    "chapter": 2,
                    "subchapter": null,
                    "url": "/read/yotsuba/it/vol/1/ch/2",
                    "slug_lang_vol_ch_sub": "it-1-2-N"
                  },
                  {
                    "chapter": 1,
                    "subchapter": 5,
                    "url": "/read/yotsuba/it/vol/1/ch/1/sub/5",
                    "slug_lang_vol_ch_sub": "it-1-1-5"
                  },
                  {
                    "chapter": 1,
                    "subchapter": null,
                    "url": "/read/yotsuba/it/vol/1/ch/1",
                    "slug_lang_vol_ch_sub": "it-1-1-N"
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals(MangaSourceIds.HASTA_TEAM, details.sourceId)
        assertEquals("https://reader.hastateam.com/comics/yotsuba", details.mangaUrl)
        assertEquals(listOf("1", "1.5", "2"), details.chapters.map { it.numberText })
    }

    @Test
    fun hastaChapterPages_readsPagesFromJson() {
        val pages = HastaTeamSource.parseChapterPageUrls(
            """
            {
              "chapter": {
                "pages": [
                  "https://reader.hastateam.com/storage/comics/yotsuba/001.png",
                  "https://reader.hastateam.com/storage/comics/yotsuba/002.png"
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals(2, pages.size)
        assertTrue(pages.first().endsWith("001.png"))
    }

    @Test
    fun sourceCatalog_resolvesSourceIdAndIdentityKeys() {
        val hastaResolved = MangaSourceCatalog.resolveSourceId(
            sourceId = null,
            url = "https://reader.hastateam.com/read/yotsuba/it/vol/1/ch/1",
        )
        val identityKey = MangaSourceCatalog.identityKey(
            sourceId = MangaSourceIds.HASTA_TEAM,
            mangaUrl = "https://reader.hastateam.com/read/yotsuba/it/vol/1/ch/1",
        )

        assertEquals(MangaSourceIds.HASTA_TEAM, hastaResolved)
        assertEquals(
            "hasta_team::https://reader.hastateam.com/comics/yotsuba",
            identityKey,
        )
    }

    @Test
    fun hastaChapterApiUrl_convertsReaderUrlToApiUrl() {
        val apiUrl = HastaTeamSource.chapterApiUrl(
            "https://reader.hastateam.com/read/yotsuba/it/vol/1/ch/1/sub/5#1",
        )

        assertNotNull(apiUrl)
        assertEquals(
            "https://reader.hastateam.com/api/read/yotsuba/it/vol/1/ch/1/sub/5",
            apiUrl,
        )
    }

    @Test
    fun hastaFilterByTitle_supportsBlankAndSingleLetterQueries() {
        val results = listOf(
            MangaSearchResult(
                sourceId = MangaSourceIds.HASTA_TEAM,
                title = "Yotsuba&!",
                mangaUrl = "https://reader.hastateam.com/comics/yotsuba",
                coverUrl = null,
            ),
            MangaSearchResult(
                sourceId = MangaSourceIds.HASTA_TEAM,
                title = "Berserk",
                mangaUrl = "https://reader.hastateam.com/comics/berserk",
                coverUrl = null,
            ),
            MangaSearchResult(
                sourceId = MangaSourceIds.HASTA_TEAM,
                title = "Alive",
                mangaUrl = "https://reader.hastateam.com/comics/alive",
                coverUrl = null,
            ),
        )

        assertEquals(
            listOf("Alive", "Berserk", "Yotsuba&!"),
            HastaTeamSource.run {
                results
                    .filterByTitle("")
                    .sortedAlphabetically()
                    .map(MangaSearchResult::title)
            },
        )
        assertEquals(
            listOf("Berserk"),
            HastaTeamSource.run {
                results
                    .filterByTitle("k")
                    .sortedAlphabetically()
                    .map(MangaSearchResult::title)
            },
        )
    }

    @Test
    fun searchConfig_allowsBrowseAllForHastaTeam() {
        val hastaConfig = MangaSourceCatalog.searchConfig(MangaSourceIds.HASTA_TEAM)
        val mangapillConfig = MangaSourceCatalog.searchConfig(MangaSourceIds.MANGAPILL)

        assertEquals(1, hastaConfig.minQueryLength)
        assertEquals(true, hastaConfig.showAllOnEmptyQuery)
        assertEquals(3, mangapillConfig.minQueryLength)
        assertEquals(false, mangapillConfig.showAllOnEmptyQuery)
    }
}
