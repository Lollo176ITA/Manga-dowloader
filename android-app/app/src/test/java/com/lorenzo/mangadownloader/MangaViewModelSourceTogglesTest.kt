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

/** Toggle per-fonte: attiva/disattiva, guardia sull'ultima fonte attiva, persistenza. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MangaViewModelSourceTogglesTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("manga_downloader_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun createViewModel() = MangaViewModel(application, AppUpdateRepository(application))

    @Test
    fun disabilitaERiabilitaUnaFonte() {
        val viewModel = createViewModel()
        viewModel.setSourceEnabled(MangaSourceIds.VYMANGA, enabled = false)
        assertEquals(setOf(MangaSourceIds.VYMANGA), viewModel.state.value.settings.disabledSourceIds)
        viewModel.setSourceEnabled(MangaSourceIds.VYMANGA, enabled = true)
        assertEquals(emptySet<String>(), viewModel.state.value.settings.disabledSourceIds)
    }

    @Test
    fun ultimaFonteAttivaNonDisabilitabile() {
        val viewModel = createViewModel()
        val allButOne = MangaSourceCatalog.descriptors.map { it.id }.drop(1)
        allButOne.forEach { viewModel.setSourceEnabled(it, enabled = false) }
        val lastActive = MangaSourceCatalog.descriptors.first().id
        viewModel.setSourceEnabled(lastActive, enabled = false)
        assertEquals(allButOne.toSet(), viewModel.state.value.settings.disabledSourceIds)
    }

    @Test
    fun sceltaPersistita() {
        createViewModel().setSourceEnabled(MangaSourceIds.VYMANGA, enabled = false)
        val second = createViewModel()
        assertEquals(setOf(MangaSourceIds.VYMANGA), second.state.value.settings.disabledSourceIds)
    }
}
