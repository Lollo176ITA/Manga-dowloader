package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Toggle "Mostra la tab Home": cambio tab immediato, persistenza, tab d'avvio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MangaViewModelShowHomeTabTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("manga_downloader_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun createViewModel() = MangaViewModel(application, AppUpdateRepository(application))

    @Test
    fun disablingWhileOnHome_switchesToSearch() {
        val vm = createViewModel()
        assertEquals(AppTab.HOME, vm.state.value.currentTab)
        vm.setShowHomeTab(false)
        assertEquals(false, vm.state.value.settings.showHomeTab)
        assertEquals(AppTab.SEARCH, vm.state.value.currentTab)
    }

    @Test
    fun disabledSetting_persists_andNextStartOpensOnSearch() {
        createViewModel().setShowHomeTab(false)
        val second = createViewModel()
        assertEquals(false, second.state.value.settings.showHomeTab)
        assertEquals(AppTab.SEARCH, second.state.value.currentTab)
    }

    @Test
    fun reenabling_keepsCurrentTab_andRestoresHomeInVisibleTabs() {
        val vm = createViewModel()
        vm.setShowHomeTab(false)
        vm.setShowHomeTab(true)
        assertEquals(AppTab.SEARCH, vm.state.value.currentTab)
        assertEquals(AppTab.entries.toList(), vm.state.value.visibleTabs())
    }
}
