package com.lorenzo.mangadownloader

import android.content.SharedPreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistenza delle ricerche recenti su [SharedPreferences] e regola pura di inserimento
 * (dedup case-insensitive, le più recenti in testa, cap a [MAX_RECENT_SEARCHES]).
 * Estratta da `MangaViewModel`.
 */
class RecentSearchesStore(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true }

    fun read(): List<String> {
        val raw = prefs.getString(KEY_RECENT_SEARCHES, null).orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }
        return try {
            json.decodeFromString<List<String>>(raw).mapNotNull {
                it.trim().takeIf(String::isNotBlank)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun persist(searches: List<String>) {
        prefs.edit()
            .putString(KEY_RECENT_SEARCHES, json.encodeToString(searches))
            .apply()
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
