package com.lorenzo.mangadownloader

/**
 * Un gruppo di risultati di ricerca che appartengono alla stessa serie (una card in UI).
 * [seriesKey] è la chiave canonica ([SeriesIdentity]); [results] preserva l'ordine di
 * arrivo dall'interleave delle fonti. Il raggruppamento è effimero: il [SeriesLink]
 * persistito nasce solo al tap sulla card.
 */
data class GroupedSearchResult(
    val seriesKey: String,
    val aniListId: Int?,
    val title: String,
    val coverUrl: String?,
    val results: List<MangaSearchResult>,
) {
    val primary: MangaSearchResult get() = results.first()
}

/**
 * Matcher conservativo fonte↔AniList: un risultato entra in un gruppo AniList solo se il suo
 * titolo normalizzato è ESATTAMENTE uno dei titoli/sinonimi del candidato. Niente fuzzy:
 * meglio due card separate che un raggruppamento sbagliato. Con [aniListCandidates] vuoto
 * (AniList giù) degrada al raggruppamento per solo titolo normalizzato.
 */
object SeriesGrouping {

    fun groupResults(
        results: List<MangaSearchResult>,
        aniListCandidates: List<AniListManga>,
    ): List<GroupedSearchResult> {
        // Primo candidato che rivendica un titolo vince (i candidati arrivano già in ordine
        // di rilevanza AniList; un eventuale media "pinnato" dal ponte Scopri è in testa).
        val titleToCandidate = LinkedHashMap<String, AniListManga>()
        aniListCandidates.forEach { candidate ->
            candidate.allTitles().forEach { title ->
                val normalized = SeriesIdentity.normalizeTitle(title)
                if (normalized.isNotBlank()) {
                    titleToCandidate.putIfAbsent(normalized, candidate)
                }
            }
        }

        val groups = LinkedHashMap<String, MutableList<MangaSearchResult>>()
        val candidateByKey = HashMap<String, AniListManga>()
        results.forEach { result ->
            val normalized = SeriesIdentity.normalizeTitle(result.title)
            val candidate = titleToCandidate[normalized]
            val key = when {
                candidate != null -> SeriesIdentity.keyForAniList(candidate.id)
                    .also { candidateByKey[it] = candidate }
                else -> SeriesIdentity.keyForTitle(result.title)
                    ?: MangaSourceCatalog.identityKey(result.sourceId, result.mangaUrl)
            }
            groups.getOrPut(key) { mutableListOf() }.add(result)
        }

        return groups.map { (key, members) ->
            val candidate = candidateByKey[key]
            GroupedSearchResult(
                seriesKey = key,
                aniListId = candidate?.id,
                title = candidate?.displayTitle() ?: members.first().title,
                coverUrl = candidate?.coverUrl ?: members.firstNotNullOfOrNull { it.coverUrl },
                results = members.toList(),
            )
        }
    }
}
