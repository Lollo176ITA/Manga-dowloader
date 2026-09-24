package com.lorenzo.mangadownloader.app

import android.content.Context
import androidx.biometric.BiometricManager
import com.lorenzo.mangadownloader.data.store.ParentalLockoutStore
import com.lorenzo.mangadownloader.domain.generateParentalPinSalt
import com.lorenzo.mangadownloader.domain.hashParentalPin
import com.lorenzo.mangadownloader.domain.parentalLockoutLabel
import com.lorenzo.mangadownloader.domain.parentalPinLockoutMillis
import com.lorenzo.mangadownloader.domain.sanitizeParentalPin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class ParentalAction {
    OPEN_SEARCH,
    CHANGE_PIN,
    DISABLE_PARENTAL_CONTROL,
    ENABLE_BIOMETRIC,
    DISABLE_BIOMETRIC,
}

enum class ParentalPinSetupMode {
    CREATE,
    CHANGE,
}

data class ParentalPinSetupState(
    val mode: ParentalPinSetupMode,
    val pin: String = "",
    val confirmPin: String = "",
    val errorMessage: String? = null,
    val completionAction: ParentalAction? = null,
)

data class ParentalPinEntryState(
    val action: ParentalAction,
    val pin: String = "",
    val errorMessage: String? = null,
)

data class ParentalBiometricPromptRequest(
    val requestId: Long,
    val action: ParentalAction,
    val title: String,
    val subtitle: String,
)

/**
 * Controllo parentale estratto da [MangaViewModel]: creazione/cambio PIN, sblocco con PIN o
 * biometria, blocco dopo tentativi errati e accesso protetto a Cerca. Lavora sullo stesso
 * [MutableStateFlow] del ViewModel (i campi `parental*` di [MangaUiState] restano lì) e
 * persiste le impostazioni tramite [updateSettings], così lo stato ha una sola fonte.
 */
class ParentalControlController(
    private val state: MutableStateFlow<MangaUiState>,
    private val lockoutStore: ParentalLockoutStore,
    private val updateSettings: ((AppSettings) -> AppSettings) -> Unit,
) {
    private var nextBiometricRequestId = 1L

    private inline fun updateState(transform: MangaUiState.() -> MangaUiState) {
        state.update(transform)
    }

    fun setEnabled(enabled: Boolean) {
        val currentSettings = state.value.settings
        if (enabled) {
            if (currentSettings.parentalControlEnabled) return
            startPinSetup(mode = ParentalPinSetupMode.CREATE)
            return
        }

        if (!currentSettings.parentalControlEnabled) return
        if (!currentSettings.parentalPinConfigured) {
            disable(clearCredentials = true)
        } else {
            requestAuthentication(ParentalAction.DISABLE_PARENTAL_CONTROL)
        }
    }

    fun requestChangePin() {
        val settings = state.value.settings
        if (!settings.parentalControlEnabled) return
        if (!settings.parentalPinConfigured) {
            startPinSetup(mode = ParentalPinSetupMode.CREATE)
        } else {
            requestAuthentication(ParentalAction.CHANGE_PIN)
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        val settings = state.value.settings
        if (!settings.parentalControlEnabled || !settings.parentalPinConfigured) return
        val action = if (enabled) {
            ParentalAction.ENABLE_BIOMETRIC
        } else {
            ParentalAction.DISABLE_BIOMETRIC
        }
        requestAuthentication(action)
    }

    fun onPinSetupChange(pin: String? = null, confirmPin: String? = null) {
        val setupState = state.value.parentalPinSetupState ?: return
        updateState {
            copy(
                parentalPinSetupState = setupState.copy(
                    pin = pin?.let(::sanitizeParentalPin) ?: setupState.pin,
                    confirmPin = confirmPin?.let(::sanitizeParentalPin) ?: setupState.confirmPin,
                    errorMessage = null,
                ),
            )
        }
    }

    fun dismissPinSetup() {
        val setupState = state.value.parentalPinSetupState ?: return
        if (setupState.mode == ParentalPinSetupMode.CREATE && !state.value.settings.parentalPinConfigured) {
            disable(clearCredentials = true)
            return
        }
        updateState {
            copy(
                parentalPinSetupState = null,
                isParentalAuthInProgress = false,
                pendingSearchAccessReturnTab = if (setupState.completionAction == ParentalAction.OPEN_SEARCH) {
                    null
                } else {
                    pendingSearchAccessReturnTab
                },
            )
        }
    }

    fun confirmPinSetup() {
        val setupState = state.value.parentalPinSetupState ?: return
        when {
            setupState.pin.length != PARENTAL_PIN_LENGTH -> {
                updateState {
                    copy(
                        parentalPinSetupState = setupState.copy(
                            errorMessage = "Il PIN deve avere 6 cifre",
                        ),
                    )
                }
            }
            setupState.confirmPin.length != PARENTAL_PIN_LENGTH -> {
                updateState {
                    copy(
                        parentalPinSetupState = setupState.copy(
                            errorMessage = "Conferma il PIN di 6 cifre",
                        ),
                    )
                }
            }
            setupState.pin != setupState.confirmPin -> {
                updateState {
                    copy(
                        parentalPinSetupState = setupState.copy(
                            errorMessage = "I due PIN non coincidono",
                        ),
                    )
                }
            }
            else -> {
                val salt = generateParentalPinSalt()
                val hash = hashParentalPin(setupState.pin, salt)
                updateSettings {
                    it.copy(
                        parentalControlEnabled = true,
                        parentalPinConfigured = true,
                        // Spento finché non lo accende il genitore: il telefono accetta ogni
                        // impronta registrata, anche quella del figlio.
                        parentalBiometricEnabled = false,
                        parentalPinSalt = salt,
                        parentalPinHash = hash,
                    )
                }
                updateState {
                    copy(
                        currentTab = if (
                            setupState.completionAction == null &&
                            (currentTab == AppTab.SEARCH || currentTab == AppTab.HOME)
                        ) {
                            // Attivando il parental si atterra su Libreria: Home e Cerca
                            // mostrano/portano a contenuti online che il parental limita.
                            AppTab.LIBRARY
                        } else {
                            currentTab
                        },
                        parentalPinSetupState = null,
                        isParentalAuthInProgress = false,
                    )
                }
                setupState.completionAction?.let(::completeAction)
            }
        }
    }

    fun onPinEntryChange(pin: String) {
        val pinEntryState = state.value.parentalPinEntryState ?: return
        updateState {
            copy(
                parentalPinEntryState = pinEntryState.copy(
                    pin = sanitizeParentalPin(pin),
                    errorMessage = null,
                ),
            )
        }
    }

    fun dismissPinEntry() {
        val pinEntryState = state.value.parentalPinEntryState ?: return
        updateState {
            copy(
                parentalPinEntryState = null,
                isParentalAuthInProgress = false,
                pendingSearchAccessReturnTab = if (pinEntryState.action == ParentalAction.OPEN_SEARCH) {
                    null
                } else {
                    pendingSearchAccessReturnTab
                },
            )
        }
    }

    fun confirmPinEntry() {
        val pinEntryState = state.value.parentalPinEntryState ?: return
        val settings = state.value.settings
        if (pinEntryState.pin.length != PARENTAL_PIN_LENGTH) {
            updateState {
                copy(
                    parentalPinEntryState = pinEntryState.copy(
                        errorMessage = "Inserisci un PIN di 6 cifre",
                    ),
                )
            }
            return
        }

        val salt = settings.parentalPinSalt
        val expectedHash = settings.parentalPinHash
        if (salt.isNullOrBlank() || expectedHash.isNullOrBlank()) {
            updateState {
                copy(
                    parentalPinEntryState = null,
                    isParentalAuthInProgress = false,
                    errorMessage = "Configura di nuovo il parental control",
                )
            }
            disable(clearCredentials = true)
            return
        }

        val now = System.currentTimeMillis()
        val lockedUntil = lockoutStore.lockedUntilMillis()
        if (lockedUntil > now) {
            updateState {
                copy(
                    parentalPinEntryState = pinEntryState.copy(
                        pin = "",
                        errorMessage = "Troppi tentativi. Riprova tra ${parentalLockoutLabel(lockedUntil - now)}",
                    ),
                )
            }
            return
        }

        val providedHash = hashParentalPin(pinEntryState.pin, salt)
        if (providedHash != expectedHash) {
            val attempts = lockoutStore.failedAttempts() + 1
            val lockout = parentalPinLockoutMillis(attempts)
            lockoutStore.recordFailure(lockedUntilMillis = now + lockout)
            updateState {
                copy(
                    parentalPinEntryState = pinEntryState.copy(
                        pin = "",
                        errorMessage = if (lockout > 0) {
                            "PIN non corretto. Riprova tra ${parentalLockoutLabel(lockout)}"
                        } else {
                            "PIN non corretto"
                        },
                    ),
                )
            }
            return
        }
        lockoutStore.reset()

        updateState {
            copy(
                parentalPinEntryState = null,
                isParentalAuthInProgress = false,
            )
        }
        completeAction(pinEntryState.action)
    }

    fun onBiometricSucceeded(requestId: Long) {
        val request = state.value.biometricPromptRequest ?: return
        if (request.requestId != requestId) return
        updateState {
            copy(
                biometricPromptRequest = null,
                isParentalAuthInProgress = false,
            )
        }
        completeAction(request.action)
    }

    fun usePinInsteadOfBiometric(requestId: Long) {
        val request = state.value.biometricPromptRequest ?: return
        if (request.requestId != requestId) return
        showPinEntryForAction(request.action)
    }

    fun cancelBiometric(requestId: Long, message: String? = null) {
        val request = state.value.biometricPromptRequest ?: return
        if (request.requestId != requestId) return
        updateState {
            copy(
                biometricPromptRequest = null,
                isParentalAuthInProgress = false,
                pendingSearchAccessReturnTab = if (request.action == ParentalAction.OPEN_SEARCH) {
                    null
                } else {
                    pendingSearchAccessReturnTab
                },
                errorMessage = message ?: errorMessage,
            )
        }
    }

    fun requestSearchAccess() {
        val settings = state.value.settings
        val originTab = state.value.currentTab
        if (!settings.parentalControlEnabled) {
            updateState { copy(currentTab = AppTab.SEARCH) }
            return
        }
        updateState { copy(pendingSearchAccessReturnTab = originTab) }
        if (!settings.parentalPinConfigured) {
            startPinSetup(
                mode = ParentalPinSetupMode.CREATE,
                completionAction = ParentalAction.OPEN_SEARCH,
            )
            return
        }
        requestAuthentication(ParentalAction.OPEN_SEARCH)
    }

    private fun requestAuthentication(action: ParentalAction) {
        if (state.value.isParentalAuthInProgress) return
        val settings = state.value.settings
        if (!settings.parentalPinConfigured) {
            startPinSetup(mode = ParentalPinSetupMode.CREATE, completionAction = action)
            return
        }
        if (settings.parentalBiometricEnabled && state.value.isBiometricAvailable) {
            val requestId = nextBiometricRequestId++
            updateState {
                copy(
                    isParentalAuthInProgress = true,
                    parentalPinEntryState = null,
                    biometricPromptRequest = ParentalBiometricPromptRequest(
                        requestId = requestId,
                        action = action,
                        title = "Parental control",
                        subtitle = when (action) {
                            ParentalAction.OPEN_SEARCH -> "Autenticati per aprire Cerca"
                            ParentalAction.CHANGE_PIN -> "Autenticati per cambiare il PIN"
                            ParentalAction.DISABLE_PARENTAL_CONTROL ->
                                "Autenticati per disattivare il parental control"
                            ParentalAction.ENABLE_BIOMETRIC,
                            ParentalAction.DISABLE_BIOMETRIC ->
                                "Autenticati per aggiornare la biometria"
                        },
                    ),
                )
            }
        } else {
            showPinEntryForAction(action)
        }
    }

    private fun startPinSetup(
        mode: ParentalPinSetupMode,
        completionAction: ParentalAction? = null,
    ) {
        updateState {
            copy(
                isParentalAuthInProgress = true,
                parentalPinEntryState = null,
                biometricPromptRequest = null,
                parentalPinSetupState = ParentalPinSetupState(
                    mode = mode,
                    completionAction = completionAction,
                ),
            )
        }
    }

    private fun showPinEntryForAction(action: ParentalAction) {
        updateState {
            copy(
                biometricPromptRequest = null,
                isParentalAuthInProgress = true,
                parentalPinEntryState = ParentalPinEntryState(action = action),
            )
        }
    }

    private fun completeAction(action: ParentalAction) {
        when (action) {
            ParentalAction.OPEN_SEARCH -> updateState {
                copy(
                    currentTab = AppTab.SEARCH,
                    pendingSearchAccessReturnTab = null,
                )
            }
            ParentalAction.CHANGE_PIN -> startPinSetup(mode = ParentalPinSetupMode.CHANGE)
            ParentalAction.DISABLE_PARENTAL_CONTROL -> disable(clearCredentials = true)
            ParentalAction.ENABLE_BIOMETRIC -> updateSettings { it.copy(parentalBiometricEnabled = true) }
            ParentalAction.DISABLE_BIOMETRIC -> updateSettings { it.copy(parentalBiometricEnabled = false) }
        }
    }

    private fun disable(clearCredentials: Boolean) {
        updateSettings {
            if (clearCredentials) {
                it.copy(
                    parentalControlEnabled = false,
                    parentalPinConfigured = false,
                    parentalBiometricEnabled = false,
                    parentalPinSalt = null,
                    parentalPinHash = null,
                )
            } else {
                it.copy(parentalControlEnabled = false, parentalBiometricEnabled = false)
            }
        }
        updateState {
            copy(
                currentTab = if (currentTab == AppTab.SEARCH) AppTab.LIBRARY else currentTab,
                pendingSearchAccessReturnTab = null,
                parentalPinSetupState = null,
                parentalPinEntryState = null,
                biometricPromptRequest = null,
                isParentalAuthInProgress = false,
            )
        }
    }

    companion object {
        private const val PARENTAL_PIN_LENGTH = 6

        fun isBiometricAvailable(context: Context): Boolean {
            return BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        }
    }
}
