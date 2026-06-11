package com.lorenzo.mangadownloader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/**
 * Categoria di una segnalazione. [trelloLabel] è il nome dell'etichetta Trello applicata via
 * `#tag` nell'oggetto dell'email (l'email-to-board la riconosce e la rimuove dal titolo).
 */
enum class ReportCategory(val label: String, val trelloLabel: String) {
    BUG("Bug", "bug"),
    FEATURE("Funzionalità", "feature"),
    OTHER("Altro", "altro"),
}

/** Sottotipi proposti per categoria: l'utente ne sceglie uno per orientare il triage. */
fun ReportCategory.subtypes(): List<String> = when (this) {
    ReportCategory.BUG -> listOf(
        "L'app si chiude / crash",
        "Download",
        "Lettore / pagine",
        "Ricerca o fonte manga",
        "Interfaccia",
        "Altro",
    )
    ReportCategory.FEATURE -> listOf(
        "Nuova fonte manga",
        "Lettore",
        "Libreria / organizzazione",
        "Notifiche / aggiornamenti",
        "Altro",
    )
    ReportCategory.OTHER -> listOf(
        "Domanda",
        "Feedback generale",
        "Altro",
    )
}

/** Bozza raccolta dal form della schermata "Segnala un problema", pronta da inviare via email. */
data class FeedbackDraft(
    val category: ReportCategory,
    val subtype: String,
    val message: String,
    val contactEmail: String,
    val imageUris: List<Uri>,
    val audioUri: Uri?,
)

/**
 * Invia le segnalazioni come **email precompilata** verso l'indirizzo "email-to-board" di Trello
 * (configurato in `local.properties` → [BuildConfig.TRELLO_REPORT_EMAIL]). Non spedisce nulla in
 * autonomia: apre il compositore email e l'invio finale lo fa l'utente, che vede cosa manda.
 */
object FeedbackReporter {

    /** Le segnalazioni sono attive solo se l'indirizzo Trello è stato configurato in build. */
    fun isConfigured(): Boolean = BuildConfig.TRELLO_REPORT_EMAIL.isNotBlank()

    private fun deviceLine(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    /** Apre il compositore email per una segnalazione del form. False se non configurato o senza app email. */
    fun sendReport(context: Context, draft: FeedbackDraft): Boolean {
        if (!isConfigured()) return false
        val subject = buildString {
            append("[${draft.category.label}")
            if (draft.subtype.isNotBlank()) append(" · ${draft.subtype}")
            append("] MangApp ${BuildConfig.VERSION_NAME} #${draft.category.trelloLabel}")
        }
        val body = buildString {
            append(draft.message.trim())
            append("\n\n—\n")
            append("Tipo: ${draft.category.label}")
            if (draft.subtype.isNotBlank()) append(" · ${draft.subtype}")
            append("\nVersione: MangApp ${BuildConfig.VERSION_NAME}")
            append("\nDispositivo: ${deviceLine()}")
            append("\nContatto: ${draft.contactEmail.trim().ifBlank { "non fornito" }}")
        }
        val attachments = buildList {
            addAll(draft.imageUris)
            draft.audioUri?.let(::add)
        }
        return launchEmail(context, subject, body, attachments)
    }

    /** Invia l'ultimo crash come segnalazione (dal dialog crash). */
    fun sendCrashReport(context: Context, report: String): Boolean {
        if (!isConfigured()) return false
        val subject = "[Crash] MangApp ${BuildConfig.VERSION_NAME} #crash"
        val body = buildString {
            append(report.trim())
            append("\n\n—\n")
            append("Versione: MangApp ${BuildConfig.VERSION_NAME}")
            append("\nDispositivo: ${deviceLine()}")
        }
        return launchEmail(context, subject, body, emptyList())
    }

    private fun launchEmail(
        context: Context,
        subject: String,
        body: String,
        attachments: List<Uri>,
    ): Boolean {
        val action = if (attachments.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
        val intent = Intent(action).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.TRELLO_REPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            when {
                attachments.size == 1 -> putExtra(Intent.EXTRA_STREAM, attachments.first())
                attachments.size > 1 -> putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    ArrayList(attachments),
                )
            }
            if (attachments.isNotEmpty()) {
                // Concede all'app email la lettura degli allegati (immagini MediaStore + audio FileProvider).
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("allegati", attachments.first()).apply {
                    attachments.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
            }
        }
        val chooser = Intent.createChooser(intent, "Invia segnalazione via email")
        return try {
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

/**
 * Registratore del messaggio vocale opzionale. Scrive un `.m4a` nella cache (`feedback/`), esposto
 * come `content://` via FileProvider per allegarlo all'email. Lo stato è osservabile da Compose.
 *
 * Il file NON viene cancellato quando la schermata si smonta (l'app email lo legge dopo l'invio):
 * la pulizia avviene per i file vecchi alla creazione del recorder.
 */
class FeedbackAudioRecorder(private val context: Context) {
    var isRecording by mutableStateOf(false)
        private set
    var recordedFile: File? by mutableStateOf(null)
        private set

    private var recorder: MediaRecorder? = null

    init {
        cleanupOldRecordings()
    }

    fun start() {
        if (isRecording) return
        discard()
        val dir = File(context.cacheDir, FEEDBACK_DIR).apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordedFile = file
            isRecording = true
        } catch (_: Exception) {
            try { rec.release() } catch (_: Exception) {}
            file.delete()
            recorder = null
            recordedFile = null
            isRecording = false
        }
    }

    /** Ferma la registrazione finalizzando il file (che resta disponibile per l'allegato). */
    fun stop() {
        val rec = recorder ?: run {
            isRecording = false
            return
        }
        try {
            rec.stop()
        } catch (_: Exception) {
            // Registrazione troppo breve/non valida: il file è inutilizzabile, scartalo.
            recordedFile?.delete()
            recordedFile = null
        }
        try { rec.release() } catch (_: Exception) {}
        recorder = null
        isRecording = false
    }

    /** Ferma (se in corso) e cancella il file registrato. */
    fun discard() {
        stop()
        recordedFile?.delete()
        recordedFile = null
    }

    fun uri(): Uri? = recordedFile?.let {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
    }

    private fun cleanupOldRecordings() {
        val dir = File(context.cacheDir, FEEDBACK_DIR)
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - OLD_RECORDING_MILLIS
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    private companion object {
        const val FEEDBACK_DIR = "feedback"
        const val OLD_RECORDING_MILLIS = 24L * 60 * 60 * 1000
    }
}

/** Crea un [FeedbackAudioRecorder] legato alla composizione: stop+release quando si smonta. */
@Composable
fun rememberFeedbackAudioRecorder(): FeedbackAudioRecorder {
    val context = LocalContext.current
    val recorder = remember(context) { FeedbackAudioRecorder(context) }
    DisposableEffect(recorder) {
        // Stop (non discard): se l'utente esce mentre registra, finalizziamo il file senza cancellarlo.
        onDispose { recorder.stop() }
    }
    return recorder
}
