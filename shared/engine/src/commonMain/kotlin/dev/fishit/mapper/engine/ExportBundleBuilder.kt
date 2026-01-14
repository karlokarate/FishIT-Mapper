package dev.fishit.mapper.engine

import dev.fishit.mapper.contract.*
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString

data class ExportFile(
    val path: String,
    val bytes: ByteArray
)

data class ExportBundle(
    val manifest: ExportManifest,
    val files: List<ExportFile>
)

/**
 * Builds a portable export bundle (zip-ready).
 *
 * Android writes the zip. The engine only produces paths + bytes.
 */
object ExportBundleBuilder {

    fun build(
        project: ProjectMeta,
        graph: MapGraph,
        chains: ChainsFile,
        sessions: List<RecordingSession>
    ): ExportBundle {
        val sessionFiles = sessions.map { "sessions/${'$'}{it.id.value}.json" }

        val manifest = ExportManifest(
            appName = ContractInfo.APP_NAME,
            bundleFormatVersion = ContractInfo.BUNDLE_FORMAT_VERSION,
            contractVersion = ContractInfo.CONTRACT_VERSION,
            createdAt = Clock.System.now(),
            project = project,
            sessionFiles = sessionFiles
        )

        val files = buildList {
            add(ExportFile("manifest.json", FishitJson.encodeToString(ExportManifest.serializer(), manifest).encodeToByteArray()))
            add(ExportFile("graph.json", FishitJson.encodeToString(MapGraph.serializer(), graph).encodeToByteArray()))
            add(ExportFile("chains.json", FishitJson.encodeToString(ChainsFile.serializer(), chains).encodeToByteArray()))
            sessions.forEach { session ->
                val path = "sessions/${'$'}{session.id.value}.json"
                val bytes = FishitJson.encodeToString(RecordingSession.serializer(), session).encodeToByteArray()
                add(ExportFile(path, bytes))
            }
            add(
                ExportFile(
                    "README.txt",
                    buildReadme(project, sessions.size, graph.nodes.size, graph.edges.size).encodeToByteArray()
                )
            )
        }

        return ExportBundle(manifest = manifest, files = files)
    }

    private fun buildReadme(project: ProjectMeta, sessions: Int, nodes: Int, edges: Int): String {
        return """FishIT-Mapper Export Bundle
        |
        |Project: ${'$'}{project.name} (${ '$'}{project.id.value})
        |Start URL: ${'$'}{project.startUrl}
        |
        |Contains:
        |- graph.json (nodes=${'$'}nodes, edges=${'$'}edges)
        |- chains.json
        |- sessions/ (${ '$'}sessions files)
        |- manifest.json
        |
        |Contract version: ${'$'}{ContractInfo.CONTRACT_VERSION}
        |Bundle format version: ${'$'}{ContractInfo.BUNDLE_FORMAT_VERSION}
        |
        |Notes:
        |- This is an MVP export format. It's intentionally simple and deterministic.
        |- Sensitive data is NOT automatically redacted yet. Be careful when sharing bundles.
        """.trimMargin()
    }
}
