package com.lorenzo.mangadownloader

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity

/** Effetti legati all'Activity, separati dalla composizione e dalla navigazione dell'app. */
@Composable
internal fun AppSystemEffects(
    activity: FragmentActivity?,
    allowLandscapeRotation: Boolean,
    biometricRequest: ParentalBiometricPromptRequest?,
    readerOpen: Boolean,
    readerFullscreen: Boolean,
    keepReaderScreenOn: Boolean,
    onBiometricSucceeded: (Long) -> Unit,
    onUsePinInstead: (Long) -> Unit,
    onBiometricCancelled: (Long, String?) -> Unit,
) {
    OrientationEffect(activity, allowLandscapeRotation)
    BiometricPromptEffect(
        activity = activity,
        request = biometricRequest,
        onSucceeded = onBiometricSucceeded,
        onUsePinInstead = onUsePinInstead,
        onCancelled = onBiometricCancelled,
    )
    ReaderWindowEffect(
        readerOpen = readerOpen,
        fullscreen = readerFullscreen,
        keepScreenOn = keepReaderScreenOn,
    )
}

@Composable
private fun OrientationEffect(activity: FragmentActivity?, allowLandscapeRotation: Boolean) {
    LaunchedEffect(activity, allowLandscapeRotation) {
        activity?.requestedOrientation = if (allowLandscapeRotation) {
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}

@Composable
private fun BiometricPromptEffect(
    activity: FragmentActivity?,
    request: ParentalBiometricPromptRequest?,
    onSucceeded: (Long) -> Unit,
    onUsePinInstead: (Long) -> Unit,
    onCancelled: (Long, String?) -> Unit,
) {
    LaunchedEffect(request?.requestId) {
        val currentRequest = request ?: return@LaunchedEffect
        val hostActivity = activity
        if (hostActivity == null) {
            onCancelled(currentRequest.requestId, "Biometria non disponibile su questo dispositivo")
            return@LaunchedEffect
        }

        val prompt = BiometricPrompt(
            hostActivity,
            ContextCompat.getMainExecutor(hostActivity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSucceeded(currentRequest.requestId)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> onUsePinInstead(currentRequest.requestId)
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> onCancelled(currentRequest.requestId, null)
                        else -> onCancelled(currentRequest.requestId, errString.toString())
                    }
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(currentRequest.title)
                .setSubtitle(currentRequest.subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .setNegativeButtonText("Usa PIN")
                .build(),
        )
    }
}

@Composable
private fun ReaderWindowEffect(
    readerOpen: Boolean,
    fullscreen: Boolean,
    keepScreenOn: Boolean,
) {
    val view = LocalView.current
    LaunchedEffect(readerOpen, fullscreen, view) {
        if (view.isInEditMode) return@LaunchedEffect
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (readerOpen && fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(readerOpen, keepScreenOn, view) {
        val window = (view.context as? Activity)?.window
        if (readerOpen && keepScreenOn && window != null && !view.isInEditMode) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
