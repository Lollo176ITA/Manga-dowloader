package com.lorenzo.mangadownloader

import android.content.SharedPreferences

/**
 * Persistenza della memoria di lettura ([ReadChapterMemory]) su [SharedPreferences], JSON
 * tipizzato come gli altri store. La forma su disco è la stessa del backup
 * ([ReadingMemoryBackupEntry]): un solo DTO e un solo decoder, così store e restore non
 * possono divergere. La mappa è piccola (un record per capitolo con progresso) e sopravvive
 * all'eliminazione dei file scaricati: è la fonte di verità di statistiche e cronologia.
 */
class ReadingMemoryStore(private val prefs: SharedPreferences) {

    fun read(): Map<String, ReadChapterMemory> {
        return prefs.readJson<Map<String, ReadingMemoryBackupEntry>>(KEY_READING_MEMORY_JSON, emptyMap())
            .mapNotNull { (relativePath, entry) ->
                if (relativePath.isBlank()) return@mapNotNull null
                relativePath to entry.toReadChapterMemory(relativePath)
            }
            .toMap()
    }

    fun persist(memory: Map<String, ReadChapterMemory>) {
        prefs.writeJson(KEY_READING_MEMORY_JSON, memory.mapValues { (_, record) -> record.toBackupEntry() })
    }

    private companion object {
        const val KEY_READING_MEMORY_JSON = "reading_memory_json"
    }
}
