package com.lorenzo.mangadownloader.data.store

import android.content.SharedPreferences
import com.lorenzo.mangadownloader.domain.series.FavoriteShelves

/** Persistenza degli scaffali dei preferiti. Tollerante: JSON illeggibile → nessuno scaffale. */
class FavoriteShelvesStore(private val prefs: SharedPreferences) {

    fun read(): FavoriteShelves = prefs.readJson(KEY_FAVORITE_SHELVES_JSON, FavoriteShelves())

    fun write(shelves: FavoriteShelves) {
        prefs.writeJson(KEY_FAVORITE_SHELVES_JSON, shelves)
    }

    /** Legge, trasforma e riscrive: parte sempre dal disco, non da una copia in memoria. */
    fun update(transform: (FavoriteShelves) -> FavoriteShelves?): FavoriteShelves? {
        val updated = transform(read()) ?: return null
        write(updated)
        return updated
    }

    private companion object {
        const val KEY_FAVORITE_SHELVES_JSON = "favorite_shelves_json"
    }
}
