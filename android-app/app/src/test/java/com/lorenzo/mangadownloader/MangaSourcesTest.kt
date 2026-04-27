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
    fun mangaWorldCanonicalSeriesUrl_normalizesSeriesAndReaderUrls() {
        val series = MangaWorldSource.canonicalSeriesUrl(
            "https://www.mangaworld.mx/manga/2604/return-of-the-blossoming-blade/",
        )
        val chapter = MangaWorldSource.canonicalSeriesUrl(
            "https://www.mangaworld.mx/manga/2604/return-of-the-blossoming-blade/read/61b258ba55fbd201aaff53e9/1?style=list",
        )

        assertEquals("https://www.mangaworld.mx/manga/2604", series)
        assertEquals(series, chapter)
    }

    @Test
    fun mangaWorldSearchResults_readsArchiveEntries() {
        val results = MangaWorldSource.parseSearchResults(
            """
            <div class="comics-grid">
              <div class="entry">
                <a class="thumb position-relative" href="https://www.mangaworld.mx/manga/2604/return-of-the-blossoming-blade" title="Return of the Mount Hua Sect">
                  <img src="https://cdn.mangaworld.mx/mangas/61b25812c836ab0222289f78.png" alt="Return of the Mount Hua Sect">
                </a>
                <div class="content">
                  <p class="name m-0">
                    <a class="manga-title" href="https://www.mangaworld.mx/manga/2604/return-of-the-blossoming-blade" title="Return of the Mount Hua Sect">Return of the Mount Hua Sect</a>
                  </p>
                </div>
              </div>
            </div>
            """.trimIndent(),
        )

        assertEquals(1, results.size)
        assertEquals(MangaSourceIds.MANGA_WORLD, results.first().sourceId)
        assertEquals("Return of the Mount Hua Sect", results.first().title)
        assertEquals("https://www.mangaworld.mx/manga/2604", results.first().mangaUrl)
        assertTrue(results.first().coverUrl!!.contains("cdn.mangaworld.mx"))
    }

    @Test
    fun mangaWorldMangaDetails_sortsChaptersAscending() {
        val details = MangaWorldSource.parseMangaDetails(
            """
            <section id="manga-page">
              <div class="comic-info">
                <div class="thumb"><img src="https://cdn.mangaworld.mx/mangas/cover.png"></div>
                <h1 class="name bigger">Return of the Mount Hua Sect</h1>
              </div>
              <div id="chapterList">
                <div class="chapters-wrapper">
                  <div class="chapter"><a class="chap" href="https://www.mangaworld.mx/manga/2604/return-of-the-blossoming-blade/read/chapter-2?style=list" title="Return Capitolo 02 Scan ITA"><span>Capitolo 02</span></a></div>
                  <div class="chapter"><a class="chap" href="https://www.mangaworld.mx/manga/2604/return-of-the-blossoming-blade/read/chapter-1?style=list" title="Return Capitolo 01 Scan ITA"><span>Capitolo 01</span></a></div>
                </div>
              </div>
            </section>
            """.trimIndent(),
            "https://www.mangaworld.mx/manga/2604/return-of-the-blossoming-blade",
        )

        assertEquals(MangaSourceIds.MANGA_WORLD, details.sourceId)
        assertEquals("https://www.mangaworld.mx/manga/2604", details.mangaUrl)
        assertEquals(listOf("01", "02"), details.chapters.map { it.numberText })
        assertEquals(
            "https://www.mangaworld.mx/manga/2604/return-of-the-blossoming-blade/read/chapter-1?style=list",
            details.chapters.first().url,
        )
    }

    @Test
    fun mangaWorldMangaDetails_readsVolumeGroupsWhenPresent() {
        val details = MangaWorldSource.parseMangaDetails(
            """
            <section id="manga-page">
              <h1 class="name bigger">Shingeki no Kyojin</h1>
              <div id="chapterList">
                <div class="chapters-wrapper py-2 pl-0">
                  <div class="volume-element pl-2">
                    <div class="volume w-100 py-2"><p class="volume-name d-inline">Volume 02</p></div>
                    <div class="volume-chapters pl-2">
                      <div class="chapter"><a class="chap" href="https://www.mangaworld.mx/manga/1816/shingeki-no-kyojin/read/chapter-5" title="Shingeki no Kyojin Capitolo 05 Scan ITA"><span>Capitolo 05</span></a></div>
                    </div>
                  </div>
                  <div class="volume-element pl-2">
                    <div class="volume w-100 py-2"><p class="volume-name d-inline">Volume 01</p></div>
                    <div class="volume-chapters pl-2">
                      <div class="chapter"><a class="chap" href="https://www.mangaworld.mx/manga/1816/shingeki-no-kyojin/read/chapter-1" title="Shingeki no Kyojin Capitolo 01 Scan ITA"><span>Capitolo 01</span></a></div>
                    </div>
                  </div>
                </div>
              </div>
            </section>
            """.trimIndent(),
            "https://www.mangaworld.mx/manga/1816/shingeki-no-kyojin",
        )

        assertEquals(listOf("01", "05"), details.chapters.map { it.numberText })
        assertEquals(listOf("Volume 01", "Volume 02"), details.chapters.map { it.volumeText })
        assertEquals("Volume 01 - Capitolo 1", details.chapters.first().displayLabel())
    }

    @Test
    fun mangaWorldChapterPages_readsListReaderImages() {
        val pages = MangaWorldSource.parsePageImageUrls(
            """
            <div class="col-12 text-center position-relative" id="page">
              <img id="page-0" class="page-image img-fluid" src="https://cdn.mangaworld.mx/chapters/series/chapter/1.jpg"><br>
              <img id="page-1" class="page-image img-fluid" src="https://cdn.mangaworld.mx/chapters/series/chapter/2.png"><br>
            </div>
            """.trimIndent(),
            "https://www.mangaworld.mx/manga/2604/return-of-the-blossoming-blade/read/61b258ba55fbd201aaff53e9/1?style=list",
        )

        assertEquals(2, pages.size)
        assertTrue(pages.first().endsWith("1.jpg"))
        assertTrue(pages.last().endsWith("2.png"))
    }

    @Test
    fun sourceCatalog_resolvesMangaWorldUrlAndIdentityKey() {
        val resolved = MangaSourceCatalog.resolveSourceId(
            sourceId = null,
            url = "https://www.mangaworld.mx/manga/2604/return-of-the-blossoming-blade/read/61b258ba55fbd201aaff53e9/1?style=list",
        )
        val identityKey = MangaSourceCatalog.identityKey(
            sourceId = MangaSourceIds.MANGA_WORLD,
            mangaUrl = "https://www.mangaworld.mx/manga/2604/return-of-the-blossoming-blade",
        )

        assertEquals(MangaSourceIds.MANGA_WORLD, resolved)
        assertEquals("manga_world::https://www.mangaworld.mx/manga/2604", identityKey)
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
