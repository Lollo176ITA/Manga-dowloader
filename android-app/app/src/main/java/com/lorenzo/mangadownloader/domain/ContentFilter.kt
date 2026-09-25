package com.lorenzo.mangadownloader.domain

import com.lorenzo.mangadownloader.data.anilist.AniListManga
import com.lorenzo.mangadownloader.data.model.MangaSearchResult
import com.lorenzo.mangadownloader.domain.series.GroupedSearchResult

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
    val adultIds = aniListCandidates.filter(AniListManga::isAdultContent).mapTo(mutableSetOf()) { it.id }
    val (kept, dropped) = groups.partition { group ->
        (group.aniListId == null || group.aniListId !in adultIds) &&
            !looksLikeAdultTitle(group.title) &&
            group.results.none(MangaSearchResult::isAdult)
    }
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
