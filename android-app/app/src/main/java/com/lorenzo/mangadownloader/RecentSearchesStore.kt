package com.lorenzo.mangadownloader

import android.content.SharedPreferences

/**
 * Persistenza delle ricerche recenti su [SharedPreferences] e regola pura di inserimento
 * (dedup case-insensitive, le più recenti in testa, cap a [MAX_RECENT_SEARCHES]).
 * Estratta da `MangaViewModel`.
 */
class RecentSearchesStore(private val prefs: SharedPreferences) {

    fun read(): List<String> {
        return prefs.readJson<List<String>>(KEY_RECENT_SEARCHES, emptyList())
            .mapNotNull {
                it.trim().takeIf(String::isNotBlank)
            }
    }

    fun persist(searches: List<String>) {
        prefs.writeJson(KEY_RECENT_SEARCHES, searches)
    }

    companion object {
        const val MAX_RECENT_SEARCHES = 8
        private const val KEY_RECENT_SEARCHES = "recent_searches_json"

        /** Lista aggiornata dopo aver registrato [query] (pura, senza I/O). Vuota/blank → invariata. */
        fun withRecorded(current: List<String>, query: String): List<String> {
            val trimmed = query.trim()
            if (trimmed.isBlank()) {
                return current
            }
            return (listOf(trimmed) + current.filterNot { it.equals(trimmed, ignoreCase = true) })
                .take(MAX_RECENT_SEARCHES)
        }
    }
}
