package com.lorenzo.mangadownloader

import android.app.Application
import android.content.Context
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

object CrashReporter {
    private const val DIAGNOSTICS_DIR = "diagnostics"
    private const val LAST_CRASH_FILE = "last_crash.txt"

    fun install(application: Application) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashReport(application, thread, throwable)
            } catch (_: Exception) {
                // Best-effort only.
            } finally {
                if (previousHandler != null) {
                    previousHandler.uncaughtException(thread, throwable)
                } else {
                    exitProcess(2)
                }
            }
        }
    }

    fun readLastCrash(context: Context): String? {
        val file = resolveCrashFile(context) ?: return null
        if (!file.exists()) {
            return null
        }
        return try {
            file.readText()
        } catch (_: Exception) {
            null
        }
    }

    fun clearLastCrash(context: Context) {
        val file = resolveCrashFile(context) ?: return
        if (file.exists()) {
            file.delete()
        }
    }

    fun crashFilePath(context: Context): String? = resolveCrashFile(context)?.absolutePath

    private fun writeCrashReport(context: Context, thread: Thread, throwable: Throwable) {
        val file = resolveCrashFile(context) ?: return
        file.parentFile?.mkdirs()

        val writer = StringWriter()
        PrintWriter(writer).use { printWriter ->
            throwable.printStackTrace(printWriter)
        }

        val timestamp = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val report = buildString {
            appendLine("Timestamp: $timestamp")
            appendLine("Thread: ${thread.name}")
            appendLine("Exception: ${throwable::class.java.name}")
            appendLine("Message: ${throwable.message.orEmpty()}")
            appendLine()
            append(writer.toString())
        }

        file.writeText(report)
    }

    private fun resolveCrashFile(context: Context): File? {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(File(baseDir, DIAGNOSTICS_DIR), LAST_CRASH_FILE)
    }
}
