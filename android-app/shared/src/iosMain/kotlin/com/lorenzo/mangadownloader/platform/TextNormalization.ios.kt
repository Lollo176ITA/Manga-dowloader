package com.lorenzo.mangadownloader.platform

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCompatibilityMapping

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun normalizeNfkd(text: String): String = (text as NSString).decomposedStringWithCompatibilityMapping
