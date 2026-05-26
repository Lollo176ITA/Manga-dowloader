package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Persistenza dei preferiti dopo il passaggio alla serializzazione tipizzata (`@Serializable`):
 * round-trip write→read e compatibilità all'indietro col vecchio JSON costruito a mano.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FavoritesPersistenceTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    private fun prefs() =
        application.getSharedPreferences("manga_downloader_prefs", Context.MODE_PRIVATE)

    private fun createViewModel() = MangaViewModel(application, AppUpdateRepository(application))

    @Test
    fun favorites_roundTripThroughTypedSerialization() {
        createViewModel().toggleFavorite(
            FavoriteManga(
                sourceId = MangaSourceIds.MANGAPILL,
                title = "Berserk",
                mangaUrl = "https://mangapill.com/manga/12345/berserk",
                coverUrl = "https://cdn.mangapill.com/cover/berserk.jpeg",
            ),
        )

        // Nuovo ViewModel: rilegge i preferiti dal formato tipizzato appena scritto.
        val favorites = createViewModel().state.value.favorites

        assertEquals(1, favorites.size)
        assertEquals("Berserk", favorites.first().title)
        assertEquals("https://mangapill.com/manga/12345/berserk", favorites.first().mangaUrl)
        assertEquals("https://cdn.mangapill.com/cover/berserk.jpeg", favorites.first().coverUrl)
    }

    @Test
    fun favorites_readLegacyHandBuiltJsonWithoutCover() {
        // Formato storico (coverUrl assente): deve essere ancora leggibile.
        prefs().edit()
            .putString(
                "favorites_json",
                """[{"sourceId":"mangapill","title":"Naruto","mangaUrl":"https://mangapill.com/manga/9/naruto"}]""",
            )
            .commit()

        val favorites = createViewModel().state.value.favorites

        assertEquals(1, favorites.size)
        assertEquals("Naruto", favorites.first().title)
        assertNull(favorites.first().coverUrl)
    }
}
