package com.lorenzo.mangadownloader.platform

import java.text.Normalizer

actual fun normalizeNfkd(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
