package com.lorenzo.mangadownloader.data.store

import android.content.ContentResolver
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit

/**
 * Un istante misurato dall'avvio del telefono: [elapsedMillis] non si sposta cambiando l'ora,
 * ma vale solo dentro lo stesso [bootCount].
 */
data class BootInstant(val bootCount: Int, val elapsedMillis: Long)

fun deviceBootInstant(resolver: ContentResolver): BootInstant = BootInstant(
    bootCount = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1),
    elapsedMillis = SystemClock.elapsedRealtime(),
)

/**
 * Errori di PIN consecutivi e blocco in corso. Su disco e non in memoria: chiudere e riaprire
 * l'app non deve azzerare il conto.
 *
 * Il blocco non usa l'ora del telefono, che si sposta a mano: nello stesso avvio conta
 * `elapsedRealtime`; dopo un riavvio non si sa quanto mancava, quindi riparte intero.
 * Riavviare non fa mai guadagnare tempo. Un blocco già scaduto ma mai ricontrollato prima
 * del riavvio riparte anche lui: costa al più un'attesa in più.
 */
class ParentalLockoutStore(
    private val prefs: SharedPreferences,
    private val now: () -> BootInstant,
) {
    init {
        // Le versioni precedenti salvavano la fine del blocco con l'ora del telefono.
        if (prefs.contains(KEY_LEGACY_LOCKED_UNTIL)) {
            val remaining = prefs.getLong(KEY_LEGACY_LOCKED_UNTIL, 0L) - System.currentTimeMillis()
            prefs.edit { remove(KEY_LEGACY_LOCKED_UNTIL) }
            if (remaining > 0) {
                startLockout(remaining.coerceAtMost(LEGACY_MAX_LOCKOUT_MILLIS))
            }
        }
    }

    fun failedAttempts(): Int = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)

    /** Quanto manca allo sblocco, `0` se il PIN si può inserire. */
    fun remainingLockoutMillis(): Long {
        val duration = prefs.getLong(KEY_LOCKOUT_DURATION, 0L)
        if (duration <= 0L) return 0L
        val current = now()
        val startElapsed = prefs.getLong(KEY_LOCKOUT_START_ELAPSED, 0L)
        val sameBoot = prefs.getInt(KEY_LOCKOUT_BOOT_COUNT, -1) == current.bootCount &&
            current.elapsedMillis >= startElapsed
        if (!sameBoot) {
            startLockout(duration)
            return duration
        }
        val remaining = startElapsed + duration - current.elapsedMillis
        if (remaining <= 0L) {
            prefs.edit { clearLockout() }
            return 0L
        }
        return remaining
    }

    fun recordFailure(lockoutMillis: Long): Int {
        val attempts = failedAttempts() + 1
        prefs.edit { putInt(KEY_FAILED_ATTEMPTS, attempts) }
        if (lockoutMillis > 0L) {
            startLockout(lockoutMillis)
        }
        return attempts
    }

    fun reset() {
        prefs.edit {
            remove(KEY_FAILED_ATTEMPTS)
            clearLockout()
        }
    }

    private fun startLockout(durationMillis: Long) {
        val current = now()
        prefs.edit {
            putLong(KEY_LOCKOUT_DURATION, durationMillis)
            putLong(KEY_LOCKOUT_START_ELAPSED, current.elapsedMillis)
            putInt(KEY_LOCKOUT_BOOT_COUNT, current.bootCount)
        }
    }

    private fun SharedPreferences.Editor.clearLockout() {
        remove(KEY_LOCKOUT_DURATION)
        remove(KEY_LOCKOUT_START_ELAPSED)
        remove(KEY_LOCKOUT_BOOT_COUNT)
    }

    private companion object {
        const val KEY_FAILED_ATTEMPTS = "parental_pin_failed_attempts"
        const val KEY_LEGACY_LOCKED_UNTIL = "parental_pin_locked_until"
        const val KEY_LOCKOUT_DURATION = "parental_pin_lockout_duration"
        const val KEY_LOCKOUT_START_ELAPSED = "parental_pin_lockout_start_elapsed"
        const val KEY_LOCKOUT_BOOT_COUNT = "parental_pin_lockout_boot_count"
        const val LEGACY_MAX_LOCKOUT_MILLIS = 15 * 60_000L
    }
}
