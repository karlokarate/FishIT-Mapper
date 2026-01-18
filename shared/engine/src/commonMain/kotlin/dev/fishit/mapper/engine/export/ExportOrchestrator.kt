package dev.fishit.mapper.engine.export

import dev.fishit.mapper.engine.api.ApiBlueprint
import dev.fishit.mapper.engine.bundle.HttpExchange

/**
 * Zentrale Export-Orchestrierung für alle Ausgabeformate.
 *
 * ## Empfohlener Workflow
 *
 * ### 1. Für direkte Codespace-Nutzung
 * ```kotlin
 * val outputs = ExportOrchestrator.exportForCodespace(blueprint, exchanges)
 * // Enthält: HAR + Copilot-Markdown + GitHub Repo Template
 * ```
 *
 * ### 2. Für Tool-Integration
 * ```kotlin
 * val har = ExportOrchestrator.exportToHar(exchanges)
 * // Import in Postman, Insomnia, etc.
 * ```
 *
 * ### 3. Für Code-Generierung
 * ```kotlin
 * val code = ExportOrchestrator.exportToCode(blueprint)
 * // Kotlin + TypeScript Clients
 * ```
 *
 * ## Output-Priorität (nach Usefulness)
 *
 * 1. **GitHub Repo Template** (★★★★★)
 *    - Sofort nutzbar: `git clone` → `./gradlew build`
 *    - Enthält CI/CD, Copilot-Config, generierte Clients
 *
 * 2. **HAR File** (★★★★☆)
 *    - Standard-Format für alle HTTP-Tools
 *    - Copilot versteht es nativ
 *    - Import in Postman/Insomnia
 *
 * 3. **Copilot-Ready Markdown** (★★★★☆)
 *    - Direkt in Repo lesbar
 *    - Copilot kann daraus Code generieren
 *    - Enthält TODO-Listen und Prompts
 *
 * 4. **OpenAPI Spec** (★★★☆☆)
 *    - Für formale API-Dokumentation
 *    - Code-Generatoren (openapi-generator)
 *
 * 5. **Raw Code** (★★★☆☆)
 *    - Kotlin/TypeScript Clients
 *    - Copy-paste ready
 */
object ExportOrchestrator {

    /**
     * Ausgabe-Bundle für einen vollständigen Export.
     */
    data class ExportBundle(
        val name: String,
        val files: Map<String, String>,
        val summary: String
    )

    /**
     * Export optimiert für GitHub Codespace Nutzung.
     *
     * Enthält alles was nötig ist um sofort loszulegen:
     * - Vollständiges Repo-Template (Gradle, CI/CD, etc.)
     * - HAR-Datei mit echtem Traffic
     * - Copilot-optimierte Dokumentation
     * - Generierte API-Clients
     *
     * @param blueprint Das analysierte API Blueprint
     * @param exchanges Die originalen HTTP-Exchanges
     * @param packageName Package-Name für generierten Code
     * @return Bundle mit allen Dateien
     */
    fun exportForCodespace(
        blueprint: ApiBlueprint,
        exchanges: List<HttpExchange>,
        packageName: String = "dev.example.api"
    ): ExportBundle {
        val files = mutableMapOf<String, String>()

        // 1. GitHub Repo Template
        val repoGenerator = GitHubRepoGenerator()
        val repoFiles = repoGenerator.generate(blueprint, packageName)
        for (file in repoFiles) {
            files[file.path] = file.content
        }

        // 2. HAR File mit echtem Traffic
        val harExporter = HarExporter()
        files["traffic/traffic.har"] = harExporter.export(exchanges)

        // 3. Copilot-Ready Dokumentation
        val copilotExporter = CopilotReadyExporter()
        files["API_ANALYSIS.md"] = copilotExporter.export(blueprint)

        // 4. OpenAPI für formale Doku
        val apiExporter = ApiExporter()
        files["openapi.yaml"] = apiExporter.toOpenApi(blueprint)

        // 5. Analysis JSON für weitere Verarbeitung
        files["traffic/analysis.json"] = apiExporter.toJson(blueprint)

        return ExportBundle(
            name = "${blueprint.name.sanitize()}-api-client",
            files = files,
            summary = buildSummary(blueprint, files)
        )
    }

    /**
     * Export nur als HAR-Datei.
     *
     * Ideal für:
     * - Import in Postman/Insomnia
     * - Chrome DevTools Analyse
     * - Copilot "read this HAR" Prompts
     */
    fun exportToHar(exchanges: List<HttpExchange>): String {
        return HarExporter().export(exchanges)
    }

    /**
     * Export als Copilot-Ready Markdown.
     *
     * Ideal für:
     * - README.md Erweiterung
     * - Copilot Workspace Prompts
     * - Team-Dokumentation
     */
    fun exportToCopilotMarkdown(blueprint: ApiBlueprint): String {
        return CopilotReadyExporter().export(blueprint)
    }

    /**
     * Export als generierter Kotlin Code.
     */
    fun exportToKotlin(
        blueprint: ApiBlueprint,
        packageName: String = "dev.example.api"
    ): String {
        return ApiExporter().toKotlin(blueprint, packageName)
    }

    /**
     * Export als generierter TypeScript Code.
     */
    fun exportToTypeScript(blueprint: ApiBlueprint): String {
        return ApiExporter().toTypeScript(blueprint)
    }

    /**
     * Export als OpenAPI Specification.
     */
    fun exportToOpenApi(blueprint: ApiBlueprint): String {
        return ApiExporter().toOpenApi(blueprint)
    }

    /**
     * Export als Postman Collection.
     */
    fun exportToPostman(blueprint: ApiBlueprint): String {
        return ApiExporter().toPostman(blueprint)
    }

    /**
     * Export als cURL Befehle.
     */
    fun exportToCurl(blueprint: ApiBlueprint): String {
        return ApiExporter().toCurl(blueprint)
    }

    /**
     * Alle Export-Formate auf einmal generieren.
     *
     * @return Map von Format-Name zu Inhalt
     */
    fun exportAll(
        blueprint: ApiBlueprint,
        exchanges: List<HttpExchange>,
        packageName: String = "dev.example.api"
    ): Map<String, String> {
        val apiExporter = ApiExporter()

        return mapOf(
            "traffic.har" to HarExporter().export(exchanges),
            "API_ANALYSIS.md" to CopilotReadyExporter().export(blueprint),
            "openapi.yaml" to apiExporter.toOpenApi(blueprint),
            "collection.postman.json" to apiExporter.toPostman(blueprint),
            "client.kt" to apiExporter.toKotlin(blueprint, packageName),
            "client.ts" to apiExporter.toTypeScript(blueprint),
            "curl-commands.sh" to apiExporter.toCurl(blueprint),
            "analysis.json" to apiExporter.toJson(blueprint)
        )
    }

    private fun buildSummary(blueprint: ApiBlueprint, files: Map<String, String>): String {
        return buildString {
            appendLine("# Export Summary")
            appendLine()
            appendLine("## API: ${blueprint.name}")
            appendLine("- Base URL: ${blueprint.baseUrl}")
            appendLine("- Endpoints: ${blueprint.endpoints.size}")
            appendLine("- Auth Patterns: ${blueprint.authPatterns.size}")
            appendLine("- Flows: ${blueprint.flows.size}")
            appendLine()
            appendLine("## Generated Files (${files.size})")
            for ((path, content) in files.entries.sortedBy { it.key }) {
                val size = content.length
                val sizeStr = when {
                    size > 1024 * 1024 -> "${size / 1024 / 1024}MB"
                    size > 1024 -> "${size / 1024}KB"
                    else -> "${size}B"
                }
                appendLine("- `$path` ($sizeStr)")
            }
            appendLine()
            appendLine("## Quick Start")
            appendLine("```bash")
            appendLine("# Repository klonen")
            appendLine("git clone <url>")
            appendLine("cd ${blueprint.name.sanitize()}-api-client")
            appendLine()
            appendLine("# Build")
            appendLine("./gradlew build")
            appendLine()
            appendLine("# In Codespace: Copilot fragen")
            appendLine("# @workspace Generiere fehlende Data Classes aus traffic.har")
            appendLine("```")
        }
    }

    private fun String.sanitize(): String {
        return this.replace(Regex("[^a-zA-Z0-9]"), "-").lowercase()
    }
}
