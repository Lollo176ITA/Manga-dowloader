package com.lorenzo.mangadownloader.data.sources

import android.content.Context
import com.lorenzo.mangadownloader.app.hidesAdultContent
import com.lorenzo.mangadownloader.data.library.DownloadStorage
import com.lorenzo.mangadownloader.data.library.LibraryRepository
import com.lorenzo.mangadownloader.data.model.ChapterEntry
import com.lorenzo.mangadownloader.data.model.MangaDetails
import com.lorenzo.mangadownloader.data.model.MangaSearchResult
import com.lorenzo.mangadownloader.data.model.mangaStatusFromText
import com.lorenzo.mangadownloader.data.network.MangaNetworkClient
import com.lorenzo.mangadownloader.data.store.SettingsStore
import com.lorenzo.mangadownloader.domain.isAdultGenre
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Fonte per **VyManga**. Il sito ha cambiato dominio: `vymanga.com` ora rimanda per tutti a
 * una pagina directory, quello vivo è [LIVE_BASE] (`mangavyvy.com`, mirror `.net`).
 * L'**identità** delle serie resta però `https://vymanga.com/manga/<slug>` ([IDENTITY_BASE]):
 * è la chiave con cui preferiti, libreria e metadati già salvati conoscono la serie, e
 * cambiarla li scollegherebbe. Solo le richieste di rete passano da [liveUrl]; a un prossimo
 * trasloco basta cambiare [LIVE_BASE].
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
        language = MangaSourceLanguage.ENG,
    )

    override val invalidChapterUrlMessage: String =
        "URL manga o capitolo VyManga non valido"

    override fun canHandleUrl(url: String): Boolean = handlesUrl(url)

    override fun searchManga(query: String): List<MangaSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return emptyList()
        }
        if (!hidesAdultContent()) {
            val url = searchUrl(trimmed, excludedGenres = emptyList())
            return parseSearchResults(fetchString(url), url)
        }
        // I risultati non hanno generi: gli adulti si escludono lato server. I valori dei
        // generi contengono un id del sito; ogni risposta elenca quelli attuali, quindi se non
        // combaciano con quelli inviati (il sito li ha cambiati) si ripete con quelli freschi.
        val url = searchUrl(trimmed, excludedGenres = DEFAULT_ADULT_GENRE_VALUES)
        val html = fetchString(url)
        val current = adultGenreValues(html)
        if (current.isNotEmpty() && current.toSet() != DEFAULT_ADULT_GENRE_VALUES.toSet()) {
            val fresh = searchUrl(trimmed, excludedGenres = current)
            return parseSearchResults(fetchString(fresh), fresh)
        }
        return parseSearchResults(html, url)
    }

    override fun fetchMangaDetails(mangaUrl: String): MangaDetails {
        val canonical = canonicalMangaUrl(mangaUrl)
            ?: throw IllegalArgumentException("URL manga VyManga non valido")
        return parseMangaDetails(fetchString(liveUrl(canonical)), canonical)
    }

    override fun fetchPageImageUrls(chapterUrl: String): List<String> {
        val ref = parseChapterRef(chapterUrl)
            ?: throw IllegalArgumentException(invalidChapterUrlMessage)
        // I token sono monouso: prendine uno fresco dalla pagina manga al momento del download.
        val mangaHtml = fetchString(liveUrl(ref.mangaUrl))
        val tokenUrl = extractChapterToken(mangaHtml, ref.chapterId)
            ?: throw IllegalStateException("Capitolo non più disponibile nella pagina manga")
        // OkHttp segue i redirect del cloaker fino alla pagina reader.
        val pages = parseReaderImageUrls(fetchString(tokenUrl))
        // Il reader serve le pagine ridimensionate a 700 px (=w700): sempre l'originale (=s0).
        return pages.map { toHighResUrl(it) }
    }

    private fun hidesAdultContent(): Boolean =
        SettingsStore(context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE))
            .read()
            .hidesAdultContent()

    override fun canonicalMangaUrl(url: String): String? = canonicalSeriesUrl(url)

    override fun normalizeChapterUrlForComparison(url: String): String {
        return parseChapterRef(url)?.let { "${it.mangaUrl}/${it.chapterId}" }
            ?: canonicalSeriesUrl(url)
            ?: super.normalizeChapterUrlForComparison(url)
    }

    companion object {
        /** Base degli URL **identità** (chiavi salvate): non cambia coi traslochi del sito. */
        private const val IDENTITY_BASE = "https://vymanga.com"

        /** Dominio attualmente vivo, usato per tutte le richieste di rete. */
        private const val LIVE_BASE = "https://mangavyvy.com"

        // Tutti i domini del sito, vecchi e nuovi: un URL di uno qualsiasi è la stessa serie.
        private const val HOSTS = """(?:www\.)?(?:vymanga|mangavyvy)\.(?:com|net)"""

        private val hostRegex = Regex("""^https?://$HOSTS""", RegexOption.IGNORE_CASE)
        private val mangaRegex =
            Regex("""^https?://$HOSTS/manga/([^/?#]+)""", RegexOption.IGNORE_CASE)
        private val chapterRefRegex =
            Regex(
                """^https?://$HOSTS/manga/([^/?#]+)/(chapter-[^/?#]+)""",
                RegexOption.IGNORE_CASE,
            )

        /**
         * `data-value` dei generi per adulti (espliciti + ecchi, non "Mature"), verificati sul
         * sito il 2026-09-25. Formato `<Nome>-<id>-<slug>`: servono esattamente così a
         * `exclude_genre[]`. Se il sito cambia gli id, [adultGenreValues] li ricava dalla pagina.
         */
        val DEFAULT_ADULT_GENRE_VALUES = listOf(
            "Ecchi-27-ecchi",
            "Erotica-146-erotica",
            "Pornographic-147-pornographic",
            "R-18-212-r18",
            "Sexual violence-117-sexual_violence",
            "Shotacon-160-shotacon",
            "Smut-65-smut",
        )
        private val genreValueRegex = Regex("""^(.+)-\d+-[^-]*$""")
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
            return "$IDENTITY_BASE/manga/${match.groupValues[1]}"
        }

        /** Un URL identità (o di un dominio vecchio) riscritto sul dominio vivo, per la rete. */
        fun liveUrl(url: String): String {
            val trimmed = url.trim()
            val match = hostRegex.find(trimmed) ?: return trimmed
            return LIVE_BASE + trimmed.substring(match.range.last + 1)
        }

        fun searchUrl(query: String, excludedGenres: List<String>): String =
            "$LIVE_BASE/search".toHttpUrl()
                .newBuilder()
                .addQueryParameter("q", query)
                .apply { excludedGenres.forEach { addQueryParameter("exclude_genre[]", it) } }
                .build()
                .toString()

        /**
         * I `data-value` dei generi per adulti elencati nel selettore generi della pagina di
         * ricerca (`.checkbox-genre`), secondo `isAdultGenre`. Vuoto se la pagina non li elenca.
         */
        fun adultGenreValues(html: String): List<String> =
            Jsoup.parse(html).select(".checkbox-genre[data-value]")
                .map { it.attr("data-value").trim() }
                .filter { value ->
                    val name = genreValueRegex.find(value)?.groupValues?.get(1) ?: return@filter false
                    isAdultGenre(name)
                }
                .distinct()

        /** Estrae serie + id capitolo da un URL sintetico `.../manga/<slug>/chapter-<n>`. */
        fun parseChapterRef(url: String): ChapterRef? {
            val match = chapterRefRegex.find(url.trim()) ?: return null
            return ChapterRef(
                mangaUrl = "$IDENTITY_BASE/manga/${match.groupValues[1]}",
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
            val anchor = Jsoup.parse(mangaHtml, LIVE_BASE).getElementById(chapterId) ?: return null
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
                description = parseDescription(document, "p.summary-content", ".summary-content", ".manga-desc"),
                status = mangaStatusFromText(statusTextNearLabel(document, "Status", "Stato")),
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
