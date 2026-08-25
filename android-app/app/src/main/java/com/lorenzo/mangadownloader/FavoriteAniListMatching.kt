package com.lorenzo.mangadownloader

import android.content.SharedPreferences

/**
 * Aggancio di un preferito ad AniList per **identità**, non per tracking: serve solo a
 * sostituire una chiave `title:` (fragile: cambia col titolo che usa la fonte) con una
 * `anilist:` stabile. È ciò che fa convergere "Attack on Titan" e "Shingeki no Kyojin" in
 * un preferito solo invece di due.
 *
 * Regola volutamente identica a [SeriesGrouping]: il titolo normalizzato del preferito deve
 * combaciare **esattamente** con un titolo del candidato. Niente fuzzy — un accoppiamento
 * sbagliato qui fonderebbe due serie diverse in un preferito solo.
 *
 * A parità di combaciamento vince chi combacia su un **titolo principale**: chi combacia solo
 * per un sinonimo passa dietro a tutti, per quanto AniList lo consideri più rilevante. Senza
 * questa precedenza "Pick Me Up" si aggancia a una raccolta hentai che ha quel titolo fra i
 * suoi sinonimi, invece che al webtoon omonimo (vedi [AniListManga.synonymTitles]).
 */
fun matchAniListCandidate(title: String, candidates: List<AniListManga>): AniListManga? {
    val normalized = SeriesIdentity.normalizeTitle(title)
    if (normalized.isBlank()) return null
    fun matchesOn(titles: (AniListManga) -> List<String>): AniListManga? =
        candidates.firstOrNull { candidate ->
            titles(candidate).any { SeriesIdentity.normalizeTitle(it) == normalized }
        }
    return matchesOn(AniListManga::primaryTitles) ?: matchesOn(AniListManga::synonymTitles)
}

/**
 * Chiavi serie per cui la risoluzione AniList è già stata tentata (con o senza successo).
 * Senza questo elenco un preferito che AniList non conosce verrebbe ricercato a ogni giro
 * del worker, per sempre. Le voci risolte spariscono da sole: la loro chiave diventa
 * `anilist:` e non rientra più tra i candidati.
 */
class AniListResolutionAttemptsStore(private val prefs: SharedPreferences) {

    fun read(): Set<String> = prefs.readJson<List<String>>(KEY_ATTEMPTS, emptyList()).toSet()

    fun write(keys: Set<String>) {
        prefs.writeJson(KEY_ATTEMPTS, keys.toList().takeLast(MAX_ATTEMPTS))
    }

    private companion object {
        const val KEY_ATTEMPTS = "favorite_anilist_resolution_attempts_json"

        // Tetto di guardia: l'elenco cresce solo con i preferiti mai risolti.
        const val MAX_ATTEMPTS = 500
    }
}

/**
 * Preferiti che vale la pena cercare su AniList in questo giro: solo quelli ancora su chiave
 * `title:` e mai tentati. Pura, così la selezione è testabile senza rete.
 */
fun favoritesNeedingAniListResolution(
    favorites: List<FavoriteManga>,
    attempted: Set<String>,
    limit: Int = MAX_ANILIST_RESOLUTIONS_PER_RUN,
): List<FavoriteManga> = favorites
    .filter { favorite ->
        val key = favorite.canonicalKey()
        key.startsWith(SeriesIdentity.TITLE_PREFIX) && key !in attempted
    }
    .take(limit.coerceAtLeast(0))

/**
 * Quante ricerche AniList al massimo per giro di worker: la risoluzione è un di più rispetto
 * al controllo capitoli, non deve allungarlo di molto né pesare sull'API pubblica.
 */
const val MAX_ANILIST_RESOLUTIONS_PER_RUN = 5
