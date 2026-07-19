package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MangaViewModelHomeTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun vm() = MangaViewModel(application)

    @Test
    fun setHomeBlockHidden_addsAndRemoves() {
        val viewModel = vm()
        viewModel.setHomeBlockHidden(HomeBlock.DISCOVER, true)
        assertTrue(HomeBlock.DISCOVER in viewModel.state.value.settings.hiddenHomeBlocks)
        viewModel.setHomeBlockHidden(HomeBlock.DISCOVER, false)
        assertFalse(HomeBlock.DISCOVER in viewModel.state.value.settings.hiddenHomeBlocks)
    }

    @Test
    fun moveHomeBlock_up_reorders() {
        val viewModel = vm()
        viewModel.moveHomeBlock(HomeBlock.FAVORITE_UPDATES, up = true)
        assertEquals(HomeBlock.FAVORITE_UPDATES, viewModel.state.value.settings.homeBlockOrder.first())
    }

    @Test
    fun restartTutorial_reopensWelcomeOnHomeTab() {
        val viewModel = vm()
        viewModel.selectTab(AppTab.LIBRARY)
        viewModel.restartTutorial()
        assertEquals(TutorialPhase.Welcome, viewModel.state.value.tutorialState.phase)
        assertFalse(viewModel.state.value.settings.tutorialCompleted)
        // Deve riportare su Home, dove vive la card di benvenuto, altrimenti sarebbe un no-op.
        assertEquals(AppTab.HOME, viewModel.state.value.currentTab)
    }
}
