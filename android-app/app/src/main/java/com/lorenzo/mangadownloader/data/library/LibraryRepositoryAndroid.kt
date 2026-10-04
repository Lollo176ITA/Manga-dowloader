package com.lorenzo.mangadownloader.data.library

import android.content.Context
import com.lorenzo.mangadownloader.platform.settings

/** Il repository della libreria con cartelle e preferenze di sempre del dispositivo Android. */
fun LibraryRepository(context: Context): LibraryRepository =
    LibraryRepository(context.appPaths(), context.settings(LibraryRepository.PREFS_NAME))
