package com.lorenzo.mangadownloader.data.store

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Errori di PIN consecutivi e fine del blocco. Su disco e non in memoria: chiudere e riaprire
 * l'app non deve azzerare il conto.
 */
class ParentalLockoutStore(private val prefs: SharedPreferences) {

    fun failedAttempts(): Int = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)

    fun lockedUntilMillis(): Long = prefs.getLong(KEY_LOCKED_UNTIL, 0L)

    fun recordFailure(lockedUntilMillis: Long): Int {
        val attempts = failedAttempts() + 1
        prefs.edit {
            putInt(KEY_FAILED_ATTEMPTS, attempts)
            putLong(KEY_LOCKED_UNTIL, lockedUntilMillis)
        }
        return attempts
    }

    fun reset() {
        prefs.edit {
            remove(KEY_FAILED_ATTEMPTS)
            remove(KEY_LOCKED_UNTIL)
        }
    }

    private companion object {
        const val KEY_FAILED_ATTEMPTS = "parental_pin_failed_attempts"
        const val KEY_LOCKED_UNTIL = "parental_pin_locked_until"
    }
}
