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

/** Persistenza e coerzioni dell'interspazio pagine del reader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MangaViewModelReaderPageSpacingTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun defaultsToHistoricalSpacing() {
        assertEquals(
            DEFAULT_READER_PAGE_SPACING_DP,
            createViewModel().state.value.settings.readerPageSpacingDp,
        )
    }

    @Test
    fun spacing_persistsAcrossViewModels() {
        createViewModel().setReaderPageSpacing(0)

        assertEquals(0, createViewModel().state.value.settings.readerPageSpacingDp)
    }

    @Test
    fun spacing_isCoercedIntoAllowedRange() {
        val viewModel = createViewModel()

        viewModel.setReaderPageSpacing(999)
        assertEquals(
            MAX_READER_PAGE_SPACING_DP,
            viewModel.state.value.settings.readerPageSpacingDp,
        )

        viewModel.setReaderPageSpacing(-4)
        assertEquals(0, viewModel.state.value.settings.readerPageSpacingDp)
    }

    private fun createViewModel(): MangaViewModel =
        MangaViewModel(application, AppUpdateRepository(application))
}
