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
class MangaViewModelLandscapeRotationTest {

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
    fun landscapeRotation_isDisabledByDefault() {
        val viewModel = createViewModel()

        assertFalse(viewModel.state.value.settings.allowLandscapeRotation)
    }

    @Test
    fun enablingLandscapeRotation_updatesStateAndPersists() {
        val viewModel = createViewModel()

        viewModel.setAllowLandscapeRotation(true)

        assertTrue(viewModel.state.value.settings.allowLandscapeRotation)
        // Una nuova istanza rilegge il valore persistito dalle SharedPreferences.
        assertTrue(createViewModel().state.value.settings.allowLandscapeRotation)
    }

    @Test
    fun disablingLandscapeRotation_revertsToPortrait() {
        val viewModel = createViewModel()
        viewModel.setAllowLandscapeRotation(true)

        viewModel.setAllowLandscapeRotation(false)

        assertFalse(viewModel.state.value.settings.allowLandscapeRotation)
        assertFalse(createViewModel().state.value.settings.allowLandscapeRotation)
    }

    @Test
    fun disablingLabs_clearsLandscapeRotation() {
        val viewModel = createViewModel()
        viewModel.setLabsEnabled(true)
        viewModel.setAllowLandscapeRotation(true)

        viewModel.setLabsEnabled(false)

        // La rotazione è ora una sub-opzione Labs: spegnere Labs la azzera (come le altre).
        assertFalse(viewModel.state.value.settings.allowLandscapeRotation)
        assertFalse(createViewModel().state.value.settings.allowLandscapeRotation)
    }

    private fun createViewModel(): MangaViewModel =
        MangaViewModel(application, AppUpdateRepository(application))

    private companion object {
        private const val PREFS_NAME = "manga_downloader_prefs"
    }
}
