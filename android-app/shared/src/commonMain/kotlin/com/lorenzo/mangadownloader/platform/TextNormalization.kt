package com.lorenzo.mangadownloader.platform

/** Normalizzazione Unicode NFKD (decomposizione di compatibilità) fornita dalla piattaforma. */
expect fun normalizeNfkd(text: String): String
