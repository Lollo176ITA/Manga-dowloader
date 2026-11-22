package com.lorenzo.mangadownloader.data.store

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.lorenzo.mangadownloader.app.FavoriteManga
import com.lorenzo.mangadownloader.data.sources.MangaSourceIds
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FavoritesStoreTest {

    private lateinit var prefs: SharedPreferences

    private val berserk = FavoriteManga(
        sourceId = MangaSourceIds.MANGAPILL,
        title = "Berserk",
        mangaUrl = "https://mangapill.com/manga/1",
        coverUrl = null,
        addedAt = 1L,
        seriesKey = "anilist:30002",
    )
    private val monster = FavoriteManga(
        sourceId = MangaSourceIds.MANGAPILL,
        title = "Monster",
        mangaUrl = "https://mangapill.com/manga/2",
        coverUrl = null,
        addedAt = 2L,
        seriesKey = "anilist:30001",
    )

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        prefs = application.getSharedPreferences("favorites_store_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun update_keepsChangesMadeByAnotherWriterInTheMeantime() {
        // Worker e ViewModel hanno ciascuno il proprio store sulle stesse preferenze.
        val workerStore = FavoritesStore(prefs)
        val viewModelStore = FavoritesStore(prefs)
        workerStore.persist(listOf(berserk))

        // Il worker legge all'inizio del giro; nel frattempo l'utente aggiunge un preferito.
        val workerSnapshot = workerStore.read()
        viewModelStore.update { stored -> listOf(monster) + stored }

        // A fine giro il worker applica solo il suo cambio di mirror, sui preferiti di adesso.
        val switchedUrl = "https://weebcentral.com/series/berserk"
        workerStore.update { stored ->
            stored.map {
                if (it.seriesKey == berserk.seriesKey) {
                    it.copy(sourceId = MangaSourceIds.WEEB_CENTRAL, mangaUrl = switchedUrl)
                } else {
                    it
                }
            }
        }

        val saved = FavoritesStore(prefs).read()
        assertEquals(1, workerSnapshot.size)
        assertEquals(listOf("Monster", "Berserk"), saved.map { it.title })
        assertEquals(switchedUrl, saved.single { it.title == "Berserk" }.mangaUrl)
    }
}
