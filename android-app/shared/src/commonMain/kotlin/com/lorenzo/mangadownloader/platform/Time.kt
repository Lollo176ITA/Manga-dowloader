package com.lorenzo.mangadownloader.platform

import kotlin.time.Clock

/** Millisecondi dall'epoch, come `System.currentTimeMillis()`. */
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
