plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.fishit.mapper.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.fishit.mapper.android"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            // CI/CD: Keystore aus Environment Variables
            // Lokal: Keystore-Datei im keystore/ Ordner (NICHT committen!)
            val envKeystoreFile = System.getenv("KEYSTORE_FILE")
            val keystoreFile = when {
                envKeystoreFile != null -> file(envKeystoreFile)
                rootProject.file("keystore/release.jks").exists() -> rootProject.file("keystore/release.jks")
                else -> null
            }

            if (keystoreFile != null && keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: "fishit-mapper"
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds werden automatisch mit Android Debug Keystore signiert
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signiere Release-Builds wenn Keystore verfügbar
            val releaseConfig = signingConfigs.findByName("release")
            if (releaseConfig?.storeFile != null) {
                signingConfig = releaseConfig
            }
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
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Enable XML output for SonarQube import
        xmlReport = true
        xmlOutput = file("build/reports/lint-results-debug.xml")

        // Also keep HTML for human-readable reports
        htmlReport = true
        htmlOutput = file("build/reports/lint-results-debug.html")

        // Don't abort build on lint errors
        abortOnError = false
    }
}

dependencies {
    // Direct dependency on contract to ensure generated code is available
    implementation(projects.shared.contract)
    implementation(projects.shared.engine)

    // KotlinX
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.documentfile)

    // Testing
    testImplementation(kotlin("test"))

    // Note: BouncyCastle and OkHttp removed - no longer needed without internal MITM proxy
    // Traffic capture is handled externally by HttpCanary
}
