package com.lorenzo.mangadownloader.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import coil3.request.ImageResult
import coil3.transition.CrossfadeTransition
import coil3.transition.Transition
import coil3.transition.TransitionTarget

/**
 * Quanto si muove l'interfaccia (impostazione stile tema). "Rimuovi animazioni" di Android
 * vale sempre: questi livelli possono solo togliere, mai rimettere.
 */
enum class AnimationLevel(val label: String) {
    FULL("Complete"),
    REDUCED("Ridotte"),
    NONE("Nessuna"),
}

/** Livello corrente, fornito in MainActivity dalle impostazioni. */
val LocalAnimationLevel = compositionLocalOf { AnimationLevel.FULL }

/**
 * Stato di movimento condiviso dal processo: lo legge il Recomposer dell'Activity (via
 * [durationScale]) e l'ImageLoader di Coil (via [imageTransitionFactory]), che vivono fuori
 * dalla composizione e quindi non vedono [LocalAnimationLevel]. Entrambi lo rileggono a ogni
 * nuova animazione/immagine, quindi cambiare livello vale subito, senza ricreare l'Activity.
 * Sono stato snapshot perché le animazioni infinite (indicatori di caricamento), ferme a
 * scala 0, ripartono solo osservando la scala tornare positiva.
 */
object AppMotion {
    var level by mutableStateOf(AnimationLevel.FULL)

    /** Scala animazioni di sistema; aggiornata da MainActivity a ogni ritorno in primo piano. */
    var systemScale by mutableFloatStateOf(1f)

    /**
     * Installata nel Recomposer della finestra al posto di quella di sistema: con [AnimationLevel.NONE]
     * ogni animazione Compose (tab, reader, componenti M3, snackbar) arriva subito alla fine.
     * Scroll e fling non ne risentono: Compose li esegue con una scala propria.
     */
    val durationScale: MotionDurationScale = object : MotionDurationScale {
        override val scaleFactor: Float
            get() = if (level == AnimationLevel.NONE) 0f else systemScale
    }

    /** Dissolvenza in entrata di copertine e pagine solo con le animazioni complete. */
    val imageTransitionFactory: Transition.Factory = object : Transition.Factory {
        private val crossfade = CrossfadeTransition.Factory()

        override fun create(target: TransitionTarget, result: ImageResult): Transition =
            if (level == AnimationLevel.FULL && systemScale > 0f) {
                crossfade.create(target, result)
            } else {
                Transition.Factory.NONE.create(target, result)
            }
    }

    fun readSystemScale(context: Context): Float = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
}
