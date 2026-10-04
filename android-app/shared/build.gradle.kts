// Modulo Kotlin Multiplatform condiviso tra l'app Android e l'app iOS: logica, dati e UI Compose
// stanno in commonMain; androidMain/iosMain contengono solo le implementazioni di piattaforma.
// I target iOS si compilano solo su macOS (vedi kotlin.native.ignoreDisabledTargets).
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    android {
        namespace = "com.lorenzo.mangadownloader.shared"
        compileSdk = 37
        minSdk = 26

        withHostTest {
            isIncludeAndroidResources = true
        }

        // Le Compose Resources (icona, changelog) arrivano nell'APK come asset Android.
        androidResources {
            enable = true
        }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            api("io.ktor:ktor-client-okhttp:3.6.0")
            implementation("androidx.core:core:1.19.1")
            // Su Android :shared compila contro le stesse librerie Compose che l'app usa a runtime
            // (BOM + material3 di :app): un'API cambiata tra il binario CMP e quello Android
            // diventa un errore di compilazione invece di un crash.
            implementation(project.dependencies.platform("androidx.compose:compose-bom:2026.09.00"))
            implementation("androidx.compose.material3:material3:1.5.0-alpha29")
        }
        iosMain.dependencies {
            api("io.ktor:ktor-client-darwin:3.6.0")
        }
        getByName("androidHostTest").dependencies {
            // Oracolo per i test di parità: il comportamento da replicare è quello di OkHttp.
            implementation("com.squareup.okhttp3:okhttp:5.5.0")
        }
        commonMain.dependencies {
            // UI Compose Multiplatform. material3 è il binario CMP (API dell'androidx su cui si basa):
            // su Android vince a runtime la material3 più recente dichiarata da :app.
            api("org.jetbrains.compose.runtime:runtime:1.12.1")
            api("org.jetbrains.compose.foundation:foundation:1.12.1")
            api("org.jetbrains.compose.ui:ui:1.12.1")
            api("org.jetbrains.compose.ui:ui-backhandler:1.12.1")
            api("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
            api("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            api("org.jetbrains.compose.components:components-resources:1.12.1")
            api("io.coil-kt.coil3:coil-compose:3.6.3")
            api("io.coil-kt.coil3:coil-network-core:3.6.3")
            // Coachmark del tutorial (multipiattaforma: android + ios).
            api("io.github.aldefy:lumen:1.0.0-beta20")
            api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
            api("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
            api("com.squareup.okio:okio:3.18.2")
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            api("com.russhwolf:multiplatform-settings:1.3.0")
            api("org.jetbrains.kotlinx:atomicfu:0.33.0")
            api("com.fleeksoft.ksoup:ksoup:0.2.6")
            api("io.ktor:ktor-client-core:3.6.0")
            api("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("com.russhwolf:multiplatform-settings-test:1.3.0")
            implementation("com.squareup.okio:okio-fakefilesystem:3.18.2")
            implementation("io.ktor:ktor-client-mock:3.6.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

// Su Windows i target iOS sono disabilitati e commonMain viene compilato solo per Android, dove le
// API Java (java.io, java.time...) risultano visibili. La compilazione dei metadati comuni invece le
// rifiuta: legarla ai test fa emergere subito il codice non portabile, senza aspettare il Mac.
tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
    dependsOn("compileCommonMainKotlinMetadata")
}

compose.resources {
    packageOfResClass = "com.lorenzo.mangadownloader.resources"
}

// Il changelog della schermata "Novità" ha un'unica fonte di verità: il CHANGELOG.md nella root
// del repo, copiato tra le risorse comuni prima che Compose le impacchetti (file ignorato da git).
val copyChangelogToResources = tasks.register<Copy>("copyChangelogToResources") {
    description = "Copia il CHANGELOG.md della root nelle Compose Resources per la schermata Novità."
    from(rootProject.file("../CHANGELOG.md"))
    into(layout.projectDirectory.dir("src/commonMain/composeResources/files"))
}
tasks.matching { it.name.startsWith("prepareComposeResourcesTask") || it.name.startsWith("copyNonXmlValueResources") }
    .configureEach { dependsOn(copyChangelogToResources) }
