import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val versionProperties = Properties().apply {
    val versionFile = rootProject.file("version.properties")
    versionFile.inputStream().use(::load)
}
val appVersionName = project.findProperty("appVersionNameOverride")
    ?.toString()
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: versionProperties.getProperty("versionName")
val derivedAppVersionCode = appVersionName.toAndroidVersionCode()
val appVersionCodeProperty = project.findProperty("appVersionCodeOverride")
    ?.toString()
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: versionProperties.getProperty("versionCode")?.trim()
val appVersionCode = appVersionCodeProperty?.let { raw ->
    raw.toIntOrNull() ?: error("versionCode non valido: $raw")
} ?: derivedAppVersionCode
val legacyAppVersionCode = appVersionName.toLegacyAndroidVersionCodeOrNull()
require(appVersionCode == derivedAppVersionCode || appVersionCode == legacyAppVersionCode) {
    val legacyMessage = legacyAppVersionCode?.let { " oppure legacy $it" }.orEmpty()
    "versionCode ($appVersionCode) non coerente con versionName ($appVersionName). Atteso: $derivedAppVersionCode$legacyMessage"
}
val updateConfigUrl = versionProperties.getProperty("updateConfigUrl")
val repoOwner = versionProperties.getProperty("repoOwner")
val repoName = versionProperties.getProperty("repoName")
val apkAssetName = versionProperties.getProperty("apkAssetName")

// Segnalazioni in-app (schermata "Segnala un problema" + crash): POST al Worker Cloudflare in
// report-relay/, che crea la card su Trello. L'URL è pubblico per natura (sta nell'APK): le
// credenziali Trello vivono solo nel Worker.
val reportRelayUrl = "https://mangapp-report-relay.lollo176ita.workers.dev/report"

fun String?.toAndroidVersionCode(): Int {
    val raw = this?.trim().orEmpty()
    require(raw.isNotBlank()) { "versionName mancante in version.properties" }

    val previewNumber = raw.substringAfter("-preview.", missingDelimiterValue = "")
        .takeIf(String::isNotBlank)
        ?.toIntOrNull()
    val stableVersion = raw.substringBefore("-preview.")

    val parts = stableVersion.split('.')
    require(parts.size in 1..3) {
        "versionName deve avere formato semver semplice tipo 1.7.1 o preview tipo 1.7.2-preview.1"
    }

    val major = parts.getOrNull(0)?.toIntOrNull()
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0

    require(major != null) { "Major non valido in versionName: $raw" }
    require(minor in 0..999) { "Minor fuori range in versionName: $raw" }
    require(patch in 0..999) { "Patch fuori range in versionName: $raw" }

    val baseVersionCode = (major * 1_000_000) + (minor * 1_000) + patch
    return if (previewNumber != null) {
        require(raw.matches(Regex("""\d+(?:\.\d+){0,2}-preview\.(?:[1-9]|[1-8]\d|9[0-8])"""))) {
            "versionName preview non valido: $raw"
        }
        baseVersionCode * 100 + previewNumber
    } else {
        require(!raw.contains("-preview.")) { "versionName preview non valido: $raw" }
        baseVersionCode * 100 + 99
    }
}

fun String?.toLegacyAndroidVersionCodeOrNull(): Int? {
    val raw = this?.trim().orEmpty()
    if (!raw.matches(Regex("""\d+(?:\.\d+){0,2}"""))) return null

    val parts = raw.split('.')
    val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    if (minor !in 0..999 || patch !in 0..999) return null

    return (major * 1_000_000) + (minor * 1_000) + patch
}

val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val hasReleaseSigning =
    !releaseKeystorePath.isNullOrBlank() &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.lorenzo.mangadownloader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lorenzo.mangadownloader"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_CONFIG_URL", "\"$updateConfigUrl\"")
        buildConfigField("String", "UPDATE_REPO_OWNER", "\"${repoOwner.orEmpty()}\"")
        buildConfigField("String", "UPDATE_REPO_NAME", "\"${repoName.orEmpty()}\"")
        buildConfigField("String", "UPDATE_APK_ASSET_NAME", "\"${apkAssetName.orEmpty()}\"")
        buildConfigField("String", "REPORT_RELAY_URL", "\"$reportRelayUrl\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Build misurata dal modulo :benchmark (Macrobenchmark): identica alla release (R8
        // compreso) ma firmata con la chiave debug e installata a parte (.benchmark), così non
        // tocca i dati né la build debug sull'emulatore.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".benchmark"
            isDebuggable = false
            // Niente controllo aggiornamenti: il dialog "Aggiornamento disponibile" arriva
            // asincrono dalla rete e può bloccare i tocchi a metà misura. Con questi valori la
            // richiesta fallisce subito (porta chiusa) e i controlli automatici tacciono.
            buildConfigField("String", "UPDATE_CONFIG_URL", "\"http://127.0.0.1:9/\"")
            buildConfigField("String", "UPDATE_REPO_OWNER", "\"\"")
            buildConfigField("String", "UPDATE_REPO_NAME", "\"\"")
            buildConfigField("String", "UPDATE_APK_ASSET_NAME", "\"\"")
            // Un crash durante una misura non deve diventare una card vera su Trello.
            buildConfigField("String", "REPORT_RELAY_URL", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

}

// Col Kotlin integrato di AGP 9 il jvmTarget eredita compileOptions.targetCompatibility (17):
// non serve più il blocco kotlin.compilerOptions.

// Il changelog mostrato in-app (schermata "Novità") ha un'unica fonte di verità: il
// CHANGELOG.md nella root del repo. Lo copiamo negli assets a ogni build, prima del merge
// degli asset, così l'app legge sempre la versione aggiornata senza duplicati che divergono.
val copyChangelogToAssets = tasks.register<Copy>("copyChangelogToAssets") {
    description = "Copia il CHANGELOG.md della root negli assets per la schermata Novità."
    from(rootProject.file("../CHANGELOG.md"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.named("preBuild").configure { dependsOn(copyChangelogToAssets) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.glance:glance-material3:1.2.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("io.github.aldefy:lumen-android:1.0.0-beta20")
    // Installa i profili ART (richiesto da Macrobenchmark; servirà anche al Baseline Profile).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.work:work-testing:2.12.0")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
