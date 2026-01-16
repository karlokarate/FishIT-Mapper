// =============================================================================
// Buildscript BouncyCastle Fix for AGP Signing
// =============================================================================
// AGP uses BouncyCastle internally for keystore operations.
// On some JDK configurations, version conflicts cause NoClassDefFoundError.
// Adding explicit dependency ensures correct version is available.
// =============================================================================
buildscript {
    dependencies {
        classpath("org.bouncycastle:bcprov-jdk18on:1.79")
        classpath("org.bouncycastle:bcpkix-jdk18on:1.79")
    }
}

plugins {
    // Keep plugin versions centralized in gradle/libs.versions.toml
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.sonarqube)
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

// =============================================================================
// SonarQube Configuration for Comprehensive Code Analysis
// =============================================================================
// Analyzes: Bugs, Vulnerabilities, Security Hotspots, Code Smells, Duplications
// Documentation: https://docs.sonarsource.com/sonarqube-server/analyzing-source-code/scanners/sonarscanner-for-gradle/
// =============================================================================

sonar {
    properties {
        // === Project Identification ===
        property("sonar.projectKey", "karlokarate_FishIT-Mapper")
        property("sonar.organization", "karlokarate")
        property("sonar.projectName", "FishIT-Mapper")
        property("sonar.projectVersion", "0.1.0")

        // === Language Configuration ===
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.kotlin.source.version", "1.9")
        property("sonar.java.source", "17")
        property("sonar.java.target", "17")

        // === Scan All Files (including non-JVM files in root) ===
        property("sonar.gradle.scanAll", "true")

        // === Android Lint Report Paths ===
        property("sonar.androidLint.reportPaths",
            "androidApp/build/reports/lint-results-debug.xml," +
            "shared/contract/build/reports/lint-results-debug.xml," +
            "shared/engine/build/reports/lint-results-debug.xml"
        )

        // === Exclusions ===
        property("sonar.exclusions",
            "**/build/**," +
            "**/test/**," +
            "**/androidTest/**," +
            "**/*.json," +
            "**/*.xml," +
            "**/R.java," +
            "**/R\$*.java," +
            "**/BuildConfig.java," +
            "**/Manifest.java"
        )

        // === Duplicate Code Detection ===
        property("sonar.cpd.exclusions",
            "**/generated/**," +
            "**/contract/src/generated/**"
        )

        // === Java Binaries ===
        // Removed explicit sonar.java.binaries configuration to let Gradle plugin
        // use its defaults, which correctly handle Android intermediates and Kotlin classes
    }
}

// =============================================================================
// Force consistent BouncyCastle versions to avoid NoClassDefFoundError
// AGP uses BouncyCastle internally for signing - version conflicts cause issues
// =============================================================================
allprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.bouncycastle") {
                useVersion("1.79")
                because("Force consistent BouncyCastle version for AGP signing compatibility")
            }
        }
    }
}
