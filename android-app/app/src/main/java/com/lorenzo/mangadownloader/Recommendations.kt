package com.lorenzo.mangadownloader

/**
 * Core puro del blocco Home "Consigliati per te" (niente Android/rete): scelta dei titoli-seme
 * dai dati dell'utente e aggregazione delle raccomandazioni AniList. Il flusso completo vive in
 * [MangaViewModel.loadRecommendations]: semi → id AniList (ricerca) → [AniListRecommendation] →
 * [aggregateRecommendations].
 */

/** Normalizzazione dei titoli per il confronto "già ce l'ho" (case/spazi-insensibile). */
fun normalizedRecommendationTitle(title: String): String = title.trim().lowercase()

/**
 * Titoli-seme per le raccomandazioni: prima i preferiti (i più recenti in testa), poi le serie
 * lette dalla memoria di lettura (le più recenti in testa). Dedup case-insensitive, max [limit]
 * (ogni seme costa una ricerca AniList).
 */
fun selectRecommendationSeeds(
    favorites: List<FavoriteManga>,
    memory: Map<String, ReadChapterMemory>,
    limit: Int = 6,
): List<String> {
    val seeds = mutableListOf<String>()
    val seen = mutableSetOf<String>()

    fun tryAdd(title: String) {
        val clean = title.trim()
        if (clean.isBlank()) return
        if (seen.add(normalizedRecommendationTitle(clean)) && seeds.size < limit) {
            seeds += clean
        }
    }

    favorites.sortedByDescending { it.addedAt }.forEach { tryAdd(it.title) }
    memory.values
        .filter { it.lastReadAtMillis > 0L }
        .sortedByDescending { it.lastReadAtMillis }
        .forEach { tryAdd(it.seriesTitle) }
    return seeds
}

/**
 * Aggrega le raccomandazioni grezze in una classifica: un titolo consigliato da più semi vale
 * più di uno consigliato una sola volta (a parità, decide la somma dei rating della community).
 * Esclude i semi stessi ([excludeIds]) e i titoli che l'utente ha già ([excludeTitles],
 * normalizzati con [normalizedRecommendationTitle]).
 */
fun aggregateRecommendations(
    recommendations: List<AniListRecommendation>,
    excludeIds: Set<Int> = emptySet(),
    excludeTitles: Set<String> = emptySet(),
    limit: Int = 12,
): List<AniListManga> {
    return recommendations
        .asSequence()
        .filter { it.manga.id !in excludeIds }
        .filter { rec ->
            listOfNotNull(rec.manga.titleEnglish, rec.manga.titleRomaji)
                .none { normalizedRecommendationTitle(it) in excludeTitles }
        }
        .groupBy { it.manga.id }
        .values
        .sortedWith(
            compareByDescending<List<AniListRecommendation>> { group -> group.size }
                .thenByDescending { group -> group.sumOf { it.rating.coerceAtLeast(0) } }
                .thenBy { group -> group.first().manga.displayTitle().lowercase() },
        )
        .take(limit.coerceAtLeast(0))
        .map { group -> group.first().manga }
}
