package com.lorenzo.mangadownloader.domain.series

import com.lorenzo.mangadownloader.app.FavoriteManga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteShelvesTest {

    private fun favorite(title: String, n: Int, seriesKey: String = "") =
        FavoriteManga("mangapill", title, "https://mangapill.com/manga/$n", null, seriesKey = seriesKey)

    private val base = FavoriteShelves()
        .withNewShelf("Da rileggere", "a")!!
        .withNewShelf("Vacanze", "b")!!

    @Test
    fun newShelf_rejectsBlankAndDuplicateNames_ignoringCase() {
        assertNull(base.withNewShelf("   ", "c"))
        assertNull(base.withNewShelf("da RILEGGERE", "c"))
        assertEquals("Estate 2026", base.withNewShelf("  Estate   2026 ", "c")!!.shelf("c")!!.name)
    }

    @Test
    fun rename_allowsSameShelfButNotAnotherShelfsName() {
        assertEquals("da rileggere", base.withRenamedShelf("a", "da rileggere")!!.shelf("a")!!.name)
        assertNull(base.withRenamedShelf("a", "vacanze"))
        assertNull(base.withRenamedShelf("missing", "Altro"))
    }

    @Test
    fun assignments_followShelfOrder_andIgnoreUnknownShelves() {
        val fav = favorite("One Piece", 1)
        val shelves = base.withShelvesFor(fav, setOf("b", "a", "ghost"))
        assertEquals(listOf("a", "b"), shelves.shelfIdsOf(fav).toList())
        assertTrue(shelves.withShelvesFor(fav, emptySet()).assignments.isEmpty())
    }

    @Test
    fun assignment_survivesPromotionToAniListKey_throughTitleAlias() {
        val beforePromotion = favorite("One Piece", 1)
        val shelves = base.withShelvesFor(beforePromotion, setOf("a"))
        // Il worker cambia la chiave in `anilist:`; lo store degli scaffali non viene toccato.
        val promoted = beforePromotion.copy(seriesKey = SeriesIdentity.keyForAniList(21))
        assertEquals(setOf("a"), shelves.shelfIdsOf(promoted))

        // Riassegnando, la voce si consolida sulla chiave nuova.
        val reassigned = shelves.withShelvesFor(promoted, setOf("b"))
        assertEquals(setOf("b"), reassigned.shelfIdsOf(promoted))
        assertEquals(setOf(SeriesIdentity.keyForAniList(21)), reassigned.assignments.keys)
    }

    @Test
    fun deletingShelf_dropsItFromEveryFavorite() {
        val one = favorite("One", 1)
        val two = favorite("Two", 2)
        val shelves = base.withShelvesFor(one, setOf("a")).withShelvesFor(two, setOf("a", "b"))
        val after = shelves.withoutShelf("a")
        assertEquals(emptySet<String>(), after.shelfIdsOf(one))
        assertEquals(setOf("b"), after.shelfIdsOf(two))
        assertEquals(1, after.assignments.size)
    }

    @Test
    fun filterAndCounts() {
        val one = favorite("One", 1)
        val two = favorite("Two", 2)
        val shelves = base.withShelvesFor(one, setOf("a")).withShelvesFor(two, setOf("a", "b"))
        assertEquals(listOf(two), filterFavoritesByShelf(listOf(one, two), "b", shelves))
        assertEquals(listOf(one, two), filterFavoritesByShelf(listOf(one, two), null, shelves))
        assertEquals(mapOf("a" to 2, "b" to 1), shelves.countsIn(listOf(one, two)))
    }

    @Test
    fun merge_unifiesShelvesByName_andAvoidsIdClashes() {
        val fav = favorite("One", 1)
        val current = FavoriteShelves().withNewShelf("Vacanze", "a")!!
        val incoming = base.withShelvesFor(fav, setOf("a", "b"))

        val merged = mergeFavoriteShelves(current, incoming)

        // "Vacanze" esiste già (id "a"); "Da rileggere" arriva con id "a" occupato.
        assertEquals(listOf("Vacanze", "Da rileggere"), merged.shelves.map { it.name })
        assertEquals(setOf("a", "a-backup"), merged.shelfIdsOf(fav))
    }
}
