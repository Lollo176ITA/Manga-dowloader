package com.lorenzo.mangadownloader

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Fonte per **VyManga** (`vymanga.com`).
 *
 * Particolarità del sito: i link dei capitoli nella pagina manga non puntano al
 * reader, ma a un redirector esterno con un **token cifrato e monouso**
 * (`aovheroes.com/rds/...` → ruota su altri domini). Il redirector, da un IP
 * "umano" (residenziale/mobile, come quello del telefono), risolve il token con
 * un semplice redirect HTTP fino alla pagina reader vera; da IP "bot" serve una
 * pagina civetta. Poiché OkHttp segue i redirect, on-device la risoluzione
 * funziona — ma il token **non è riusabile**, quindi non possiamo salvarlo come
 * URL del capitolo.
 *
 * Soluzione: il capitolo è identificato da un URL **sintetico e stabile**
 * `https://vymanga.com/manga/<slug>/chapter-<n>` (che NON esiste sul sito, è solo
 * un identificatore). Al momento del download, [fetchPageImageUrls] ri-scarica la
 * pagina manga, recupera il token **fresco** di quel capitolo
 * (`getElementById("chapter-<n>")`) e lo segue fino al reader.
 *
 * Le immagini delle pagine sono lazy-loaded (`lozad`): l'URL reale sta in
 * `data-src` ed è ospitato su Google Blogger (`*.bp.blogspot.com/drive-storage/...`),
 * pubblicamente scaricabile.
 */
class VyMangaSource(
    context: Context,
    networkClient: MangaNetworkClient,
    libraryRepository: LibraryRepository = LibraryRepository(context),
) : BaseMangaSource(context, networkClient, libraryRepository) {
    override val descriptor = MangaSourceDescriptor(
        id = MangaSourceIds.VYMANGA,
        displayName = "VyManga",
        shortName = "VY",
    )

    override val invalidChapterUrlMessage: String =
        "URL manga o capitolo VyManga non valido"

    override fun canHandleUrl(url: String): Boolean = handlesUrl(url)

    override fun searchManga(query: String): List<MangaSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }
        val url = "$BASE_URL/search".toHttpUrl()
            .newBuilder()
            .addQueryParameter("q", trimmed)
            .build()
            .toString()
        return parseSearchResults(fetchString(url), url)
    }

    override fun fetchMangaDetails(mangaUrl: String): MangaDetails {
        val canonical = canonicalMangaUrl(mangaUrl)
            ?: throw IllegalArgumentException("URL manga VyManga non valido")
        return parseMangaDetails(fetchString(canonical), canonical)
    }

    override fun fetchPageImageUrls(chapterUrl: String): List<String> {
        val ref = parseChapterRef(chapterUrl)
            ?: throw IllegalArgumentException(invalidChapterUrlMessage)
        // I token sono monouso: prendine uno fresco dalla pagina manga al momento del download.
        val mangaHtml = fetchString(ref.mangaUrl)
        val tokenUrl = extractChapterToken(mangaHtml, ref.chapterId)
            ?: throw IllegalStateException("Capitolo non più disponibile nella pagina manga")
        // OkHttp segue i redirect del cloaker fino alla pagina reader.
        val pages = parseReaderImageUrls(fetchString(tokenUrl))
        // Le pagine sono servite ridimensionate (es. =w700); su richiesta passa al full-res.
        return if (highResImagesEnabled()) pages.map { toHighResUrl(it) } else pages
    }

    override fun canonicalMangaUrl(url: String): String? = canonicalSeriesUrl(url)

    override fun normalizeChapterUrlForComparison(url: String): String {
        return parseChapterRef(url)?.let { "${it.mangaUrl}/${it.chapterId}" }
            ?: canonicalSeriesUrl(url)
            ?: super.normalizeChapterUrlForComparison(url)
    }

    companion object {
        private const val BASE_URL = "https://vymanga.com"

        private val mangaRegex =
            Regex("""^https?://(?:www\.)?vymanga\.(?:com|net)/manga/([^/?#]+)""", RegexOption.IGNORE_CASE)
        private val chapterRefRegex =
            Regex(
                """^https?://(?:www\.)?vymanga\.(?:com|net)/manga/([^/?#]+)/(chapter-[^/?#]+)""",
                RegexOption.IGNORE_CASE,
            )
        private val chapterNumberInText =
            Regex("""chapter\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        // Suffisso di ridimensionamento Google Blogger in coda all'URL (es. =w700, =s1600, =w700-h1000).
        private val imageSizeSuffix =
            Regex("""=[swh]\d+(?:-[swh]\d+)*$""", RegexOption.IGNORE_CASE)

        /** Riferimento stabile a un capitolo: serie canonica + id `chapter-<n>`. */
        data class ChapterRef(val mangaUrl: String, val chapterId: String)

        fun handlesUrl(url: String): Boolean = canonicalSeriesUrl(url) != null

        fun canonicalSeriesUrl(url: String): String? {
            val match = mangaRegex.find(url.trim()) ?: return null
            return "$BASE_URL/manga/${match.groupValues[1]}"
        }

        /** Estrae serie + id capitolo da un URL sintetico `.../manga/<slug>/chapter-<n>`. */
        fun parseChapterRef(url: String): ChapterRef? {
            val match = chapterRefRegex.find(url.trim()) ?: return null
            return ChapterRef(
                mangaUrl = "$BASE_URL/manga/${match.groupValues[1]}",
                chapterId = match.groupValues[2],
            )
        }

        // --- Parsing puro, testabile senza rete ---

        fun parseSearchResults(raw: String, baseUrl: String): List<MangaSearchResult> {
            return parseSearchResults(Jsoup.parse(raw, baseUrl))
        }

        fun parseMangaDetails(raw: String, mangaUrl: String): MangaDetails {
            return parseMangaDetails(Jsoup.parse(raw, mangaUrl), mangaUrl)
        }

        /** URL del token (cloaker) per il capitolo con id [chapterId] dato l'HTML della pagina manga. */
        fun extractChapterToken(mangaHtml: String, chapterId: String): String? {
            val anchor = Jsoup.parse(mangaHtml, BASE_URL).getElementById(chapterId) ?: return null
            return firstNonBlankTrimmed(anchor.absUrl("href"), anchor.attr("href"))
        }

        /**
         * Converte l'URL Blogger ridimensionato alla risoluzione originale: il suffisso di
         * resize finale (`=w700`, `=s1600`, `=w700-h1000`...) diventa `=s0`. Se non c'è
         * suffisso, l'URL resta invariato.
         */
        fun toHighResUrl(url: String): String =
            if (imageSizeSuffix.containsMatchIn(url)) imageSizeSuffix.replace(url, "=s0") else url

        /** URL immagine (in ordine) dall'HTML del reader. */
        fun parseReaderImageUrls(raw: String): List<String> {
            val document = Jsoup.parse(raw)
            val ordered = linkedSetOf<String>()
            val selectors = listOf(
                "div.hview img.lozad",
                "img.lozad[data-src*=blogspot]",
                "img.lozad[data-src*=drive-storage]",
            )
            for (selector in selectors) {
                for (image in document.select(selector)) {
                    val src = firstNonBlankTrimmed(image.attr("data-src"), image.attr("src")) ?: continue
                    if (!src.startsWith("http", ignoreCase = true)) continue
                    if (isPlaceholderImage(src)) continue
                    ordered.add(src)
                }
                if (ordered.isNotEmpty()) break
            }
            if (ordered.isEmpty()) {
                throw IllegalStateException("Nessuna immagine trovata per il capitolo")
            }
            return ordered.toList()
        }

        private fun parseSearchResults(document: Document): List<MangaSearchResult> {
            val results = linkedMapOf<String, MangaSearchResult>()
            for (anchor in document.select("""div.book-list .comic-item a[href*="/manga/"]""")) {
                val mangaUrl = canonicalSeriesUrl(anchor.absUrl("href")) ?: continue
                val image = anchor.selectFirst("img")
                val title = firstNonBlankTrimmed(
                    anchor.selectFirst(".comic-title")?.text(),
                    image?.attr("title"),
                    image?.attr("alt"),
                ) ?: mangaUrl.substringAfterLast('/').replace('-', ' ').trim()
                if (title.isBlank()) continue
                val cover = image?.let {
                    firstNonBlankTrimmed(it.absUrl("data-src"), it.absUrl("src"), it.attr("data-src"))
                }?.takeUnless(::isPlaceholderImage)
                results.putIfAbsent(
                    mangaUrl,
                    MangaSearchResult(
                        sourceId = MangaSourceIds.VYMANGA,
                        title = title,
                        mangaUrl = mangaUrl,
                        coverUrl = cover,
                    ),
                )
            }
            return results.values.toList()
        }

        private fun parseMangaDetails(document: Document, mangaUrl: String): MangaDetails {
            val canonical = canonicalSeriesUrl(mangaUrl)
                ?: throw IllegalArgumentException("URL manga VyManga non valido")
            val title = firstNonBlankTrimmed(
                document.selectFirst("h1.title")?.text(),
                document.selectFirst("h1")?.text(),
            ) ?: "manga"
            val cover = firstNonBlankTrimmed(
                document.selectFirst("div.img-manga img")?.absUrl("src"),
                document.selectFirst("div.img-manga img")?.attr("src"),
                document.selectFirst("""meta[property="og:image"]""")?.attr("content"),
                document.selectFirst("""meta[name="twitter:image:src"]""")?.attr("content"),
            )?.takeUnless(::isPlaceholderImage)
            val chapters = parseChapters(document, canonical)
            return MangaDetails(
                sourceId = MangaSourceIds.VYMANGA,
                title = title,
                coverUrl = cover,
                mangaUrl = canonical,
                chapters = chapters,
            )
        }

        private fun parseChapters(document: Document, canonical: String): List<ChapterEntry> {
            val entries = linkedMapOf<String, ChapterEntry>()
            for (anchor in document.select("div.div-chapter a.list-chapter")) {
                val chapterId = anchor.id().trim()
                if (chapterId.isBlank() || !chapterId.startsWith("chapter-", ignoreCase = true)) continue
                val numberText = firstNonBlankTrimmed(
                    chapterId.removePrefix("chapter-").takeIf { DownloadStorage.parseChapterValueOrNull(it) != null },
                    chapterNumberInText.find(anchor.text())?.groupValues?.getOrNull(1),
                ) ?: continue
                val numberValue = DownloadStorage.parseChapterValueOrNull(numberText) ?: continue
                val chapterUrl = "$canonical/$chapterId"
                entries[chapterUrl] = ChapterEntry(
                    numberText = numberText,
                    numberValue = numberValue,
                    url = chapterUrl,
                    slug = "capitolo-${numberText.replace('.', '-')}",
                )
            }
            if (entries.isEmpty()) {
                throw IllegalStateException("Nessun capitolo trovato sulla pagina manga")
            }
            return entries.values.sortedBy { it.numberValue }
        }

        private fun isPlaceholderImage(url: String): Boolean {
            val lowered = url.lowercase()
            return lowered.startsWith("data:") ||
                "loading.gif" in lowered ||
                "blank.gif" in lowered
        }

    }
}
