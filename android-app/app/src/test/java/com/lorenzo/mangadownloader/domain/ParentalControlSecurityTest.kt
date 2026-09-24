package com.lorenzo.mangadownloader.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ParentalControlSecurityTest {

    @Test
    fun lockout_startsAfterFreeAttempts_doublesAndCaps() {
        assertEquals(0L, parentalPinLockoutMillis(4))
        assertEquals(30_000L, parentalPinLockoutMillis(5))
        assertEquals(60_000L, parentalPinLockoutMillis(6))
        assertEquals(120_000L, parentalPinLockoutMillis(7))
        assertEquals(15 * 60_000L, parentalPinLockoutMillis(20))
        assertEquals(15 * 60_000L, parentalPinLockoutMillis(Int.MAX_VALUE))
    }

    @Test
    fun lockoutLabel_roundsUp() {
        assertEquals("1 secondo", parentalLockoutLabel(10))
        assertEquals("30 secondi", parentalLockoutLabel(29_001))
        assertEquals("1 minuto", parentalLockoutLabel(60_000))
        assertEquals("2 minuti", parentalLockoutLabel(61_000))
    }
}
