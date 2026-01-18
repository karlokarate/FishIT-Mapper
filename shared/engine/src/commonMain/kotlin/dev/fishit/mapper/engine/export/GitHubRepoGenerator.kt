package dev.fishit.mapper.engine.export

import dev.fishit.mapper.engine.api.*

/**
 * Generiert ein vollständiges GitHub Repository Template.
 *
 * Das Template enthält:
 * - Gradle Build-Konfiguration für KMP
 * - Generierte API-Client-Stubs
 * - GitHub Actions für Auto-Regeneration
 * - Copilot-optimierte Dokumentation
 *
 * ## Verwendung
 * ```kotlin
 * val generator = GitHubRepoGenerator()
 * val files = generator.generate(blueprint, exchanges)
 * // files enthält alle Dateien für das Repo
 * ```
 *
 * ## Output-Struktur
 * ```
 * my-api-client/
 * ├── .github/
 * │   └── workflows/
 * │       └── regenerate.yml
 * ├── shared/
 * │   └── src/commonMain/kotlin/
 * │       ├── ApiClient.kt
 * │       └── Models.kt
 * ├── traffic/
 * │   ├── traffic.har
 * │   └── analysis.json
 * ├── build.gradle.kts
 * ├── settings.gradle.kts
 * └── README.md
 * ```
 */
class GitHubRepoGenerator {

    data class RepoFile(
        val path: String,
        val content: String
    )

    /**
     * Generiert alle Dateien für ein GitHub Repository.
     */
    fun generate(
        blueprint: ApiBlueprint,
        packageName: String = "dev.example.api"
    ): List<RepoFile> {
        val files = mutableListOf<RepoFile>()

        // Root files
        files.add(generateSettingsGradle(blueprint.name))
        files.add(generateRootBuildGradle())
        files.add(generateReadme(blueprint))
        files.add(generateGitignore())

        // Shared module
        files.add(generateSharedBuildGradle(packageName))
        files.add(generateApiClient(blueprint, packageName))
        files.add(generateModels(blueprint, packageName))

        // GitHub Actions
        files.add(generateGitHubAction())

        // Copilot config
        files.add(generateCopilotInstructions(blueprint))

        return files
    }

    private fun generateSettingsGradle(projectName: String): RepoFile {
        val safeName = projectName.replace(Regex("[^a-zA-Z0-9]"), "-").lowercase()
        return RepoFile(
            path = "settings.gradle.kts",
            content = """
                |rootProject.name = "$safeName-api-client"
                |
                |include(":shared")
            """.trimMargin()
        )
    }

    private fun generateRootBuildGradle(): RepoFile {
        return RepoFile(
            path = "build.gradle.kts",
            content = """
                |plugins {
                |    kotlin("multiplatform") version "1.9.22" apply false
                |    kotlin("plugin.serialization") version "1.9.22" apply false
                |}
                |
                |allprojects {
                |    repositories {
                |        mavenCentral()
                |    }
                |}
            """.trimMargin()
        )
    }

    private fun generateSharedBuildGradle(packageName: String): RepoFile {
        return RepoFile(
            path = "shared/build.gradle.kts",
            content = """
                |plugins {
                |    kotlin("multiplatform")
                |    kotlin("plugin.serialization")
                |}
                |
                |kotlin {
                |    jvm()
                |
                |    // Uncomment for other targets:
                |    // iosArm64()
                |    // iosSimulatorArm64()
                |    // js(IR) { browser(); nodejs() }
                |
                |    sourceSets {
                |        commonMain.dependencies {
                |            implementation("io.ktor:ktor-client-core:2.3.7")
                |            implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
                |            implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
                |            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
                |            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                |        }
                |
                |        commonTest.dependencies {
                |            implementation(kotlin("test"))
                |            implementation("io.ktor:ktor-client-mock:2.3.7")
                |        }
                |
                |        jvmMain.dependencies {
                |            implementation("io.ktor:ktor-client-okhttp:2.3.7")
                |        }
                |    }
                |}
            """.trimMargin()
        )
    }

    private fun generateApiClient(blueprint: ApiBlueprint, packageName: String): RepoFile {
        val className = blueprint.name.replace(Regex("[^a-zA-Z0-9]"), "") + "Api"

        val content = buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import io.ktor.client.*")
            appendLine("import io.ktor.client.call.*")
            appendLine("import io.ktor.client.plugins.contentnegotiation.*")
            appendLine("import io.ktor.client.request.*")
            appendLine("import io.ktor.http.*")
            appendLine("import io.ktor.serialization.kotlinx.json.*")
            appendLine("import kotlinx.serialization.json.Json")
            appendLine()
            appendLine("/**")
            appendLine(" * API Client für ${blueprint.name}")
            appendLine(" * ")
            appendLine(" * Auto-generiert von FishIT-Mapper")
            appendLine(" * Base URL: ${blueprint.baseUrl}")
            appendLine(" * Endpoints: ${blueprint.endpoints.size}")
            appendLine(" */")
            appendLine("class $className(")
            appendLine("    private val baseUrl: String = \"${blueprint.baseUrl}\",")
            appendLine("    private val httpClient: HttpClient = defaultHttpClient()")
            appendLine(") {")
            appendLine()

            // Auth token property
            if (blueprint.authPatterns.any { it is AuthPattern.BearerTokenPattern }) {
                appendLine("    var authToken: String? = null")
                appendLine()
            }

            // Generate methods for each endpoint
            for (endpoint in blueprint.endpoints) {
                appendLine(generateEndpointMethod(endpoint))
                appendLine()
            }

            // Private helper
            appendLine("    companion object {")
            appendLine("        fun defaultHttpClient() = HttpClient {")
            appendLine("            install(ContentNegotiation) {")
            appendLine("                json(Json {")
            appendLine("                    ignoreUnknownKeys = true")
            appendLine("                    isLenient = true")
            appendLine("                })")
            appendLine("            }")
            appendLine("        }")
            appendLine("    }")
            appendLine("}")
        }

        return RepoFile(
            path = "shared/src/commonMain/kotlin/${packageName.replace('.', '/')}/ApiClient.kt",
            content = content
        )
    }

    private fun generateEndpointMethod(endpoint: ApiEndpoint): String {
        val methodName = endpoint.id
            .replace(Regex("[^a-zA-Z0-9_]"), "_")
            .split("_")
            .mapIndexed { i, s -> if (i == 0) s.lowercase() else s.replaceFirstChar { it.uppercase() } }
            .joinToString("")

        val params = mutableListOf<String>()

        // Path parameters
        for (param in endpoint.pathParameters) {
            params.add("${param.name}: ${kotlinType(param.type)}")
        }

        // Query parameters
        for (param in endpoint.queryParameters) {
            val nullable = if (!param.required) "?" else ""
            val default = if (!param.required) " = null" else ""
            params.add("${param.name}: ${kotlinType(param.type)}$nullable$default")
        }

        // Body parameter
        if (endpoint.requestBody != null) {
            params.add("body: Any")
        }

        // Build path with substitutions
        var pathExpr = "\"${endpoint.pathTemplate}\""
        for (param in endpoint.pathParameters) {
            pathExpr = pathExpr.replace("{${param.name}}", "\$${param.name}")
        }

        return buildString {
            appendLine("    /**")
            appendLine("     * ${endpoint.method.name} ${endpoint.pathTemplate}")
            endpoint.metadata.description?.let { appendLine("     * $it") }
            appendLine("     */")
            appendLine("    suspend fun $methodName(${params.joinToString(", ")}): HttpResponse {")
            appendLine("        return httpClient.request(\"\$baseUrl$pathExpr\") {")
            appendLine("            method = HttpMethod.${endpoint.method.name}")

            // Add auth header if needed
            if (endpoint.authRequired != AuthType.None) {
                appendLine("            authToken?.let { headers[\"Authorization\"] = \"Bearer \$it\" }")
            }

            // Add query parameters
            for (param in endpoint.queryParameters) {
                if (param.required) {
                    appendLine("            parameter(\"${param.name}\", ${param.name})")
                } else {
                    appendLine("            ${param.name}?.let { parameter(\"${param.name}\", it) }")
                }
            }

            // Add body
            if (endpoint.requestBody != null) {
                appendLine("            contentType(ContentType.Application.Json)")
                appendLine("            setBody(body)")
            }

            appendLine("        }")
            append("    }")
        }
    }

    private fun generateModels(blueprint: ApiBlueprint, packageName: String): RepoFile {
        val content = buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import kotlinx.serialization.Serializable")
            appendLine("import kotlinx.serialization.SerialName")
            appendLine()
            appendLine("/**")
            appendLine(" * Data Models für ${blueprint.name}")
            appendLine(" * ")
            appendLine(" * Auto-generiert von FishIT-Mapper")
            appendLine(" * ")
            appendLine(" * TODO: Diese Models wurden aus Response-Beispielen inferiert.")
            appendLine(" * Nutze GitHub Copilot um sie zu vervollständigen:")
            appendLine(" * @workspace Vervollständige die Data Classes basierend auf traffic.har")
            appendLine(" */")
            appendLine()

            // Generate placeholder models based on endpoints
            val modelNames = mutableSetOf<String>()

            for (endpoint in blueprint.endpoints) {
                // Request model
                if (endpoint.requestBody != null) {
                    val modelName = "${endpoint.id.toPascalCase()}Request"
                    if (modelName !in modelNames) {
                        modelNames.add(modelName)
                        appendLine("@Serializable")
                        appendLine("data class $modelName(")
                        appendLine("    // TODO: Felder aus Request-Body extrahieren")
                        appendLine("    val placeholder: String? = null")
                        appendLine(")")
                        appendLine()
                    }
                }

                // Response models
                for (response in endpoint.responses.filter { it.statusCode in 200..299 }) {
                    val modelName = "${endpoint.id.toPascalCase()}Response"
                    if (modelName !in modelNames) {
                        modelNames.add(modelName)
                        appendLine("@Serializable")
                        appendLine("data class $modelName(")
                        appendLine("    // TODO: Felder aus Response-Body extrahieren")
                        appendLine("    val placeholder: String? = null")
                        appendLine(")")
                        appendLine()
                    }
                }
            }

            // Common models
            appendLine("// Common error response")
            appendLine("@Serializable")
            appendLine("data class ApiError(")
            appendLine("    val message: String? = null,")
            appendLine("    val code: String? = null,")
            appendLine("    val details: Map<String, String>? = null")
            appendLine(")")
        }

        return RepoFile(
            path = "shared/src/commonMain/kotlin/${packageName.replace('.', '/')}/Models.kt",
            content = content
        )
    }

    private fun generateGitHubAction(): RepoFile {
        return RepoFile(
            path = ".github/workflows/regenerate.yml",
            content = """
                |name: Regenerate API Client
                |
                |on:
                |  push:
                |    paths:
                |      - 'traffic/**'
                |  workflow_dispatch:
                |
                |jobs:
                |  regenerate:
                |    runs-on: ubuntu-latest
                |
                |    steps:
                |      - uses: actions/checkout@v4
                |
                |      - name: Set up JDK 17
                |        uses: actions/setup-java@v4
                |        with:
                |          java-version: '17'
                |          distribution: 'temurin'
                |
                |      - name: Analyze Traffic with Copilot
                |        uses: actions/github-script@v7
                |        with:
                |          script: |
                |            // Hier könnte ein Custom-Script für Traffic-Analyse laufen
                |            // Oder ein Call an Copilot API
                |            console.log('Traffic analysis would run here')
                |
                |      - name: Build and Test
                |        run: ./gradlew build
                |
                |      - name: Commit changes
                |        run: |
                |          git config --local user.email "action@github.com"
                |          git config --local user.name "GitHub Action"
                |          git add -A
                |          git diff --staged --quiet || git commit -m "chore: regenerate API client"
                |          git push
            """.trimMargin()
        )
    }

    private fun generateCopilotInstructions(blueprint: ApiBlueprint): RepoFile {
        return RepoFile(
            path = ".github/copilot-instructions.md",
            content = """
                |# Copilot Instructions für ${blueprint.name} API Client
                |
                |## Kontext
                |Dieses Repository enthält einen auto-generierten API Client für:
                |- Base URL: ${blueprint.baseUrl}
                |- ${blueprint.endpoints.size} Endpoints
                |- ${blueprint.authPatterns.size} Auth Patterns
                |
                |## Traffic-Daten
                |Die Datei `traffic/traffic.har` enthält echte HTTP-Exchanges.
                |Nutze diese für:
                |- Vervollständigung von Data Classes
                |- Erkennung von Feldtypen
                |- Generierung von Tests
                |
                |## Bevorzugte Patterns
                |- Kotlin Multiplatform (KMP)
                |- Ktor Client
                |- kotlinx.serialization
                |- Coroutines für async
                |
                |## Aufgaben
                |Wenn der User nach Code-Generierung fragt:
                |1. Lies `traffic/traffic.har` für echte Beispiele
                |2. Analysiere Request/Response Bodies
                |3. Generiere typsichere Data Classes
                |4. Aktualisiere `Models.kt` mit echten Feldern
            """.trimMargin()
        )
    }

    private fun generateReadme(blueprint: ApiBlueprint): RepoFile {
        return RepoFile(
            path = "README.md",
            content = """
                |# ${blueprint.name} API Client
                |
                |Auto-generierter Kotlin Multiplatform API Client.
                |
                |## 📦 Installation
                |
                |```kotlin
                |// settings.gradle.kts
                |dependencyResolutionManagement {
                |    repositories {
                |        maven("https://jitpack.io")
                |    }
                |}
                |
                |// build.gradle.kts
                |dependencies {
                |    implementation("com.github.USER:REPO:VERSION")
                |}
                |```
                |
                |## 🚀 Quick Start
                |
                |```kotlin
                |val api = ${blueprint.name.replace(Regex("[^a-zA-Z0-9]"), "")}Api()
                |
                |// Optional: Auth setzen
                |api.authToken = "your-token"
                |
                |// API aufrufen
                |val response = api.someEndpoint(param = "value")
                |```
                |
                |## 📡 Endpoints
                |
                |${blueprint.endpoints.take(10).joinToString("\n") { "- `${it.method.name}` ${it.pathTemplate}" }}
                |${if (blueprint.endpoints.size > 10) "\n... und ${blueprint.endpoints.size - 10} weitere" else ""}
                |
                |## 🔄 Regenerieren
                |
                |1. Neue Traffic-Daten in `traffic/` ablegen
                |2. Push zu GitHub
                |3. GitHub Action regeneriert den Client automatisch
                |
                |## 📖 Dokumentation
                |
                |Siehe [API_ANALYSIS.md](./API_ANALYSIS.md) für detaillierte Endpoint-Dokumentation.
                |
                |---
                |
                |*Generiert mit [FishIT-Mapper](https://github.com/karlokarate/FishIT-Mapper)*
            """.trimMargin()
        )
    }

    private fun generateGitignore(): RepoFile {
        return RepoFile(
            path = ".gitignore",
            content = """
                |# Gradle
                |.gradle/
                |build/
                |
                |# IDE
                |.idea/
                |*.iml
                |.vscode/
                |
                |# Kotlin
                |*.class
                |
                |# OS
                |.DS_Store
                |Thumbs.db
            """.trimMargin()
        )
    }

    private fun kotlinType(type: ParameterType): String {
        return when (type) {
            ParameterType.INTEGER -> "Int"
            ParameterType.NUMBER -> "Double"
            ParameterType.BOOLEAN -> "Boolean"
            ParameterType.ARRAY -> "List<Any>"
            ParameterType.OBJECT -> "Map<String, Any>"
            else -> "String"
        }
    }

    private fun String.toPascalCase(): String {
        return this.split(Regex("[^a-zA-Z0-9]"))
            .filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
    }
}
