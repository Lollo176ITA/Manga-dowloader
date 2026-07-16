package com.lorenzo.mangadownloader

import android.content.SharedPreferences

/**
 * Persistenza del diario di lettura ([ReadingDayStats] per giorno) su [SharedPreferences],
 * JSON tipizzato come gli altri store. La forma su disco è la stessa del backup
 * ([ReadingDiaryBackupEntry]): un solo DTO, un solo decoder.
 */
class ReadingDiaryStore(private val prefs: SharedPreferences) {

    fun read(): Map<String, ReadingDayStats> {
        return prefs.readJson<Map<String, ReadingDiaryBackupEntry>>(KEY_READING_DIARY_JSON, emptyMap())
            .mapNotNull { (dayKey, entry) ->
                if (diaryDayOf(dayKey) == null) return@mapNotNull null
                dayKey to entry.toReadingDayStats()
            }
            .toMap()
    }

    fun persist(diary: Map<String, ReadingDayStats>) {
        prefs.writeJson(KEY_READING_DIARY_JSON, diary.mapValues { (_, stats) -> stats.toBackupEntry() })
    }

    private companion object {
        const val KEY_READING_DIARY_JSON = "reading_diary_json"
    }
}
