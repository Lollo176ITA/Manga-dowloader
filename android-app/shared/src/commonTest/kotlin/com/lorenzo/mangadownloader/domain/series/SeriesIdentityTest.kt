package com.lorenzo.mangadownloader.domain.series

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeriesIdentityTest {

    @Test
    fun normalizza_minuscole_accenti_e_punteggiatura() {
        assertEquals("l attacco dei giganti", SeriesIdentity.normalizeTitle("L'Attacco dei Giganti!"))
        assertEquals("shingeki no kyojin", SeriesIdentity.normalizeTitle("  Shingeki no Kyojin  "))
        assertEquals("perche no", SeriesIdentity.normalizeTitle("Perché... nò?"))
    }

    /** Output fissato dalla versione con java.text.Normalizer (NFKD): sono chiavi salvate. */
    @Test
    fun normalizzazione_di_compatibilita_invariata() {
        val cases = mapOf(
            "Ｏｎｅ　Ｐｉｅｃｅ" to "one piece",
            "ワンピース" to "ワンヒース",
            "Pokémon²" to "pokemon2",
            "½ Prince" to "1 2 prince",
            "Ŧest" to "ŧest",
            "Ⅻ Kingdom" to "xii kingdom",
            "進撃の巨人" to "進撃の巨人",
            "Ångström" to "angstrom",
        )
        cases.forEach { (raw, expected) -> assertEquals(expected, SeriesIdentity.normalizeTitle(raw)) }
    }

    @Test
    fun collassa_spazi_multipli() {
        assertEquals("one piece", SeriesIdentity.normalizeTitle("One   Piece"))
    }

    @Test
    fun chiavi_anilist_e_title() {
        assertEquals("anilist:30013", SeriesIdentity.keyForAniList(30013))
        assertEquals("title:one piece", SeriesIdentity.keyForTitle("One Piece!"))
        assertNull(SeriesIdentity.keyForTitle("  ...  "))
    }

    @Test
    fun estrae_aniListId_dalla_chiave() {
        assertEquals(30013, SeriesIdentity.aniListIdFromKey("anilist:30013"))
        assertNull(SeriesIdentity.aniListIdFromKey("title:one piece"))
        assertNull(SeriesIdentity.aniListIdFromKey("anilist:abc"))
    }
}
