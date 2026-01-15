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
        property("sonar.projectName", "FishIT-Mapper")
        property("sonar.projectVersion", "0.1.0")
        
        // === Language Configuration ===
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.kotlin.source.version", "1.9")
        property("sonar.java.source", "17")
        property("sonar.java.target", "17")
        
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
        
        // === Android Lint Integration ===
        property("sonar.android.lint.report", "androidApp/build/reports/lint-results-debug.xml")
        
        // === Java Binaries ===
        property("sonar.java.binaries", "**/build/classes")
    }
}
        
        // === Android Lint Integration (if lint reports exist) ===
        property("sonar.android.lint.report", "androidApp/build/reports/lint-results-debug.xml")
        
        // === Java Binaries (für Bytecode-Analyse) ===
        property("sonar.java.binaries", "**/build/classes")
    }
}