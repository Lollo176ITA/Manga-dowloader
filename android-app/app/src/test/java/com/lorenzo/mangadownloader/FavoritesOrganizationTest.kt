package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logica pura di ordinamento/filtro/categorie dei preferiti. JVM, niente Android. */
class FavoritesOrganizationTest {

    private fun fav(title: String, n: Int, addedAt: Long = 0L) =
        FavoriteManga("mangapill", title, "https://mangapill.com/manga/$n", null, addedAt)

    private fun key(f: FavoriteManga) = MangaSourceCatalog.identityKey(f.sourceId, f.mangaUrl)

    @Test
    fun sortByDateAdded_descWithLegacyZerosLast() {
        val r = fav("R", 1, addedAt = 0L)
        val s = fav("S", 2, addedAt = 0L)
        val p = fav("P", 3, addedAt = 300L)
        val q = fav("Q", 4, addedAt = 100L)
        val sorted = sortFavorites(listOf(r, s, p, q), FavoriteSort.DATE_ADDED, emptyMap(), emptyMap())
        assertEquals(listOf("P", "Q", "R", "S"), sorted.map { it.title })
    }

    @Test
    fun sortByTitle_caseInsensitive() {
        val sorted = sortFavorites(
            listOf(fav("banana", 1), fav("Apple", 2), fav("cherry", 3)),
            FavoriteSort.TITLE_ASC,
            emptyMap(),
            emptyMap(),
        )
        assertEquals(listOf("Apple", "banana", "cherry"), sorted.map { it.title })
    }

    @Test
    fun sortByPublicationStatus_ongoingUnknownCompletedDropped() {
        val a = fav("A", 1)
        val b = fav("B", 2)
        val c = fav("C", 3)
        val d = fav("D", 4)
        val statusByKey = mapOf(
            key(a) to MangaPublicationStatus.ONGOING,
            key(b) to MangaPublicationStatus.COMPLETED,
            key(d) to MangaPublicationStatus.DROPPED,
            // c: assente → UNKNOWN
        )
        val sorted = sortFavorites(listOf(b, d, c, a), FavoriteSort.PUBLICATION_STATUS, statusByKey, emptyMap())
        assertEquals(listOf("A", "C", "B", "D"), sorted.map { it.title })
    }

    @Test
    fun sortByLastUpdate_higherChapterFirstMissingLast() {
        val x = fav("X", 1)
        val y = fav("Y", 2)
        val z = fav("Z", 3)
        val seen = mapOf(
            key(x) to FavoriteSeenState("5"),
            key(y) to FavoriteSeenState("12"),
            // z: nessuno → in fondo
        )
        val sorted = sortFavorites(listOf(x, y, z), FavoriteSort.LAST_UPDATE, emptyMap(), seen)
        assertEquals(listOf("Y", "X", "Z"), sorted.map { it.title })
    }

    @Test
    fun filterByText_isCaseInsensitive() {
        val list = listOf(fav("Berserk", 1), fav("Naruto", 2))
        assertEquals(listOf("Berserk"), filterFavorites(list, "ber", null, emptyMap()).map { it.title })
    }

    @Test
    fun filterByCategory_nullIsAll_andUncategorized() {
        val a = fav("A", 1)
        val b = fav("B", 2)
        val list = listOf(a, b)
        val assignments = mapOf(key(a) to "cat_reading")

        assertEquals(2, filterFavorites(list, "", null, assignments).size)
        assertEquals(listOf("A"), filterFavorites(list, "", "cat_reading", assignments).map { it.title })
        assertEquals(listOf("B"), filterFavorites(list, "", UNCATEGORIZED_CATEGORY_ID, assignments).map { it.title })
    }

    @Test
    fun categoryCounts_includesAllAndUncategorizedBuckets() {
        val a = fav("A", 1)
        val b = fav("B", 2)
        val c = fav("C", 3)
        val assignments = mapOf(key(a) to "cat_reading", key(b) to "cat_reading")
        val counts = categoryCounts(listOf(a, b, c), assignments)
        assertEquals(3, counts[null])
        assertEquals(2, counts["cat_reading"])
        assertEquals(1, counts[UNCATEGORIZED_CATEGORY_ID])
    }

    @Test
    fun addCategory_appendsUniqueAndRejectsDuplicateName() {
        val base = DefaultFavoriteCategories.items
        assertEquals(base, addCategory(base, "Sto leggendo")) // nome duplicato → invariato
        val added = addCategory(base, "Manhwa")
        assertEquals(base.size + 1, added.size)
        assertEquals("Manhwa", added.last().name)
        assertEquals(3, added.last().order)
    }

    @Test
    fun addCategory_resolvesIdCollisionWithSuffix() {
        val one = addCategory(emptyList(), "A!")
        val two = addCategory(one, "A?")
        assertEquals(2, two.size)
        assertEquals(setOf("cat_a", "cat_a_1"), two.map { it.id }.toSet())
    }

    @Test
    fun renameCategory_changesOnlyTarget() {
        val renamed = renameCategory(DefaultFavoriteCategories.items, DefaultFavoriteCategories.ID_READING, "Letture")
        assertEquals("Letture", renamed.first { it.id == DefaultFavoriteCategories.ID_READING }.name)
        assertEquals("Da leggere", renamed.first { it.id == DefaultFavoriteCategories.ID_TO_READ }.name)
    }

    @Test
    fun removeCategory_dropsTarget() {
        val removed = removeCategory(DefaultFavoriteCategories.items, DefaultFavoriteCategories.ID_TO_READ)
        assertEquals(2, removed.size)
        assertFalse(removed.any { it.id == DefaultFavoriteCategories.ID_TO_READ })
    }

    @Test
    fun defaultCategories_haveStableIds() {
        assertEquals(
            listOf("cat_reading", "cat_toread", "cat_completed"),
            DefaultFavoriteCategories.items.map { it.id },
        )
        assertTrue(DefaultFavoriteCategories.items.all { it.name.isNotBlank() })
    }
}
