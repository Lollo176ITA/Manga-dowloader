package com.lorenzo.mangadownloader.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.withTimeoutOrNull

/** Permanenza massima delle snackbar con azione prima di sparire da sole (come le Short). */
private const val SnackbarWithActionTimeoutMs = 4_000L

/**
 * Come [SnackbarHostState.showSnackbar], ma le snackbar con azione spariscono da sole dopo
 * [SnackbarWithActionTimeoutMs], allineate ai 4s delle Short: il default Material 3 con
 * `actionLabel` è `Indefinite` (restano finché non le tocchi). Allo scadere il risultato
 * è [SnackbarResult.Dismissed], come per uno swipe. Senza azione, comportamento
 * standard (Short).
 */
suspend fun SnackbarHostState.showAutoDismissSnackbar(
    message: String,
    actionLabel: String? = null,
): SnackbarResult {
    if (actionLabel == null) {
        return showSnackbar(message)
    }
    return withTimeoutOrNull(SnackbarWithActionTimeoutMs) {
        showSnackbar(message, actionLabel, duration = SnackbarDuration.Indefinite)
    } ?: SnackbarResult.Dismissed
}
