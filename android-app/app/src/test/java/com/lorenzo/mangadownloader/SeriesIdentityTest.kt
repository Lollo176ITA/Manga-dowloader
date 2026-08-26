package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesIdentityTest {

    @Test
    fun `normalizza minuscole accenti e punteggiatura`() {
        assertEquals("l attacco dei giganti", SeriesIdentity.normalizeTitle("L'Attacco dei Giganti!"))
        assertEquals("shingeki no kyojin", SeriesIdentity.normalizeTitle("  Shingeki no Kyojin  "))
        assertEquals("perche no", SeriesIdentity.normalizeTitle("Perché... nò?"))
    }

    @Test
    fun `collassa spazi multipli`() {
        assertEquals("one piece", SeriesIdentity.normalizeTitle("One   Piece"))
    }

    @Test
    fun `chiavi anilist e title`() {
        assertEquals("anilist:30013", SeriesIdentity.keyForAniList(30013))
        assertEquals("title:one piece", SeriesIdentity.keyForTitle("One Piece!"))
        assertNull(SeriesIdentity.keyForTitle("  ...  "))
    }

    @Test
    fun `estrae aniListId dalla chiave`() {
        assertEquals(30013, SeriesIdentity.aniListIdFromKey("anilist:30013"))
        assertNull(SeriesIdentity.aniListIdFromKey("title:one piece"))
        assertNull(SeriesIdentity.aniListIdFromKey("anilist:abc"))
    }
}
