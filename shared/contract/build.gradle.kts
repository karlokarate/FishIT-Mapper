plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            // Use src/generated instead of build/generated for better IDE support and reliability
            kotlin.srcDir(projectDir.resolve("src/generated/kotlin"))

            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
            }
        }
    }
}

android {
    namespace = "dev.fishit.mapper.contract"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

// --- KotlinPoet contract generation wiring ---
// Use src/generated instead of build/generated for better reliability
val generatedDir = projectDir.resolve("src/generated/kotlin")

val codegenClasspath by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    add(codegenClasspath.name, projects.tools.codegenContract)
}

val generateFishitContract = tasks.register<JavaExec>("generateFishitContract") {
    group = "codegen"
    description = "Generates the FishIT contract sources from schema/contract.schema.json"
    classpath = codegenClasspath
    mainClass.set("dev.fishit.mapper.codegen.MainKt")
    args(
        "--schema", "${rootProject.projectDir}/schema/contract.schema.json",
        "--out", generatedDir.absolutePath
    )
    
    // Declare output directory for incremental build support
    outputs.dir(generatedDir)
    
    // Ensure output directory exists using Gradle file utilities
    doFirst {
        project.mkdir(generatedDir)
    }
}

// Ensure all Kotlin compilation tasks depend on code generation
// This includes compileKotlin*, compileDebugKotlin*, compileReleaseKotlin* variants
tasks.matching { task ->
    task.name.matches(Regex(".*[Cc]ompile.*[Kk]otlin.*"))
}.configureEach {
    dependsOn(generateFishitContract)
}
