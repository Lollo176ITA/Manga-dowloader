pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Permette a Gradle di scaricare da solo il JDK richiesto da gradle-daemon-jvm.properties
    // quando non è installato (es. in locale, dove il java di sistema non è compatibile).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MangaDownloaderAndroid"
include(":app")
