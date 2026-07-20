package com.lorenzo.mangadownloader

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Fonte per **DemonicScans** (`demonicscans.org`), sito PHP con pagine HTML da
 * parsare con jsoup.
 *
 * - ricerca: `GET /search.php?manga=<query>` → frammenti `<a href="/manga/<Slug>">`
 *   con `img.search-thumb` e il titolo.
 * - serie:   `/manga/<Slug>` → `<h1>`, `og:image`, e i capitoli come link
 *   `chaptered.php?manga=<id>&chapter=<numero>`.
 * - reader:  `chaptered.php?manga=<id>&chapter=<n>` fa un redirect a
 *   `/title/<Slug>/chapter/<n>/1`, la vera pagina del reader, dove le immagini
 *   sono `img.imgholder` ospitate su un CDN esterno (`mangareadon.org`).
 *
 * Poiché il link `chaptered.php` contiene l'**id numerico** ma non lo slug (e
 * `buildDownloadPlan` deve risalire dal capitolo alla serie), gli URL dei capitoli
 * sono normalizzati sulla forma **slug-based** del reader
 * `https://demonicscans.org/title/<Slug>/chapter/<numero>/1`: si canonicalizza a
 * `/manga/<Slug>` e carica le pagine direttamente, senza bisogno dell'id.
 */
class DemonicScansSource(
    context: Context,
    networkClient: MangaNetworkClient,
    libraryRepository: LibraryRepository = LibraryRepository(context),
) : BaseMangaSource(context, networkClient, libraryRepository) {
    override val descriptor = MangaSourceDescriptor(
        id = MangaSourceIds.DEMONIC_SCANS,
        displayName = "DemonicScans",
        shortName = "DS",
        language = MangaSourceLanguage.ENG,
    )

    override val invalidChapterUrlMessage: String =
        "URL manga o capitolo DemonicScans non valido"

    override fun canHandleUrl(url: String): Boolean = handlesUrl(url)

    override fun searchManga(query: String): List<MangaSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }
        val url = "$BASE_URL/search.php".toHttpUrl()
            .newBuilder()
            .addQueryParameter("manga", trimmed)
            .build()
            .toString()
        return parseSearchResults(fetchString(url), url)
    }

    override fun fetchMangaDetails(mangaUrl: String): MangaDetails {
        val canonical = canonicalMangaUrl(mangaUrl)
            ?: throw IllegalArgumentException("URL manga DemonicScans non valido")
        return parseMangaDetails(fetchString(canonical), canonical)
    }

    override fun fetchPageImageUrls(chapterUrl: String): List<String> {
        // L'URL reader carica le pagine direttamente (o via redirect da chaptered.php).
        return parseReaderImageUrls(fetchString(chapterUrl.trim()))
    }

    override fun canonicalMangaUrl(url: String): String? = canonicalSeriesUrl(url)

    companion object {
        private const val BASE_URL = "https://demonicscans.org"

        private val seriesRegex =
            Regex("""^https?://demonicscans\.org/manga/([^/?#]+)""", RegexOption.IGNORE_CASE)
        private val readerRegex =
            Regex(
                """^https?://demonicscans\.org/title/([^/?#]+)/chapter/([^/?#]+)""",
                RegexOption.IGNORE_CASE,
            )
        private val chapterParamRegex =
            Regex("""[?&]chapter=([^&#]+)""", RegexOption.IGNORE_CASE)

        fun handlesUrl(url: String): Boolean = canonicalSeriesUrl(url) != null

        fun canonicalSeriesUrl(url: String): String? {
            val normalized = url.trim()
            seriesRegex.find(normalized)?.let { return "$BASE_URL/manga/${it.groupValues[1]}" }
            readerRegex.find(normalized)?.let { return "$BASE_URL/manga/${it.groupValues[1]}" }
            return null
        }

        /** URL del reader (slug-based) per un capitolo di una serie. */
        fun readerUrl(seriesSlug: String, numberText: String): String =
            "$BASE_URL/title/$seriesSlug/chapter/$numberText/1"

        // --- Parsing puro, testabile senza rete ---

        fun parseSearchResults(html: String, baseUrl: String): List<MangaSearchResult> {
            val document = Jsoup.parse(html, baseUrl)
            val results = linkedMapOf<String, MangaSearchResult>()
            for (anchor in document.select("""a[href^="/manga/"]""")) {
                val mangaUrl = canonicalSeriesUrl(anchor.absUrl("href")) ?: continue
                val image = anchor.selectFirst("img.search-thumb") ?: anchor.selectFirst("img")
                val title = firstNonBlankTrimmed(
                    anchor.selectFirst(".seach-right > div")?.text(),
                    anchor.selectFirst("div")?.text(),
                    image?.attr("alt"),
                ) ?: mangaUrl.substringAfterLast('/').replace('-', ' ').trim()
                if (title.isBlank()) {
                    continue
                }
                val cover = image?.let { firstNonBlankTrimmed(it.absUrl("src"), it.attr("src")) }
                results.putIfAbsent(
                    mangaUrl,
                    MangaSearchResult(
                        sourceId = MangaSourceIds.DEMONIC_SCANS,
                        title = title,
                        mangaUrl = mangaUrl,
                        coverUrl = cover,
                    ),
                )
            }
            return results.values.toList()
        }

        fun parseMangaDetails(html: String, mangaUrl: String): MangaDetails {
            val canonical = canonicalSeriesUrl(mangaUrl)
                ?: throw IllegalArgumentException("URL manga DemonicScans non valido")
            val document: Document = Jsoup.parse(html, canonical)
            val seriesSlug = canonical.substringAfterLast('/')
            val title = firstNonBlankTrimmed(
                document.selectFirst("h1")?.text(),
                document.selectFirst("""meta[property="og:title"]""")?.attr("content"),
            ) ?: "manga"
            val cover = firstNonBlankTrimmed(
                document.selectFirst("""meta[property="og:image"]""")?.attr("content"),
                document.selectFirst("img.series-profile-thumb")?.absUrl("src"),
            )
            val chapters = parseChapters(document, seriesSlug)
            return MangaDetails(
                sourceId = MangaSourceIds.DEMONIC_SCANS,
                title = title,
                coverUrl = cover,
                mangaUrl = canonical,
                chapters = chapters,
                description = parseDescription(document, "div.white-font"),
                status = mangaStatusFromText(statusTextNearLabel(document, "Status", "Stato")),
            )
        }

        fun parseReaderImageUrls(html: String): List<String> {
            val document = Jsoup.parse(html, BASE_URL)
            val ordered = linkedSetOf<String>()
            for (image in document.select("img.imgholder")) {
                val src = firstNonBlankTrimmed(image.absUrl("src"), image.attr("src")) ?: continue
                if (!src.startsWith("http", ignoreCase = true)) {
                    continue
                }
                if (isNonPageImage(src)) {
                    continue
                }
                ordered.add(src)
            }
            if (ordered.isEmpty()) {
                throw IllegalStateException("Nessuna immagine trovata per il capitolo")
            }
            return ordered.toList()
        }

        private fun parseChapters(document: Document, seriesSlug: String): List<ChapterEntry> {
            val entries = linkedMapOf<String, ChapterEntry>()
            for (anchor in document.select("""a[href*="chaptered.php"]""")) {
                val href = anchor.attr("href")
                val numberText = chapterParamRegex.find(href)?.groupValues?.getOrNull(1)?.trim()
                    ?: continue
                val numberValue = DownloadStorage.parseChapterValueOrNull(numberText) ?: continue
                val chapterUrl = readerUrl(seriesSlug, numberText)
                entries.putIfAbsent(
                    chapterUrl,
                    ChapterEntry(
                        numberText = numberText,
                        numberValue = numberValue,
                        url = chapterUrl,
                        slug = "chapter-$numberText",
                    ),
                )
            }
            if (entries.isEmpty()) {
                throw IllegalStateException("Nessun capitolo trovato sulla pagina manga")
            }
            return entries.values.sortedBy { it.numberValue }
        }

        /** Scarta gli `img.imgholder` che non sono pagine: l'ad interno e i placeholder locali. */
        private fun isNonPageImage(src: String): Boolean {
            val lowered = src.lowercase()
            return "free_ads" in lowered || "demonicscans.org/img/" in lowered
        }
    }
}
