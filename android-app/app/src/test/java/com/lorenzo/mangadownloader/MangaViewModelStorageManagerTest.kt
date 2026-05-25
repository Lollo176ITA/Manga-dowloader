package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MangaViewModelStorageManagerTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun openStorageManager_showsStorageScreen() {
        val viewModel = createViewModel()

        viewModel.openStorageManager()

        assertTrue(viewModel.state.value.showStorageManager)
    }

    @Test
    fun closeStorageManager_returnsToSettings() {
        val viewModel = createViewModel()
        viewModel.openSettings()
        viewModel.openStorageManager()

        viewModel.closeStorageManager()

        // Tornando indietro dalla memoria si resta nelle impostazioni.
        assertFalse(viewModel.state.value.showStorageManager)
        assertTrue(viewModel.state.value.showSettings)
    }

    @Test
    fun closeSettings_resetsStorageManagerToo() {
        val viewModel = createViewModel()
        viewModel.openSettings()
        viewModel.openStorageManager()

        viewModel.closeSettings()

        assertFalse(viewModel.state.value.showSettings)
        assertFalse(viewModel.state.value.showStorageManager)
    }

    private fun createViewModel(): MangaViewModel =
        MangaViewModel(application, AppUpdateRepository(application))

    private companion object {
        private const val PREFS_NAME = "manga_downloader_prefs"
    }
}
