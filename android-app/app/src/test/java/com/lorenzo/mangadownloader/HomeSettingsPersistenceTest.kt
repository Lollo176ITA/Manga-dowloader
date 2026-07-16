package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeSettingsPersistenceTest {

    private fun store(): SettingsStore {
        val prefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("home_test_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return SettingsStore(prefs)
    }

    @Test
    fun defaults_whenNothingPersisted() {
        val s = store().read()
        assertEquals(DEFAULT_HOME_BLOCK_ORDER, s.homeBlockOrder)
        assertEquals(emptySet<HomeBlock>(), s.hiddenHomeBlocks)
    }

    @Test
    fun roundTrip_cardDensity() {
        val st = store()
        st.persist(AppSettings(cardDensity = CardDensity.COMPACT))
        assertEquals(CardDensity.COMPACT, st.read().cardDensity)
        // Default per chi non ha mai scelto (o per un backup vecchio senza il campo).
        assertEquals(CardDensity.NORMAL, AppSettings().cardDensity)
    }

    @Test
    fun roundTrip_orderAndHidden() {
        val st = store()
        val custom = AppSettings(
            homeBlockOrder = listOf(
                HomeBlock.DISCOVER, HomeBlock.RESUME, HomeBlock.FAVORITE_UPDATES, HomeBlock.RECENT_FAVORITES,
                HomeBlock.RECOMMENDED, HomeBlock.STATS, HomeBlock.HISTORY, HomeBlock.TO_FINISH,
            ),
            hiddenHomeBlocks = setOf(HomeBlock.DISCOVER),
        )
        st.persist(custom)
        val read = st.read()
        assertEquals(custom.homeBlockOrder, read.homeBlockOrder)
        assertEquals(custom.hiddenHomeBlocks, read.hiddenHomeBlocks)
    }

    @Test
    fun read_reconcilesPartialStoredOrder() {
        val st = store()
        st.persist(AppSettings(homeBlockOrder = listOf(HomeBlock.RESUME)))
        // I blocchi mancanti vengono appesi in coda nell'ordine di default.
        assertEquals(
            listOf(
                HomeBlock.RESUME,
                HomeBlock.FAVORITE_UPDATES,
                HomeBlock.RECENT_FAVORITES,
                HomeBlock.DISCOVER,
                HomeBlock.RECOMMENDED,
                HomeBlock.STATS,
                HomeBlock.HISTORY,
                HomeBlock.TO_FINISH,
            ),
            st.read().homeBlockOrder,
        )
    }
}
