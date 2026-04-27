package com.lorenzo.mangadownloader

import android.content.Context
import java.math.BigDecimal
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MangaWorldSource(
    context: Context,
    networkClient: MangaNetworkClient,
) : BaseMangaSource(context, networkClient) {
    override val descriptor = MangaSourceDescriptor(
        id = MangaSourceIds.MANGA_WORLD,
        displayName = "MangaWorld",
        shortName = "MW",
    )

    override val invalidChapterUrlMessage: String =
        "URL manga o capitolo MangaWorld non valido"

    override fun canHandleUrl(url: String): Boolean = handlesUrl(url)

    override fun searchManga(query: String): List<MangaSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }
        val url = BASE_URL.toHttpUrl()
            .newBuilder()
            .addPathSegment("archive")
            .addQueryParameter("keyword", trimmed)
            .build()
            .toString()

        return parseSearchResults(fetchString(url))
    }

    override fun fetchMangaDetails(mangaUrl: String): MangaDetails {
        val canonical = canonicalMangaUrl(mangaUrl)
            ?: throw IllegalArgumentException("URL manga MangaWorld non valido")
        val document = fetchDocument(canonical)
        return parseMangaDetails(document, canonical)
    }

    override fun canonicalMangaUrl(url: String): String? = canonicalSeriesUrl(url)

    override fun fetchPageImageUrls(chapterUrl: String): List<String> {
        val readerUrl = chapterReaderUrl(chapterUrl)
            ?: throw IllegalArgumentException("URL capitolo MangaWorld non valido")
        val document = fetchDocument(readerUrl)
        return parsePageImageUrls(document, readerUrl)
    }

    override fun normalizeChapterUrlForComparison(url: String): String {
        return normalizedChapterIdentityUrl(url) ?: super.normalizeChapterUrlForComparison(url)
    }

    companion object {
        private const val BASE_URL = "https://www.mangaworld.mx/"
        private val mangaRegex =
            Regex(
                """^https?://(?:www\.)?mangaworld\.mx/manga/(\d+)(?:/[^/?#]+)?/?(?:[?#].*)?$""",
                RegexOption.IGNORE_CASE,
            )
        private val readRegex =
            Regex(
                """^https?://(?:www\.)?mangaworld\.mx/manga/(\d+)(?:/([^/?#]+))?/read/([^/?#]+)(?:/\d+)?""",
                RegexOption.IGNORE_CASE,
            )
        private val chapterNumberRegex =
            Regex("""capitolo\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

        fun handlesUrl(url: String): Boolean {
            return canonicalSeriesUrl(url) != null || chapterReaderUrl(url) != null
        }

        fun canonicalSeriesUrl(url: String): String? {
            val normalized = url.trim()
            readRegex.find(normalized)?.let { match ->
                return "${BASE_URL}manga/${match.groupValues[1]}"
            }
            mangaRegex.find(normalized)?.let { match ->
                return "${BASE_URL}manga/${match.groupValues[1]}"
            }
            return null
        }

        fun parseSearchResults(raw: String): List<MangaSearchResult> {
            return parseSearchResults(Jsoup.parse(raw, BASE_URL))
        }

        fun parseMangaDetails(raw: String, mangaUrl: String): MangaDetails {
            return parseMangaDetails(Jsoup.parse(raw, mangaUrl), mangaUrl)
        }

        fun parsePageImageUrls(raw: String, chapterUrl: String): List<String> {
            return parsePageImageUrls(Jsoup.parse(raw, chapterUrl), chapterUrl)
        }

        private fun parseSearchResults(document: Document): List<MangaSearchResult> {
            val results = linkedMapOf<String, MangaSearchResult>()
            val selectors = """a.manga-title[href*="/manga/"], .comics-grid .entry a.thumb[href*="/manga/"]"""
            for (anchor in document.select(selectors)) {
                val mangaUrl = canonicalSeriesUrl(anchor.absUrl("href")) ?: continue
                val entry = anchor.closest(".entry")
                val title = firstNonBlankStatic(
                    anchor.attr("title"),
                    anchor.text(),
                    entry?.selectFirst(".manga-title")?.text(),
                    entry?.selectFirst("img")?.attr("alt"),
                ) ?: continue
                val cover = entry?.selectFirst("img")
                    ?.let { image ->
                        firstNonBlankStatic(image.absUrl("src"), image.absUrl("data-src"))
                    }
                results[mangaUrl] = MangaSearchResult(
                    sourceId = MangaSourceIds.MANGA_WORLD,
                    title = title,
                    mangaUrl = mangaUrl,
                    coverUrl = cover,
                )
            }
            return results.values.toList()
        }

        private fun parseMangaDetails(document: Document, mangaUrl: String): MangaDetails {
            val canonical = canonicalSeriesUrl(mangaUrl)
                ?: throw IllegalArgumentException("URL manga MangaWorld non valido")
            val title = document.selectFirst("h1.name")?.text()?.trim()
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: "manga"
            val cover = firstNonBlankStatic(
                document.selectFirst(".comic-info .thumb img")?.absUrl("src"),
                document.selectFirst("""meta[property="og:image"]""")?.attr("content"),
            )
            val chapters = parseChapters(document)
            return MangaDetails(
                sourceId = MangaSourceIds.MANGA_WORLD,
                title = title,
                coverUrl = cover,
                mangaUrl = canonical,
                chapters = chapters,
            )
        }

        private fun parseChapters(document: Document): List<ChapterEntry> {
            val entries = linkedMapOf<String, ChapterEntry>()
            for (volume in document.select(".chapters-wrapper .volume-element")) {
                val volumeText = volume.selectFirst(".volume-name")?.text()?.trim()
                collectChapterEntries(
                    target = entries,
                    anchors = volume.select("""a.chap[href*="/read/"]"""),
                    volumeText = volumeText,
                )
            }

            val ungroupedSelectors = """#chapterList a.chap[href*="/read/"], .chapters-wrapper a[href*="/read/"]"""
            val ungroupedAnchors = document.select(ungroupedSelectors)
                .filter { anchor -> anchor.closest(".volume-element") == null }
            collectChapterEntries(
                target = entries,
                anchors = ungroupedAnchors,
                volumeText = null,
            )

            if (entries.isEmpty()) {
                throw IllegalStateException("Nessun capitolo trovato sulla pagina manga")
            }

            return entries.values.sortedBy { it.numberValue }
        }

        private fun collectChapterEntries(
            target: LinkedHashMap<String, ChapterEntry>,
            anchors: Iterable<Element>,
            volumeText: String?,
        ) {
            for (anchor in anchors) {
                val href = anchor.absUrl("href").ifBlank { anchor.attr("href").trim() }
                val chapterUrl = chapterReaderUrl(href) ?: continue
                val title = firstNonBlankStatic(
                    anchor.selectFirst("span")?.text(),
                    anchor.attr("title"),
                    anchor.text(),
                ) ?: continue
                val numberText = chapterNumberRegex.find(title)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?: continue
                val numberValue = DownloadStorage.parseChapterValueOrNull(numberText)
                    ?: numberText.toBigDecimalOrNull()
                    ?: BigDecimal(numberText)
                target[chapterUrl] = ChapterEntry(
                    numberText = numberText,
                    numberValue = numberValue,
                    url = chapterUrl,
                    slug = chapterSlug(chapterUrl, numberText),
                    volumeText = volumeText?.takeIf(String::isNotBlank),
                )
            }
        }

        private fun parsePageImageUrls(document: Document, chapterUrl: String): List<String> {
            val ordered = linkedSetOf<String>()
            for (image in document.select("""#page img.page-image, img.page-image""")) {
                val src = firstNonBlankStatic(
                    image.absUrl("data-src"),
                    image.absUrl("src"),
                    image.attr("data-src"),
                    image.attr("src"),
                ) ?: continue
                ordered.add(src)
            }
            if (ordered.isEmpty()) {
                throw IllegalStateException("Nessuna immagine trovata per il capitolo")
            }
            return ordered.toList()
        }

        private fun chapterReaderUrl(url: String): String? {
            val normalized = url.trim()
            val match = readRegex.find(normalized) ?: return null
            val mangaId = match.groupValues[1]
            val slug = match.groupValues.getOrNull(2).orEmpty()
            val chapterId = match.groupValues[3]
            return buildString {
                append(BASE_URL)
                append("manga/")
                append(mangaId)
                if (slug.isNotBlank()) {
                    append('/')
                    append(slug)
                }
                append("/read/")
                append(chapterId)
                append("?style=list")
            }
        }

        private fun normalizedChapterIdentityUrl(url: String): String? {
            val match = readRegex.find(url.trim()) ?: return null
            return "${BASE_URL}manga/${match.groupValues[1]}/read/${match.groupValues[3]}"
        }

        private fun chapterSlug(chapterUrl: String, numberText: String): String {
            val id = readRegex.find(chapterUrl)?.groupValues?.getOrNull(3)
                ?: chapterUrl.substringBefore('?').substringAfterLast('/')
            val chapter = numberText.lowercase(Locale.US).replace('.', '-')
            return "capitolo-$chapter-$id"
        }

        private fun firstNonBlankStatic(vararg values: String?): String? {
            for (value in values) {
                val trimmed = value?.trim().orEmpty()
                if (trimmed.isNotBlank()) {
                    return trimmed
                }
            }
            return null
        }
    }
}
