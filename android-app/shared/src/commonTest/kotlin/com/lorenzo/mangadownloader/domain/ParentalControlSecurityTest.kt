package com.lorenzo.mangadownloader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ParentalControlSecurityTest {

    @Test
    fun pinHash_matchesHashesStoredByPreviousVersions() {
        assertEquals("h84li5sGhLK9ETmPhbacyiLZZIo4gxCtE6UGzYQ4w6g=", hashParentalPin("1234", "c2FsdA=="))
        assertEquals(
            "uNZ5BHkfBhBVADesw3pe661lsVoT6MT/eZ8Rry+Birc=",
            hashParentalPin("000000", "AAAAAAAAAAAAAAAAAAAAAA=="),
        )
        assertEquals("Ztu/zD0mhhDaDE4swXOzxWspCoRtUpDT774yC8m2rrw=", hashParentalPin("987654", "ünï"))
    }

    @Test
    fun pinSalt_isRandom16BytesInBase64() {
        val first = generateParentalPinSalt()
        val second = generateParentalPinSalt()
        assertEquals(24, first.length)
        assertTrue(first.endsWith("=="))
        assertNotEquals(first, second)
    }

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
