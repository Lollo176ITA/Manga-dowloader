package com.lorenzo.mangadownloader.data.store

import android.content.SharedPreferences

/**
 * Registro delle serie per adulti: le chiavi con cui ogni serie compare nelle schermate (vedi
 * `adultRegistryKeys`). Si scrive quando una serie segnalata viene messa tra i preferiti o
 * scaricata, e dal recupero AniList su preferiti e download già presenti. Non si toglie mai
 * niente: se il filtro è spento il registro non nasconde nulla.
 */
class AdultSeriesStore(private val prefs: SharedPreferences) {

    fun read(): Set<String> = prefs.readJson<Set<String>>(KEY_SERIES, emptySet())

    /** Aggiunge [keys] e restituisce il registro aggiornato. */
    fun add(keys: Collection<String>): Set<String> {
        val current = read()
        val updated = current + keys.filter(String::isNotBlank)
        if (updated.size != current.size) {
            prefs.writeJson(KEY_SERIES, updated)
        }
        return updated
    }

    /** Media AniList già controllati dal recupero: ognuno si chiede una volta sola. */
    fun readCheckedAniListIds(): Set<Int> = prefs.readJson<Set<Int>>(KEY_CHECKED_ANILIST_IDS, emptySet())

    fun addCheckedAniListIds(ids: Collection<Int>) {
        if (ids.isEmpty()) return
        prefs.writeJson(KEY_CHECKED_ANILIST_IDS, readCheckedAniListIds() + ids)
    }

    private companion object {
        const val KEY_SERIES = "adult_series_keys_json"
        const val KEY_CHECKED_ANILIST_IDS = "adult_series_checked_anilist_ids_json"
    }
}
