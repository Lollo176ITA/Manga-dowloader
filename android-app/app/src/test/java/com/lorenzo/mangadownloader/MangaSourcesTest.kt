package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaSourcesTest {

    @Test
    fun descriptorsForScope_escludeLeFontiDisabilitate() {
        val ids = MangaSourceCatalog
            .descriptorsForScope(SearchScope.ALL, disabledSourceIds = setOf(MangaSourceIds.VYMANGA))
            .map { it.id }
        assertFalse(MangaSourceIds.VYMANGA in ids)
        assertTrue(MangaSourceIds.MANGAPILL in ids)
    }

    @Test
    fun descriptorsForScope_filtroCheSvuotaLoScopeRipiegaSullElencoCompleto() {
        val ids = MangaSourceCatalog
            .descriptorsForScope(
                SearchScope.ITA,
                disabledSourceIds = setOf(MangaSourceIds.HASTA_TEAM, MangaSourceIds.MANGA_WORLD),
            )
            .map { it.id }
        assertEquals(listOf(MangaSourceIds.HASTA_TEAM, MangaSourceIds.MANGA_WORLD), ids)
    }

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
    fun mangapillSearchResults_prefersVisibleHeadingOverDoubledImageAlt() {
        // HTML reale delle card di ricerca Mangapill: l'alt della copertina è
        // "titolo + titolo alternativo" concatenati ("One Piece One Piece"),
        // il titolo pulito sta nel div "line-clamp-2" del secondo anchor.
        val results = MangapillSource.parseSearchResults(
            """
            <div class="my-3 grid">
              <div>
                <a href="/manga/2/one-piece" class="relative block">
                  <figure>
                    <img data-src="https://cdn.mangapill.com/i/2.webp" alt="One Piece One Piece">
                  </figure>
                </a>
                <div class="flex flex-col justify-end">
                  <a href="/manga/2/one-piece" class="mb-2">
                    <div class="mt-3 font-black leading-tight line-clamp-2">One Piece</div>
                  </a>
                </div>
              </div>
            </div>
            """.trimIndent(),
            "https://mangapill.com/search?q=one+piece",
        )

        assertEquals(1, results.size)
        assertEquals("One Piece", results.first().title)
        assertTrue(results.first().coverUrl!!.endsWith("2.webp"))
    }

    @Test
    fun mangapillSearchResults_collapsesDoubledAltWhenHeadingMissing() {
        // Senza il div del titolo (layout diverso/futuro) resta solo l'alt:
        // se è la stessa stringa ripetuta due volte va dimezzato, altrimenti
        // va tenuto intatto.
        val results = MangapillSource.parseSearchResults(
            """
            <div>
              <a href="/manga/3259/one-piece-party">
                <img src="https://cdn.mangapill.com/i/3259.webp" alt="One Piece Party One Piece Party">
              </a>
              <a href="/manga/12345/berserk">
                <img src="https://cdn.mangapill.com/i/12345.webp" alt="Berserk">
              </a>
            </div>
            """.trimIndent(),
            "https://mangapill.com/search?q=one+piece",
        )

        assertEquals(2, results.size)
        assertEquals("One Piece Party", results.first().title)
        assertEquals("Berserk", results[1].title)
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
    fun mangapillMangaDetails_qualifiesOnlySecondaryScanlationGroup() {
        // Mangapill pubblica più gruppi per la stessa serie: il titolo dell'anchor antepone
        // "Group N" dal secondo in poi. Il gruppo più numeroso resta senza variante (nomi file
        // e chiavi invariati); gli altri vengono qualificati per non collidere.
        val details = MangapillSource.parseMangaDetails(
            """
            <html><body>
              <h1>Berserk</h1>
              <div id="chapters">
                <a href="/chapters/1-20003000/berserk-chapter-3" title=" Group 2 Chapter 3">Group 2 Chapter 3</a>
                <a href="/chapters/1-20002000/berserk-chapter-2" title=" Group 2 Chapter 2">Group 2 Chapter 2</a>
                <a href="/chapters/1-20001000/berserk-chapter-1" title=" Group 2 Chapter 1">Group 2 Chapter 1</a>
                <a href="/chapters/1-10001000/berserk-chapter-1" title=" Chapter 1">Chapter 1</a>
              </div>
            </body></html>
            """.trimIndent(),
            "https://mangapill.com/manga/1/berserk",
        )

        assertEquals(4, details.chapters.size)
        val chapterOnes = details.chapters.filter { it.numberText == "1" }
        assertEquals(2, chapterOnes.size)

        // Il gruppo maggioritario ("Group 2", 3 capitoli) resta il principale.
        val main = chapterOnes.first { it.url.contains("1-20001000") }
        assertNull(main.variantTag)
        assertEquals("Capitolo 1", main.displayShortLabel())
        assertEquals("chapter_001.cbz", DownloadStorage.buildChapterFileName(main))

        // Quello del gruppo minoritario viene qualificato: etichetta e file distinti.
        val secondary = chapterOnes.first { it.url.contains("1-10001000") }
        assertEquals("Group 1", secondary.variantTag)
        assertEquals("Capitolo 1 (Group 1)", secondary.displayShortLabel())
        assertEquals("chapter_001__Group_1.cbz", DownloadStorage.buildChapterFileName(secondary))
    }

    @Test
    fun mangapillMangaDetails_singleGroupKeepsChaptersUnqualified() {
        // Caso normale (un solo gruppo): nessuna variante, così nulla cambia per le serie
        // già in libreria.
        val details = MangapillSource.parseMangaDetails(
            """
            <html><body>
              <h1>Berserk</h1>
              <div id="chapters">
                <a href="/chapters/12345-2/berserk-chapter-2" title="Chapter 2">Chapter 2</a>
                <a href="/chapters/12345-1/berserk-chapter-1" title="Chapter 1">Chapter 1</a>
              </div>
            </body></html>
            """.trimIndent(),
            "https://mangapill.com/manga/12345/berserk",
        )

        assertTrue(details.chapters.all { it.variantTag == null })
        assertEquals("chapter_001.cbz", DownloadStorage.buildChapterFileName(details.chapters.first()))
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
    fun sourceCatalog_groupsSourcesByLanguage() {
        val ita = MangaSourceCatalog.descriptorsForScope(SearchScope.ITA).map { it.id }
        val eng = MangaSourceCatalog.descriptorsForScope(SearchScope.ENG).map { it.id }

        assertEquals(listOf(MangaSourceIds.HASTA_TEAM, MangaSourceIds.MANGA_WORLD), ita)
        assertEquals(
            listOf(
                MangaSourceIds.MANGAPILL,
                MangaSourceIds.VYMANGA,
                MangaSourceIds.ASURA_SCANS,
                MangaSourceIds.DEMONIC_SCANS,
                MangaSourceIds.TCB_SCANS,
            ),
            eng,
        )
        // Ogni fonte appartiene a esattamente una lingua: ITA+ENG coprono tutto il catalogo.
        assertEquals(
            MangaSourceCatalog.descriptors.map { it.id }.toSet(),
            (ita + eng).toSet(),
        )
    }

    @Test
    fun sourceCatalog_scopeAllUsesFullCatalogAndRejectsLegacySource() {
        assertEquals(
            MangaSourceCatalog.descriptors,
            MangaSourceCatalog.descriptorsForScope(SearchScope.ALL),
        )
        assertThrows(IllegalArgumentException::class.java) {
            MangaSourceCatalog.descriptorsForScope(SearchScope.SOURCE)
        }
    }

    @Test
    fun sourceCatalog_languageOfResolvesAndFallsBack() {
        assertEquals(MangaSourceLanguage.ITA, MangaSourceCatalog.languageOf(MangaSourceIds.MANGA_WORLD))
        assertEquals(MangaSourceLanguage.ENG, MangaSourceCatalog.languageOf(MangaSourceIds.VYMANGA))
        // Id sconosciuto → lingua della fonte di default (Mangapill, ENG).
        assertEquals(MangaSourceLanguage.ENG, MangaSourceCatalog.languageOf("boh"))
    }

    @Test
    fun searchScope_forLanguageMapsBothLanguages() {
        assertEquals(SearchScope.ITA, SearchScope.forLanguage(MangaSourceLanguage.ITA))
        assertEquals(SearchScope.ENG, SearchScope.forLanguage(MangaSourceLanguage.ENG))
    }

    @Test
    fun interleaveBySource_alternatesSourcesPreservingEachOrder() {
        val combined = MangaSourceCatalog.interleaveBySource(
            listOf(
                listOf("a1", "a2", "a3", "a4"),
                listOf("b1"),
                listOf("c1", "c2"),
            ),
        )

        // 1° di ogni fonte, poi i 2°, ...: fonti mescolate, ordine interno intatto.
        assertEquals(listOf("a1", "b1", "c1", "a2", "c2", "a3", "a4"), combined)
    }

    @Test
    fun interleaveBySource_handlesEmptyInputs() {
        assertEquals(emptyList<String>(), MangaSourceCatalog.interleaveBySource<String>(emptyList()))
        assertEquals(
            listOf("a1", "a2"),
            MangaSourceCatalog.interleaveBySource(listOf(emptyList(), listOf("a1", "a2"))),
        )
    }

    @Test
    fun mangaWorldSearchResults_readsMinifiedUnquotedMarkupWithAltTitleMatch() {
        // Markup reale dell'archivio MangaWorld (minificato, attributi senza virgolette),
        // risposta a keyword="demon slayer": il server matcha il titolo inglese alternativo
        // ma espone quello canonico "Kimetsu no Yaiba".
        val results = MangaWorldSource.parseSearchResults(
            """
            <div class=comics-grid><!--F#p_16[0]--><div class=entry><a class="thumb position-relative" href=https://www.mangaworld.mx/manga/716/kimetsu-no-yaiba title="Kimetsu no Yaiba"><img src=https://cdn.mangaworld.mx/mangas/5f77ef0f640268083d44369e.jpg?178 alt="Kimetsu no Yaiba"></a><div class=content><p class="name m-0"><a class=manga-title href=https://www.mangaworld.mx/manga/716/kimetsu-no-yaiba title="Kimetsu no Yaiba">Kimetsu no Yaiba</a></p></div></div></div>
            """.trimIndent(),
        )

        assertEquals(1, results.size)
        assertEquals("Kimetsu no Yaiba", results.first().title)
        assertEquals("https://www.mangaworld.mx/manga/716", results.first().mangaUrl)
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

    @Test
    fun vyMangaCanonicalSeriesUrl_normalizesChapterAndNetUrls() {
        val series = VyMangaSource.canonicalSeriesUrl(
            "https://vymanga.com/manga/kajiya-de-hajimeru-isekai-slow-life",
        )
        val chapter = VyMangaSource.canonicalSeriesUrl(
            "https://vymanga.com/manga/kajiya-de-hajimeru-isekai-slow-life/chapter-31.1",
        )
        val netMirror = VyMangaSource.canonicalSeriesUrl(
            "https://vymanga.net/manga/kajiya-de-hajimeru-isekai-slow-life",
        )

        assertEquals("https://vymanga.com/manga/kajiya-de-hajimeru-isekai-slow-life", series)
        assertEquals(series, chapter)
        assertEquals(series, netMirror)
    }

    @Test
    fun vyMangaSearchResults_mapsComicItems() {
        val results = VyMangaSource.parseSearchResults(
            """
            <div class="row book-list">
              <div class="col-lg-2 col-md-3 col-4">
                <div class="comic-item">
                  <a href="/manga/kajiya-de-hajimeru-isekai-slow-life">
                    <div class="comic-image">
                      <img class="image lozad" data-src="https://cdnxyz.xyz/web/cover/64366/thumbnail.png" src="/web/img/blank.gif" title="Kajiya de Hajimeru Isekai Slow Life" alt="Kajiya de Hajimeru Isekai Slow Life"/>
                      <span class="tray-item">Chapter 31 : Ch 31 1</span>
                    </div>
                    <div class="comic-title"> Kajiya de Hajimeru Isekai Slow Life </div>
                  </a>
                </div>
              </div>
            </div>
            """.trimIndent(),
            "https://vymanga.com/search?q=kajiya",
        )

        assertEquals(1, results.size)
        assertEquals(MangaSourceIds.VYMANGA, results.first().sourceId)
        assertEquals("Kajiya de Hajimeru Isekai Slow Life", results.first().title)
        assertEquals(
            "https://vymanga.com/manga/kajiya-de-hajimeru-isekai-slow-life",
            results.first().mangaUrl,
        )
        assertTrue(results.first().coverUrl!!.endsWith("thumbnail.png"))
    }

    @Test
    fun vyMangaMangaDetails_sortsChaptersAscendingAndBuildsSyntheticUrls() {
        val details = VyMangaSource.parseMangaDetails(
            """
            <html><body>
              <h1 class="title">Kajiya de Hajimeru Isekai Slow Life</h1>
              <div class="img-manga"><img src="https://cdnxyz.xyz/web/cover/64366/thumbnail.png" title="Kajiya" alt="Kajiya"></div>
              <div class="div-chapter">
                <a class="btn btn-success w-100 btn-first-last" href="https://aovheroes.com/rds/br/rdsd?data=FIRST">First Chapter Chapter 1</a>
                <a class="list-group-item list-group-item-action list-chapter" id="chapter-2" href="https://aovheroes.com/rds/br/rdsd?data=BBB">Chapter 2 May 19, 2026</a>
                <a class="list-group-item list-group-item-action list-chapter" id="chapter-10" href="https://aovheroes.com/rds/br/rdsd?data=CCC">Chapter 10 Mar 01, 2026</a>
                <a class="list-group-item list-group-item-action list-chapter" id="chapter-1" href="https://aovheroes.com/rds/br/rdsd?data=DDD">Chapter 1 Jan 01, 2026</a>
              </div>
            </body></html>
            """.trimIndent(),
            "https://vymanga.com/manga/kajiya-de-hajimeru-isekai-slow-life",
        )

        assertEquals(MangaSourceIds.VYMANGA, details.sourceId)
        assertEquals("Kajiya de Hajimeru Isekai Slow Life", details.title)
        assertEquals("https://vymanga.com/manga/kajiya-de-hajimeru-isekai-slow-life", details.mangaUrl)
        // ordine numerico crescente (10 dopo 2), non lessicale; il bottone first-last è escluso
        assertEquals(listOf("1", "2", "10"), details.chapters.map { it.numberText })
        assertEquals(
            "https://vymanga.com/manga/kajiya-de-hajimeru-isekai-slow-life/chapter-1",
            details.chapters.first().url,
        )
        assertTrue(details.coverUrl!!.contains("cdnxyz.xyz"))
    }

    @Test
    fun vyMangaExtractChapterToken_findsFreshTokenById() {
        val token = VyMangaSource.extractChapterToken(
            """
            <div class="div-chapter">
              <a class="list-chapter" id="chapter-2" href="https://aovheroes.com/rds/br/rdsd?data=BBB">Chapter 2</a>
              <a class="list-chapter" id="chapter-10" href="https://aovheroes.com/rds/br/rdsd?data=CCC">Chapter 10</a>
            </div>
            """.trimIndent(),
            "chapter-10",
        )

        assertEquals("https://aovheroes.com/rds/br/rdsd?data=CCC", token)
    }

    @Test
    fun vyMangaReaderImages_readsLozadDataSrcInOrderSkippingAdsAndPlaceholders() {
        val pages = VyMangaSource.parseReaderImageUrls(
            """
            <div id="content-reader">
              <div class="hview mb-2" data-page="0">
                <img class="d-block w-100 lozad" data-src="https://2.bp.blogspot.com/drive-storage/AAA=w700" src="https://2.bp.blogspot.com/drive-storage/AAA=w700" data-loaded="true">
              </div>
              <div id="ads_vertical_reader_1" class="ads_reader hview mb-2">
                <h5>Page of Ads</h5><div id="container-ads"></div>
              </div>
              <div class="hview mb-2" data-page="1">
                <img class="d-block w-100 lozad" data-src="https://2.bp.blogspot.com/drive-storage/BBB=w700" src="https://vymanga.net/web/img/loading.gif">
              </div>
            </div>
            """.trimIndent(),
        )

        assertEquals(2, pages.size)
        assertTrue(pages.first().endsWith("AAA=w700"))
        assertTrue(pages.last().endsWith("BBB=w700"))
        assertTrue(pages.none { it.contains("loading.gif") })
    }

    @Test
    fun sourceCatalog_resolvesVyMangaUrlAndIdentityKey() {
        val resolved = MangaSourceCatalog.resolveSourceId(
            sourceId = null,
            url = "https://vymanga.com/manga/kajiya-de-hajimeru-isekai-slow-life/chapter-10",
        )
        val identityKey = MangaSourceCatalog.identityKey(
            sourceId = MangaSourceIds.VYMANGA,
            mangaUrl = "https://vymanga.com/manga/kajiya-de-hajimeru-isekai-slow-life/chapter-10",
        )

        assertEquals(MangaSourceIds.VYMANGA, resolved)
        assertEquals(
            "vymanga::https://vymanga.com/manga/kajiya-de-hajimeru-isekai-slow-life",
            identityKey,
        )
    }

    @Test
    fun mangapillMangaDetails_readsDescriptionFromOgMeta() {
        val details = MangapillSource.parseMangaDetails(
            """
            <html><head>
              <meta property="og:description" content="Gatsu, il Guerriero Nero, cerca vendetta.">
            </head><body>
              <h1>Berserk</h1>
              <div id="chapters">
                <a href="/chapters/12345-1/berserk-chapter-1" title="Chapter 1">Chapter 1</a>
              </div>
            </body></html>
            """.trimIndent(),
            "https://mangapill.com/manga/12345/berserk",
        )

        assertEquals("Gatsu, il Guerriero Nero, cerca vendetta.", details.description)
    }

    @Test
    fun hastaMangaDetails_readsDescriptionFromJson() {
        val details = HastaTeamSource.parseMangaDetails(
            """
            {
              "comic": {
                "title": "Yotsuba&!",
                "url": "/comics/yotsuba",
                "description": "Le giornate di Yotsuba.",
                "chapters": [
                  { "chapter": 1, "subchapter": null, "url": "/read/yotsuba/it/vol/1/ch/1", "slug_lang_vol_ch_sub": "it-1-1-N" }
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals("Le giornate di Yotsuba.", details.description)
    }

    @Test
    fun vyMangaMangaDetails_readsStatusFromPreTitleSpans() {
        // Markup reale di VyManga: etichetta, ":" e valore in tre <span> distinti.
        val details = VyMangaSource.parseMangaDetails(
            """
            <html><body>
              <h1 class="title">Kajiya</h1>
              <p><span class="pre-title">Status</span><span class="space">:</span><span class="text-ongoing">Ongoing</span></p>
              <div class="div-chapter">
                <a class="list-chapter" id="chapter-1" href="https://aovheroes.com/rds/br/rdsd?data=X">Chapter 1</a>
              </div>
            </body></html>
            """.trimIndent(),
            "https://vymanga.com/manga/kajiya",
        )

        assertEquals(MangaPublicationStatus.ONGOING, details.status)
    }

    @Test
    fun vyMangaMangaDetails_readsDescriptionFromOgMeta() {
        val details = VyMangaSource.parseMangaDetails(
            """
            <html><head>
              <meta property="og:description" content="Un fabbro inizia una slow life in un altro mondo.">
            </head><body>
              <h1 class="title">Kajiya</h1>
              <div class="div-chapter">
                <a class="list-chapter" id="chapter-1" href="https://aovheroes.com/rds/br/rdsd?data=X">Chapter 1</a>
              </div>
            </body></html>
            """.trimIndent(),
            "https://vymanga.com/manga/kajiya",
        )

        assertEquals("Un fabbro inizia una slow life in un altro mondo.", details.description)
    }

    @Test
    fun parseDescription_prefersSpecificSelectorThenOgFallback() {
        val withSelector = org.jsoup.Jsoup.parse(
            """<div class="syn">Trama specifica</div><meta property="og:description" content="OG">""",
        )
        assertEquals("Trama specifica", parseDescription(withSelector, ".syn"))

        val ogOnly = org.jsoup.Jsoup.parse("""<meta property="og:description" content="Solo OG">""")
        assertEquals("Solo OG", parseDescription(ogOnly, ".syn"))

        assertNull(parseDescription(org.jsoup.Jsoup.parse("<div>niente meta</div>")))
    }

    @Test
    fun vyMangaToHighResUrl_upgradesBloggerSizeSuffix() {
        assertEquals(
            "https://2.bp.blogspot.com/drive-storage/AAA=s0",
            VyMangaSource.toHighResUrl("https://2.bp.blogspot.com/drive-storage/AAA=w700"),
        )
        // suffisso composito (larghezza+altezza) → originale
        assertEquals(
            "https://2.bp.blogspot.com/drive-storage/BBB=s0",
            VyMangaSource.toHighResUrl("https://2.bp.blogspot.com/drive-storage/BBB=w700-h1000"),
        )
        // nessun suffisso di resize → invariato
        val noSuffix = "https://2.bp.blogspot.com/drive-storage/CCC"
        assertEquals(noSuffix, VyMangaSource.toHighResUrl(noSuffix))
    }

    // --- Asura Scans (API JSON) ---

    @Test
    fun asuraCanonicalSeriesUrl_stripsHashAndNormalizesChapterUrl() {
        val series = AsuraScansSource.canonicalSeriesUrl(
            "https://asurascans.com/comics/chronicles-of-the-demon-faction-f886a8af",
        )
        val chapter = AsuraScansSource.canonicalSeriesUrl(
            "https://asurascans.com/comics/chronicles-of-the-demon-faction-f886a8af/chapter/12",
        )

        assertEquals("https://asurascans.com/comics/chronicles-of-the-demon-faction", series)
        assertEquals(series, chapter)
    }

    @Test
    fun asuraSearchResponse_mapsDataToCleanSeriesUrls() {
        val results = AsuraScansSource.parseSearchResponse(
            """
            {"data":[
              {"slug":"reborn-as-the-heavenly-demon","title":"Reborn As The Heavenly Demon","cover":"https://cdn.asurascans.com/asura-images/covers/reborn-as-the-heavenly-demon.aec242.webp","status":"ongoing","public_url":"/comics/reborn-as-the-heavenly-demon-f886a8af"}
            ]}
            """.trimIndent(),
        )

        assertEquals(1, results.size)
        assertEquals(MangaSourceIds.ASURA_SCANS, results.first().sourceId)
        assertEquals("Reborn As The Heavenly Demon", results.first().title)
        assertEquals("https://asurascans.com/comics/reborn-as-the-heavenly-demon", results.first().mangaUrl)
        assertTrue(results.first().coverUrl!!.endsWith("reborn-as-the-heavenly-demon.aec242.webp"))
    }

    @Test
    fun asuraMangaDetails_sortsChaptersExcludesPremiumAndCleansDescription() {
        val details = AsuraScansSource.parseMangaDetails(
            """
            {"series":{"slug":"chronicles-of-the-demon-faction","title":"Chronicles of the Demon Faction","description":"<p>Chun Hajin.</p><p>Reincarnated.</p>","cover":"https://cdn.asurascans.com/asura-images/covers/chronicles-of-the-demon-faction.d4dcb8.webp","status":"ongoing"}}
            """.trimIndent(),
            """
            {"data":[
              {"number":3,"slug":"chapter-3","is_premium":true},
              {"number":2,"slug":"chapter-2","is_premium":false},
              {"number":10,"slug":"chapter-10","is_premium":false},
              {"number":1,"slug":"chapter-1","is_premium":false}
            ]}
            """.trimIndent(),
            "https://asurascans.com/comics/chronicles-of-the-demon-faction-f886a8af",
        )

        assertEquals(MangaSourceIds.ASURA_SCANS, details.sourceId)
        assertEquals("Chronicles of the Demon Faction", details.title)
        assertEquals("https://asurascans.com/comics/chronicles-of-the-demon-faction", details.mangaUrl)
        // premium (3) escluso, ordine numerico crescente (10 dopo 2)
        assertEquals(listOf("1", "2", "10"), details.chapters.map { it.numberText })
        assertEquals(
            "https://asurascans.com/comics/chronicles-of-the-demon-faction/chapter/1",
            details.chapters.first().url,
        )
        assertEquals(MangaPublicationStatus.ONGOING, details.status)
        // Tag HTML rimossi dalla descrizione.
        val description = details.description.orEmpty()
        assertTrue(description.contains("Chun Hajin"))
        assertFalse(description.contains("<p>"))
    }

    @Test
    fun asuraChapterPages_readsPagesInOrder() {
        val pages = AsuraScansSource.parsePageImageUrls(
            """
            {"data":{"is_locked":false,"chapter":{"number":1,"pages":[
              {"url":"https://cdn.asurascans.com/asura-images/chapters/x/1/001.webp?v=1","width":1200,"height":800},
              {"url":"https://cdn.asurascans.com/asura-images/chapters/x/1/002.webp?v=1","width":800,"height":1200}
            ]}}}
            """.trimIndent(),
        )

        assertEquals(2, pages.size)
        assertTrue(pages.first().endsWith("001.webp?v=1"))
        assertTrue(pages.last().endsWith("002.webp?v=1"))
    }

    @Test
    fun asuraChapterPages_throwsWhenLocked() {
        assertThrows(IllegalStateException::class.java) {
            AsuraScansSource.parsePageImageUrls(
                """{"data":{"is_locked":true,"chapter":{"pages":[]}}}""",
            )
        }
    }

    @Test
    fun sourceCatalog_resolvesAsuraUrlAndIdentityKey() {
        val resolved = MangaSourceCatalog.resolveSourceId(
            sourceId = null,
            url = "https://asurascans.com/comics/chronicles-of-the-demon-faction-f886a8af/chapter/12",
        )
        val identityKey = MangaSourceCatalog.identityKey(
            sourceId = MangaSourceIds.ASURA_SCANS,
            mangaUrl = "https://asurascans.com/comics/chronicles-of-the-demon-faction-f886a8af/chapter/12",
        )

        assertEquals(MangaSourceIds.ASURA_SCANS, resolved)
        assertEquals(
            "asura_scans::https://asurascans.com/comics/chronicles-of-the-demon-faction",
            identityKey,
        )
    }

    // --- DemonicScans (scraping HTML) ---

    @Test
    fun demonicCanonicalSeriesUrl_normalizesMangaAndReaderUrls() {
        val series = DemonicScansSource.canonicalSeriesUrl("https://demonicscans.org/manga/Solo-Leveling")
        val reader = DemonicScansSource.canonicalSeriesUrl(
            "https://demonicscans.org/title/Solo-Leveling/chapter/100/1",
        )

        assertEquals("https://demonicscans.org/manga/Solo-Leveling", series)
        assertEquals(series, reader)
    }

    @Test
    fun demonicSearchResults_mapsAnchorsWithThumbAndTitle() {
        val results = DemonicScansSource.parseSearchResults(
            """
            <a href="/manga/Solo-Leveling">
              <li class="flex flex-row">
                <img src="https://readermc.org/images/thumbnails/Solo-Leveling.webp" class="search-thumb">
                <div class="flex flex-col seach-right justify-space-between">
                  <div>Solo Leveling</div>
                  <div style="font-size:12px">Completed</div>
                </div>
              </li>
            </a>
            """.trimIndent(),
            "https://demonicscans.org/search.php?manga=solo",
        )

        assertEquals(1, results.size)
        assertEquals(MangaSourceIds.DEMONIC_SCANS, results.first().sourceId)
        assertEquals("Solo Leveling", results.first().title)
        assertEquals("https://demonicscans.org/manga/Solo-Leveling", results.first().mangaUrl)
        assertTrue(results.first().coverUrl!!.contains("readermc.org"))
    }

    @Test
    fun demonicMangaDetails_buildsReaderUrlsSortedAndDedupsFirstChapter() {
        val details = DemonicScansSource.parseMangaDetails(
            """
            <html><head>
              <meta property="og:image" content="https://readermc.org/images/thumbnails/Solo-Leveling.webp">
            </head><body>
              <h1>Solo Leveling</h1>
              <ul>
                <li style="width:150px;color:#b2b2b2;">Status</li>
                <li>Completed</li>
              </ul>
              <div class="white-font">10 anni fa si aprirono i Gate.</div>
              <a href="/chaptered.php?manga=6&chapter=0">Read First Chapter</a>
              <div id="chapters-list">
                <a href="/chaptered.php?manga=6&chapter=2">Chapter 2</a>
                <a href="/chaptered.php?manga=6&chapter=10">Chapter 10</a>
                <a href="/chaptered.php?manga=6&chapter=1">Chapter 1</a>
                <a href="/chaptered.php?manga=6&chapter=0">Chapter 0</a>
              </div>
            </body></html>
            """.trimIndent(),
            "https://demonicscans.org/manga/Solo-Leveling",
        )

        assertEquals(MangaSourceIds.DEMONIC_SCANS, details.sourceId)
        assertEquals("Solo Leveling", details.title)
        assertEquals("https://demonicscans.org/manga/Solo-Leveling", details.mangaUrl)
        // Ordine numerico crescente; capitolo 0 deduplicato (bottone + lista).
        assertEquals(listOf("0", "1", "2", "10"), details.chapters.map { it.numberText })
        assertEquals(
            "https://demonicscans.org/title/Solo-Leveling/chapter/0/1",
            details.chapters.first().url,
        )
        assertEquals(MangaPublicationStatus.COMPLETED, details.status)
        assertTrue(details.description!!.contains("Gate"))
    }

    @Test
    fun demonicReaderImages_keepsContentPagesInOrderSkippingAds() {
        val pages = DemonicScansSource.parseReaderImageUrls(
            """
            <div id="chapter-container">
              <img class="imgholder" src="/img/free_ads.jpg">
              <img class="imgholder" src="https://mangareadon.org/Solo-Leveling/1/1.jpg">
              <img class="imgholder" src="https://mangareadon.org/Solo-Leveling/1/2.jpg">
            </div>
            """.trimIndent(),
        )

        assertEquals(2, pages.size)
        assertTrue(pages.first().endsWith("1.jpg"))
        assertTrue(pages.last().endsWith("2.jpg"))
        assertTrue(pages.none { it.contains("free_ads") })
    }

    @Test
    fun sourceCatalog_resolvesDemonicUrlAndIdentityKey() {
        val resolved = MangaSourceCatalog.resolveSourceId(
            sourceId = null,
            url = "https://demonicscans.org/title/Solo-Leveling/chapter/100/1",
        )
        val identityKey = MangaSourceCatalog.identityKey(
            sourceId = MangaSourceIds.DEMONIC_SCANS,
            mangaUrl = "https://demonicscans.org/title/Solo-Leveling/chapter/100/1",
        )

        assertEquals(MangaSourceIds.DEMONIC_SCANS, resolved)
        assertEquals("demonic_scans::https://demonicscans.org/manga/Solo-Leveling", identityKey)
    }

    // --- TCB Scans (scraping HTML, catalogo senza ricerca, URL capitolo sintetico) ---

    @Test
    fun tcbCanonicalSeriesUrl_normalizesSeriesAndSyntheticChapterUrls() {
        val series = TcbScansSource.canonicalSeriesUrl("https://tcbonepiecechapters.com/mangas/5/one-piece")
        val synthetic = TcbScansSource.canonicalSeriesUrl(
            "https://tcbonepiecechapters.com/mangas/5/one-piece/chapters/7995/one-piece-chapter-1188",
        )

        assertEquals("https://tcbonepiecechapters.com/mangas/5/one-piece", series)
        assertEquals(series, synthetic)
        // Un URL capitolo "reale" del sito non incorpora l'id/slug della serie: non è
        // normalizzabile senza rete, quindi canonicalSeriesUrl lo rifiuta correttamente.
        assertNull(
            TcbScansSource.canonicalSeriesUrl(
                "https://tcbonepiecechapters.com/chapters/7995/one-piece-chapter-1188",
            ),
        )
    }

    @Test
    fun tcbParseChapterRef_extractsSeriesAndChapterFromSyntheticUrl() {
        val ref = TcbScansSource.parseChapterRef(
            "https://tcbonepiecechapters.com/mangas/5/one-piece/chapters/7995/one-piece-chapter-1188",
        )

        assertNotNull(ref)
        assertEquals("5", ref!!.seriesId)
        assertEquals("one-piece", ref.seriesSlug)
        assertEquals("7995", ref.chapterId)
        assertEquals("one-piece-chapter-1188", ref.chapterSlug)
    }

    @Test
    fun tcbCatalog_mergesThumbnailAndTitleAnchorsIgnoringLayoutClasses() {
        // La seconda scheda è volutamente priva delle classi Tailwind del layout (`items-center`,
        // `font-bold`): l'accoppiamento thumbnail/titolo deve reggersi solo sull'href condiviso.
        val results = TcbScansSource.parseCatalog(
            """
            <a href="/"><img src="/files/h-logo.png"></a>
            <div class="flex flex-col items-center md:flex-row md:items-start">
                <div class="relative h-24 w-24">
                    <a href="/mangas/5/one-piece">
                        <img src="https://cdn.onepiecechapters.com/file/CDN-M-A-N/one-piece.png" class="w-24 h-24 rounded-lg">
                    </a>
                </div>
                <div class="flex-auto sm:ml-5">
                    <a class="mb-3 text-white text-lg font-bold" href="/mangas/5/one-piece">One Piece</a>
                </div>
            </div>
            <div>
                <a href="/mangas/4/jujutsu-kaisen"><img src="https://altro-cdn.example/jjk.png"></a>
                <a href="/mangas/4/jujutsu-kaisen">Jujutsu Kaisen</a>
            </div>
            """.trimIndent(),
            "https://tcbonepiecechapters.com/projects",
        )

        assertEquals(2, results.size)
        val onePiece = results.first { it.title == "One Piece" }
        assertEquals("https://tcbonepiecechapters.com/mangas/5/one-piece", onePiece.mangaUrl)
        assertTrue(onePiece.coverUrl!!.endsWith("one-piece.png"))

        assertEquals(
            listOf("Jujutsu Kaisen", "One Piece"),
            TcbScansSource.run { results.filterByTitle("").sortedAlphabetically().map { it.title } },
        )
        assertEquals(
            listOf("One Piece"),
            TcbScansSource.run { results.filterByTitle("piece").sortedAlphabetically().map { it.title } },
        )
    }

    @Test
    fun tcbMangaDetails_buildsSyntheticChapterUrlsSortedAscending() {
        val details = TcbScansSource.parseMangaDetails(
            """
            <html><body>
              <a href="/"><img src="/files/h-logo.png"></a>
              <h1 class="my-3 font-bold text-3xl">One Piece </h1>
              <img src="https://cdn.onepiecechapters.com/file/CDN-M-A-N/one-piece-cover.png" alt="One Piece" height="325">
              <a href="/chapters/7995/one-piece-chapter-1188" class="block border p-3 rounded">
                <div class="text-lg font-bold">One Piece  Chapter 1188</div>
                <div class="text-gray-500">Wailing Void</div>
              </a>
              <a href="/chapters/1/one-piece-chapter-1" class="block border p-3 rounded">
                <div class="text-lg font-bold">One Piece  Chapter 1</div>
                <div class="text-gray-500">Romance Dawn</div>
              </a>
              <a href="/chapters/50/one-piece-chapter-10" class="block border p-3 rounded">
                <div class="text-lg font-bold">One Piece  Chapter 10</div>
                <div class="text-gray-500">Ope Ope</div>
              </a>
            </body></html>
            """.trimIndent(),
            "https://tcbonepiecechapters.com/mangas/5/one-piece",
        )

        assertEquals(MangaSourceIds.TCB_SCANS, details.sourceId)
        assertEquals("One Piece", details.title)
        assertEquals("https://tcbonepiecechapters.com/mangas/5/one-piece", details.mangaUrl)
        assertTrue(details.coverUrl!!.endsWith("one-piece-cover.png"))
        // Ordine numerico crescente, non l'ordine "più recente prima" della pagina.
        assertEquals(listOf("1", "10", "1188"), details.chapters.map { it.numberText })
        assertEquals(
            "https://tcbonepiecechapters.com/mangas/5/one-piece/chapters/7995/one-piece-chapter-1188",
            details.chapters.last().url,
        )
    }

    @Test
    fun tcbPageImageUrls_extractsFixedRatioImagesInOrder() {
        val pages = TcbScansSource.parsePageImageUrls(
            """
            <div class="reader">
              <img class="fixed-ratio-content" src="https://cdn.onepiecechapters.com/file/CDN-M-A-N/op_1188_void_001.png">
              <img class="fixed-ratio-content" src="https://cdn.onepiecechapters.com/file/CDN-M-A-N/op_1188_void_002.png">
            </div>
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "https://cdn.onepiecechapters.com/file/CDN-M-A-N/op_1188_void_001.png",
                "https://cdn.onepiecechapters.com/file/CDN-M-A-N/op_1188_void_002.png",
            ),
            pages,
        )
    }

    @Test
    fun sourceCatalog_resolvesTcbSyntheticChapterUrlAndIdentityKey() {
        val syntheticChapterUrl =
            "https://tcbonepiecechapters.com/mangas/5/one-piece/chapters/7995/one-piece-chapter-1188"
        val resolved = MangaSourceCatalog.resolveSourceId(sourceId = null, url = syntheticChapterUrl)
        val identityKey = MangaSourceCatalog.identityKey(
            sourceId = MangaSourceIds.TCB_SCANS,
            mangaUrl = syntheticChapterUrl,
        )

        assertEquals(MangaSourceIds.TCB_SCANS, resolved)
        assertEquals("tcb_scans::https://tcbonepiecechapters.com/mangas/5/one-piece", identityKey)
    }
}
