package com.lorenzo.mangadownloader

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
import java.io.File
import java.util.Properties
import javax.activation.DataHandler
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Categoria di una segnalazione. [trelloLabel] è il tag `#...` aggiunto all'oggetto: l'email-to-board
 * di Trello lo riconosce come etichetta (se esiste) e lo toglie dal titolo della card.
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
 * Invia le segnalazioni (form + crash) spedendo un'**email via SMTP** all'indirizzo email-to-board
 * di Trello, che la trasforma in una card. Le credenziali SMTP ([BuildConfig.SMTP_USER]/`SMTP_PASSWORD`)
 * sono di un account email **dedicato/usa-e-getta**: finiscono nell'APK e sono estraibili, quindi il
 * danno in caso di leak è limitato (spam da quell'indirizzo) ed è sufficiente rigenerare la password.
 * L'invio è automatico, ma parte solo su azione esplicita dell'utente.
 */
object FeedbackReporter {

    /** Segnalazioni attive solo se l'account SMTP e il destinatario sono configurati in build. */
    fun isConfigured(): Boolean =
        BuildConfig.SMTP_USER.isNotBlank() &&
            BuildConfig.SMTP_PASSWORD.isNotBlank() &&
            BuildConfig.REPORT_TO_EMAIL.isNotBlank()

    private fun deviceLine(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    /** Invia una segnalazione del form. Sospesa: fa rete su IO. True se l'invio SMTP riesce. */
    suspend fun sendReport(context: Context, draft: FeedbackDraft): Boolean {
        if (!isConfigured()) return false
        val subtypeSuffix = if (draft.subtype.isNotBlank()) " · ${draft.subtype}" else ""
        val subject = "[${draft.category.label}$subtypeSuffix] MangApp ${BuildConfig.VERSION_NAME} #${draft.category.trelloLabel}"
        val body = buildString {
            append(draft.message.trim())
            append("\n\n—\n")
            append("Tipo: ${draft.category.label}$subtypeSuffix")
            append("\nVersione: MangApp ${BuildConfig.VERSION_NAME}")
            append("\nDispositivo: ${deviceLine()}")
            append("\nContatto: ${draft.contactEmail.trim().ifBlank { "non fornito" }}")
        }
        return sendEmail(context, subject, body, draft.imageUris, draft.audioFile)
    }

    /** Invia l'ultimo crash come segnalazione (dal dialog crash). */
    suspend fun sendCrashReport(context: Context, report: String): Boolean {
        if (!isConfigured()) return false
        val subject = "[Crash] MangApp ${BuildConfig.VERSION_NAME} #crash"
        val body = buildString {
            append(report.trim())
            append("\n\n—\n")
            append("Versione: MangApp ${BuildConfig.VERSION_NAME}")
            append("\nDispositivo: ${deviceLine()}")
        }
        return sendEmail(context, subject, body, emptyList(), null)
    }

    private suspend fun sendEmail(
        context: Context,
        subject: String,
        body: String,
        imageUris: List<Uri>,
        audioFile: File?,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.host", BuildConfig.SMTP_HOST)
                put("mail.smtp.port", BuildConfig.SMTP_PORT)
                if (BuildConfig.SMTP_PORT == "465") {
                    put("mail.smtp.socketFactory.port", BuildConfig.SMTP_PORT)
                    put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                } else {
                    put("mail.smtp.starttls.enable", "true")
                }
            }
            val session = Session.getInstance(
                props,
                object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication =
                        PasswordAuthentication(BuildConfig.SMTP_USER, BuildConfig.SMTP_PASSWORD)
                },
            )

            val multipart = MimeMultipart()
            multipart.addBodyPart(MimeBodyPart().apply { setText(body, "utf-8") })

            val resolver = context.contentResolver
            imageUris.forEachIndexed { index, uri ->
                val bytes = runCatching {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull() ?: return@forEachIndexed
                val mime = resolver.getType(uri) ?: "image/jpeg"
                val ext = when {
                    mime.contains("png") -> "png"
                    mime.contains("webp") -> "webp"
                    else -> "jpg"
                }
                multipart.addBodyPart(attachmentPart(bytes, mime, "immagine_${index + 1}.$ext"))
            }
            if (audioFile != null && audioFile.exists()) {
                multipart.addBodyPart(attachmentPart(audioFile.readBytes(), "audio/mp4", audioFile.name))
            }

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(BuildConfig.SMTP_USER))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(BuildConfig.REPORT_TO_EMAIL))
                setSubject(subject, "utf-8")
                setContent(multipart)
            }
            Transport.send(message)
            true
        }.getOrDefault(false)
    }

    private fun attachmentPart(bytes: ByteArray, mime: String, fileName: String): MimeBodyPart =
        MimeBodyPart().apply {
            dataHandler = DataHandler(ByteArrayDataSource(bytes, mime))
            setFileName(fileName)
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
