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

/**
 * Il tasto indietro fa "pop" della schermata in cima rispettando la gerarchia: la gestione
 * memoria (aperta dentro le impostazioni) torna alle impostazioni, poi alle tab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MangaViewModelBackNavigationTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("manga_downloader_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun createViewModel() = MangaViewModel(application, AppUpdateRepository(application))

    @Test
    fun handleBack_popsStorageManagerThenSettingsThenTabs() {
        val viewModel = createViewModel()

        viewModel.openSettings()
        assertEquals(Screen.Settings, viewModel.state.value.currentScreen())

        viewModel.openStorageManager()
        assertEquals(Screen.StorageManager, viewModel.state.value.currentScreen())

        viewModel.handleBack()
        assertEquals(Screen.Settings, viewModel.state.value.currentScreen())

        viewModel.handleBack()
        assertEquals(Screen.Tabs, viewModel.state.value.currentScreen())
    }

    @Test
    fun handleBack_onTabsIsNoOp() {
        val viewModel = createViewModel()

        assertEquals(Screen.Tabs, viewModel.state.value.currentScreen())
        viewModel.handleBack()
        assertEquals(Screen.Tabs, viewModel.state.value.currentScreen())
    }
}
