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
            kotlin.srcDir(layout.buildDirectory.dir("generated/source/fishitContract/commonMain/kotlin"))

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
}

// --- KotlinPoet contract generation wiring ---
val generatedDir = layout.buildDirectory.dir("generated/source/fishitContract/commonMain/kotlin")

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
        "--out", generatedDir.get().asFile.absolutePath
    )
}

tasks.matching { it.name.startsWith("compileKotlin") }.configureEach {
    dependsOn(generateFishitContract)
}
