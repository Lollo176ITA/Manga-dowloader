package com.lorenzo.mangadownloader

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Estrazione della data di pubblicazione dai capitoli, fonte per fonte. Il markup e i campi
 * JSON qui dentro sono copiati dalle pagine reali (verificate a mano), non inventati.
 *
 * Mangapill e TCB Scans non pubblicano alcuna data nella lista capitoli: per loro il test
 * fissa il comportamento atteso, cioè `null` — così se un domani il markup cambiasse e la
 * data comparisse, il test resterebbe verde ma sapremmo dove aggiungerla.
 */
class ChapterPublishDateParsingTest {

    private fun midnightMillis(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun mangaWorldChapters_readTheItalianChapDate() {
        val details = MangaWorldSource.parseMangaDetails(
            """
            <section id="manga-page">
              <h1 class="name bigger">Sayonara Eri</h1>
              <div id="chapterList">
                <div class="chapters-wrapper">
                  <div class="chapter pl-2"><a class="chap" href="https://www.mangaworld.mx/manga/2754/sayonara-eri/read/chapter-1" title="Sayonara Eri Capitolo 01 Scan ITA"><span class="d-inline-block">Capitolo 01</span><i class="text-right text-muted chap-date">03 Maggio 2022</i><div class="clearfix"></div></a></div>
                </div>
              </div>
            </section>
            """.trimIndent(),
            "https://www.mangaworld.mx/manga/2754/sayonara-eri",
        )

        val chapter = details.chapters.single()
        assertEquals("01", chapter.numberText)
        assertEquals(midnightMillis(2022, 5, 3), chapter.publishedAtMillis)
    }

    @Test
    fun mangaWorldChapters_haveNoDateWhenTheSiteOmitsIt() {
        val details = MangaWorldSource.parseMangaDetails(
            """
            <section id="manga-page">
              <h1 class="name bigger">Sayonara Eri</h1>
              <div id="chapterList">
                <div class="chapters-wrapper">
                  <div class="chapter pl-2"><a class="chap" href="https://www.mangaworld.mx/manga/2754/sayonara-eri/read/chapter-1" title="Sayonara Eri Capitolo 01 Scan ITA"><span>Capitolo 01</span></a></div>
                </div>
              </div>
            </section>
            """.trimIndent(),
            "https://www.mangaworld.mx/manga/2754/sayonara-eri",
        )

        assertNull(details.chapters.single().publishedAtMillis)
    }

    @Test
    fun hastaTeamChapters_readPublishedOn() {
        val details = HastaTeamSource.parseMangaDetails(
            """
            {
              "comic": {
                "title": "Yotsuba&!",
                "url": "/comics/yotsuba",
                "chapters": [
                  {
                    "chapter": 1,
                    "subchapter": null,
                    "url": "/read/yotsuba/it/vol/1/ch/1",
                    "slug_lang_vol_ch_sub": "it-1-1-N",
                    "updated_at": "2024-11-26T09:38:03.000000Z",
                    "published_on": "2018-04-10T16:00:04.000000Z"
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals(1523376004000L, details.chapters.single().publishedAtMillis)
    }

    @Test
    fun asuraChapters_readPublishedAt() {
        val details = AsuraScansSource.parseMangaDetails(
            """
            {"series":{"slug":"reaper","title":"Reaper","description":"<p>Trama.</p>","status":"ongoing"}}
            """.trimIndent(),
            """
            {"data":[
              {"number":135,"slug":"chapter-135","is_premium":false,"published_at":"2026-04-19T17:07:50.694Z"}
            ]}
            """.trimIndent(),
            "https://asurascans.com/comics/reaper",
        )

        assertEquals(1776618470694L, details.chapters.single().publishedAtMillis)
    }

    @Test
    fun demonicChapters_readTheDateSpanInsideTheAnchor() {
        val details = DemonicScansSource.parseMangaDetails(
            """
            <html><body>
              <h1>Solo Leveling</h1>
              <div id="chapters-list">
                <li><a href="/chaptered.php?manga=6&chapter=200" class="chplinks" title="Solo Leveling 200"> Chapter 200 <span style="float:right;text-align: right;">2023-10-16</span></a></li>
              </div>
            </body></html>
            """.trimIndent(),
            "https://demonicscans.org/manga/Solo-Leveling",
        )

        assertEquals(midnightMillis(2023, 10, 16), details.chapters.single().publishedAtMillis)
    }

    @Test
    fun mangapillChapters_haveNoDateBecauseTheSiteDoesNotPublishOne() {
        val details = MangapillSource.parseMangaDetails(
            """
            <html><body>
              <h1>One Piece</h1>
              <div id="chapters">
                <a href="/chapters/2-11910000/one-piece-chapter-1191" title=" Chapter 1191">Chapter 1191</a>
              </div>
            </body></html>
            """.trimIndent(),
            "https://mangapill.com/manga/2/one-piece",
        )

        assertNull(details.chapters.single().publishedAtMillis)
    }
}
