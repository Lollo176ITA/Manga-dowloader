package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Regola pura di inserimento delle ricerche recenti (estratta da `MangaViewModel`):
 * dedup case-insensitive, le più recenti in testa, cap a MAX, blank ignorato.
 */
class RecentSearchesStoreTest {

    @Test
    fun withRecorded_putsNewQueryFirst() {
        assertEquals(
            listOf("naruto", "berserk"),
            RecentSearchesStore.withRecorded(listOf("berserk"), "naruto"),
        )
    }

    @Test
    fun withRecorded_dedupsCaseInsensitiveAndPromotes() {
        assertEquals(
            listOf("Berserk", "naruto"),
            RecentSearchesStore.withRecorded(listOf("naruto", "berserk"), "Berserk"),
        )
    }

    @Test
    fun withRecorded_capsAtMax() {
        val current = (1..RecentSearchesStore.MAX_RECENT_SEARCHES).map { "q$it" }

        val result = RecentSearchesStore.withRecorded(current, "nuovo")

        assertEquals(RecentSearchesStore.MAX_RECENT_SEARCHES, result.size)
        assertEquals("nuovo", result.first())
    }

    @Test
    fun withRecorded_blankQueryLeavesListUnchanged() {
        val current = listOf("berserk")

        assertSame(current, RecentSearchesStore.withRecorded(current, "   "))
    }
}
