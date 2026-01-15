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
        
        // === Source Configuration ===
        // HINWEIS: sonar.sources wird dynamisch über Workflow gesetzt
        // Default-Wert nur als Fallback (falls lokal ausgeführt)
        if (!project.hasProperty("sonar.sources")) {
            property("sonar.sources", listOf(
                "androidApp/src/main/java",
                "shared/contract/src/commonMain/kotlin",
                "shared/contract/src/generated/kotlin",
                "shared/engine/src/commonMain/kotlin",
                "tools/codegen-contract/src/main/kotlin"
            ))
        }
        
        // === Language Configuration ===
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.kotlin.source.version", "1.9")
        property("sonar.java.source", "17")
        property("sonar.java.target", "17")
        
        // === Exclusions ===
        // Exclude build artifacts, tests, and non-code files from analysis
        property("sonar.exclusions", listOf(
            "**/build/**",
            "**/test/**",
            "**/androidTest/**",
            "**/*.json",
            "**/*.xml",
            "**/R.java",
            "**/R\$*.java",
            "**/BuildConfig.java",
            "**/Manifest.java"
        ))
        
        // === Duplicate Code Detection ===
        // Exclude generated code from duplication check (it's expected to have patterns)
        property("sonar.cpd.exclusions", listOf(
            "**/generated/**",
            "**/contract/src/generated/**"
        ))
        
        // === Android Lint Integration (if lint reports exist) ===
        property("sonar.android.lint.report", "androidApp/build/reports/lint-results-debug.xml")
        
        // === Java Binaries (für Bytecode-Analyse) ===
        property("sonar.java.binaries", "**/build/classes")
    }
}