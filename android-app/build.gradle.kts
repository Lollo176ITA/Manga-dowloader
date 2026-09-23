plugins {
    // Da AGP 9 il supporto Kotlin è integrato: niente più plugin org.jetbrains.kotlin.android.
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
}
