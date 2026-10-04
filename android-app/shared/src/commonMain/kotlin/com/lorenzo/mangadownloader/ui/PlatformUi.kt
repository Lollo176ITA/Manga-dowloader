package com.lorenzo.mangadownloader.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.lorenzo.mangadownloader.app.BackupDocument
import com.lorenzo.mangadownloader.app.ParentalBiometricPromptRequest

/**
 * Pezzi di interfaccia che esistono solo su alcune piattaforme (widget della Home, segnalazioni
 * via email…). La piattaforma li fornisce alla radice con [LocalPlatformUi]; dove mancano
 * (`null`) la UI comune nasconde la voce corrispondente.
 */
class PlatformUi(
    /** Riga delle impostazioni per aggiungere il widget "Continua a leggere". */
    val readingWidgetSettings: (@Composable () -> Unit)? = null,
    /** Schermata "Segnala un problema"; `onResult(true)` quando la segnalazione è partita. */
    val reportProblem: (@Composable (padding: PaddingValues, onResult: (sent: Boolean) -> Unit) -> Unit)? = null,
    /** La build ha le credenziali per inviare segnalazioni (altrimenti l'invio fallisce sempre). */
    val isFeedbackConfigured: Boolean = false,
)

val LocalPlatformUi = staticCompositionLocalOf { PlatformUi() }

/**
 * Ciò che la radice dell'app chiede al sistema operativo: permessi, browser, selettori di file
 * ed effetti sulla finestra. Lo implementa ogni piattaforma attorno alla propria Activity o
 * view controller.
 *
 * Le funzioni `remember…` sono composable perché il risultato può arrivare dopo che il sistema
 * ha ricreato l'app (selettore di file aperto, processo ucciso): il callback va registrato a
 * ogni composizione, non conservato in memoria.
 */
interface PlatformHost {
    /** Le notifiche dell'app sono consentite; si rilegge a ogni ritorno in primo piano. */
    fun notificationsAllowed(): Boolean

    /** Un'azione che chiede il permesso notifiche e riporta l'esito a [onResult]. */
    @Composable
    fun rememberNotificationsPermissionRequest(onResult: (granted: Boolean) -> Unit): () -> Unit

    /** Le impostazioni di sistema delle notifiche dell'app. */
    fun openNotificationSettings()

    /** Apre [url] nel browser; `false` se non ce n'è uno. */
    fun openUrl(url: String): Boolean

    /** Selettori del file di backup: [onExport] riceve dove scrivere, [onImport] cosa leggere. */
    @Composable
    fun rememberBackupPickers(
        onExport: (BackupDocument) -> Unit,
        onImport: (BackupDocument) -> Unit,
    ): BackupPickers

    /**
     * L'app è stata aperta da una notifica di download che porta alla Libreria. Consuma la
     * richiesta: la seconda chiamata restituisce `false`.
     */
    fun consumeOpenLibraryRequest(): Boolean

    /** Orientamento, schermo sempre acceso e barre nascoste nel reader, sblocco biometrico. */
    @Composable
    fun SystemEffects(request: SystemEffectsRequest)
}

class BackupPickers(
    val pickExportTarget: (suggestedName: String) -> Unit,
    val pickImportSource: () -> Unit,
)

class SystemEffectsRequest(
    val allowLandscapeRotation: Boolean,
    val biometricRequest: ParentalBiometricPromptRequest?,
    val readerOpen: Boolean,
    val readerFullscreen: Boolean,
    val keepReaderScreenOn: Boolean,
    val onBiometricSucceeded: (requestId: Long) -> Unit,
    val onUsePinInstead: (requestId: Long) -> Unit,
    val onBiometricCancelled: (requestId: Long, message: String?) -> Unit,
)
