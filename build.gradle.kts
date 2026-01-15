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
// =============================================================================: https://docs.sonarsource.com/sonarqube-server/analyzing-source-code/scanners/sonarscanner-for-gradle/
// =============================================================================

sonar {
    properties {
        // === Project Identification ===
        property("sonar.projectKey", "karlokarate_FishIT-Mapper")
        property("sonar.projectName", "FishIT-Mapper")
        property("sonar.projectVersion", "0.1.0")
        
        // === Source Configuration ===
        // CLI-Parameter (-Dsonar.sources=...) haben Vorrang für dynamische Modul-Auswahl
        // Default: Alle Module analysieren
        if (System.getProperty("sonar.sources") == null) {
            property("sonar.sources", 
                "androidApp/src/main/java," +
                "shared/contract/src/commonMain/kotlin," +
                "shared/contract/src/generated/kotlin," +
                "shared/engine/src/commonMain/kotlin," +
                "tools/codegen-contract/src/main/kotlin"
            )
        }
        
        // === Language Configuration ===
        property("sonar.sourceEncoding", "UTF-8")
        property
        property("sonar.java.target", "17")
        
        // === Exclusions ===
        // Exclude build artifacts, tests, and non-code files from analysis
        // CLI-Parameter haben Vorrang
        if (System.getProperty("sonar.exclusions") == null) {
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
        }
        
        // === Duplicate Code Detection ===
        // Exclude generated code from duplication check (it's expected to have patterns)
        // CLI-Parameter haben Vorrang (für workflow_dispatch: enable_duplications=false)
        if (System.getProperty("sonar.cpd.exclusions") == null) {
            property("sonar.cpd.exclusions",
                "**/generated/**," +
                "**/contract/src/generated/**"
            )
        }
        
        // === Android Lint Integration (if lint reports exist) ===
        property("sonar.android.lint.report", "androidApp/build/reports/lint-results-debug.xml")
        
        // === Java Binaries (für Bytecode-Analyse) ===
        property("sonar.java.binaries", "**/build/classes")
    }
}