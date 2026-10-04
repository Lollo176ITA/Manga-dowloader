package com.lorenzo.mangadownloader.data.library

import android.content.Context
import android.os.Environment
import okio.Path.Companion.toOkioPath

/** Le cartelle di sempre: download nella memoria esterna dell'app, cache interna. */
fun Context.appPaths(): AppPaths = AppPaths(
    downloadsRootProvider = {
        (getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("Cartella download dell'app non disponibile")).toOkioPath()
    },
    cacheRoot = cacheDir.toOkioPath(),
)
