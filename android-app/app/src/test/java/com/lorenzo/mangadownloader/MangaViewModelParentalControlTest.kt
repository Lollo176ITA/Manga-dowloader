package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MangaViewModelParentalControlTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.let { root ->
                val libraryDir = root.resolve(DownloadStorage.LIBRARY_FOLDER_NAME)
                if (libraryDir.exists()) {
                    libraryDir.deleteRecursively()
                }
            }
    }

    @Test
    fun enablingParental_startsPinSetupWithoutEnablingImmediately() {
        val viewModel = createViewModel()

        viewModel.setParentalControlEnabled(true)

        val state = viewModel.state.value
        assertNotNull(state.parentalPinSetupState)
        assertEquals(ParentalPinSetupMode.CREATE, state.parentalPinSetupState?.mode)
        assertFalse(state.settings.parentalControlEnabled)
        assertFalse(state.settings.parentalPinConfigured)
    }

    @Test
    fun dismissingInitialPinSetup_keepsParentalDisabled() {
        val viewModel = createViewModel()
        viewModel.setParentalControlEnabled(true)

        viewModel.dismissParentalPinSetup()

        val state = viewModel.state.value
        assertNull(state.parentalPinSetupState)
        assertFalse(state.isParentalAuthInProgress)
        assertFalse(state.settings.parentalControlEnabled)
        assertFalse(state.settings.parentalPinConfigured)
    }

    @Test
    fun confirmingPinSetup_enablesParentalAndAutoEnablesBiometricWhenAvailable() {
        val viewModel = createViewModel()
        viewModel.setParentalControlEnabled(true)

        savePin(viewModel)

        val state = viewModel.state.value
        assertTrue(state.settings.parentalControlEnabled)
        assertTrue(state.settings.parentalPinConfigured)
        assertEquals(state.isBiometricAvailable, state.settings.parentalBiometricEnabled)
        assertNotNull(state.settings.parentalPinSalt)
        assertNotNull(state.settings.parentalPinHash)
        assertEquals(AppTab.LIBRARY, state.currentTab)
    }

    @Test
    fun disablingParentalFromSettings_requiresAuthAndClearsCredentials() {
        val viewModel = createConfiguredViewModel()

        viewModel.setParentalControlEnabled(false)
        completePendingAuthentication(viewModel)

        val state = viewModel.state.value
        assertFalse(state.settings.parentalControlEnabled)
        assertFalse(state.settings.parentalPinConfigured)
        assertFalse(state.settings.parentalBiometricEnabled)
        assertNull(state.settings.parentalPinSalt)
        assertNull(state.settings.parentalPinHash)
    }

    @Test
    fun openingSearchWithParental_afterAuth_movesToSearch() {
        val viewModel = createConfiguredViewModel()
        viewModel.selectTab(AppTab.FAVORITES)

        viewModel.selectTab(AppTab.SEARCH)

        val pendingState = viewModel.state.value
        assertEquals(AppTab.FAVORITES, pendingState.currentTab)
        assertEquals(AppTab.FAVORITES, pendingState.pendingSearchAccessReturnTab)

        completePendingAuthentication(viewModel)

        val state = viewModel.state.value
        assertEquals(AppTab.SEARCH, state.currentTab)
        assertNull(state.pendingSearchAccessReturnTab)
    }

    @Test
    fun cancelingSearchAuth_keepsPreviousTabAndClearsPendingRollbackState() {
        val viewModel = createConfiguredViewModel()
        viewModel.selectTab(AppTab.FAVORITES)

        viewModel.selectTab(AppTab.SEARCH)

        val pendingState = viewModel.state.value
        assertEquals(AppTab.FAVORITES, pendingState.pendingSearchAccessReturnTab)

        when {
            pendingState.biometricPromptRequest != null -> {
                val request = pendingState.biometricPromptRequest
                    ?: throw AssertionError("Expected biometric request")
                viewModel.cancelBiometricAuthentication(request.requestId)
            }
            pendingState.parentalPinEntryState != null ->
                viewModel.dismissParentalPinEntry()
            else -> fail("Expected an authentication prompt for Cerca")
        }

        val state = viewModel.state.value
        assertEquals(AppTab.FAVORITES, state.currentTab)
        assertNull(state.pendingSearchAccessReturnTab)
        assertNull(state.parentalPinEntryState)
        assertNull(state.biometricPromptRequest)
    }

    private fun createConfiguredViewModel(): MangaViewModel {
        return createViewModel().also { viewModel ->
            viewModel.setParentalControlEnabled(true)
            savePin(viewModel)
        }
    }

    private fun savePin(viewModel: MangaViewModel, pin: String = TEST_PIN) {
        viewModel.onParentalPinSetupChange(pin = pin)
        viewModel.onParentalPinSetupChange(confirmPin = pin)
        viewModel.confirmParentalPinSetup()
    }

    private fun completePendingAuthentication(viewModel: MangaViewModel, pin: String = TEST_PIN) {
        val biometricRequest = viewModel.state.value.biometricPromptRequest
        if (biometricRequest != null) {
            viewModel.usePinInsteadOfBiometric(biometricRequest.requestId)
        }

        val pinEntryState = viewModel.state.value.parentalPinEntryState
        if (pinEntryState != null) {
            viewModel.onParentalPinEntryChange(pin)
            viewModel.confirmParentalPinEntry()
            return
        }

        val request = viewModel.state.value.biometricPromptRequest
            ?: throw AssertionError("Expected biometric or PIN authentication")
        viewModel.onBiometricAuthenticationSucceeded(request.requestId)
    }

    private fun createViewModel(): MangaViewModel = MangaViewModel(application)

    companion object {
        private const val PREFS_NAME = "manga_downloader_prefs"
        private const val TEST_PIN = "123456"
    }
}
