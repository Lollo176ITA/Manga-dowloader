import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lorenzo.mangadownloader"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_CONFIG_URL", "\"$updateConfigUrl\"")
        buildConfigField("String", "UPDATE_REPO_OWNER", "\"${repoOwner.orEmpty()}\"")
        buildConfigField("String", "UPDATE_REPO_NAME", "\"${repoName.orEmpty()}\"")
        buildConfigField("String", "UPDATE_APK_ASSET_NAME", "\"${apkAssetName.orEmpty()}\"")
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // Work around known lint detector crashes with the current Kotlin/Compose toolchain mix.
        // Release CI still builds, signs, shrinks and uploads the APK; lint can run separately when the toolchain is stable.
        checkReleaseBuilds = false
        disable += setOf(
            "FrequentlyChangingValue",
            "NullSafeMutableLiveData",
            "RememberInComposition",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation("androidx.compose.material3:material3-window-size-class:1.5.0-alpha18")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("com.canopas.intro-showcase-view:introshowcaseview:2.0.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.14.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
