package com.lorenzo.mangadownloader.platform

import okio.FileSystem
import okio.Path
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber

actual fun availableBytes(path: Path): Long {
    val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(path.toString(), null)
    return (attributes?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: Long.MAX_VALUE
}

actual val systemFileSystem: FileSystem get() = FileSystem.SYSTEM
