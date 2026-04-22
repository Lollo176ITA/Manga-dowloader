package com.lorenzo.mangadownloader

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.util.Locale

class MangapillSource(
    context: Context,
    networkClient: MangaNetworkClient,
) : BaseMangaSource(context, networkClient) {
    override val descriptor = MangaSourceDescriptor(
        id = MangaSourceIds.MANGAPILL,
        displayName = "Mangapill",
        shortName = "MP",
    )

    override val invalidChapterUrlMessage: String =
        "URL manga o capitolo Mangapill non valido"

    override fun canHandleUrl(url: String): Boolean = handlesUrl(url)

    override fun searchManga(query: String): List<MangaSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }
        val url = "https://mangapill.com/search".toHttpUrl()
            .newBuilder()
            .addQueryParameter("q", trimmed)
            .build()
            .toString()

        val document = fetchDocument(url)
        val accumulated = linkedMapOf<String, Pair<String?, String?>>()

        for (anchor in document.select("""a[href^="/manga/"]""")) {
            val href = anchor.attr("href").trim()
            val mangaUrl = canonicalMangaUrl(absolutize(url, href)) ?: continue

            val image = anchor.selectFirst("img")
            val coverRaw = image?.let { img ->
                sequenceOf(img.attr("data-src"), img.attr("src"))
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            }
            val cover = coverRaw?.let { absolutize(url, it) }

            val anchorText = anchor.text().trim()
            val titleCandidate = firstNonBlank(
                image?.attr("alt"),
                anchor.attr("title"),
                anchorText.takeIf { it.isNotBlank() },
            )

            val prior = accumulated[mangaUrl]
            accumulated[mangaUrl] = Pair(
                prior?.first ?: titleCandidate,
                prior?.second ?: cover,
            )
        }

        return accumulated.entries.mapNotNull { (mangaUrl, pair) ->
            val (titleRaw, cover) = pair
            val title = titleRaw?.trim().orEmpty().ifBlank {
                mangaUrl.substringAfterLast('/').replace('-', ' ').trim()
            }
            if (title.isBlank()) null
            else {
                MangaSearchResult(
                    sourceId = descriptor.id,
                    title = title,
                    mangaUrl = mangaUrl,
                    coverUrl = cover,
                )
            }
        }
    }

    override fun fetchMangaDetails(mangaUrl: String): MangaDetails {
        val canonical = canonicalMangaUrl(mangaUrl)
            ?: throw IllegalArgumentException("URL manga non valido")
        val document = fetchDocument(canonical)
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { "manga" }
        val cover = findCoverImage(document)?.let { absolutize(canonical, it) }
        val chapters = fetchChapterEntries(document, canonical)
        return MangaDetails(
            sourceId = descriptor.id,
            title = title,
            coverUrl = cover,
            mangaUrl = canonical,
            chapters = chapters,
        )
    }

    private fun fetchChapterEntries(document: Document, mangaUrl: String): List<ChapterEntry> {
        val entries = linkedMapOf<String, ChapterEntry>()

        for (link in document.select("""#chapters a[href^="/chapters/"]""")) {
            val href = link.attr("href").trim()
            if (href.isBlank()) {
                continue
            }
            val chapterUrl = absolutize(mangaUrl, href)
            val title = link.attr("title").trim().ifBlank { link.text().trim() }
            val numberText = Regex("""chapter\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                .find(title)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex("""chapter-(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                    .find(chapterUrl)
                    ?.groupValues
                    ?.getOrNull(1)
                ?: continue

            entries[chapterUrl] = ChapterEntry(
                numberText = numberText,
                numberValue = parseChapterNumber(numberText),
                url = chapterUrl,
                slug = chapterUrl.substringAfterLast('/'),
            )
        }

        if (entries.isEmpty()) {
            throw IllegalStateException("Nessun capitolo trovato sulla pagina manga")
        }

        return entries.values.sortedBy { it.numberValue }
    }

    override fun fetchPageImageUrls(chapterUrl: String): List<String> {
        val document = fetchDocument(chapterUrl)
        val selectors = listOf(
            "chapter-page img.js-page",
            "chapter-page picture img",
            "img.page-image",
        )

        val ordered = linkedSetOf<String>()
        for (selector in selectors) {
            for (image in document.select(selector)) {
                val src = image.attr("data-src").ifBlank { image.attr("src") }.trim()
                if (src.isNotBlank()) {
                    ordered.add(absolutize(chapterUrl, src))
                }
            }
        }

        if (ordered.isEmpty()) {
            throw IllegalStateException("Nessuna immagine trovata per il capitolo")
        }

        return ordered.toList()
    }

    override fun canonicalMangaUrl(url: String): String? = canonicalSeriesUrl(url)

    private fun findCoverImage(document: Document): String? {
        val candidates = listOf(
            "figure img",
            "picture img",
            "div.flex img",
            "img",
        )
        for (selector in candidates) {
            for (image in document.select(selector)) {
                val src = image.attr("data-src").ifBlank { image.attr("src") }.trim()
                if (src.isBlank()) {
                    continue
                }
                if (looksLikeCover(src, image)) {
                    return src
                }
            }
        }
        return null
    }

    private fun looksLikeCover(src: String, image: Element): Boolean {
        val lowered = src.lowercase(Locale.US)
        if ("mangapill.com" in lowered || "cdn.mangapill" in lowered || "cover" in lowered) {
            return true
        }
        val alt = image.attr("alt").lowercase(Locale.US)
        return alt.isNotBlank() && alt != "logo"
    }

    companion object {
        private val mangaRegex =
            Regex("""^https?://mangapill\.com/manga/([^/?#]+)(?:/([^/?#]+))?""", RegexOption.IGNORE_CASE)
        private val chapterRegex =
            Regex("""^https?://mangapill\.com/chapters/([^/]+)/""", RegexOption.IGNORE_CASE)

        fun handlesUrl(url: String): Boolean {
            return canonicalSeriesUrl(url) != null
        }

        fun canonicalSeriesUrl(url: String): String? {
            val normalized = url.trim()
            val mangaMatch = mangaRegex.find(normalized)
            if (mangaMatch != null) {
                val id = mangaMatch.groupValues[1]
                val slug = mangaMatch.groupValues.getOrNull(2).orEmpty()
                return if (slug.isBlank()) {
                    "https://mangapill.com/manga/$id"
                } else {
                    "https://mangapill.com/manga/$id/$slug"
                }
            }
            val chapterMatch = chapterRegex.find(normalized)
            if (chapterMatch != null) {
                val mangaId = chapterMatch.groupValues[1].substringBefore('-')
                if (mangaId.isNotBlank()) {
                    return "https://mangapill.com/manga/$mangaId"
                }
            }
            return null
        }
    }
}
