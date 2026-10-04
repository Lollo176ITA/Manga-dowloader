package com.lorenzo.mangadownloader.platform

import android.os.StatFs
import okio.FileSystem
import okio.Path

actual fun availableBytes(path: Path): Long = StatFs(path.toString()).availableBytes

actual val systemFileSystem: FileSystem get() = FileSystem.SYSTEM
