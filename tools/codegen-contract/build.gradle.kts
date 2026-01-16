plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("dev.fishit.mapper.codegen.MainKt")
}
