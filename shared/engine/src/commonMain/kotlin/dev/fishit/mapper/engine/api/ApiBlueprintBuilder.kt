package dev.fishit.mapper.engine.api

import dev.fishit.mapper.android.import.httpcanary.CapturedExchange
import dev.fishit.mapper.android.import.httpcanary.CorrelatedAction
import dev.fishit.mapper.android.import.httpcanary.WebsiteMap
import kotlinx.datetime.Clock

/**
 * Baut einen vollständigen API Blueprint aus Traffic-Daten.
 *
 * Der Builder orchestriert die verschiedenen Analyzer-Komponenten:
 * - EndpointExtractor: Extrahiert API-Endpoints
 * - ParameterAnalyzer: Analysiert Parameter-Typen
 * - AuthPatternDetector: Erkennt Auth-Patterns
 * - FlowDetector: Erkennt API-Flows
 *
 * ## Verwendung
 * ```kotlin
 * val builder = ApiBlueprintBuilder()
 *
 * // Minimal: Nur aus Exchanges
 * val blueprint = builder.build(
 *     projectId = "my-project",
 *     projectName = "My API",
 *     exchanges = capturedExchanges
 * )
 *
 * // Mit Korrelation: Aus WebsiteMap
 * val blueprintWithFlows = builder.buildFromWebsiteMap(
 *     projectId = "my-project",
 *     projectName = "My API",
 *     websiteMap = correlatedMap,
 *     exchanges = capturedExchanges
 * )
 * ```
 */
class ApiBlueprintBuilder {

    private val endpointExtractor = EndpointExtractor()
    private val authPatternDetector = AuthPatternDetector()
    private val flowDetector = FlowDetector()

    private var blueprintIdCounter = 0

    /**
     * Baut einen API Blueprint aus HTTP-Exchanges.
     *
     * @param projectId Projekt-ID für den Blueprint
     * @param projectName Name des Projekts/der API
     * @param exchanges Liste der HTTP-Exchanges
     * @param filterApiOnly Nur API-Requests berücksichtigen (Standard: true)
     * @return Vollständiger API Blueprint
     */
    fun build(
        projectId: String,
        projectName: String,
        exchanges: List<CapturedExchange>,
        filterApiOnly: Boolean = true
    ): ApiBlueprint {
        val now = Clock.System.now()

        // 1. Extrahiere Endpoints
        val endpoints = endpointExtractor.extract(exchanges, filterApiOnly)

        // 2. Erkenne Auth-Patterns
        val authPatterns = authPatternDetector.detect(exchanges)

        // 3. Erkenne Base-URL
        val baseUrl = inferBaseUrl(exchanges)

        // 4. Berechne Metadata
        val metadata = BlueprintMetadata(
            totalExchangesAnalyzed = exchanges.size,
            uniqueEndpointsDetected = endpoints.size,
            authPatternsDetected = authPatterns.size,
            flowsDetected = 0,
            coveragePercent = calculateCoverage(endpoints, exchanges)
        )

        return ApiBlueprint(
            id = "blueprint_${blueprintIdCounter++}",
            projectId = projectId,
            name = projectName,
            description = "API Blueprint generiert aus ${ exchanges.size } HTTP-Exchanges",
            baseUrl = baseUrl,
            endpoints = endpoints,
            authPatterns = authPatterns,
            flows = emptyList(), // Keine Flows ohne Korrelation
            metadata = metadata,
            createdAt = now,
            updatedAt = now
        )
    }

    /**
     * Baut einen API Blueprint mit Flow-Detection aus einer WebsiteMap.
     *
     * @param projectId Projekt-ID
     * @param projectName Name des Projekts/der API
     * @param websiteMap Korrelierte WebsiteMap
     * @param exchanges Alle HTTP-Exchanges
     * @return Vollständiger API Blueprint mit Flows
     */
    fun buildFromWebsiteMap(
        projectId: String,
        projectName: String,
        websiteMap: WebsiteMap,
        exchanges: List<CapturedExchange>
    ): ApiBlueprint {
        val now = Clock.System.now()

        // 1. Extrahiere Endpoints
        val endpoints = endpointExtractor.extract(exchanges)

        // 2. Erkenne Auth-Patterns
        val authPatterns = authPatternDetector.detect(exchanges)

        // 3. Erkenne Flows aus korrelierten Actions
        val flows = flowDetector.detect(
            correlatedActions = websiteMap.actions,
            exchanges = exchanges,
            endpoints = endpoints
        )

        // 4. Erkenne Base-URL
        val baseUrl = inferBaseUrl(exchanges)

        // 5. Berechne Metadata
        val metadata = BlueprintMetadata(
            totalExchangesAnalyzed = exchanges.size,
            uniqueEndpointsDetected = endpoints.size,
            authPatternsDetected = authPatterns.size,
            flowsDetected = flows.size,
            coveragePercent = calculateCoverage(endpoints, exchanges)
        )

        return ApiBlueprint(
            id = "blueprint_${blueprintIdCounter++}",
            projectId = projectId,
            name = projectName,
            description = buildDescription(websiteMap, exchanges, flows),
            baseUrl = baseUrl,
            endpoints = endpoints,
            authPatterns = authPatterns,
            flows = flows,
            metadata = metadata,
            createdAt = now,
            updatedAt = now
        )
    }

    /**
     * Aktualisiert einen bestehenden Blueprint mit neuen Exchanges.
     *
     * Merged neue Endpoints und Patterns ohne bestehende zu überschreiben.
     *
     * @param existing Bestehender Blueprint
     * @param newExchanges Neue HTTP-Exchanges
     * @param newWebsiteMap Optionale neue WebsiteMap für Flow-Updates
     * @return Aktualisierter Blueprint
     */
    fun merge(
        existing: ApiBlueprint,
        newExchanges: List<CapturedExchange>,
        newWebsiteMap: WebsiteMap? = null
    ): ApiBlueprint {
        val now = Clock.System.now()

        // Extrahiere neue Endpoints
        val newEndpoints = endpointExtractor.extract(newExchanges)

        // Merge Endpoints (neue hinzufügen, bestehende updaten)
        val mergedEndpoints = mergeEndpoints(existing.endpoints, newEndpoints)

        // Merge Auth-Patterns
        val newAuthPatterns = authPatternDetector.detect(newExchanges)
        val mergedAuthPatterns = mergeAuthPatterns(existing.authPatterns, newAuthPatterns)

        // Merge Flows (wenn WebsiteMap vorhanden)
        val mergedFlows = if (newWebsiteMap != null) {
            val newFlows = flowDetector.detect(
                correlatedActions = newWebsiteMap.actions,
                exchanges = newExchanges,
                endpoints = mergedEndpoints
            )
            mergeFlows(existing.flows, newFlows)
        } else {
            existing.flows
        }

        // Update Metadata
        val metadata = BlueprintMetadata(
            totalExchangesAnalyzed = existing.metadata.totalExchangesAnalyzed + newExchanges.size,
            uniqueEndpointsDetected = mergedEndpoints.size,
            authPatternsDetected = mergedAuthPatterns.size,
            flowsDetected = mergedFlows.size,
            coveragePercent = calculateCoverage(mergedEndpoints, newExchanges)
        )

        return existing.copy(
            endpoints = mergedEndpoints,
            authPatterns = mergedAuthPatterns,
            flows = mergedFlows,
            metadata = metadata,
            updatedAt = now
        )
    }

    /**
     * Inferiert die Base-URL aus den Exchanges.
     */
    private fun inferBaseUrl(exchanges: List<CapturedExchange>): String {
        if (exchanges.isEmpty()) return ""

        // Zähle Hosts
        val hostCounts = exchanges
            .mapNotNull { exchange ->
                try {
                    val url = java.net.URL(exchange.request.url)
                    "${url.protocol}://${url.host}"
                } catch (e: Exception) {
                    null
                }
            }
            .groupingBy { it }
            .eachCount()

        // Wähle häufigsten Host
        return hostCounts.maxByOrNull { it.value }?.key ?: ""
    }

    /**
     * Berechnet die Coverage (wie viel % der Exchanges zu Endpoints matchen).
     */
    private fun calculateCoverage(endpoints: List<ApiEndpoint>, exchanges: List<CapturedExchange>): Float {
        if (exchanges.isEmpty()) return 0f

        val coveredExchanges = endpoints.flatMap { it.examples }.toSet()
        return (coveredExchanges.size.toFloat() / exchanges.size) * 100f
    }

    /**
     * Baut eine Beschreibung für den Blueprint.
     */
    private fun buildDescription(
        websiteMap: WebsiteMap,
        exchanges: List<CapturedExchange>,
        flows: List<ApiFlow>
    ): String {
        return buildString {
            append("API Blueprint generiert aus ${exchanges.size} HTTP-Exchanges")
            append(" und ${websiteMap.actions.size} User Actions.")
            if (flows.isNotEmpty()) {
                append(" ${flows.size} API-Flows erkannt.")
            }
        }
    }

    /**
     * Merged zwei Listen von Endpoints.
     */
    private fun mergeEndpoints(
        existing: List<ApiEndpoint>,
        new: List<ApiEndpoint>
    ): List<ApiEndpoint> {
        val merged = existing.toMutableList()
        val existingIds = existing.map { it.id }.toSet()

        for (newEndpoint in new) {
            val existingIndex = merged.indexOfFirst { it.id == newEndpoint.id }

            if (existingIndex >= 0) {
                // Update bestehenden Endpoint
                val old = merged[existingIndex]
                merged[existingIndex] = old.copy(
                    examples = (old.examples + newEndpoint.examples).distinct(),
                    metadata = EndpointMetadata(
                        hitCount = old.metadata.hitCount + newEndpoint.metadata.hitCount,
                        firstSeen = minOf(old.metadata.firstSeen, newEndpoint.metadata.firstSeen),
                        lastSeen = maxOf(old.metadata.lastSeen, newEndpoint.metadata.lastSeen),
                        avgResponseTimeMs = listOfNotNull(
                            old.metadata.avgResponseTimeMs,
                            newEndpoint.metadata.avgResponseTimeMs
                        ).average().toLong().takeIf { it > 0 }
                    )
                )
            } else {
                // Neuen Endpoint hinzufügen
                merged.add(newEndpoint)
            }
        }

        return merged
    }

    /**
     * Merged zwei Listen von Auth-Patterns.
     */
    private fun mergeAuthPatterns(
        existing: List<AuthPattern>,
        new: List<AuthPattern>
    ): List<AuthPattern> {
        val existingTypes = existing.map { it.type }.toSet()
        val merged = existing.toMutableList()

        for (newPattern in new) {
            if (newPattern.type !in existingTypes) {
                merged.add(newPattern)
            }
        }

        return merged
    }

    /**
     * Merged zwei Listen von Flows.
     */
    private fun mergeFlows(
        existing: List<ApiFlow>,
        new: List<ApiFlow>
    ): List<ApiFlow> {
        val merged = existing.toMutableList()
        val existingNames = existing.map { it.name }.toSet()

        for (newFlow in new) {
            if (newFlow.name !in existingNames) {
                merged.add(newFlow)
            }
        }

        return merged
    }
}

/**
 * Konfiguration für den Blueprint-Builder.
 */
data class BlueprintBuilderConfig(
    val filterApiOnly: Boolean = true,
    val minEndpointHits: Int = 1,
    val minFlowSteps: Int = 2,
    val maxExamplesPerEndpoint: Int = 5,
    val inferParameterTypes: Boolean = true,
    val detectAuthPatterns: Boolean = true,
    val detectFlows: Boolean = true
)
