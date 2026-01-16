plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.fishit.mapper.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.fishit.mapper.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            // Debug builds werden automatisch mit Android Debug Keystore signiert
            // Dies ist ausreichend für VPN und Zertifikat-Funktionalität
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Für Release-Build sollte ein eigener Keystore verwendet werden:
            // signingConfig = signingConfigs.getByName("release")
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

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
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

    // Security & Networking
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    
    // VPN & Packet Processing
    implementation(libs.tun2socks)
    implementation(libs.pcap4j.core)
}
