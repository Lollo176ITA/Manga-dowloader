package com.lorenzo.mangadownloader.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lorenzo.mangadownloader.data.update.AppUpdateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Scaffali dei preferiti: persistenza, filtro e pulizia del filtro quando lo scaffale sparisce. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MangaViewModelFavoriteShelvesTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("manga_downloader_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun createViewModel() = MangaViewModel(application, AppUpdateRepository(application))

    private val favorite = FavoriteManga("mangapill", "One Piece", "https://mangapill.com/manga/2", null)

    @Test
    fun shelvesAndAssignments_persistAcrossRestarts() {
        val vm = createViewModel()
        val id = vm.createFavoriteShelf("Da rileggere")
        assertNotNull(id)
        assertNull(vm.createFavoriteShelf("da rileggere"))
        vm.setShelvesForFavorite(favorite, setOf(id!!))

        val restarted = createViewModel().state.value.favoriteShelves
        assertEquals(listOf("Da rileggere"), restarted.shelves.map { it.name })
        assertEquals(setOf(id), restarted.shelfIdsOf(favorite))
    }

    @Test
    fun deletingTheFilteredShelf_clearsTheFilter() {
        val vm = createViewModel()
        val id = vm.createFavoriteShelf("Vacanze")!!
        vm.setFavoriteFilterShelf(id)
        assertEquals(id, vm.state.value.favoriteFilterShelfId)

        vm.deleteFavoriteShelf(id)

        assertNull(vm.state.value.favoriteFilterShelfId)
        assertEquals(emptyList<Any>(), vm.state.value.favoriteShelves.shelves)
    }

    @Test
    fun rename_rejectsNameOfAnotherShelf() {
        val vm = createViewModel()
        val a = vm.createFavoriteShelf("A")!!
        vm.createFavoriteShelf("B")
        assertEquals(false, vm.renameFavoriteShelf(a, "b"))
        assertEquals(true, vm.renameFavoriteShelf(a, "Alfa"))
        assertEquals("Alfa", vm.state.value.favoriteShelves.shelf(a)!!.name)
    }
}
