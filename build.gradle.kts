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

// SonarQube configuration for code analysis
sonar {
    properties {
        property("sonar.projectKey", "FishIT-Mapper")
        property("sonar.projectName", "FishIT-Mapper")
        property("sonar.sourceEncoding", "UTF-8")
        
        // Kotlin source directories
        property("sonar.sources", listOf(
            "androidApp/src/main/java",
            "shared/contract/src/commonMain/kotlin",
            "shared/contract/src/generated/kotlin",
            "shared/engine/src/commonMain/kotlin",
            "tools/codegen-contract/src/main/kotlin"
        ).joinToString(","))
        
        // Exclusions
        property("sonar.exclusions", listOf(
            "**/build/**",
            "**/test/**",
            "**/*.json"
        ).joinToString(","))
    }
}