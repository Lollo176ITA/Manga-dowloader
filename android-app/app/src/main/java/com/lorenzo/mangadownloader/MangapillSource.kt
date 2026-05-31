package com.lorenzo.mangadownloader

import android.content.Context
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MangapillSource(
    context: Context,
    networkClient: MangaNetworkClient,
    libraryRepository: LibraryRepository = LibraryRepository(context),
) : BaseMangaSource(context, networkClient, libraryRepository) {
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

        return parseSearchResults(fetchString(url), url)
    }

    override fun fetchMangaDetails(mangaUrl: String): MangaDetails {
        val canonical = canonicalMangaUrl(mangaUrl)
            ?: throw IllegalArgumentException("URL manga non valido")
        return parseMangaDetails(fetchString(canonical), canonical)
    }

    override fun fetchPageImageUrls(chapterUrl: String): List<String> {
        return parsePageImageUrls(fetchString(chapterUrl), chapterUrl)
    }

    override fun canonicalMangaUrl(url: String): String? = canonicalSeriesUrl(url)

    companion object {
        private val mangaRegex =
            Regex("""^https?://mangapill\.com/manga/([^/?#]+)(?:/([^/?#]+))?""", RegexOption.IGNORE_CASE)
        private val chapterRegex =
            Regex("""^https?://mangapill\.com/chapters/([^/]+)/""", RegexOption.IGNORE_CASE)
        private val chapterNumberInTitle =
            Regex("""chapter\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val chapterNumberInUrl =
            Regex("""chapter-(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

        fun handlesUrl(url: String): Boolean {
            return canonicalSeriesUrl(url) != null
        }

        fun canonicalSeriesUrl(url: String): String? {
            val normalized = url.trim()
            val mangaMatch = mangaRegex.find(normalized)
            if (mangaMatch != null) {
                val id = mangaMatch.groupValues[1]
                return "https://mangapill.com/manga/$id"
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

        /** Estrae i risultati di ricerca dall'HTML della pagina `/search`. */
        fun parseSearchResults(raw: String, baseUrl: String): List<MangaSearchResult> {
            return parseSearchResults(Jsoup.parse(raw, baseUrl))
        }

        /** Estrae titolo, copertina e capitoli dall'HTML della pagina manga. */
        fun parseMangaDetails(raw: String, mangaUrl: String): MangaDetails {
            val canonical = canonicalSeriesUrl(mangaUrl)
                ?: throw IllegalArgumentException("URL manga non valido")
            return parseMangaDetails(Jsoup.parse(raw, canonical), canonical)
        }

        /** Estrae gli URL delle immagini di un capitolo dall'HTML del reader. */
        fun parsePageImageUrls(raw: String, chapterUrl: String): List<String> {
            return parsePageImageUrls(Jsoup.parse(raw, chapterUrl), chapterUrl)
        }

        private fun parseSearchResults(document: Document): List<MangaSearchResult> {
            val accumulated = linkedMapOf<String, Pair<String?, String?>>()

            for (anchor in document.select("""a[href^="/manga/"]""")) {
                val mangaUrl = canonicalSeriesUrl(anchor.absUrl("href")) ?: continue

                val image = anchor.selectFirst("img")
                val cover = image?.let { img ->
                    sequenceOf("data-src", "src")
                        .firstOrNull { attr ->
                            val value = img.attr(attr).trim()
                            value.isNotBlank() && !value.startsWith("data:")
                        }
                        ?.let { attr -> img.absUrl(attr) }
                }

                val anchorText = anchor.text().trim()
                val titleCandidate = firstNonBlankTrimmed(
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
                if (title.isBlank()) {
                    null
                } else {
                    MangaSearchResult(
                        sourceId = MangaSourceIds.MANGAPILL,
                        title = title,
                        mangaUrl = mangaUrl,
                        coverUrl = cover,
                    )
                }
            }
        }

        private fun parseMangaDetails(document: Document, canonical: String): MangaDetails {
            val title = document.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { "manga" }
            val cover = findCoverImage(document)
            val chapters = parseChapterEntries(document)
            return MangaDetails(
                sourceId = MangaSourceIds.MANGAPILL,
                title = title,
                coverUrl = cover,
                mangaUrl = canonical,
                chapters = chapters,
                description = parseDescription(document),
                status = mangaStatusFromText(statusTextNearLabel(document, "Status", "Stato")),
            )
        }

        private fun parseChapterEntries(document: Document): List<ChapterEntry> {
            val entries = linkedMapOf<String, ChapterEntry>()

            for (link in document.select("""#chapters a[href^="/chapters/"]""")) {
                if (link.attr("href").trim().isBlank()) {
                    continue
                }
                val chapterUrl = link.absUrl("href")
                val title = link.attr("title").trim().ifBlank { link.text().trim() }
                val numberText = chapterNumberInTitle.find(title)?.groupValues?.getOrNull(1)
                    ?: chapterNumberInUrl.find(chapterUrl)?.groupValues?.getOrNull(1)
                    ?: continue

                val numberValue = DownloadStorage.parseChapterValueOrNull(numberText)
                    ?: throw IllegalArgumentException("Numero capitolo non valido: $numberText")

                entries[chapterUrl] = ChapterEntry(
                    numberText = numberText,
                    numberValue = numberValue,
                    url = chapterUrl,
                    slug = chapterUrl.substringAfterLast('/'),
                )
            }

            if (entries.isEmpty()) {
                throw IllegalStateException("Nessun capitolo trovato sulla pagina manga")
            }

            return entries.values.sortedBy { it.numberValue }
        }

        private fun parsePageImageUrls(document: Document, chapterUrl: String): List<String> {
            val selectors = listOf(
                "chapter-page img.js-page",
                "chapter-page picture img",
                "img.page-image",
            )

            val ordered = linkedSetOf<String>()
            for (selector in selectors) {
                for (image in document.select(selector)) {
                    val attr = if (image.attr("data-src").isNotBlank()) "data-src" else "src"
                    if (image.attr(attr).trim().isBlank()) {
                        continue
                    }
                    ordered.add(image.absUrl(attr))
                }
            }

            if (ordered.isEmpty()) {
                throw IllegalStateException("Nessuna immagine trovata per il capitolo")
            }

            return ordered.toList()
        }

        private fun findCoverImage(document: Document): String? {
            val candidates = listOf(
                "figure img",
                "picture img",
                "div.flex img",
                "img",
            )
            for (selector in candidates) {
                for (image in document.select(selector)) {
                    val attr = if (image.attr("data-src").isNotBlank()) "data-src" else "src"
                    val src = image.attr(attr).trim()
                    if (src.isBlank()) {
                        continue
                    }
                    if (looksLikeCover(src, image)) {
                        return image.absUrl(attr)
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

    }
}
