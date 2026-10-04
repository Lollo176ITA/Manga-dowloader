package com.lorenzo.mangadownloader.data.backup

import java.io.InputStream
import java.io.OutputStream

/** Scrive il backup come JSON UTF-8 sullo stream fornito (aperto/chiuso dal chiamante). */
fun BackupManager.export(output: OutputStream, nowMs: Long) {
    output.write(exportJson(nowMs).toByteArray(Charsets.UTF_8))
}

/** Legge e applica un backup JSON UTF-8 dallo stream (vedi [BackupManager.restoreJson]). */
fun BackupManager.restore(input: InputStream, mode: BackupRestoreMode): BackupRestoreResult? =
    restoreJson(input.readBytes().toString(Charsets.UTF_8), mode)
