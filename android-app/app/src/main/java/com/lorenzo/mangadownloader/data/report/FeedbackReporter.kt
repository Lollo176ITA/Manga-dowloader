package com.lorenzo.mangadownloader.data.report

import android.content.Context
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
import com.lorenzo.mangadownloader.BuildConfig
import com.lorenzo.mangadownloader.data.network.SharedHttpClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Categoria di una segnalazione. [trelloLabel] è l'etichetta che il relay applica alla card
 * (deve essere una di quelle accettate da `report-relay`: bug, feature, altro, crash).
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

/** Bozza raccolta dal form della schermata "Segnala un problema", pronta da inviare. */
data class FeedbackDraft(
    val category: ReportCategory,
    val subtype: String,
    val message: String,
    val contactEmail: String,
    val imageUris: List<Uri>,
    val audioFile: File?,
)

/**
 * Invia le segnalazioni (form + crash) con un POST multipart al Worker Cloudflare `report-relay`
 * ([BuildConfig.REPORT_RELAY_URL]), che crea la card sulla board Trello. Le credenziali Trello
 * stanno solo nel Worker: l'APK conosce solo l'URL, quindi chi lo estrae può al massimo creare
 * card, entro i limiti di frequenza e dimensione del relay.
 */
object FeedbackReporter {

    /** Segnalazioni attive solo se la build ha l'URL del relay. */
    fun isConfigured(): Boolean = BuildConfig.REPORT_RELAY_URL.isNotBlank()

    private fun deviceLine(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    /** Invia una segnalazione del form. Sospesa: fa rete su IO. True se il relay la accetta. */
    suspend fun sendReport(context: Context, draft: FeedbackDraft): Boolean {
        if (!isConfigured()) return false
        val subtypeSuffix = if (draft.subtype.isNotBlank()) " · ${draft.subtype}" else ""
        val title = "[${draft.category.label}$subtypeSuffix] MangApp ${BuildConfig.VERSION_NAME}"
        val body = buildString {
            append(draft.message.trim())
            append("\n\n—\n")
            append("Tipo: ${draft.category.label}$subtypeSuffix")
            append("\nVersione: MangApp ${BuildConfig.VERSION_NAME}")
            append("\nDispositivo: ${deviceLine()}")
            append("\nContatto: ${draft.contactEmail.trim().ifBlank { "non fornito" }}")
        }
        return send(context, title, body, draft.category.trelloLabel, draft.imageUris, draft.audioFile)
    }

    /** Invia l'ultimo crash come segnalazione (dal dialog crash). */
    suspend fun sendCrashReport(context: Context, report: String): Boolean {
        if (!isConfigured()) return false
        val title = "[Crash] MangApp ${BuildConfig.VERSION_NAME}"
        val body = buildString {
            append(report.trim())
            append("\n\n—\n")
            append("Versione: MangApp ${BuildConfig.VERSION_NAME}")
            append("\nDispositivo: ${deviceLine()}")
        }
        return send(context, title, body, "crash", emptyList(), null)
    }

    private suspend fun send(
        context: Context,
        title: String,
        body: String,
        label: String,
        imageUris: List<Uri>,
        audioFile: File?,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val form = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("title", title)
                .addFormDataPart("body", body)
                .addFormDataPart("label", label)

            val resolver = context.contentResolver
            imageUris.forEachIndexed { index, uri ->
                val bytes = runCatching {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull() ?: return@forEachIndexed
                val mime = resolver.getType(uri) ?: "image/jpeg"
                val ext = mime.substringAfter("image/", "jpg").substringBefore(';').ifBlank { "jpg" }
                form.addFormDataPart(
                    "attachment",
                    "immagine_${index + 1}.$ext",
                    bytes.toRequestBody(mime.toMediaType()),
                )
            }
            if (audioFile != null && audioFile.exists()) {
                form.addFormDataPart(
                    "attachment",
                    audioFile.name,
                    audioFile.asRequestBody("audio/mp4".toMediaType()),
                )
            }

            val request = Request.Builder()
                .url(BuildConfig.REPORT_RELAY_URL)
                .post(form.build())
                .build()
            SharedHttpClient.get(context).newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}

/**
 * Registratore del messaggio vocale opzionale. Scrive un `.m4a` nella cache (`feedback/`), poi
 * allegato all'email. Lo stato è osservabile da Compose.
 *
 * Il file NON viene cancellato quando la schermata si smonta (può servire all'invio): la pulizia
 * dei file vecchi avviene alla creazione del recorder.
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

    /** Ferma la registrazione finalizzando il file (che resta disponibile per l'invio). */
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
