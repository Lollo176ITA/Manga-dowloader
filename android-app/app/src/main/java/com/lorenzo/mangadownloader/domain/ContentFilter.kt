package com.lorenzo.mangadownloader.domain

import com.lorenzo.mangadownloader.data.anilist.AniListManga
import com.lorenzo.mangadownloader.data.model.MangaSearchResult
import com.lorenzo.mangadownloader.data.sources.MangaSourceCatalog
import com.lorenzo.mangadownloader.domain.series.GroupedSearchResult
import com.lorenzo.mangadownloader.domain.series.SeriesIdentity

/**
 * Filtro dei manga per adulti. I segnali sono tre, in OR:
 * - la **fonte** stessa ([MangaSearchResult.isAdult]): quasi tutte espongono generi o un flag
 *   già nella risposta di ricerca, e Weeb Central esclude gli adulti lato server. Le fonti
 *   senza alcun segnale economico non si interrogano proprio col filtro attivo
 *   (`MangaSourceCatalog.sourcesWithoutAdultSignal`);
 * - **AniList**, che la ricerca interroga già per raggruppare i risultati: il flag `isAdult`
 *   (hentai) e i generi per adulti;
 * - poche parole inequivocabili nel titolo, come ultima rete.
 *
 * "Per adulti" significa espliciti **ed ecchi**: per un filtro pensato per i minori vanno
 * esclusi entrambi. "Maturo/Mature" (violenza: Berserk, Claymore) invece resta visibile.
 * Tutto puro.
 */
private val ADULT_GENRES = setOf(
    "hentai",
    "ecchi",
    "smut",
    "adult",
    "adulti",
    "adulto",
    "erotico",
    "erotica",
    "lolicon",
    "shotacon",
    "pornographic",
    "r-18",
    "r18",
    "sexual violence",
)

private val ADULT_TITLE_MARKERS = Regex(
    """(?i)(\bhentai\b|\br-?18\b|(^|[\s\[(])18\+|\bsmut\b|\bporn)""",
)

/**
 * Il genere (nome o slug, di qualsiasi fonte o di AniList) indica un titolo per adulti?
 * Tollera maiuscole, spazi e la virgola finale con cui alcuni siti separano i generi.
 */
fun isAdultGenre(genre: String): Boolean =
    genre.trim().trimEnd(',').trim().lowercase() in ADULT_GENRES

fun AniListManga.isAdultContent(): Boolean = isAdult || genres.any(::isAdultGenre)

fun looksLikeAdultTitle(title: String): Boolean = ADULT_TITLE_MARKERS.containsMatchIn(title)

fun List<AniListManga>.withoutAdultContent(): List<AniListManga> = filterNot(AniListManga::isAdultContent)

private fun MangaSearchResult.looksAdult(): Boolean = isAdult || looksLikeAdultTitle(title)

private fun List<AniListManga>.adultIds(): Set<Int> =
    filter(AniListManga::isAdultContent).mapTo(mutableSetOf()) { it.id }

private fun GroupedSearchResult.isAdultGroup(adultAniListIds: Set<Int>): Boolean =
    (aniListId != null && aniListId in adultAniListIds) ||
        looksLikeAdultTitle(title) ||
        results.any(MangaSearchResult::isAdult)

/**
 * Chiavi con cui una serie entra nel registro adulti e con cui la si riconosce nelle schermate:
 * chiave serie, identità per-fonte e chiave-titolo. Il titolo ce l'hanno tutte (cronologia e
 * download compresi); le altre servono quando il titolo cambia da una fonte all'altra. Pura.
 */
fun adultRegistryKeys(
    title: String?,
    sourceId: String? = null,
    mangaUrl: String? = null,
    seriesKey: String? = null,
): Set<String> = buildSet {
    seriesKey?.trim()?.takeIf(String::isNotBlank)?.let(::add)
    MangaSourceCatalog.identityKeyOrNull(sourceId, mangaUrl)?.let(::add)
    title?.let(SeriesIdentity::keyForTitle)?.let(::add)
}

/** La serie descritta da queste coordinate è nel registro [adultKeys]? Pura. */
fun isRegisteredAdult(
    adultKeys: Set<String>,
    title: String?,
    sourceId: String? = null,
    mangaUrl: String? = null,
    seriesKey: String? = null,
): Boolean = adultKeys.isNotEmpty() &&
    adultRegistryKeys(title, sourceId, mangaUrl, seriesKey).any { it in adultKeys }

/**
 * Chiavi di registro di tutte le serie per adulti viste in una ricerca, a filtro acceso o
 * spento: servono a ricordarle se poi finiscono tra i preferiti o vengono scaricate. Pura.
 */
fun adultSearchRegistryKeys(
    results: List<MangaSearchResult>,
    groups: List<GroupedSearchResult>,
    aniListCandidates: List<AniListManga>,
): Set<String> {
    val adultIds = aniListCandidates.adultIds()
    return buildSet {
        groups.filter { it.isAdultGroup(adultIds) }.forEach { group ->
            addAll(adultRegistryKeys(group.title, seriesKey = group.seriesKey))
            group.results.forEach { addAll(adultRegistryKeys(it.title, it.sourceId, it.mangaUrl)) }
        }
        results.filter { it.looksAdult() }.forEach { addAll(adultRegistryKeys(it.title, it.sourceId, it.mangaUrl)) }
    }
}

/** Risultati della ricerca aggregata, piatti e raggruppati, dopo il filtro. */
data class FilteredSearchResults(
    val results: List<MangaSearchResult>,
    val groups: List<GroupedSearchResult>,
)

/**
 * Toglie dalla ricerca i gruppi agganciati a un media AniList per adulti, quelli in cui almeno
 * una fonte segnala la serie come per adulti (è la stessa serie: basta che lo dica un mirror)
 * e i risultati con un titolo esplicito. I risultati piatti seguono i gruppi: un risultato
 * finito in un gruppo scartato sparisce anche dall'elenco piatto.
 */
fun filterAdultSearchResults(
    results: List<MangaSearchResult>,
    groups: List<GroupedSearchResult>,
    aniListCandidates: List<AniListManga>,
): FilteredSearchResults {
    val adultIds = aniListCandidates.adultIds()
    val (dropped, kept) = groups.partition { it.isAdultGroup(adultIds) }
    val droppedKeys = dropped.flatMapTo(mutableSetOf()) { group ->
        group.results.map { it.sourceId to it.mangaUrl }
    }
    val keptGroups = kept.mapNotNull { group ->
        val visible = group.results.filterNot { it.looksAdult() }
        if (visible.isEmpty()) null else group.copy(results = visible)
    }
    return FilteredSearchResults(
        results = results.filter { (it.sourceId to it.mangaUrl) !in droppedKeys && !it.looksAdult() },
        groups = keptGroups,
    )
}
