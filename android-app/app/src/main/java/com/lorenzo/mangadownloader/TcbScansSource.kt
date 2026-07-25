package com.lorenzo.mangadownloader

import android.content.Context
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Fonte per **TCB Scans** (`tcbonepiecechapters.com`), sito HTML server-rendered da
 * parsare con jsoup.
 *
 * Il dominio suggerisce "solo One Piece", ma il sito pubblica un intero catalogo
 * (~19 serie: One Piece, Jujutsu Kaisen, Chainsaw Man, Attack on Titan, Bleach, My
 * Hero Academia, Hunter X Hunter, Black Clover, One-Punch Man, Demon Slayer, Spy X
 * Family, Haikyuu, ecc.), elencato per intero in `/projects`.
 *
 * - ricerca: il sito **non ha una funzione di ricerca**. `searchManga` scarica
 *   sempre `/projects` (tutte le serie in un'unica pagina, senza paginazione) e
 *   filtra il titolo lato client, come fa `HastaTeamSource` per le query corte.
 * - serie:   `/mangas/<id>/<slug>` → `<h1>`, la copertina come unica `<img>` che non sia
 *   il logo del sito, e **tutti** i capitoli in un'unica pagina HTML (nessuna
 *   paginazione/infinite scroll) come `<a href="/chapters/<chapterId>/<slug>">`.
 * - reader:  `/chapters/<chapterId>/<slug>` → le pagine sono semplici
 *   `<img class="fixed-ratio-content" src="...">` già nell'ordine di lettura, ospitate
 *   sullo stesso CDN, senza lazy-load JS, token o controlli di referrer/hotlink.
 *
 * Particolarità del sito: l'URL reale di un capitolo (`/chapters/<chapterId>/<slug>`)
 * **non contiene l'id o lo slug della serie** (a differenza di DemonicScans/Asura, dove
 * lo slug del capitolo combacia con quello della serie), quindi non è derivabile a
 * ritroso con una semplice regex come richiede `canonicalMangaUrl`/`buildDownloadPlan`.
 * Soluzione (stesso principio delle "synthetic URL" di VyManga, qui senza bisogno di
 * ri-risolvere alcun token): [ChapterEntry.url] usa un URL **sintetico e stabile**
 * `https://tcbonepiecechapters.com/mangas/<seriesId>/<seriesSlug>/chapters/<chapterId>/<chapterSlug>`,
 * che non esiste sul sito ma incorpora l'identità della serie. `canonicalSeriesUrl`
 * (puro, senza rete) ne legge il prefisso `/mangas/<id>/<slug>`; [fetchPageImageUrls]
 * ricostruisce da esso il vero URL del reader per scaricare le pagine.
 */
class TcbScansSource(
    context: Context,
    networkClient: MangaNetworkClient,
    libraryRepository: LibraryRepository = LibraryRepository(context),
) : BaseMangaSource(context, networkClient, libraryRepository) {
    override val descriptor = MangaSourceDescriptor(
        id = MangaSourceIds.TCB_SCANS,
        displayName = "TCB Scans",
        shortName = "TC",
        language = MangaSourceLanguage.ENG,
    )

    override val invalidChapterUrlMessage: String =
        "URL manga o capitolo TCB Scans non valido"

    override fun canHandleUrl(url: String): Boolean = handlesUrl(url)

    override fun searchManga(query: String): List<MangaSearchResult> {
        val all = parseCatalog(fetchString(PROJECTS_URL), PROJECTS_URL)
        return all.filterByTitle(query).sortedAlphabetically()
    }

    override fun fetchMangaDetails(mangaUrl: String): MangaDetails {
        val canonical = canonicalMangaUrl(mangaUrl)
            ?: throw IllegalArgumentException("URL manga TCB Scans non valido")
        return parseMangaDetails(fetchString(canonical), canonical)
    }

    override fun fetchPageImageUrls(chapterUrl: String): List<String> {
        val ref = parseChapterRef(chapterUrl)
            ?: throw IllegalArgumentException(invalidChapterUrlMessage)
        return parsePageImageUrls(fetchString(realChapterUrl(ref.chapterId, ref.chapterSlug)))
    }

    override fun canonicalMangaUrl(url: String): String? = canonicalSeriesUrl(url)

    companion object {
        private const val BASE_URL = "https://tcbonepiecechapters.com"
        private const val PROJECTS_URL = "$BASE_URL/projects"

        private val seriesRegex =
            Regex("""^https?://tcbonepiecechapters\.com/mangas/(\d+)/([^/?#]+)""", RegexOption.IGNORE_CASE)
        private val syntheticChapterRegex =
            Regex(
                """^https?://tcbonepiecechapters\.com/mangas/(\d+)/([^/?#]+)/chapters/(\d+)/([^/?#]+)""",
                RegexOption.IGNORE_CASE,
            )
        private val realChapterRegex =
            Regex("""^https?://tcbonepiecechapters\.com/chapters/(\d+)/([^/?#]+)""", RegexOption.IGNORE_CASE)
        private val chapterNumberRegex = Regex("""Chapter\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

        /** Riferimento a un capitolo: id+slug della serie (dal synthetic URL) + id+slug del capitolo. */
        data class ChapterRef(
            val seriesId: String,
            val seriesSlug: String,
            val chapterId: String,
            val chapterSlug: String,
        )

        fun handlesUrl(url: String): Boolean = canonicalSeriesUrl(url) != null

        fun canonicalSeriesUrl(url: String): String? {
            val match = seriesRegex.find(url.trim()) ?: return null
            return "$BASE_URL/mangas/${match.groupValues[1]}/${match.groupValues[2]}"
        }

        /** Estrae id/slug di serie e capitolo da un [ChapterEntry.url] sintetico. */
        fun parseChapterRef(url: String): ChapterRef? {
            val match = syntheticChapterRegex.find(url.trim()) ?: return null
            return ChapterRef(
                seriesId = match.groupValues[1],
                seriesSlug = match.groupValues[2],
                chapterId = match.groupValues[3],
                chapterSlug = match.groupValues[4],
            )
        }

        private fun syntheticChapterUrl(
            seriesId: String,
            seriesSlug: String,
            chapterId: String,
            chapterSlug: String,
        ): String = "$BASE_URL/mangas/$seriesId/$seriesSlug/chapters/$chapterId/$chapterSlug"

        private fun realChapterUrl(chapterId: String, chapterSlug: String): String =
            "$BASE_URL/chapters/$chapterId/$chapterSlug"

        // --- Parsing puro, testabile senza rete ---

        /**
         * Catalogo completo da `/projects`. Ogni serie compare con due `<a>` allo stesso href:
         * la thumbnail (testo vuoto, contiene l'`<img>`) e il titolo (testo, nessuna immagine).
         * Si uniscono per URL canonico invece di agganciarsi alle classi Tailwind del layout
         * (`font-bold`, `items-center`), puramente estetiche e a rischio a ogni restyle.
         */
        fun parseCatalog(html: String, baseUrl: String): List<MangaSearchResult> {
            val document = Jsoup.parse(html, baseUrl)
            val titles = linkedMapOf<String, String>()
            val covers = mutableMapOf<String, String>()
            for (anchor in document.select("a[href]")) {
                val mangaUrl = canonicalSeriesUrl(anchor.absUrl("href")) ?: continue
                anchor.text().trim().takeIf(String::isNotBlank)
                    ?.let { titles.putIfAbsent(mangaUrl, it) }
                anchor.selectFirst("img")
                    ?.let { firstNonBlankTrimmed(it.absUrl("src"), it.attr("src")) }
                    ?.takeUnless(::isSiteChromeImage)
                    ?.let { covers.putIfAbsent(mangaUrl, it) }
            }
            return titles.map { (mangaUrl, title) ->
                MangaSearchResult(
                    sourceId = MangaSourceIds.TCB_SCANS,
                    title = title,
                    mangaUrl = mangaUrl,
                    coverUrl = covers[mangaUrl],
                )
            }
        }

        fun parseMangaDetails(html: String, mangaUrl: String): MangaDetails {
            val canonical = canonicalSeriesUrl(mangaUrl)
                ?: throw IllegalArgumentException("URL manga TCB Scans non valido")
            val match = seriesRegex.find(canonical)
                ?: throw IllegalArgumentException("URL manga TCB Scans non valido")
            val seriesId = match.groupValues[1]
            val seriesSlug = match.groupValues[2]
            val document: Document = Jsoup.parse(html, canonical)
            val title = firstNonBlankTrimmed(document.selectFirst("h1")?.text()) ?: "manga"
            // Le pagine serie hanno due sole immagini: il logo del sito e la copertina.
            val cover = document.select("img")
                .asSequence()
                .mapNotNull { firstNonBlankTrimmed(it.absUrl("src"), it.attr("src")) }
                .firstOrNull { !isSiteChromeImage(it) }
            val chapters = parseChapters(document, seriesId, seriesSlug)
            return MangaDetails(
                sourceId = MangaSourceIds.TCB_SCANS,
                title = title,
                coverUrl = cover,
                mangaUrl = canonical,
                chapters = chapters,
            )
        }

        fun parsePageImageUrls(html: String): List<String> {
            val document = Jsoup.parse(html, BASE_URL)
            val urls = document.select("img.fixed-ratio-content")
                .mapNotNull { img -> firstNonBlankTrimmed(img.absUrl("src"), img.attr("src")) }
            if (urls.isEmpty()) {
                throw IllegalStateException("Nessuna immagine trovata per il capitolo")
            }
            return urls
        }

        private fun parseChapters(
            document: Document,
            seriesId: String,
            seriesSlug: String,
        ): List<ChapterEntry> {
            val entries = linkedMapOf<String, ChapterEntry>()
            for (anchor in document.select("a[href]")) {
                val chapterMatch = realChapterRegex.find(anchor.absUrl("href")) ?: continue
                val chapterId = chapterMatch.groupValues[1]
                val chapterSlug = chapterMatch.groupValues[2]
                val labelText = firstNonBlankTrimmed(anchor.selectFirst("div")?.text(), anchor.text()).orEmpty()
                val numberText = chapterNumberRegex.find(labelText)?.groupValues?.getOrNull(1) ?: continue
                val numberValue = DownloadStorage.parseChapterValueOrNull(numberText) ?: continue
                val chapterUrl = syntheticChapterUrl(seriesId, seriesSlug, chapterId, chapterSlug)
                entries.putIfAbsent(
                    chapterUrl,
                    ChapterEntry(
                        numberText = numberText,
                        numberValue = numberValue,
                        url = chapterUrl,
                        slug = chapterSlug,
                    ),
                )
            }
            if (entries.isEmpty()) {
                throw IllegalStateException("Nessun capitolo trovato sulla pagina manga")
            }
            return entries.values.sortedBy { it.numberValue }
        }

        /** Loghi e sfondi del sito (`/files/...`), da non scambiare per la copertina di una serie. */
        private fun isSiteChromeImage(url: String): Boolean {
            val lowered = url.lowercase()
            return lowered.startsWith("/files/") || "tcbonepiecechapters.com/files/" in lowered
        }

        fun List<MangaSearchResult>.filterByTitle(query: String): List<MangaSearchResult> {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                return this
            }
            return filter { result -> result.title.contains(trimmed, ignoreCase = true) }
        }

        fun List<MangaSearchResult>.sortedAlphabetically(): List<MangaSearchResult> {
            return sortedBy { result -> result.title.lowercase(Locale.ROOT) }
        }
    }
}
