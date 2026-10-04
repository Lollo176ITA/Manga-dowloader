package com.lorenzo.mangadownloader.platform

/** [size] byte da un generatore crittograficamente sicuro della piattaforma. */
expect fun secureRandomBytes(size: Int): ByteArray
