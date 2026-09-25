plugins {
    id("com.android.test")
}

android {
    // Non "…benchmark": è il pacchetto dell'app di test e collide con l'app misurata
    // (com.lorenzo.mangadownloader.benchmark).
    namespace = "com.lorenzo.mangadownloader.perftest"
    compileSdk = 37

    defaultConfig {
        // 31: UiAutomation.executeShellCommandRw (dati finti passati via stdin). L'app resta a 26.
        minSdk = 31
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Gira sull'emulatore per scelta: i conteggi di ricomposizione restano affidabili.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}

// Solo la variante benchmark: le altre non hanno una build dell'app da misurare.
androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}
