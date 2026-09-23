package com.lorenzo.mangadownloader.domain

import com.lorenzo.mangadownloader.data.anilist.AniListManga
import com.lorenzo.mangadownloader.data.model.MangaSearchResult
import com.lorenzo.mangadownloader.domain.series.GroupedSearchResult

/**
 * Filtro dei manga per adulti. Le fonti non dicono se un titolo è per adulti (nei risultati di
 * ricerca c'è solo titolo e copertina), quindi il segnale principale viene da AniList, che la
 * ricerca interroga già per raggruppare i risultati: il flag `isAdult` (hentai) e i generi
 * Hentai/Ecchi, che per un filtro pensato per i minori vanno esclusi entrambi. Come rete di
 * sicurezza per i titoli che AniList non conosce, poche parole inequivocabili nel titolo.
 *
 * È un filtro, non una garanzia: un titolo sconosciuto ad AniList e con un nome innocuo passa.
 * Tutto puro.
 */
private val ADULT_GENRES = setOf("hentai", "ecchi")

private val ADULT_TITLE_MARKERS = Regex(
    """(?i)(\bhentai\b|\br-?18\b|(^|[\s\[(])18\+|\bsmut\b|\bporn)""",
)

fun AniListManga.isAdultContent(): Boolean =
    isAdult || genres.any { it.trim().lowercase() in ADULT_GENRES }

fun looksLikeAdultTitle(title: String): Boolean = ADULT_TITLE_MARKERS.containsMatchIn(title)

fun List<AniListManga>.withoutAdultContent(): List<AniListManga> = filterNot(AniListManga::isAdultContent)

/** Risultati della ricerca aggregata, piatti e raggruppati, dopo il filtro. */
data class FilteredSearchResults(
    val results: List<MangaSearchResult>,
    val groups: List<GroupedSearchResult>,
)

/**
 * Toglie dalla ricerca i gruppi agganciati a un media AniList per adulti e i risultati con un
 * titolo esplicito. I risultati piatti seguono i gruppi: un risultato finito in un gruppo
 * scartato sparisce anche dall'elenco piatto.
 */
fun filterAdultSearchResults(
    results: List<MangaSearchResult>,
    groups: List<GroupedSearchResult>,
    aniListCandidates: List<AniListManga>,
): FilteredSearchResults {
    val adultIds = aniListCandidates.filter(AniListManga::isAdultContent).mapTo(mutableSetOf()) { it.id }
    val (kept, dropped) = groups.partition { group ->
        (group.aniListId == null || group.aniListId !in adultIds) && !looksLikeAdultTitle(group.title)
    }
    val droppedKeys = dropped.flatMapTo(mutableSetOf()) { group ->
        group.results.map { it.sourceId to it.mangaUrl }
    }
    val keptGroups = kept.mapNotNull { group ->
        val visible = group.results.filterNot { looksLikeAdultTitle(it.title) }
        if (visible.isEmpty()) null else group.copy(results = visible)
    }
    return FilteredSearchResults(
        results = results.filter { (it.sourceId to it.mangaUrl) !in droppedKeys && !looksLikeAdultTitle(it.title) },
        groups = keptGroups,
    )
}
