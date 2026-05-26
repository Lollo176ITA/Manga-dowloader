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
    fun mangapillSearchResults_mapsCanonicalUrlsAndMergesDuplicateAnchors() {
        val results = MangapillSource.parseSearchResults(
            """
            <div class="container">
              <a href="/manga/12345/berserk">
                <img data-src="https://cdn.mangapill.com/cover/berserk.jpeg" alt="">
              </a>
              <a href="/manga/12345/berserk" title="Berserk">Berserk</a>
              <a href="/manga/678/oyasumi-punpun">
                <img src="https://cdn.mangapill.com/cover/punpun.jpeg" alt="Oyasumi Punpun">
              </a>
            </div>
            """.trimIndent(),
            "https://mangapill.com/search?q=ber",
        )

        assertEquals(2, results.size)
        assertEquals(MangaSourceIds.MANGAPILL, results.first().sourceId)
        assertEquals("Berserk", results.first().title)
        assertEquals("https://mangapill.com/manga/12345", results.first().mangaUrl)
        assertTrue(results.first().coverUrl!!.endsWith("berserk.jpeg"))
        assertEquals("Oyasumi Punpun", results[1].title)
        assertEquals("https://mangapill.com/manga/678", results[1].mangaUrl)
    }

    @Test
    fun mangapillMangaDetails_sortsChaptersAscendingAndReadsCover() {
        val details = MangapillSource.parseMangaDetails(
            """
            <html><body>
              <h1>Berserk</h1>
              <figure><img data-src="https://cdn.mangapill.com/cover/berserk.jpeg" alt="Berserk"></figure>
              <div id="chapters">
                <a href="/chapters/12345-13900000/berserk-chapter-2" title="Chapter 2">Chapter 2</a>
                <a href="/chapters/12345-13900000/berserk-chapter-10" title="Chapter 10">Chapter 10</a>
                <a href="/chapters/12345-13900000/berserk-chapter-1" title="Chapter 1">Chapter 1</a>
              </div>
            </body></html>
            """.trimIndent(),
            "https://mangapill.com/manga/12345/berserk",
        )

        assertEquals(MangaSourceIds.MANGAPILL, details.sourceId)
        assertEquals("Berserk", details.title)
        assertEquals("https://mangapill.com/manga/12345", details.mangaUrl)
        assertEquals(listOf("1", "2", "10"), details.chapters.map { it.numberText })
        assertTrue(details.chapters.first().url.endsWith("berserk-chapter-1"))
        assertTrue(details.coverUrl!!.endsWith("berserk.jpeg"))
    }

    @Test
    fun mangapillMangaDetails_fallsBackToChapterNumberFromUrl() {
        val details = MangapillSource.parseMangaDetails(
            """
            <html><body>
              <h1>Berserk</h1>
              <div id="chapters">
                <a href="/chapters/12345-13900020/berserk-chapter-2">Leggi</a>
                <a href="/chapters/12345-13900010/berserk-chapter-1">Leggi</a>
              </div>
            </body></html>
            """.trimIndent(),
            "https://mangapill.com/manga/12345/berserk",
        )

        assertEquals(listOf("1", "2"), details.chapters.map { it.numberText })
    }

    @Test
    fun mangapillChapterPages_readsReaderImagesInOrder() {
        val pages = MangapillSource.parsePageImageUrls(
            """
            <div id="reader">
              <chapter-page><img class="js-page lazy" data-src="https://cdn.mangapill.com/chapters/12345-10/1.png"></chapter-page>
              <chapter-page><img class="js-page lazy" data-src="https://cdn.mangapill.com/chapters/12345-10/2.png"></chapter-page>
            </div>
            """.trimIndent(),
            "https://mangapill.com/chapters/12345-10/berserk-chapter-10",
        )

        assertEquals(2, pages.size)
        assertTrue(pages.first().endsWith("1.png"))
        assertTrue(pages.last().endsWith("2.png"))
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
              <div class="has-shadow comic-description px-3 mt-4">
                <div class="heading pt-3">TRAMA</div>
                <div id="noidungm" class="mb-3">Una seconda vita piena di magia e mostri.</div>
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
        assertEquals("Una seconda vita piena di magia e mostri.", details.description)
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
    fun mangaWorldMangaDetails_readsVolumeOnlyEntries() {
        val details = MangaWorldSource.parseMangaDetails(
            """
            <section id="manga-page">
              <h1 class="name bigger">20th Century Boys</h1>
              <div id="chapterList">
                <div class="chapters-wrapper py-2 pl-0">
                  <div class="volume-element pl-2">
                    <div class="volume w-100 py-2"><p class="volume-name d-inline">Volume 02</p></div>
                    <div class="volume-chapters pl-2">
                      <div class="chapter"><a class="chap" href="https://www.mangaworld.mx/manga/2726/20th-century-boys/read/volume-2" title="20th Century Boys Volume 02 Scan ITA"><span>Volume 02</span></a></div>
                    </div>
                  </div>
                  <div class="volume-element pl-2">
                    <div class="volume w-100 py-2"><p class="volume-name d-inline">Volume 01</p></div>
                    <div class="volume-chapters pl-2">
                      <div class="chapter"><a class="chap" href="https://www.mangaworld.mx/manga/2726/20th-century-boys/read/volume-1" title="20th Century Boys Volume 01 Scan ITA"><span>Volume 01</span></a></div>
                    </div>
                  </div>
                </div>
              </div>
            </section>
            """.trimIndent(),
            "https://www.mangaworld.mx/manga/2726/20th-century-boys",
        )

        assertEquals(listOf("01", "02"), details.chapters.map { it.numberText })
        assertEquals(listOf("Volume", "Volume"), details.chapters.map { it.labelPrefix })
        assertTrue(details.chapters.none { it.volumeText != null })
        assertEquals("Volume 1", details.chapters.first().displayLabel())
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

    @Test
    fun mangaSource_exposesPublicChapterPageUrlApiForStreamingReader() {
        assertTrue(
            MangaSource::class.java.methods.any { method ->
                method.name == "fetchChapterPageImageUrls" &&
                    method.parameterTypes.toList() == listOf(String::class.java)
            },
        )
    }
}
