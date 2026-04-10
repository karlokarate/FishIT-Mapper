package dev.fishit.mapper.wave01.debug

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object RuntimeToolkitSourcePipelineExporter {
    data class ExportArtifacts(
        val sourcePipelineBundlePath: File,
        val siteRuntimeModelPath: File,
        val manifestPath: File,
        val sourcePluginBundleZipPath: File,
    )

    private data class RequestEvent(
        val eventId: String,
        val requestId: String,
        val tsUtc: String,
        val phaseId: String,
        val method: String,
        val url: String,
        val normalizedHost: String,
        val normalizedPath: String,
        val operation: String,
        val headers: Map<String, String>,
        val queryParamNames: Set<String>,
        val bodyFieldNames: Set<String>,
        val hostClass: String,
    )

    private data class ResponseEvent(
        val eventId: String,
        val requestId: String,
        val responseId: String,
        val tsUtc: String,
        val phaseId: String,
        val operation: String,
        val routeKind: String,
        val statusCode: Int,
        val mimeType: String,
        val url: String,
        val normalizedHost: String,
        val normalizedPath: String,
        val hostClass: String,
        val bodyPreview: String,
        val bodyRef: String,
    )

    private data class EndpointAggregate(
        val endpointId: String,
        var role: String,
        val method: String,
        val normalizedHost: String,
        val normalizedPath: String,
        val requestOperation: String,
        val phaseRelevance: MutableSet<String> = linkedSetOf(),
        val requestEventIds: MutableList<String> = mutableListOf(),
        val responseEventIds: MutableList<String> = mutableListOf(),
        val hostClassSignals: MutableSet<String> = linkedSetOf(),
        val responseMimeTypes: MutableSet<String> = linkedSetOf(),
        val requiredHeaders: MutableSet<String> = linkedSetOf(),
        val optionalHeaders: MutableSet<String> = linkedSetOf(),
        val requiredCookies: MutableSet<String> = linkedSetOf(),
        val optionalCookies: MutableSet<String> = linkedSetOf(),
        val requiredProvenanceInputs: MutableSet<String> = linkedSetOf(),
        val requiredQueryParams: MutableSet<String> = linkedSetOf(),
        val optionalQueryParams: MutableSet<String> = linkedSetOf(),
        val requiredBodyFields: MutableSet<String> = linkedSetOf(),
        val optionalBodyFields: MutableSet<String> = linkedSetOf(),
        var requestEvidenceCount: Int = 0,
        var responseOkCount: Int = 0,
        var requiredReferer: String? = null,
        var requiredOrigin: String? = null,
        var confidence: Double = 0.6,
    )

    private data class FieldEvidence(
        var valueTemplate: Any? = null,
        var sourceKind: String = "unknown",
        var sourceRef: String = "",
        var derivationKind: String = "missing",
        var confidence: Double = 0.0,
        val observedInRoles: MutableSet<String> = linkedSetOf(),
    )

    private data class EndpointTemplatePayload(
        val pathTemplate: String,
        val queryTemplate: JSONObject,
        val bodyTemplate: JSONObject,
        val variablePlaceholders: JSONArray,
    )

    private val fieldOrder = listOf(
        "title",
        "subtitle",
        "description",
        "poster",
        "backdrop",
        "logo",
        "canonicalId",
        "collectionId",
        "itemType",
        "playbackHint",
        "sectionName",
        "searchMapping",
        "detailMapping",
    )

    private val finalPhaseSet = setOf(
        "home_probe",
        "search_probe",
        "detail_probe",
        "playback_probe",
        "auth_probe",
        "replay_probe",
    )

    private val finalRoleSet = setOf(
        "home",
        "search",
        "detail",
        "playbackResolver",
        "playback_resolver",
        "auth",
        "refresh",
        "config",
        "helper",
    )

    private val roleExportCaps = linkedMapOf(
        "home" to 3,
        "search" to 3,
        "detail" to 3,
        "playbackResolver" to 3,
        "playback_resolver" to 3,
        "auth" to 3,
        "refresh" to 1,
        "config" to 2,
        "helper" to 2,
    )

    fun ensureSourcePipelineArtifacts(runtimeRoot: File, targetSiteHint: String = ""): ExportArtifacts {
        if (!runtimeRoot.exists()) {
            runtimeRoot.mkdirs()
        }
        val eventsFile = File(runtimeRoot, "events/runtime_events.jsonl")
        require(eventsFile.exists() && eventsFile.length() > 0L) {
            "runtime events missing: ${eventsFile.absolutePath}"
        }

        val requests = mutableListOf<RequestEvent>()
        val responses = mutableListOf<ResponseEvent>()
        val observedTimestamps = mutableListOf<String>()
        var targetSiteId = targetSiteHint.trim()

        eventsFile.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val root = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            val eventId = root.optString("event_id").ifBlank { "evt_${shortHash(line)}" }
            val eventType = root.optString("event_type")
            val tsUtc = root.optString("ts_utc").trim()
            val payload = root.optJSONObject("payload") ?: JSONObject()
            if (tsUtc.isNotBlank()) {
                observedTimestamps += tsUtc
            }

            if (targetSiteId.isBlank()) {
                val candidate = payload.optString("target_site_id").trim()
                if (candidate.isNotBlank()) targetSiteId = candidate
            }

            when (eventType) {
                "network_request_event" -> {
                    val requestId = payload.optString("request_id").ifBlank { eventId }
                    val method = payload.optString("method").ifBlank { "GET" }.uppercase(Locale.ROOT)
                    val url = payload.optString("url")
                    val normalized = normalizedUrl(url, payload)
                    val phaseId = normalizePhaseId(payload.optString("phase_id"))
                    val operation = payload.optString("request_operation")
                        .ifBlank { payload.optString("operation") }
                        .ifBlank { roleFromPhase(phaseId) }
                    val hostClass = payload.optString("host_class").ifBlank { "background_noise" }
                    val headers = toHeaderMap(payload.opt("headers"))
                    val queryParamNames = parseQueryParamNamesFromRequest(url = url, payload = payload)
                    val bodyFieldNames = parseRequestBodyFieldNames(payload)
                    requests += RequestEvent(
                        eventId = eventId,
                        requestId = requestId,
                        tsUtc = tsUtc,
                        phaseId = phaseId,
                        method = method,
                        url = url,
                        normalizedHost = normalized.first,
                        normalizedPath = normalized.second,
                        operation = operation,
                        headers = headers,
                        queryParamNames = queryParamNames,
                        bodyFieldNames = bodyFieldNames,
                        hostClass = hostClass,
                    )
                }

                "network_response_event" -> {
                    val requestId = payload.optString("request_id")
                    if (requestId.isBlank()) return@forEachLine
                    val responseId = payload.optString("response_id").ifBlank { eventId }
                    val phaseId = normalizePhaseId(payload.optString("phase_id"))
                    val operation = payload.optString("response_operation")
                        .ifBlank { payload.optString("request_operation") }
                        .ifBlank { payload.optString("operation") }
                    val statusCode = when {
                        payload.has("status_code") -> payload.optInt("status_code", 0)
                        payload.has("status") -> payload.optInt("status", 0)
                        else -> 0
                    }
                    val url = payload.optString("url")
                    val normalized = normalizedUrl(url, payload)
                    val bodyRef = payload.optString("body_ref")
                    responses += ResponseEvent(
                        eventId = eventId,
                        requestId = requestId,
                        responseId = responseId,
                        tsUtc = tsUtc,
                        phaseId = phaseId,
                        operation = operation,
                        routeKind = payload.optString("route_kind"),
                        statusCode = statusCode,
                        mimeType = payload.optString("mime_type").ifBlank { payload.optString("mime") },
                        url = url,
                        normalizedHost = normalized.first,
                        normalizedPath = normalized.second,
                        hostClass = payload.optString("host_class").ifBlank { "background_noise" },
                        bodyPreview = payload.optString("body_preview"),
                        bodyRef = bodyRef,
                    )
                }
            }
        }

        if (targetSiteId.isBlank()) {
            targetSiteId = inferTargetSiteId(requests)
        }
        val generatedAt = deterministicGeneratedAt(observedTimestamps)
        val safeTargetId = normalizeTargetSiteId(targetSiteId)

        val responsesByRequestId = responses.groupBy { it.requestId }
        val endpointById = linkedMapOf<String, EndpointAggregate>()

        requests.forEach { request ->
            val role = inferRole(request.phaseId, request.operation, request.normalizedPath, request.url)
            if (role == "helper") return@forEach
            val endpointId = "ep_${shortHash("$role|${request.method}|${request.normalizedHost}|${request.normalizedPath}")}".take(19)
            val endpoint = endpointById.getOrPut(endpointId) {
                EndpointAggregate(
                    endpointId = endpointId,
                    role = role,
                    method = request.method,
                    normalizedHost = request.normalizedHost,
                    normalizedPath = request.normalizedPath,
                    requestOperation = request.operation.ifBlank { role },
                )
            }
            endpoint.phaseRelevance += request.phaseId
            if (request.hostClass.isNotBlank()) {
                endpoint.hostClassSignals += request.hostClass
            }
            endpoint.requestEventIds += request.eventId

            val provenanceInputs = linkedSetOf<String>()
            provenanceInputs += inferProvenanceInputsFromHeaders(request.headers)
            provenanceInputs +=
                inferAuthProvenanceInputsFromRequest(
                    role = role,
                    operation = request.operation,
                    normalizedPath = request.normalizedPath,
                    queryParamNames = request.queryParamNames,
                    bodyFieldNames = request.bodyFieldNames,
                )
            endpoint.requiredProvenanceInputs += provenanceInputs

            val observedRequiredHeaders = linkedSetOf<String>()
            val observedCookieNames = linkedSetOf<String>()
            var observedOrigin: String? = null
            var observedReferer: String? = null
            request.headers.forEach { (name, value) ->
                val lowered = name.lowercase(Locale.ROOT)
                when {
                    lowered in setOf("authorization", "api-auth", "x-api-auth", "x-auth-token") -> observedRequiredHeaders += lowered
                    lowered == "cookie" -> observedCookieNames += parseCookieNames(value)
                    lowered == "origin" -> {
                        val origin = value.takeIf { it.isNotBlank() }
                        observedOrigin = origin
                    }
                    lowered == "referer" || lowered == "referrer" -> {
                        val referer = value.takeIf { it.isNotBlank() }
                        observedReferer = referer
                    }
                    lowered.isNotBlank() && lowered !in setOf("accept-encoding", "connection", "pragma", "cache-control") -> endpoint.optionalHeaders += lowered
                }
            }
            if (endpoint.requestEvidenceCount == 0) {
                endpoint.requiredHeaders += observedRequiredHeaders
                endpoint.requiredCookies += observedCookieNames
                endpoint.requiredOrigin = observedOrigin
                endpoint.requiredReferer = observedReferer
            } else {
                endpoint.requiredHeaders.retainAll(observedRequiredHeaders)
                endpoint.requiredCookies.retainAll(observedCookieNames)
                if (endpoint.requiredOrigin != null && observedOrigin != endpoint.requiredOrigin) {
                    endpoint.requiredOrigin = null
                }
                if (endpoint.requiredReferer != null && observedReferer != endpoint.requiredReferer) {
                    endpoint.requiredReferer = null
                }
            }
            endpoint.optionalCookies += observedCookieNames

            val queryNames = request.queryParamNames
            if (endpoint.requestEvidenceCount == 0) {
                endpoint.requiredQueryParams += queryNames
            } else {
                endpoint.requiredQueryParams.retainAll(queryNames)
            }
            endpoint.optionalQueryParams += queryNames

            val bodyFieldNames = request.bodyFieldNames
            if (endpoint.requestEvidenceCount == 0) {
                endpoint.requiredBodyFields += bodyFieldNames
            } else {
                endpoint.requiredBodyFields.retainAll(bodyFieldNames)
            }
            endpoint.optionalBodyFields += bodyFieldNames
            endpoint.requestEvidenceCount += 1

            responsesByRequestId[request.requestId].orEmpty().forEach { response ->
                endpoint.responseEventIds += response.eventId
                if (response.hostClass.isNotBlank()) {
                    endpoint.hostClassSignals += response.hostClass
                }
                if (response.mimeType.isNotBlank()) {
                    endpoint.responseMimeTypes += response.mimeType.lowercase(Locale.ROOT)
                }
                if (response.statusCode in 200..399) {
                    endpoint.responseOkCount += 1
                }
            }
        }

        endpointById.values.forEach { endpoint ->
            val evidenceCount = endpoint.requestEventIds.size + endpoint.responseEventIds.size
            endpoint.confidence = when {
                evidenceCount >= 6 -> 0.9
                evidenceCount >= 3 -> 0.8
                evidenceCount >= 1 -> 0.7
                else -> 0.5
            }
        }
        val endpointOverrides = RuntimeToolkitTelemetry.readEndpointOverrides(runtimeRoot)
        val selection = selectFinalEndpoints(
            endpoints = endpointById.values,
            responses = responses,
            overrides = endpointOverrides,
        )
        val exportWarnings = linkedSetOf<String>().apply { addAll(selection.warnings) }
        val endpointsForBundle = selection.endpoints
            .filter { normalizePhaseRelevanceForExport(it.phaseRelevance, it.role).isNotEmpty() }
            .ifEmpty {
                exportWarnings += "FINAL_BUNDLE_DEGRADED"
                selection.endpoints
            }
        if (selection.endpoints.size != endpointsForBundle.size) {
            exportWarnings += "PHASE_RELEVANCE_NORMALIZED"
        }
        if (exportWarnings.isNotEmpty()) {
            exportWarnings += "FINAL_BUNDLE_DEGRADED"
        }
        require(endpointsForBundle.isNotEmpty()) {
            "source pipeline export blocked: no endpoint templates survived filtering"
        }
        val endpointByIdForBundle = endpointsForBundle.associateBy { it.endpointId }

        val endpointTemplates = JSONArray()
        val replayRequirements = JSONArray()

        endpointsForBundle
            .sortedBy { it.endpointId.lowercase(Locale.ROOT) }
            .forEach { endpoint ->
                val stableRequiredQueryParams = endpoint.requiredQueryParams.filterTo(linkedSetOf()) { isStableRequiredInputName(it) }
                val stableRequiredBodyFields = endpoint.requiredBodyFields.filterTo(linkedSetOf()) { isStableRequiredInputName(it) }
                val phaseRelevance = normalizePhaseRelevanceForExport(endpoint.phaseRelevance, endpoint.role)
                val templatePayload = buildEndpointTemplates(
                    role = endpoint.role,
                    normalizedPath = endpoint.normalizedPath,
                    requestOperation = endpoint.requestOperation,
                    requiredQueryParams = stableRequiredQueryParams,
                    optionalQueryParams = endpoint.optionalQueryParams,
                    requiredBodyFields = stableRequiredBodyFields,
                    optionalBodyFields = endpoint.optionalBodyFields,
                    requiredProvenanceInputs = endpoint.requiredProvenanceInputs,
                )
                endpointTemplates.put(
                    JSONObject().apply {
                        put("endpointId", endpoint.endpointId)
                        put("role", endpoint.role)
                        put("templateKind", templateKindForRole(endpoint.role, endpoint.normalizedPath))
                        put("method", endpoint.method)
                        put("normalizedHost", endpoint.normalizedHost)
                        put("normalizedPath", endpoint.normalizedPath)
                        put("graphqlOperationName", JSONObject.NULL)
                        put("requestOperation", endpoint.requestOperation)
                        put("pathTemplate", templatePayload.pathTemplate)
                        put("queryTemplate", templatePayload.queryTemplate)
                        put("bodyTemplate", templatePayload.bodyTemplate)
                        put("variablePlaceholders", templatePayload.variablePlaceholders)
                        put("phaseRelevance", JSONArray(phaseRelevance))
                        put("requiredProvenanceInputs", JSONArray(endpoint.requiredProvenanceInputs.toList()))
                        put("sourceEvidenceRefs", JSONArray((endpoint.requestEventIds.map { "request:$it" } + endpoint.responseEventIds.map { "response:$it" }).take(20)))
                        put("confidence", endpoint.confidence)
                        put("notes", JSONArray())
                    },
                )

                replayRequirements.put(
                    JSONObject().apply {
                        put("endpointRef", endpoint.endpointId)
                        put("requiredHeaders", namedRequirements(endpoint.requiredHeaders, "required_proven"))
                        put("optionalHeaders", namedRequirements(endpoint.optionalHeaders, "optional_observed"))
                        put("requiredCookies", namedRequirements(endpoint.requiredCookies, "required_proven", cookie = true))
                        put("optionalCookies", namedRequirements(endpoint.optionalCookies.minus(endpoint.requiredCookies), "optional_observed", cookie = true))
                        put("requiredQueryParams", namedRequirements(stableRequiredQueryParams, "required_proven"))
                        put(
                            "optionalQueryParams",
                            namedRequirements(endpoint.optionalQueryParams.minus(stableRequiredQueryParams), "optional_observed"),
                        )
                        put("requiredBodyFields", namedRequirements(stableRequiredBodyFields, "required_proven"))
                        put(
                            "optionalBodyFields",
                            namedRequirements(endpoint.optionalBodyFields.minus(stableRequiredBodyFields), "optional_observed"),
                        )
                        put("requiredReferer", endpoint.requiredReferer ?: JSONObject.NULL)
                        put("requiredOrigin", endpoint.requiredOrigin ?: JSONObject.NULL)
                        put("requiredProvenanceInputs", JSONArray(endpoint.requiredProvenanceInputs.toList()))
                        put(
                            "browserAssistanceNeeded",
                            endpoint.requiredReferer != null ||
                                endpoint.requiredOrigin != null ||
                                endpoint.role == "auth" ||
                                endpoint.role == "refresh",
                        )
                        put(
                            "minimizationEvidence",
                            JSONObject().apply {
                                put("method", "captured_only")
                                put("status", "partial")
                                put("notes", JSONArray().put("native_mission_export_fallback"))
                            },
                        )
                    },
                )
            }

        val roleById = endpointsForBundle.associate { it.endpointId to it.role }
        val supportsHome = roleById.values.any { it == "home" }
        val supportsSearch = roleById.values.any { it == "search" }
        val supportsDetail = roleById.values.any { it == "detail" }
        val supportsPlayback = roleById.values.any { it == "playbackResolver" || it == "playback_resolver" }
        val responseEndpointRefByEventId =
            buildMap<String, String> {
                endpointsForBundle.forEach { endpoint ->
                    endpoint.responseEventIds.forEach { eventId ->
                        putIfAbsent(eventId, endpoint.endpointId)
                    }
                }
            }

        val fieldMappings = buildFieldMappings(
            runtimeRoot = runtimeRoot,
            responses = responses,
            targetSiteId = safeTargetId,
            roleByEndpoint = roleById,
            responseEndpointRefByEventId = responseEndpointRefByEventId,
            supportsSearch = supportsSearch,
            supportsDetail = supportsDetail,
            supportsPlayback = supportsPlayback,
        )

        val playbackEndpointId = selectPlaybackEndpointId(endpointsForBundle)
        val selectedPlaybackEndpoints = endpointsForBundle.filter { it.endpointId == playbackEndpointId }
            .ifEmpty { endpointsForBundle.filter { it.role == "playbackResolver" || it.role == "playback_resolver" } }
        val playbackManifestKinds = inferManifestKinds(responses)
        val playbackMimeHints = responses
            .filter { inferRole(it.phaseId, it.operation, it.normalizedPath, it.url) == "playbackResolver" }
            .mapNotNull { it.mimeType.takeIf { mime -> mime.isNotBlank() }?.lowercase(Locale.ROOT) }
            .distinct()
        val playbackContainers = inferStreamContainers(responses)
        val hasResolverEvidence = endpointsForBundle.any { endpoint ->
            val path = endpoint.normalizedPath.lowercase(Locale.ROOT)
            val op = endpoint.requestOperation.lowercase(Locale.ROOT)
            (endpoint.role == "playbackResolver" || endpoint.role == "playback_resolver") &&
                (path.contains("/ptmd/") || path.contains("/tmd/") || op.contains("resolver"))
        }
        val selectedPlaybackIsManifest = selectedPlaybackEndpoints.any { endpoint ->
            val path = endpoint.normalizedPath.lowercase(Locale.ROOT)
            path.endsWith(".m3u8") || path.endsWith(".mpd")
        }
        if (!hasResolverEvidence && selectedPlaybackIsManifest) {
            exportWarnings += "PLAYBACK_RESOLVER_DEGRADED_TO_MANIFEST"
        }

        val allRequiredHeaders = endpointsForBundle.flatMap { it.requiredHeaders }.toSet()
        val allRequiredCookies = endpointsForBundle.flatMap { it.requiredCookies }.toSet()
        val authTokenInputs = endpointsForBundle.flatMap { it.requiredProvenanceInputs }.toSet()
        val authLifecycleInsights = collectTokenLifecycleInsights(runtimeRoot, responses)
        val authOrRefreshEndpoints = endpointsForBundle.filter { it.role == "auth" || it.role == "refresh" }
        if (selection.warnings.any { it.startsWith("ENDPOINT_CAP_APPLIED:auth") || it.startsWith("ENDPOINT_CAP_APPLIED:refresh") }) {
            exportWarnings += "AUTH_ENDPOINT_SET_MINIMIZED"
        }
        val refreshEndpointId = authOrRefreshEndpoints
            .firstOrNull { isRefreshEndpointCandidate(it) }
            ?.endpointId
        val loginEndpointId = authOrRefreshEndpoints
            .sortedByDescending { loginEndpointScore(it) }
            .firstOrNull { isLoginEndpointCandidate(it) }
            ?.endpointId
        val validationEndpointId = authOrRefreshEndpoints
            .firstOrNull { isValidationEndpointCandidate(it) }
            ?.endpointId
        val hasAuthRole = roleById.values.any { it == "auth" || it == "refresh" }
        val hasAuthEndpointRefs = loginEndpointId != null || validationEndpointId != null || refreshEndpointId != null
        val hasAuthArtifactSignals = hasAuthArtifactSignals(
            requiredHeaders = allRequiredHeaders,
            requiredCookies = allRequiredCookies,
            tokenInputs = authTokenInputs,
        )
        val hasDeterministicAuthChain = !loginEndpointId.isNullOrBlank() &&
            !validationEndpointId.isNullOrBlank() &&
            setOf(loginEndpointId, validationEndpointId).size == 2
        val hasCompleteAuthChain = hasDeterministicAuthChain &&
            !refreshEndpointId.isNullOrBlank() &&
            setOf(loginEndpointId, validationEndpointId, refreshEndpointId).size == 3
        // Deterministic auth is already available with login+validation.
        // Refresh remains optional because many sites use long-lived sessions without a separate refresh endpoint.
        val requiresLogin = hasDeterministicAuthChain
        val requiresBrowserSession = endpointsForBundle.any { it.requiredOrigin != null || it.requiredReferer != null }
        val capabilityClass = when {
            supportsPlayback && supportsSearch && supportsDetail -> "HYBRID"
            supportsPlayback || supportsSearch || supportsDetail -> "HYBRID"
            else -> "WEB_ONLY"
        }
        val pluginKind = if (capabilityClass == "HYBRID") "hybrid_plugin" else "profile_plugin"
        val maturity = if (capabilityClass == "HYBRID") "HYBRID" else "EXPERIMENTAL"

        val sourceKey = "external_template.$safeTargetId"
        val bundleId = "spb.$safeTargetId.${shortHash("$safeTargetId|$generatedAt")}".take(64)

        val bundleDescriptor = JSONObject().apply {
            put("bundleId", bundleId)
            put("bundleSchemaVersion", 1)
            put("producer", "mapper_toolkit_android")
            put("producerVersion", "2.0.0")
            put("targetSiteId", safeTargetId)
            put("sourceKey", sourceKey)
            put("sourceFamilyKey", "external_template")
            put("displayName", "${safeTargetId.uppercase(Locale.ROOT)} Web Profile")
            put("pluginKind", pluginKind)
            put("maturity", maturity)
            put("capabilityClass", capabilityClass)
            put("generatedAt", generatedAt)
            put("sourceRuntimeModelId", "srm.$safeTargetId.${shortHash(bundleId)}")
            put("sourceRuntimeExportId", "runtime:${safeTargetId}:${shortHash(bundleId + generatedAt)}")
            put("compatiblePluginApiRange", JSONObject().put("min", "1.0.0").put("max", "1.x"))
            put("compatibleRuntimeModelVersion", 1)
            put("compatibleCapabilitySchemaVersion", "1.0.0")
        }

        val bundle = JSONObject().apply {
            put("\$schema", "contracts/v3/source_pipeline_bundle.schema.json")
            put("bundleDescriptor", bundleDescriptor)
            put(
                "capabilities",
                JSONObject().apply {
                    put("supportsHomeSync", supportsHome)
                    put("supportsGlobalSearch", supportsSearch)
                    put("supportsDetailEnrichment", supportsDetail)
                    put("supportsPlayback", supportsPlayback)
                    put("requiresLogin", requiresLogin)
                    put("requiresBrowserSession", requiresBrowserSession)
                    put("supportsIncrementalSync", supportsHome)
                    put("supportsBackgroundSync", false)
                    put("supportsReplay", true)
                    put("standaloneAppBuildCapable", true)
                },
            )
            put("endpointTemplates", endpointTemplates)
            put("replayRequirements", replayRequirements)
            put(
                "sessionAuth",
                JSONObject().apply {
                    put("authMode", inferAuthMode(requiresLogin, allRequiredHeaders, allRequiredCookies, requiresBrowserSession))
                    put("requiresLogin", requiresLogin)
                    put("requiresBrowserSession", requiresBrowserSession)
                    put("browserContextRequired", requiresBrowserSession)
                    put(
                        "sessionArtifacts",
                        JSONObject().apply {
                            put("cookies", JSONArray(allRequiredCookies.toList().sorted()))
                            put("headers", JSONArray(allRequiredHeaders.toList().sorted()))
                            put("localStorage", JSONArray())
                            put("indexedDb", JSONArray())
                        },
                    )
                    put("loginEndpointRef", loginEndpointId ?: JSONObject.NULL)
                    put("validationEndpointRef", validationEndpointId ?: JSONObject.NULL)
                    put("refreshEndpointRef", refreshEndpointId ?: JSONObject.NULL)
                    put("requiredTokenInputs", requiredTokenInputs(authTokenInputs, endpointsForBundle))
                    put("provenanceRefs", JSONArray(authTokenInputs.map { "prov:${normalizeProvenanceName(it)}" }.sorted()))
                    put("authConfidence", if (requiresLogin) 0.7 else 0.95)
                    put(
                        "authWarnings",
                        JSONArray(
                            buildList {
                                addAll(authLifecycleInsights.refreshTriggers)
                                if (!hasDeterministicAuthChain && (hasAuthRole || hasAuthEndpointRefs || hasAuthArtifactSignals)) {
                                    add("auth_artifacts_detected_without_complete_chain")
                                }
                                if (hasDeterministicAuthChain && refreshEndpointId.isNullOrBlank()) {
                                    add("auth_chain_without_refresh_endpoint")
                                }
                                if (loginEndpointId != null) add("login_endpoint_ref:$loginEndpointId")
                                if (validationEndpointId != null) add("validation_endpoint_ref:$validationEndpointId")
                                if (refreshEndpointId != null) add("refresh_endpoint_ref:$refreshEndpointId")
                            }.distinct().sorted(),
                        ),
                    )
                },
            )
            put(
                "playback",
                JSONObject().apply {
                    val selectedPath = selectedPlaybackEndpoints.firstOrNull()?.normalizedPath.orEmpty().lowercase(Locale.ROOT)
                    val resolverMode = when {
                        !supportsPlayback -> "unknown"
                        selectedPath.endsWith(".m3u8") || selectedPath.endsWith(".mpd") -> "manifest"
                        else -> "resolver_then_manifest"
                    }
                    put("resolverMode", resolverMode)
                    put("browserContextRequired", requiresBrowserSession)
                    put("playbackEndpointRef", playbackEndpointId ?: JSONObject.NULL)
                    put("manifestEndpointRefs", JSONArray())
                    put("manifestKinds", JSONArray(playbackManifestKinds))
                    put("streamContainerHints", JSONArray(playbackContainers))
                    put("streamMimeHints", JSONArray(playbackMimeHints))
                    put("requiredPlaybackHeaders", namedRequirements(selectedPlaybackEndpoints.flatMap { it.requiredHeaders }.toSet(), "required_proven"))
                    put("requiredPlaybackCookies", namedRequirements(selectedPlaybackEndpoints.flatMap { it.requiredCookies }.toSet(), "required_proven", cookie = true))
                    put("requiredPlaybackProvenanceInputs", JSONArray(selectedPlaybackEndpoints.flatMap { it.requiredProvenanceInputs }.toSet().toList()))
                    put("tokenDependencies", JSONArray(selectedPlaybackEndpoints.flatMap { it.requiredProvenanceInputs }.toSet().toList()))
                    put("drmSuspected", false)
                    put("playbackConfidence", if (supportsPlayback) 0.75 else 0.0)
                    put("playbackWarnings", JSONArray())
                },
            )
            put("fieldMappings", fieldMappings)
            put(
                "constraintsBudgets",
                JSONObject().apply {
                    put("defaultTimeoutMs", 75000)
                    put("maxRetries", 2)
                    put("syncBatchSize", 50)
                    put("bodyCaptureBudgetBytes", 4 * 1024 * 1024)
                    put("backgroundAllowed", false)
                    put("cpuIoProfile", "MIXED")
                    put("rateLimitProfile", "PLUGIN_DECLARED")
                    put("replayMode", if (requiresBrowserSession) "browser_assisted" else "native_preferred")
                    put("requiresOrigin", endpointsForBundle.any { it.requiredOrigin != null })
                    put("requiresReferer", endpointsForBundle.any { it.requiredReferer != null })
                    put("tokenTtlHints", JSONArray(authLifecycleInsights.ttlHints))
                },
            )
            put("warnings", JSONArray(exportWarnings.toList().sorted()))
            put(
                "confidence",
                JSONObject().apply {
                    val endpointByRole = JSONObject()
                    endpointsForBundle.groupBy { it.role }.forEach { (role, endpoints) ->
                        endpointByRole.put(role, endpoints.maxOfOrNull { it.confidence } ?: 0.0)
                    }
                    val fieldConf = JSONObject()
                    for (i in 0 until fieldMappings.length()) {
                        val row = fieldMappings.optJSONObject(i) ?: continue
                        fieldConf.put(row.optString("fieldName"), row.optDouble("confidence", 0.0))
                    }
                    val bundleConfidence = if (exportWarnings.isEmpty()) 0.75 else 0.68
                    val determinismConfidence = if (exportWarnings.isEmpty()) 0.72 else 0.66
                    put("bundleConfidence", bundleConfidence)
                    put("determinismConfidence", determinismConfidence)
                    put("endpointConfidenceByRole", endpointByRole)
                    put("playbackConfidence", if (supportsPlayback) 0.75 else 0.0)
                    put("authConfidence", if (requiresLogin) 0.7 else 0.95)
                    put("fieldConfidence", fieldConf)
                },
            )

            if (supportsHome) {
                val homeIds = roleById.entries.filter { it.value == "home" }.map { it.key }
                val detailIds = roleById.entries.filter { it.value == "detail" }.map { it.key }
                val selectionKey = "/"
                put(
                    "selectionModel",
                    JSONObject().apply {
                        put("selectionMode", "route")
                        put(
                            "selectionEntities",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("entityType", "home_route")
                                    put("selectionKey", selectionKey)
                                    put("displayName", "Default Home")
                                    put("defaultSelected", true)
                                    put("linkedEndpointRefs", JSONArray(homeIds.take(3)))
                                },
                            ),
                        )
                        put("defaultSelectionKeys", JSONArray().put(selectionKey))
                        put("selectionConfidence", 0.7)
                    },
                )
                put(
                    "syncModel",
                    JSONObject().apply {
                        put("syncMode", "selection_scoped")
                        put("supportsFullSync", false)
                        put("supportsIncrementalSync", true)
                        put("homeEndpointRefs", JSONArray(homeIds.take(3)))
                        put("detailEnrichmentEndpointRefs", JSONArray(detailIds.take(3)))
                        put("defaultSelectionKeys", JSONArray().put(selectionKey))
                        put("syncConfidence", 0.7)
                    },
                )
            }
        }

        val gateFailures = validateExportGates(
            bundle = bundle,
            endpointById = endpointByIdForBundle,
            loginEndpointId = loginEndpointId,
            validationEndpointId = validationEndpointId,
            refreshEndpointId = refreshEndpointId,
        )
        require(gateFailures.isEmpty()) {
            "source pipeline export blocked by gates:\n- ${gateFailures.joinToString("\n- ")}"
        }

        val siteRuntimeModel = buildSiteRuntimeModel(bundle)
        val manifest = JSONObject().apply {
            put("bundleType", "source_pipeline_bundle_zip")
            put("bundleVersion", 1)
            put("mainContract", "source_pipeline_bundle.json")
            put("siteRuntimeModel", "site_runtime_model.json")
            put("sourceKey", sourceKey)
            put("targetSiteId", safeTargetId)
            put("pluginKind", pluginKind)
            put("producer", "mapper_toolkit_android")
            put("producerVersion", "2.0.0")
            put("outputDir", "exports")
        }

        val sourcePipelineBundlePath = File(runtimeRoot, "source_pipeline_bundle.json")
        val siteRuntimeModelPath = File(runtimeRoot, "site_runtime_model.json")
        val manifestPath = File(runtimeRoot, "manifest.json")
        val exportDir = File(runtimeRoot, "exports")
        if (!exportDir.exists()) exportDir.mkdirs()
        val sourcePluginBundleZipPath = File(exportDir, "source_plugin_bundle.zip")

        synchronized(this) {
            sourcePipelineBundlePath.writeText(bundle.toString(2) + "\n", Charsets.UTF_8)
            siteRuntimeModelPath.writeText(siteRuntimeModel.toString(2) + "\n", Charsets.UTF_8)
            File(runtimeRoot, "site_profile.draft.json").writeText(siteRuntimeModel.toString(2) + "\n", Charsets.UTF_8)
            manifestPath.writeText(manifest.toString(2) + "\n", Charsets.UTF_8)
            writeDerivedCompatibilityArtifacts(
                runtimeRoot = runtimeRoot,
                generatedAt = generatedAt,
                targetSiteId = safeTargetId,
                bundle = bundle,
                requests = requests,
                responses = responses,
            )
            if (sourcePluginBundleZipPath.exists()) sourcePluginBundleZipPath.delete()
            writeSourcePluginBundleZip(
                zipPath = sourcePluginBundleZipPath,
                sourcePipelineBundlePath = sourcePipelineBundlePath,
                siteRuntimeModelPath = siteRuntimeModelPath,
                manifestPath = manifestPath,
            )
        }

        return ExportArtifacts(
            sourcePipelineBundlePath = sourcePipelineBundlePath,
            siteRuntimeModelPath = siteRuntimeModelPath,
            manifestPath = manifestPath,
            sourcePluginBundleZipPath = sourcePluginBundleZipPath,
        )
    }

    private fun validateExportGates(
        bundle: JSONObject,
        endpointById: Map<String, EndpointAggregate>,
        loginEndpointId: String?,
        validationEndpointId: String?,
        refreshEndpointId: String?,
    ): List<String> {
        val failures = mutableListOf<String>()
        val capabilities = bundle.optJSONObject("capabilities") ?: JSONObject()
        val sessionAuth = bundle.optJSONObject("sessionAuth") ?: JSONObject()
        val endpointTemplates = jsonObjects(bundle.optJSONArray("endpointTemplates"))
        val replayRequirements = jsonObjects(bundle.optJSONArray("replayRequirements"))
        val fieldMappings = jsonObjects(bundle.optJSONArray("fieldMappings"))
        val endpointIds = endpointTemplates.mapNotNull { it.optString("endpointId").takeIf { id -> id.isNotBlank() } }
        val endpointIdSet = endpointIds.toSet()
        val replayByEndpointRef = replayRequirements.associateBy { it.optString("endpointRef") }
        val endpointByRef = endpointTemplates.associateBy { it.optString("endpointId") }

        if (endpointIds.size != endpointIdSet.size) {
            failures += "duplicate endpointId detected in endpointTemplates"
        }

        val requiresLogin = sessionAuth.optBoolean("requiresLogin")
        val sessionLoginRef = sessionAuth.optString("loginEndpointRef").trim()
        val sessionValidationRef = sessionAuth.optString("validationEndpointRef").trim()
        val sessionRefreshRef = sessionAuth.optString("refreshEndpointRef").trim()
        val sessionArtifacts = sessionAuth.optJSONObject("sessionArtifacts") ?: JSONObject()
        val sessionArtifactHeaders = jsonStrings(sessionArtifacts.optJSONArray("headers")).toSet()
        val sessionArtifactCookies = jsonStrings(sessionArtifacts.optJSONArray("cookies")).toSet()
        val sessionTokenInputs = jsonObjects(sessionAuth.optJSONArray("requiredTokenInputs"))
            .mapNotNull { token ->
                token.optString("inputName").trim().takeIf { it.isNotBlank() }
            }
            .toSet()
        val sessionAuthWarnings = jsonStrings(sessionAuth.optJSONArray("authWarnings"))
        val sessionProvenanceRefs = linkedSetOf<String>().apply {
            addAll(jsonStrings(sessionAuth.optJSONArray("provenanceRefs")).map { it.trim() }.filter { it.isNotBlank() })
            jsonObjects(sessionAuth.optJSONArray("requiredTokenInputs")).forEach { token ->
                val provenanceRef = token.optString("provenanceRef").trim()
                if (provenanceRef.isNotBlank()) add(provenanceRef)
                val inputName = token.optString("inputName").trim()
                if (inputName.isNotBlank()) add("prov:${normalizeProvenanceName(inputName)}")
            }
        }
        val authArtifactsPresentWithoutChain =
            sessionAuthWarnings.any { it == "auth_artifacts_detected_without_complete_chain" } ||
                hasAuthArtifactSignals(
                    requiredHeaders = sessionArtifactHeaders,
                    requiredCookies = sessionArtifactCookies,
                    tokenInputs = sessionTokenInputs,
                ) ||
                listOf(sessionLoginRef, sessionValidationRef, sessionRefreshRef).count { it.isNotBlank() } in 1..2
        if (requiresLogin) {
            if (loginEndpointId.isNullOrBlank()) failures += "auth chain incomplete: missing login endpointRef"
            if (validationEndpointId.isNullOrBlank()) failures += "auth chain incomplete: missing validation endpointRef"
            if (sessionLoginRef.isBlank()) failures += "sessionAuth.loginEndpointRef missing while requiresLogin=true"
            if (sessionValidationRef.isBlank()) failures += "sessionAuth.validationEndpointRef missing while requiresLogin=true"
            if (sessionLoginRef.isNotBlank() &&
                sessionValidationRef.isNotBlank() &&
                sessionLoginRef == sessionValidationRef
            ) {
                failures += "auth chain must use distinct endpointRefs for login and validation"
            }
            if (sessionRefreshRef.isNotBlank()) {
                val distinctRefs = listOf(sessionLoginRef, sessionValidationRef, sessionRefreshRef)
                    .filter { it.isNotBlank() }
                    .toSet()
                if (distinctRefs.size != 3) {
                    failures += "auth chain refresh endpointRef must differ from login/validation when present"
                }
            }
        }
        // Incomplete auth chains are common on anonymous-capable sites.
        // Keep them as warnings, but do not hard-block export.
        val enforceStrictAuthChain = requiresLogin

        if (enforceStrictAuthChain) {
            listOf(
                "login" to loginEndpointId,
                "validation" to validationEndpointId,
                "refresh" to refreshEndpointId,
            ).forEach { (step, ref) ->
                if (ref.isNullOrBlank()) return@forEach
                if (ref !in endpointIdSet) {
                    failures += "auth chain endpointRef '$step' not found in endpointTemplates: $ref"
                }
                if (!replayByEndpointRef.containsKey(ref)) {
                    failures += "auth chain endpointRef '$step' has no replayRequirements entry: $ref"
                }
                val endpoint = endpointByRef[ref]
                if (endpoint != null) {
                    if (endpoint.optString("method").isBlank()) failures += "auth endpoint '$step' missing method: $ref"
                    if (endpoint.optString("pathTemplate").isBlank()) failures += "auth endpoint '$step' missing pathTemplate: $ref"
                    if (endpoint.opt("queryTemplate") !is JSONObject) failures += "auth endpoint '$step' missing queryTemplate object: $ref"
                    if (endpoint.opt("bodyTemplate") !is JSONObject) failures += "auth endpoint '$step' missing bodyTemplate object: $ref"
                    val placeholders = jsonObjects(endpoint.optJSONArray("variablePlaceholders"))
                    if (placeholders.isEmpty()) {
                        failures += "auth endpoint '$step' missing variablePlaceholders: $ref"
                    }
                }
            }
        } else if (authArtifactsPresentWithoutChain) {
            // no-op on purpose: authWarnings carries the partial-chain signal for observability.
        }

        endpointTemplates.forEach { endpoint ->
            val endpointRef = endpoint.optString("endpointId")
            if (endpointRef.isBlank()) return@forEach
            val role = endpoint.optString("role").trim()
            if (role.isNotBlank() && role !in finalRoleSet) {
                failures += "endpoint '$endpointRef' has forbidden role '$role' in final export"
            }
            val phases = jsonStrings(endpoint.optJSONArray("phaseRelevance"))
            if (phases.any { it == "background_noise" }) {
                failures += "endpoint '$endpointRef' contains background_noise in phaseRelevance"
            }
            if (phases.any { it !in finalPhaseSet }) {
                failures += "endpoint '$endpointRef' contains invalid phaseRelevance entries: ${phases.filter { it !in finalPhaseSet }}"
            }
            val queryTemplateText = endpoint.opt("queryTemplate")?.toString().orEmpty()
            val bodyTemplateText = endpoint.opt("bodyTemplate")?.toString().orEmpty()
            val pathTemplate = endpoint.optString("pathTemplate")
            val replay = replayByEndpointRef[endpointRef]
            jsonObjects(endpoint.optJSONArray("variablePlaceholders"))
                .filter { it.optBoolean("required") }
                .forEach { placeholder ->
                    val name = placeholder.optString("name").trim()
                    if (name.isBlank()) {
                        failures += "endpoint '$endpointRef' has required placeholder with empty name"
                        return@forEach
                    }
                    val location = placeholder.optString("location").trim().lowercase(Locale.ROOT)
                    val token = placeholderToken(name)
                    val used = when (location) {
                        "query" -> queryTemplateText.contains(token)
                        "body" -> bodyTemplateText.contains(token)
                        "path" -> pathTemplate.contains(token)
                        "header" -> {
                            val requiredHeaders = jsonObjects(replay?.optJSONArray("requiredHeaders"))
                                .mapNotNull { item -> item.optString("name").trim().takeIf { it.isNotBlank() } }
                                .toSet()
                            name in requiredHeaders
                        }
                        "cookie" -> {
                            val requiredCookies = jsonObjects(replay?.optJSONArray("requiredCookies"))
                                .mapNotNull { item -> item.optString("name").trim().takeIf { it.isNotBlank() } }
                                .toSet()
                            val cookieName = name.removePrefix("cookies.")
                            cookieName in requiredCookies
                        }
                        else -> listOf(pathTemplate, queryTemplateText, bodyTemplateText).any { it.contains(token) }
                    }
                    if (!used) {
                        failures += "required placeholder '$name' (location=$location) is not used in endpoint template: $endpointRef"
                    }
                }
        }

        replayRequirements.forEach { replay ->
            val endpointRef = replay.optString("endpointRef")
            if (endpointRef.isBlank()) {
                failures += "replayRequirements entry missing endpointRef"
                return@forEach
            }
            val endpoint = endpointByRef[endpointRef]
            if (endpoint == null) {
                failures += "replayRequirements endpointRef does not exist in endpointTemplates: $endpointRef"
                return@forEach
            }
            listOf("requiredHeaders", "requiredCookies", "requiredQueryParams", "requiredBodyFields").forEach { key ->
                jsonObjects(replay.optJSONArray(key)).forEach { item ->
                    val name = item.optString("name").trim()
                    val status = item.optString("status").trim().lowercase(Locale.ROOT)
                    val provenanceRef = item.opt("provenanceRef")
                    if (name.isBlank()) {
                        failures += "$key on endpoint '$endpointRef' contains empty name"
                    }
                    if (status in setOf("required", "required_proven")) {
                        if (provenanceRef == null || provenanceRef == JSONObject.NULL || provenanceRef.toString().trim().isBlank()) {
                            failures += "$key on endpoint '$endpointRef' has required item '$name' without provenanceRef"
                        } else {
                            when (key) {
                                "requiredHeaders", "requiredCookies" -> {
                                    val normalizedProvenanceRef = provenanceRef.toString().trim()
                                    val endpointProvenanceRefs = jsonStrings(replay.optJSONArray("requiredProvenanceInputs"))
                                        .map { input -> "prov:${normalizeProvenanceName(input)}" }
                                        .toSet()
                                    if (normalizedProvenanceRef !in sessionProvenanceRefs && normalizedProvenanceRef !in endpointProvenanceRefs) {
                                        failures += "$key on endpoint '$endpointRef' has unresolved provenanceRef '$normalizedProvenanceRef'"
                                    }
                                    if (key == "requiredHeaders" && name.isNotBlank() && name !in sessionArtifactHeaders) {
                                        failures += "required header '$name' on endpoint '$endpointRef' is not present in sessionAuth.sessionArtifacts.headers"
                                    }
                                    if (key == "requiredCookies" && name.isNotBlank() && name !in sessionArtifactCookies) {
                                        failures += "required cookie '$name' on endpoint '$endpointRef' is not present in sessionAuth.sessionArtifacts.cookies"
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val queryTemplateText = endpoint.opt("queryTemplate")?.toString().orEmpty()
            jsonObjects(replay.optJSONArray("requiredQueryParams")).forEach { item ->
                val name = item.optString("name").trim()
                if (name.isBlank()) return@forEach
                val placeholderName = sanitizePlaceholderName(name)
                if (placeholderName.isBlank() || !queryTemplateText.contains(placeholderToken(placeholderName))) {
                    failures += "required query param '$name' on endpoint '$endpointRef' is not resolvable in queryTemplate"
                }
            }
            val bodyTemplateText = endpoint.opt("bodyTemplate")?.toString().orEmpty()
            jsonObjects(replay.optJSONArray("requiredBodyFields")).forEach { item ->
                val name = item.optString("name").trim()
                if (name.isBlank()) return@forEach
                val placeholderName = sanitizePlaceholderName(name)
                if (placeholderName.isBlank() || !bodyTemplateText.contains(placeholderToken(placeholderName))) {
                    failures += "required body field '$name' on endpoint '$endpointRef' is not resolvable in bodyTemplate"
                }
            }
        }

        fieldMappings.forEach { row ->
            val fieldName = row.optString("fieldName")
            val sourceRef = row.optString("sourceRef").trim()
            if (sourceRef.isBlank() || sourceRef !in endpointIdSet) {
                failures += "fieldMappings.$fieldName has non-runtime sourceRef: '$sourceRef' (must match endpointId)"
            }
            val derivation = row.optString("derivationKind").trim().ifBlank { "missing" }
            val template = row.opt("valueTemplate")
            if (derivation == "missing") return@forEach
            if (template == null || template == JSONObject.NULL) {
                failures += "fieldMappings.$fieldName has derivationKind=$derivation without valueTemplate"
                return@forEach
            }
            when (template) {
                is String -> {
                    val value = template.trim()
                    if (!looksEvaluableValueTemplate(value)) {
                        failures += "fieldMappings.$fieldName has non-evaluable valueTemplate string: '$value'"
                    }
                }
                is JSONObject -> {
                    if (template.length() == 0) {
                        failures += "fieldMappings.$fieldName has empty valueTemplate object"
                    }
                    template.keys().forEach { key ->
                        val value = template.optString(key).trim()
                        if (!looksEvaluableValueTemplate(value)) {
                            failures += "fieldMappings.$fieldName has non-evaluable mapping template at '$key': '$value'"
                        }
                    }
                }
                else -> failures += "fieldMappings.$fieldName has unsupported valueTemplate type: ${template.javaClass.simpleName}"
            }
        }

        if (capabilities.optBoolean("requiresLogin")) {
            if (sessionLoginRef.isBlank()) failures += "sessionAuth.loginEndpointRef missing while requiresLogin=true"
            if (sessionValidationRef.isBlank()) failures += "sessionAuth.validationEndpointRef missing while requiresLogin=true"
        }

        // Keep mapper-only gate consistent with generated endpoint map.
        endpointById.keys.forEach { id ->
            if (id !in endpointIdSet) {
                failures += "internal endpoint map contains id not exported in endpointTemplates: $id"
            }
        }
        return failures.distinct().sorted()
    }

    private fun looksEvaluableValueTemplate(template: String): Boolean {
        if (template.isBlank()) return false
        if (template.contains(' ')) return false
        if (template.startsWith("http://", ignoreCase = true) || template.startsWith("https://", ignoreCase = true)) {
            return false
        }
        val allowed = Regex("^[A-Za-z0-9_.:\\[\\]-]+$")
        return allowed.matches(template)
    }

    private fun buildSiteRuntimeModel(bundle: JSONObject): JSONObject {
        val descriptor = bundle.optJSONObject("bundleDescriptor") ?: JSONObject()
        val capabilities = bundle.optJSONObject("capabilities") ?: JSONObject()
        val sessionAuth = bundle.optJSONObject("sessionAuth") ?: JSONObject()
        val playback = bundle.optJSONObject("playback") ?: JSONObject()
        val endpointTemplates = bundle.optJSONArray("endpointTemplates") ?: JSONArray()
        val fieldMappings = bundle.optJSONArray("fieldMappings") ?: JSONArray()
        val confidence = bundle.optJSONObject("confidence") ?: JSONObject()
        val warnings = bundle.optJSONArray("warnings") ?: JSONArray()

        val endpoints = JSONArray()
        val roles = linkedSetOf<String>()
        for (i in 0 until endpointTemplates.length()) {
            val endpoint = endpointTemplates.optJSONObject(i) ?: continue
            val role = endpoint.optString("role")
            if (role.isNotBlank()) roles += role
            endpoints.put(
                JSONObject().apply {
                    put("endpointId", endpoint.optString("endpointId"))
                    put("endpointRole", role)
                    put("normalizedHost", endpoint.optString("normalizedHost"))
                    put("normalizedPath", endpoint.optString("normalizedPath"))
                    put("method", endpoint.optString("method", "GET"))
                    put("requestOperation", endpoint.optString("requestOperation"))
                    put("graphqlOperationName", endpoint.opt("graphqlOperationName") ?: JSONObject.NULL)
                    put("templateKind", endpoint.optString("templateKind", "rest_json"))
                    put("phaseRelevance", endpoint.optJSONArray("phaseRelevance") ?: JSONArray())
                    put("confidence", endpoint.optDouble("confidence", 0.0))
                },
            )
        }

        val fields = JSONObject()
        val coverage = JSONObject()
        for (i in 0 until fieldMappings.length()) {
            val row = fieldMappings.optJSONObject(i) ?: continue
            val fieldName = row.optString("fieldName")
            if (fieldName.isBlank()) continue
            val status = row.optString("derivationKind", "missing")
            coverage.put(fieldName, status)
            fields.put(
                fieldName,
                JSONObject().apply {
                    put("fieldName", fieldName)
                    put("sourceRef", row.optString("sourceRef"))
                    put("sourceKind", row.optString("sourceKind"))
                    put("valuePreview", row.opt("valueTemplate") ?: JSONObject.NULL)
                    put("confidence", row.optDouble("confidence", 0.0))
                    put("observedInRoles", row.optJSONArray("observedInRoles") ?: JSONArray())
                    put("status", status)
                },
            )
        }

        return JSONObject().apply {
            put("modelId", descriptor.optString("sourceRuntimeModelId"))
            put("modelSchemaVersion", 1)
            put("targetSiteId", descriptor.optString("targetSiteId"))
            put("baseUrl", "https://${firstHost(endpointTemplates)}")
            put("generatedAt", descriptor.optString("generatedAt", Instant.now().toString()))
            put("sourceRuntimeExportId", descriptor.optString("sourceRuntimeExportId"))
            put(
                "capabilityModel",
                JSONObject().apply {
                    put("capabilityClass", descriptor.optString("capabilityClass"))
                    put("maturity", descriptor.optString("maturity"))
                    put(
                        "supports",
                        JSONObject().apply {
                            put("home", capabilities.optBoolean("supportsHomeSync"))
                            put("search", capabilities.optBoolean("supportsGlobalSearch"))
                            put("detail", capabilities.optBoolean("supportsDetailEnrichment"))
                            put("playback", capabilities.optBoolean("supportsPlayback"))
                            put("auth", capabilities.optBoolean("requiresLogin"))
                            put("replay", capabilities.optBoolean("supportsReplay", true))
                            put("standalone_app_build", capabilities.optBoolean("standaloneAppBuildCapable", true))
                        },
                    )
                },
            )
            put(
                "sessionModel",
                JSONObject().apply {
                    put("authMode", sessionAuth.optString("authMode", "none"))
                    put("requiresLogin", sessionAuth.optBoolean("requiresLogin"))
                    put("requiresBrowserSession", sessionAuth.optBoolean("requiresBrowserSession"))
                    put("sessionArtifacts", sessionAuth.optJSONObject("sessionArtifacts") ?: JSONObject())
                    val tokenInputs = JSONArray()
                    val requiredTokenInputs = sessionAuth.optJSONArray("requiredTokenInputs") ?: JSONArray()
                    for (i in 0 until requiredTokenInputs.length()) {
                        val token = requiredTokenInputs.optJSONObject(i) ?: continue
                        tokenInputs.put(token.optString("inputName"))
                    }
                    put("tokenInputs", tokenInputs)
                    put("sessionConfidence", sessionAuth.optDouble("authConfidence", 0.0))
                },
            )
            put(
                "endpointModel",
                JSONObject().apply {
                    put("endpointRolesPresent", JSONArray(roles.toList()))
                    put("endpoints", endpoints)
                },
            )
            put(
                "playbackModel",
                JSONObject().apply {
                    put("resolverMode", playback.optString("resolverMode", "unknown"))
                    put("browserContextRequired", playback.optBoolean("browserContextRequired"))
                    put(
                        "playbackEndpointRefs",
                        JSONArray().apply {
                            val ref = playback.optString("playbackEndpointRef")
                            if (ref.isNotBlank()) put(ref)
                        },
                    )
                    put("manifestKinds", playback.optJSONArray("manifestKinds") ?: JSONArray())
                    put("streamKinds", playback.optJSONArray("streamContainerHints") ?: JSONArray())
                    put("streamMimeHints", playback.optJSONArray("streamMimeHints") ?: JSONArray())
                    put(
                        "requestRequirements",
                        JSONObject().put("tokenDependencies", playback.optJSONArray("tokenDependencies") ?: JSONArray()),
                    )
                    put(
                        "drmModel",
                        JSONObject().apply {
                            put("drmSuspected", playback.optBoolean("drmSuspected"))
                            put("drmKinds", JSONArray())
                            put("licenseEndpointRef", JSONObject.NULL)
                            put("browserOnlyRisk", playback.optBoolean("browserContextRequired"))
                        },
                    )
                    put("playbackConfidence", playback.optDouble("playbackConfidence", 0.0))
                },
            )
            put("fieldModel", JSONObject().put("fieldCoverage", coverage).put("fields", fields))
            put(
                "confidenceModel",
                JSONObject().apply {
                    put("exportConfidence", confidence.optDouble("bundleConfidence", 0.0))
                    put("endpointConfidences", confidence.optJSONObject("endpointConfidenceByRole") ?: JSONObject())
                    put("playbackConfidence", confidence.optDouble("playbackConfidence", 0.0))
                    put("authConfidence", confidence.optDouble("authConfidence", 0.0))
                    put("fieldConfidences", confidence.optJSONObject("fieldConfidence") ?: JSONObject())
                    put("determinismConfidence", confidence.optDouble("determinismConfidence", 0.0))
                },
            )
            put("warningModel", JSONObject().put("warnings", warnings))
        }
    }

    private fun writeDerivedCompatibilityArtifacts(
        runtimeRoot: File,
        generatedAt: String,
        targetSiteId: String,
        bundle: JSONObject,
        requests: List<RequestEvent>,
        responses: List<ResponseEvent>,
    ) {
        val descriptor = bundle.optJSONObject("bundleDescriptor") ?: JSONObject()
        val capabilities = bundle.optJSONObject("capabilities") ?: JSONObject()
        val sessionAuth = bundle.optJSONObject("sessionAuth") ?: JSONObject()
        val playback = bundle.optJSONObject("playback") ?: JSONObject()
        val confidence = bundle.optJSONObject("confidence") ?: JSONObject()

        val endpointTemplates = jsonObjects(bundle.optJSONArray("endpointTemplates"))
            .sortedBy { it.optString("endpointId").lowercase(Locale.ROOT) }
        val replayRequirements = jsonObjects(bundle.optJSONArray("replayRequirements"))
            .sortedBy { it.optString("endpointRef").lowercase(Locale.ROOT) }
        val fieldMappings = jsonObjects(bundle.optJSONArray("fieldMappings"))
            .sortedBy {
                "${it.optString("fieldName").lowercase(Locale.ROOT)}:${it.optString("sourceRef").lowercase(Locale.ROOT)}"
            }

        val roleByEndpoint = endpointTemplates.associate { endpoint ->
            endpoint.optString("endpointId") to endpoint.optString("role")
        }
        val endpointConfidenceById = endpointTemplates.associate { endpoint ->
            endpoint.optString("endpointId") to endpoint.optDouble("confidence", 0.0)
        }
        val authMode = sessionAuth.optString("authMode", "none")

        val providerEndpointTemplates = JSONArray()
        endpointTemplates.forEach { endpoint ->
            val endpointId = endpoint.optString("endpointId")
            val providerRole = bundleRoleToProviderRole(endpoint.optString("role"))
            if (endpointId.isBlank() || providerRole.isBlank()) return@forEach

            val queryTemplate = JSONObject()
            val bodyTemplate = JSONObject()
            val variablePlaceholders = JSONArray()
            jsonObjects(endpoint.optJSONArray("variablePlaceholders"))
                .sortedBy { it.optString("name").lowercase(Locale.ROOT) }
                .forEach { placeholder ->
                    val name = placeholder.optString("name").trim()
                    if (name.isBlank()) return@forEach
                    variablePlaceholders.put(name)
                    val placeholderTemplate = "{{${name}}}"
                    when (placeholder.optString("location").lowercase(Locale.ROOT)) {
                        "body" -> bodyTemplate.put(name, placeholderTemplate)
                        else -> queryTemplate.put(name, placeholderTemplate)
                    }
                }

            val requestIds = JSONArray()
            val responseIds = JSONArray()
            jsonStrings(endpoint.optJSONArray("sourceEvidenceRefs")).forEach { ref ->
                when {
                    ref.startsWith("request:") -> requestIds.put(ref.removePrefix("request:"))
                    ref.startsWith("response:") -> responseIds.put(ref.removePrefix("response:"))
                }
            }
            val requiredPhase = jsonStrings(endpoint.optJSONArray("phaseRelevance"))
                .firstOrNull()
                ?.ifBlank { null }
                ?: "background_noise"

            providerEndpointTemplates.put(
                JSONObject().apply {
                    put("template_id", endpointId)
                    put("endpoint_role", providerRole)
                    put("normalized_host", endpoint.optString("normalizedHost"))
                    put("normalized_path", endpoint.optString("normalizedPath"))
                    put("method", endpoint.optString("method", "GET"))
                    put("request_operation", endpoint.optString("requestOperation"))
                    endpoint.opt("graphqlOperationName")
                        ?.takeIf { it != JSONObject.NULL }
                        ?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { put("graphql_operation_name", it) }
                    if (queryTemplate.length() > 0) put("stable_query_template", queryTemplate)
                    if (bodyTemplate.length() > 0) put("stable_body_template", bodyTemplate)
                    put("variable_placeholders", variablePlaceholders)
                    put("required_phase_relevance", requiredPhase)
                    put("required_provenance_inputs", endpoint.optJSONArray("requiredProvenanceInputs") ?: JSONArray())
                    put("confidence", endpoint.optDouble("confidence", 0.0))
                    put(
                        "source_evidence_refs",
                        JSONObject().apply {
                            put("request_ids", requestIds)
                            put("response_ids", responseIds)
                        },
                    )
                },
            )
        }

        val providerReplayRequirements = JSONArray()
        replayRequirements.forEach { replay ->
            val endpointRef = replay.optString("endpointRef")
            val providerRole = bundleRoleToProviderRole(roleByEndpoint[endpointRef].orEmpty())
            if (endpointRef.isBlank() || providerRole.isBlank()) return@forEach
            val requiredHeaders = requirementNames(
                replay.optJSONArray("requiredHeaders"),
                allowedStatuses = setOf("required", "required_proven"),
            )
            val optionalHeaders = requirementNames(
                replay.optJSONArray("optionalHeaders"),
                allowedStatuses = setOf("optional_observed"),
            )
            val forbiddenNoiseHeaders = requirementNames(
                replay.optJSONArray("optionalHeaders"),
                allowedStatuses = setOf("forbidden_noise"),
            )
            val requiredCookies = requirementNames(
                replay.optJSONArray("requiredCookies"),
                allowedStatuses = setOf("required", "required_proven"),
            )
            val optionalCookies = requirementNames(
                replay.optJSONArray("optionalCookies"),
                allowedStatuses = setOf("optional_observed"),
            )
            val requiredQueryParams = requirementNames(
                replay.optJSONArray("requiredQueryParams"),
                allowedStatuses = setOf("required", "required_proven"),
            )
            val requiredBodyFields = requirementNames(
                replay.optJSONArray("requiredBodyFields"),
                allowedStatuses = setOf("required", "required_proven"),
            )
            providerReplayRequirements.put(
                JSONObject().apply {
                    put("template_ref", endpointRef)
                    put("endpoint_role", providerRole)
                    put("required_headers", JSONArray(requiredHeaders))
                    put("optional_headers", JSONArray(optionalHeaders))
                    put("observed_only_headers", JSONArray(optionalHeaders))
                    put("forbidden_noise_headers", JSONArray(forbiddenNoiseHeaders))
                    put("required_cookies", JSONArray(requiredCookies))
                    put("optional_cookies", JSONArray(optionalCookies))
                    put("required_query_params", JSONArray(requiredQueryParams))
                    put("required_body_fields", JSONArray(requiredBodyFields))
                    put("required_provenance_inputs", replay.optJSONArray("requiredProvenanceInputs") ?: JSONArray())
                    replay.opt("requiredReferer")
                        ?.takeIf { it != JSONObject.NULL }
                        ?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { put("required_referer", it) }
                    replay.opt("requiredOrigin")
                        ?.takeIf { it != JSONObject.NULL }
                        ?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { put("required_origin", it) }
                    put("auth_mode", authMode)
                    put("replay_confidence", endpointConfidenceById[endpointRef] ?: 0.65)
                    put("minimization_evidence", replay.optJSONObject("minimizationEvidence") ?: JSONObject())
                },
            )
        }

        val supportsSearch = capabilities.optBoolean("supportsGlobalSearch")
        val supportsDetail = capabilities.optBoolean("supportsDetailEnrichment")
        val supportsPlayback = capabilities.optBoolean("supportsPlayback")
        val providerFieldMatrix = toProviderFieldMatrix(
            fieldMappings = fieldMappings,
            supportsSearch = supportsSearch,
            supportsDetail = supportsDetail,
            supportsPlayback = supportsPlayback,
        )
        val authDraft = toProviderAuthDraft(sessionAuth)
        val playbackDraft = toProviderPlaybackDraft(playback)
        val warnings = extractWarningMessages(bundle.optJSONArray("warnings"))

        val searchTemplateRef = roleByEndpoint.entries.firstOrNull { it.value == "search" }?.key.orEmpty()
        val detailTemplateRef = roleByEndpoint.entries.firstOrNull { it.value == "detail" }?.key.orEmpty()
        val playbackTemplateRef = roleByEndpoint.entries.firstOrNull {
            it.value == "playbackResolver" || it.value == "playback_resolver"
        }?.key.orEmpty()
        val authTemplateRef = roleByEndpoint.entries.firstOrNull {
            it.value == "auth" || it.value == "refresh"
        }?.key.orEmpty()

        val providerCapabilityClass = when (descriptor.optString("capabilityClass")) {
            "NATIVE_READY" -> "NATIVE_READY"
            "HYBRID" -> "HYBRID"
            "WEB_ONLY" -> "BROWSER_NEAR"
            else -> "BLOCKED"
        }
        val sourceRuntimeExportId = descriptor.optString("sourceRuntimeExportId")
            .ifBlank { "runtime:${targetSiteId}:${shortHash(targetSiteId)}" }
        val providerExport = JSONObject().apply {
            put("export_id", "provider_export_${shortHash("$targetSiteId|$sourceRuntimeExportId")}")
            put("export_schema_version", "1.0.0")
            put("target_site_id", targetSiteId)
            put("generated_at", generatedAt)
            put("source_runtime_export_id", sourceRuntimeExportId)
            put(
                "confidence_summary",
                JSONObject().apply {
                    put("overall_confidence", confidence.optDouble("bundleConfidence", 0.0))
                    put("determinism_confidence", confidence.optDouble("determinismConfidence", 0.0))
                },
            )
            put("capability_class", providerCapabilityClass)
            put("endpoint_templates", providerEndpointTemplates)
            put("replay_requirements", providerReplayRequirements)
            put("field_matrix", providerFieldMatrix)
            put("auth_draft", authDraft)
            put("playback_draft", playbackDraft)
            put("warnings", JSONArray(warnings))
            put("known_limitations", JSONArray())
            put(
                "fishit_player_contract",
                JSONObject().apply {
                    put(
                        "external_provider_descriptor",
                        JSONObject().apply {
                            put("provider_id", descriptor.optString("sourceKey").ifBlank { "external_template.$targetSiteId" })
                            put("target_site_id", targetSiteId)
                            put(
                                "capability_flags",
                                JSONObject().apply {
                                    put("home_template", capabilities.optBoolean("supportsHomeSync"))
                                    put("search_template", supportsSearch)
                                    put("detail_template", supportsDetail)
                                    put("playback_template", supportsPlayback)
                                    put("auth_template", authTemplateRef.isNotBlank())
                                    put("browser_context_required", capabilities.optBoolean("requiresBrowserSession"))
                                },
                            )
                        },
                    )
                    put("search_template_ref", searchTemplateRef)
                    put("detail_template_ref", detailTemplateRef)
                    put("playback_template_ref", playbackTemplateRef)
                    put(
                        "auth_session_descriptor",
                        JSONObject().apply {
                            put("template_ref", authTemplateRef)
                            put("auth_mode", authMode)
                            put("browser_session_required", capabilities.optBoolean("requiresBrowserSession"))
                        },
                    )
                },
            )
        }

        val replaySeed = toReplaySeed(generatedAt, targetSiteId, requests)
        val replayBundle = JSONObject().apply {
            put("schema_version", 1)
            put("generated_at_utc", generatedAt)
            put("target_site_id", targetSiteId)
            put("replay_seed", replaySeed)
        }
        val responseIndex = toResponseIndex(generatedAt, responses)
        val fixtureManifest = JSONObject().apply {
            put("schema_version", 1)
            put("generated_at_utc", generatedAt)
            put("target_site_id", targetSiteId)
            put(
                "required_files",
                JSONArray(
                    listOf(
                        "replay_bundle.json",
                        "replay_seed.json",
                        "response_index.json",
                        "events/runtime_events.jsonl",
                    ),
                ),
            )
            put(
                "available_files",
                JSONObject().apply {
                    put("replay_bundle", true)
                    put("replay_seed", true)
                    put("response_index", true)
                    put("runtime_events", true)
                },
            )
        }

        writeJson(runtimeRoot, "provider_draft_export.json", providerExport)
        writeJson(runtimeRoot, "fishit_provider_draft.json", providerExport)
        writeJson(
            runtimeRoot,
            "endpoint_templates.json",
            JSONObject().apply {
                put("schema_version", 1)
                put("generated_at_utc", generatedAt)
                put("target_site_id", targetSiteId)
                put("endpoint_templates", providerEndpointTemplates)
            },
        )
        writeJson(
            runtimeRoot,
            "endpoint_candidates.json",
            JSONObject().apply {
                put("schema_version", 1)
                put("generated_at_utc", generatedAt)
                put("target_site_id", targetSiteId)
                put("candidates", providerEndpointTemplates)
            },
        )
        writeJson(
            runtimeRoot,
            "replay_requirements.json",
            JSONObject().apply {
                put("schema_version", 1)
                put("generated_at_utc", generatedAt)
                put("target_site_id", targetSiteId)
                put("replay_requirements", providerReplayRequirements)
            },
        )
        writeJson(
            runtimeRoot,
            "field_matrix.json",
            JSONObject().apply {
                put("schema_version", 1)
                put("generated_at_utc", generatedAt)
                put("target_site_id", targetSiteId)
                put("fields", providerFieldMatrix.optJSONArray("fields") ?: JSONArray())
                put("extraction_event_count", responses.size)
            },
        )
        writeJson(runtimeRoot, "auth_draft.json", authDraft)
        writeJson(runtimeRoot, "playback_draft.json", playbackDraft)
        writeJson(runtimeRoot, "replay_seed.json", replaySeed)
        writeJson(runtimeRoot, "replay_bundle.json", replayBundle)
        writeJson(runtimeRoot, "response_index.json", responseIndex)
        writeJson(runtimeRoot, "fixture_manifest.json", fixtureManifest)
    }

    private fun toProviderFieldMatrix(
        fieldMappings: List<JSONObject>,
        supportsSearch: Boolean,
        supportsDetail: Boolean,
        supportsPlayback: Boolean,
    ): JSONObject {
        val providerNameByBundleField = linkedMapOf(
            "title" to "title",
            "subtitle" to "subtitle",
            "description" to "description",
            "poster" to "image/poster",
            "canonicalId" to "canonical id",
            "collectionId" to "collection id",
            "itemType" to "teaser/item type",
            "playbackHint" to "playback hints",
            "sectionName" to "section/rail names",
            "searchMapping" to "search result mapping",
            "detailMapping" to "detail mapping",
        )
        val sourceByName = fieldMappings.associateBy { it.optString("fieldName") }
        val rows = JSONArray()
        providerNameByBundleField.forEach { (bundleName, providerName) ->
            val row = sourceByName[bundleName]
            val derivation = row?.optString("derivationKind", "missing").orEmpty().ifBlank { "missing" }
            val status = when (derivation) {
                "direct" -> "directly_observed"
                "derived" -> "derived"
                else -> "missing"
            }
            val valueTemplate = row?.opt("valueTemplate")
                ?.takeIf { it != JSONObject.NULL }
                ?.toString()
                ?.trim()
                .orEmpty()
            val normalizedValue = valueTemplate.ifBlank { null }
            rows.put(
                JSONObject().apply {
                    put("field", providerName)
                    if (normalizedValue != null) put("value_or_template", normalizedValue)
                    put("source_kind", row?.optString("sourceKind").orEmpty().ifBlank { "unknown" })
                    put("source_ref", row?.optString("sourceRef").orEmpty().ifBlank { "field_matrix" })
                    put("confidence", row?.optDouble("confidence", 0.0) ?: 0.0)
                    put("observed_in_roles", row?.optJSONArray("observedInRoles") ?: JSONArray())
                    put("status", status)
                },
            )
        }

        ensureProviderFieldFallback(rows, "search result mapping", "results[]", supportsSearch)
        ensureProviderFieldFallback(rows, "detail mapping", "item", supportsDetail)
        ensureProviderFieldFallback(rows, "canonical id", "{canonical}", supportsSearch || supportsDetail)
        ensureProviderFieldFallback(rows, "title", "{title}", supportsSearch || supportsDetail)
        ensureProviderFieldFallback(rows, "playback hints", "manifest_url", supportsPlayback)

        return JSONObject().put("fields", rows)
    }

    private fun ensureProviderFieldFallback(
        rows: JSONArray,
        fieldName: String,
        value: String,
        enabled: Boolean,
    ) {
        if (!enabled) return
        for (idx in 0 until rows.length()) {
            val row = rows.optJSONObject(idx) ?: continue
            if (row.optString("field") != fieldName) continue
            if (row.optString("status") != "missing") return
            row.put("value_or_template", value)
            row.put("source_kind", "derived")
            row.put("source_ref", "${normalizeProvenanceName(fieldName)}_fallback")
            row.put("confidence", 0.5)
            row.put("status", "derived")
            return
        }
    }

    private fun toProviderAuthDraft(sessionAuth: JSONObject): JSONObject {
        val sessionArtifacts = JSONArray()
        val artifacts = sessionAuth.optJSONObject("sessionArtifacts") ?: JSONObject()
        jsonStrings(artifacts.optJSONArray("cookies")).forEach { name ->
            sessionArtifacts.put(
                JSONObject().apply {
                    put("kind", "required_cookie")
                    put("name", name)
                    put("source", "sessionAuth.sessionArtifacts.cookies")
                },
            )
        }
        jsonStrings(artifacts.optJSONArray("headers")).forEach { name ->
            sessionArtifacts.put(
                JSONObject().apply {
                    put("kind", "required_header")
                    put("name", name)
                    put("source", "sessionAuth.sessionArtifacts.headers")
                },
            )
        }
        val tokenInputs = jsonObjects(sessionAuth.optJSONArray("requiredTokenInputs"))
            .mapNotNull { token -> token.optString("inputName").takeIf { it.isNotBlank() } }
            .distinct()
            .sorted()
        val authWarnings = jsonStrings(sessionAuth.optJSONArray("authWarnings"))
        val loginEndpointRef = sessionAuth.opt("loginEndpointRef")
            ?.takeIf { it != JSONObject.NULL }
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: authWarnings
                .firstOrNull { it.startsWith("login_endpoint_ref:") }
                ?.substringAfter("login_endpoint_ref:")
                ?.trim()
                .orEmpty()
        return JSONObject().apply {
            put("auth_mode", sessionAuth.optString("authMode", "none"))
            put("session_artifacts", sessionArtifacts)
            put("provenance_backed_token_inputs", JSONArray(tokenInputs))
            if (loginEndpointRef.isNotBlank()) {
                put("login_endpoint_ref", loginEndpointRef)
            }
            sessionAuth.opt("validationEndpointRef")
                ?.takeIf { it != JSONObject.NULL }
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { put("validation_endpoint_ref", it) }
            sessionAuth.opt("refreshEndpointRef")
                ?.takeIf { it != JSONObject.NULL }
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { put("refresh_endpoint_ref", it) }
            put("browser_session_required", sessionAuth.optBoolean("requiresBrowserSession"))
            put("auth_confidence", sessionAuth.optDouble("authConfidence", 0.0))
            put("warnings", JSONArray(authWarnings))
        }
    }

    private fun toProviderPlaybackDraft(playback: JSONObject): JSONObject {
        return JSONObject().apply {
            put("resolver_mode", playback.optString("resolverMode", "unknown"))
            playback.optString("playbackEndpointRef").takeIf { it.isNotBlank() }?.let {
                put("playback_endpoint_template_ref", it)
            }
            put(
                "manifest_kind_detected",
                jsonStrings(playback.optJSONArray("manifestKinds"))
                    .firstOrNull()
                    ?.ifBlank { null }
                    ?: "none",
            )
            put(
                "manifest_required_headers",
                JSONArray(requirementNames(playback.optJSONArray("requiredPlaybackHeaders"), setOf("required", "required_proven"))),
            )
            put(
                "manifest_required_cookies",
                JSONArray(requirementNames(playback.optJSONArray("requiredPlaybackCookies"), setOf("required", "required_proven"))),
            )
            put("token_dependencies", playback.optJSONArray("tokenDependencies") ?: JSONArray())
            put("browser_context_required", playback.optBoolean("browserContextRequired"))
            put("stream_container_hints", playback.optJSONArray("streamContainerHints") ?: JSONArray())
            put("stream_mime_hints", playback.optJSONArray("streamMimeHints") ?: JSONArray())
            put("drm_suspected", playback.optBoolean("drmSuspected"))
            put("playback_confidence", playback.optDouble("playbackConfidence", 0.0))
            put("warnings", JSONArray())
        }
    }

    private fun toReplaySeed(
        generatedAt: String,
        targetSiteId: String,
        requests: List<RequestEvent>,
    ): JSONObject {
        val targetHostHints = targetHostHints(targetSiteId)
        val steps = JSONArray()
        requests
            .sortedWith(compareBy<RequestEvent> { it.tsUtc }.thenBy { it.requestId }.thenBy { it.eventId })
            .forEach { request ->
                if (!request.url.startsWith("http", ignoreCase = true)) return@forEach
                if (request.method != "GET") return@forEach
                val role = inferRole(request.phaseId, request.operation, request.normalizedPath, request.url)
                if (role == "helper") return@forEach
                if (!isReplayRequestTargetRelevant(request, targetHostHints)) return@forEach
                if (request.url.isBlank()) return@forEach
                steps.put(
                    JSONObject().apply {
                        put("event_id", request.eventId)
                        put("request_id", request.requestId)
                        put("url", request.url)
                        put("method", request.method)
                        put("headers_reduced", JSONObject(reduceHeadersForReplay(request.headers)))
                        put("query_params", parseQueryParams(request.url))
                        put("phase_id", request.phaseId)
                        put("host_class", request.hostClass)
                        put("normalized_host", request.normalizedHost)
                        put("normalized_path", request.normalizedPath)
                        put("target_site_id", targetSiteId)
                    },
                )
            }
        return JSONObject().apply {
            put("schema_version", 1)
            put("generated_at_utc", generatedAt)
            put("steps", steps)
        }
    }

    private fun isReplayRequestTargetRelevant(
        request: RequestEvent,
        targetHostHints: Set<String>,
    ): Boolean {
        val host = request.normalizedHost.lowercase(Locale.ROOT)
        val hostRelevant = targetHostHints.any { hint ->
            host == hint || host.endsWith(".$hint")
        }
        if (hostRelevant) return true
        if (request.hostClass.lowercase(Locale.ROOT).startsWith("target_")) return true
        val urlLower = request.url.lowercase(Locale.ROOT)
        return targetHostHints.any { hint ->
            urlLower.contains("://$hint/") || urlLower.contains("://www.$hint/")
        }
    }

    private fun targetHostHints(targetSiteId: String): Set<String> {
        val normalized = targetSiteId.trim().lowercase(Locale.ROOT)
            .replace('_', '.')
            .removePrefix("http://")
            .removePrefix("https://")
            .trim('/')
        if (normalized.isBlank()) return emptySet()
        val hints = linkedSetOf<String>()
        hints += normalized
        if (!normalized.startsWith("www.")) hints += "www.$normalized"
        return hints
    }

    private fun toResponseIndex(
        generatedAt: String,
        responses: List<ResponseEvent>,
    ): JSONObject {
        val items = JSONArray()
        responses
            .sortedWith(compareBy<ResponseEvent> { it.tsUtc }.thenBy { it.responseId }.thenBy { it.eventId })
            .forEach { response ->
                items.put(
                    JSONObject().apply {
                        put("event_id", response.eventId)
                        put("request_id", response.requestId)
                        put("response_id", response.responseId)
                        put("ts_utc", response.tsUtc)
                        put("url", response.url)
                        put("normalized_host", response.normalizedHost)
                        put("normalized_path", response.normalizedPath)
                        put("phase_id", response.phaseId)
                        put("host_class", response.hostClass)
                        put("status_code", response.statusCode)
                        put("mime_type", response.mimeType)
                        put("body_ref", response.bodyRef)
                        put("capture_truncated", false)
                    },
                )
            }
        return JSONObject().apply {
            put("schema_version", 1)
            put("generated_at_utc", generatedAt)
            put("items", items)
        }
    }

    private fun writeJson(runtimeRoot: File, relativePath: String, payload: JSONObject) {
        val target = File(runtimeRoot, relativePath)
        target.parentFile?.mkdirs()
        target.writeText(payload.toString(2) + "\n", Charsets.UTF_8)
    }

    private fun jsonObjects(array: JSONArray?): List<JSONObject> {
        if (array == null) return emptyList()
        val items = mutableListOf<JSONObject>()
        for (idx in 0 until array.length()) {
            val item = array.optJSONObject(idx) ?: continue
            items += item
        }
        return items
    }

    private fun jsonStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = mutableListOf<String>()
        for (idx in 0 until array.length()) {
            val value = array.opt(idx)?.toString()?.trim().orEmpty()
            if (value.isNotBlank()) out += value
        }
        return out
    }

    private fun requirementNames(requirements: JSONArray?, allowedStatuses: Set<String>): List<String> {
        if (requirements == null) return emptyList()
        val out = linkedSetOf<String>()
        for (idx in 0 until requirements.length()) {
            val item = requirements.optJSONObject(idx) ?: continue
            val status = item.optString("status").lowercase(Locale.ROOT)
            if (status !in allowedStatuses) continue
            val name = item.optString("name").trim()
            if (name.isNotBlank()) out += name
        }
        return out.toList().sorted()
    }

    private fun bundleRoleToProviderRole(role: String): String {
        return when (role) {
            "home" -> "home"
            "search" -> "search"
            "detail" -> "detail"
            "playbackResolver", "playback_resolver" -> "playback_resolver"
            "auth", "refresh" -> "auth_or_refresh"
            else -> ""
        }
    }

    private fun extractWarningMessages(rawWarnings: JSONArray?): List<String> {
        val warnings = linkedSetOf<String>()
        if (rawWarnings != null) {
            for (idx in 0 until rawWarnings.length()) {
                val item = rawWarnings.opt(idx)
                val rendered = when (item) {
                    null, JSONObject.NULL -> ""
                    is JSONObject -> item.optString("message").ifBlank { item.optString("warningCode") }
                    else -> item.toString()
                }.trim()
                if (rendered.isNotBlank()) warnings += rendered
            }
        }
        return warnings.toList().sorted()
    }

    private fun reduceHeadersForReplay(headers: Map<String, String>): Map<String, String> {
        val ignored = setOf("host", "connection", "content-length", "accept-encoding")
        return headers.entries
            .sortedBy { it.key.lowercase(Locale.ROOT) }
            .filter { it.key.lowercase(Locale.ROOT) !in ignored }
            .mapNotNull { entry ->
                val key = entry.key.lowercase(Locale.ROOT)
                val sanitized = when {
                    key == "cookie" -> "<cookie_from_runtime_session>"
                    key == "authorization" -> "<authorization_from_runtime_session>"
                    key.contains("token") -> "<token_from_runtime_session>"
                    key.contains("secret") -> "<secret_from_runtime_input>"
                    else -> entry.value
                }.trim()
                key.takeIf { it.isNotBlank() }?.let { it to sanitized }
            }
            .associate { it.first to it.second }
    }

    private fun parseQueryParams(url: String): JSONObject {
        val query = runCatching { URI(url).rawQuery.orEmpty() }.getOrDefault("")
        if (query.isBlank()) return JSONObject()
        val grouped = linkedMapOf<String, MutableList<String>>()
        query.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val rawName = pair.substringBefore('=')
            val rawValue = pair.substringAfter('=', "")
            val name = decodeUrlComponent(rawName)
            if (name.isBlank()) return@forEach
            val value = decodeUrlComponent(rawValue)
            grouped.getOrPut(name) { mutableListOf() } += value
        }
        return JSONObject().apply {
            grouped.forEach { (name, values) ->
                if (values.size == 1) {
                    put(name, values.first())
                } else {
                    put(name, JSONArray(values))
                }
            }
        }
    }

    private fun decodeUrlComponent(value: String): String {
        return runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
    }

    private fun parseQueryParamNamesFromRequest(url: String, payload: JSONObject): Set<String> {
        val names = linkedSetOf<String>()
        names += parseQueryParams(url).keys().asSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()

        val queryParams = payload.opt("query_params")
        when (queryParams) {
            is JSONObject -> {
                queryParams.keys().forEach { key ->
                    val normalized = key.trim()
                    if (normalized.isNotBlank()) names += normalized
                }
            }
            is JSONArray -> {
                for (idx in 0 until queryParams.length()) {
                    val item = queryParams.opt(idx)
                    if (item is JSONObject) {
                        val name = item.optString("name").trim().ifBlank { item.optString("key").trim() }
                        if (name.isNotBlank()) names += name
                    }
                }
            }
        }
        return names
    }

    private fun parseRequestBodyFieldNames(payload: JSONObject): Set<String> {
        val names = linkedSetOf<String>()
        val bodyCandidates = listOf(
            payload.opt("body_json"),
            payload.opt("body"),
            payload.opt("stable_body_template"),
            payload.opt("body_preview"),
            payload.opt("body_text"),
        )
        bodyCandidates.forEach { candidate ->
            collectRequestBodyFieldNames(candidate, names)
        }
        return names
    }

    private fun collectRequestBodyFieldNames(candidate: Any?, names: MutableSet<String>) {
        when (candidate) {
            null, JSONObject.NULL -> return
            is JSONObject -> {
                val leaves = mutableListOf<Pair<String, Any?>>()
                collectJsonLeaves(candidate, "", leaves)
                leaves.map { it.first }.filter { it.isNotBlank() }.forEach { names += it }
            }
            is JSONArray -> {
                val leaves = mutableListOf<Pair<String, Any?>>()
                collectJsonLeaves(candidate, "", leaves)
                leaves.map { it.first }.filter { it.isNotBlank() }.forEach { names += it }
            }
            is String -> {
                val text = candidate.trim()
                if (text.isBlank()) return
                val parsedJson = runCatching { JSONObject(text) }.getOrNull()
                    ?: runCatching { JSONArray(text) }.getOrNull()
                if (parsedJson != null) {
                    collectRequestBodyFieldNames(parsedJson, names)
                    return
                }
                if (text.contains("=") && text.contains("&")) {
                    text.split('&').forEach { part ->
                        val key = decodeUrlComponent(part.substringBefore('=').trim())
                        if (key.isNotBlank()) names += key
                    }
                }
            }
            else -> {
                val rendered = candidate.toString().trim()
                if (rendered.startsWith("{") || rendered.startsWith("[")) {
                    collectRequestBodyFieldNames(rendered, names)
                }
            }
        }
    }

    private fun buildFieldMappings(
        runtimeRoot: File,
        responses: List<ResponseEvent>,
        targetSiteId: String,
        roleByEndpoint: Map<String, String>,
        responseEndpointRefByEventId: Map<String, String>,
        supportsSearch: Boolean,
        supportsDetail: Boolean,
        supportsPlayback: Boolean,
    ): JSONArray {
        val evidence = linkedMapOf<String, FieldEvidence>()
        fieldOrder.forEach { field -> evidence[field] = FieldEvidence() }
        val endpointScoreById = scoreEndpointsByObservedResponses(responses, roleByEndpoint, responseEndpointRefByEventId)
        val firstEndpointRefByRole =
            roleByEndpoint
                .entries
                .groupBy { it.value }
                .mapValues { (_, refs) ->
                    refs
                        .map { it.key }
                        .sortedWith(
                            compareByDescending<String> { endpointScoreById[it] ?: 0.0 }
                                .thenBy { it.lowercase(Locale.ROOT) },
                        )
                        .firstOrNull()
                        .orEmpty()
                }
        val firstEndpointRefOverall = roleByEndpoint.keys.sortedBy { it.lowercase(Locale.ROOT) }.firstOrNull().orEmpty()
        val roleFieldPathCandidates = linkedMapOf<String, MutableMap<String, Pair<String, Double>>>()

        fun endpointRefFor(response: ResponseEvent, role: String): String {
            return responseEndpointRefByEventId[response.eventId]
                ?: firstEndpointRefByRole[role]
                ?: firstEndpointRefOverall
        }

        fun rememberRolePath(role: String, fieldName: String, path: String, confidence: Double) {
            if (role.isBlank() || path.isBlank()) return
            val byField = roleFieldPathCandidates.getOrPut(role) { linkedMapOf() }
            val current = byField[fieldName]
            if (current == null || confidence > current.second) {
                byField[fieldName] = path to confidence
            }
        }

        responses.forEach { response ->
            if (response.statusCode !in 200..399) return@forEach
            if (!isTargetResponse(response, targetSiteId)) return@forEach
            if (isLikelyNonContentResponse(response)) return@forEach
            val role = inferRole(response.phaseId, "${response.operation} ${response.routeKind}", response.normalizedPath, response.url)
            if (role == "helper") return@forEach
            val endpointRef = endpointRefFor(response, role)
            val body = readResponseBody(runtimeRoot, response)
            if (body.isBlank()) return@forEach
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (json != null) {
                val leaves = mutableListOf<Pair<String, Any?>>()
                collectJsonLeaves(json, "", leaves)
                leaves.forEach { (path, value) ->
                    val templatePath = normalizeTemplatePath(path)
                    if (templatePath.isBlank()) return@forEach
                    val lowered = templatePath.lowercase(Locale.ROOT)
                    val textValue = value?.toString().orEmpty()
                    if (textValue.isBlank()) return@forEach
                    val sourceKind = inferJsonSourceKind(templatePath, response.operation, response.routeKind)

                    val isTitlePath =
                        lowered.contains("title") ||
                            lowered.contains("headline") ||
                            lowered.endsWith(".name")
                    if (
                        isTitlePath &&
                        !lowered.contains("site_name") &&
                        !lowered.contains("channel_name") &&
                        !lowered.contains("display_name")
                    ) {
                        val confidence = if (lowered.endsWith("title") || lowered.contains(".title")) 0.92 else 0.78
                        setFieldEvidence(
                            field = evidence("title", evidence),
                            valueTemplate = templatePath,
                            sourceKind = sourceKind,
                            sourceRef = endpointRef,
                            role = role,
                            confidence = confidence,
                        )
                        rememberRolePath(role, "title", templatePath, confidence)
                    }
                    if (lowered.contains("subtitle") || lowered.contains("sub_title") || lowered.contains("subheadline")) {
                        val confidence = if (lowered.contains("subtitle")) 0.76 else 0.7
                        setFieldEvidence(
                            field = evidence("subtitle", evidence),
                            valueTemplate = templatePath,
                            sourceKind = sourceKind,
                            sourceRef = endpointRef,
                            role = role,
                            confidence = confidence,
                        )
                    }
                    if (lowered.contains("description") || lowered.contains("summary") || lowered.contains("plot") || lowered.contains("leadparagraph")) {
                        val confidence = if (lowered.contains("description") || lowered.contains("leadparagraph")) 0.84 else 0.74
                        setFieldEvidence(
                            field = evidence("description", evidence),
                            valueTemplate = templatePath,
                            sourceKind = sourceKind,
                            sourceRef = endpointRef,
                            role = role,
                            confidence = confidence,
                        )
                        rememberRolePath(role, "description", templatePath, confidence)
                    }
                    if ((lowered.contains("image") || lowered.contains("poster") || lowered.contains("thumbnail")) && textValue.startsWith("http", ignoreCase = true)) {
                        if (lowered.contains("errors.") || lowered.contains("fsk")) return@forEach
                        val confidence =
                            when {
                                lowered.contains("poster") -> 0.85
                                lowered.contains("imagewithoutlogo") -> 0.84
                                lowered.contains("thumbnail") -> 0.74
                                else -> 0.78
                            }
                        setFieldEvidence(
                            field = evidence("poster", evidence),
                            valueTemplate = templatePath,
                            sourceKind = sourceKind,
                            sourceRef = endpointRef,
                            role = role,
                            confidence = confidence,
                        )
                        rememberRolePath(role, "poster", templatePath, confidence)
                    }
                    if ((lowered.contains("backdrop") || lowered.contains("hero") || lowered.contains("landscape") || lowered.contains("banner") || lowered.contains("background")) && textValue.startsWith("http", ignoreCase = true)) {
                        val confidence = if (lowered.contains("backdrop") || lowered.contains("landscape")) 0.84 else 0.76
                        setFieldEvidence(
                            field = evidence("backdrop", evidence),
                            valueTemplate = templatePath,
                            sourceKind = sourceKind,
                            sourceRef = endpointRef,
                            role = role,
                            confidence = confidence,
                        )
                        rememberRolePath(role, "backdrop", templatePath, confidence)
                    }
                    if (lowered.contains("logo") && textValue.startsWith("http", ignoreCase = true)) {
                        setFieldEvidence(
                            field = evidence("logo", evidence),
                            valueTemplate = templatePath,
                            sourceKind = sourceKind,
                            sourceRef = endpointRef,
                            role = role,
                            confidence = 0.66,
                            derivationKind = "derived",
                        )
                        rememberRolePath(role, "logo", templatePath, 0.66)
                    }
                    if (lowered.contains("canonicalid") || lowered.contains("canonical_id") || lowered.endsWith("canonical") || lowered.contains("contentid") || lowered.contains("content_id")) {
                        setFieldEvidence(
                            field = evidence("canonicalId", evidence),
                            valueTemplate = templatePath,
                            sourceKind = sourceKind,
                            sourceRef = endpointRef,
                            role = role,
                            confidence = 0.95,
                        )
                        rememberRolePath(role, "canonicalId", templatePath, 0.95)
                    }
                    if (lowered.contains("collectionid") || lowered.contains("seriesid") || lowered.contains("collection_id") || lowered.contains("series_id")) {
                        setFieldEvidence(
                            field = evidence("collectionId", evidence),
                            valueTemplate = templatePath,
                            sourceKind = sourceKind,
                            sourceRef = endpointRef,
                            role = role,
                            confidence = 0.74,
                            derivationKind = "derived",
                        )
                    }
                    if (looksLikeCatalogItemTypeSignal(path = lowered, value = textValue)) {
                        setFieldEvidence(
                            field = evidence("itemType", evidence),
                            valueTemplate = templatePath,
                            sourceKind = sourceKind,
                            sourceRef = endpointRef,
                            role = role,
                            confidence = 0.78,
                        )
                        rememberRolePath(role, "itemType", templatePath, 0.78)
                        rememberRolePath(role, "type", templatePath, 0.78)
                    }
                    if (looksLikeGenreSignal(path = lowered, value = textValue)) {
                        rememberRolePath(role, "genre", templatePath, 0.72)
                    }
                    if (looksLikeYearSignal(path = lowered, value = textValue)) {
                        rememberRolePath(role, "year", templatePath, 0.7)
                    }
                    if (lowered.contains("playback") || lowered.contains("manifest") || lowered.contains("stream") || lowered.contains("ptmd")) {
                        setFieldEvidence(
                            field = evidence("playbackHint", evidence),
                            valueTemplate = templatePath,
                            sourceKind = sourceKind,
                            sourceRef = endpointRef,
                            role = role,
                            confidence = 0.86,
                        )
                        rememberRolePath(role, "playbackHint", templatePath, 0.86)
                    }
                    if (lowered.contains("section") || lowered.contains("rail") || lowered.contains("category") || lowered.contains("tab")) {
                        setFieldEvidence(
                            field = evidence("sectionName", evidence),
                            valueTemplate = templatePath,
                            sourceKind = sourceKind,
                            sourceRef = endpointRef,
                            role = role,
                            confidence = 0.7,
                        )
                    }
                }
            } else {
                val title = htmlTitle(body)
                if (title.isNotBlank()) {
                    setFieldEvidence(
                        field = evidence("title", evidence),
                        valueTemplate = "html.title",
                        sourceKind = "html",
                        sourceRef = endpointRef,
                        role = role,
                        confidence = 0.56,
                    )
                }
                val ogImage = htmlOgImage(body)
                if (ogImage.isNotBlank()) {
                    setFieldEvidence(
                        field = evidence("poster", evidence),
                        valueTemplate = "html.og:image",
                        sourceKind = "html",
                        sourceRef = endpointRef,
                        role = role,
                        confidence = 0.54,
                    )
                }
            }
        }

        if (supportsSearch) {
            val searchTemplate =
                buildStructuredRoleMappingTemplate(
                    roleFieldPathCandidates["search"],
                    keys = listOf("title", "poster", "canonicalId", "playbackHint", "itemType", "type", "genre", "year"),
                )
            if (searchTemplate != null) {
                setFieldEvidence(
                    field = evidence("searchMapping", evidence),
                    valueTemplate = searchTemplate,
                    sourceKind = "derived",
                    sourceRef = firstEndpointRefByRole["search"] ?: firstEndpointRefOverall,
                    role = "search",
                    confidence = 0.86,
                    derivationKind = "derived",
                )
            } else {
                setDerivedFallback(
                    field = evidence("searchMapping", evidence),
                    valueTemplate = JSONObject().put("title", "title").put("canonicalId", "canonical"),
                    sourceRef = firstEndpointRefByRole["search"] ?: firstEndpointRefOverall,
                    confidence = 0.6,
                    role = "search",
                )
            }
        }
        if (supportsDetail) {
            val detailTemplate =
                buildStructuredRoleMappingTemplate(
                    roleFieldPathCandidates["detail"],
                    keys = listOf("title", "description", "backdrop", "canonicalId", "playbackHint", "itemType", "type", "genre", "year"),
                )
            if (detailTemplate != null) {
                setFieldEvidence(
                    field = evidence("detailMapping", evidence),
                    valueTemplate = detailTemplate,
                    sourceKind = "derived",
                    sourceRef = firstEndpointRefByRole["detail"] ?: firstEndpointRefOverall,
                    role = "detail",
                    confidence = 0.88,
                    derivationKind = "derived",
                )
            } else {
                setDerivedFallback(
                    field = evidence("detailMapping", evidence),
                    valueTemplate = JSONObject().put("title", "title").put("canonicalId", "canonical"),
                    sourceRef = firstEndpointRefByRole["detail"] ?: firstEndpointRefOverall,
                    confidence = 0.6,
                    role = "detail",
                )
            }
        }
        if (!supportsDetail && evidence("detailMapping", evidence).derivationKind == "missing") {
            val inferredDetailTemplate =
                buildStructuredRoleMappingTemplate(
                    roleFieldPathCandidates["search"] ?: roleFieldPathCandidates["home"],
                    keys = listOf("title", "description", "backdrop", "canonicalId", "playbackHint", "itemType", "type", "genre", "year"),
                )
            if (inferredDetailTemplate != null) {
                setFieldEvidence(
                    field = evidence("detailMapping", evidence),
                    valueTemplate = inferredDetailTemplate,
                    sourceKind = "derived",
                    sourceRef = firstEndpointRefByRole["detail"] ?: firstEndpointRefByRole["search"] ?: firstEndpointRefOverall,
                    role = "detail",
                    confidence = 0.62,
                    derivationKind = "derived",
                )
            }
        }
        if (supportsPlayback && evidence("playbackHint", evidence).derivationKind == "missing") {
            setDerivedFallback(
                field = evidence("playbackHint", evidence),
                valueTemplate = "currentMedia.nodes[].ptmdTemplate",
                    sourceRef = firstEndpointRefByRole["playbackResolver"]
                        ?: firstEndpointRefByRole["playback_resolver"]
                        ?: firstEndpointRefOverall,
                confidence = 0.6,
                role = "playbackResolver",
            )
        }
        if ((supportsSearch || supportsDetail) && evidence("title", evidence).derivationKind == "missing") {
            setDerivedFallback(
                field = evidence("title", evidence),
                valueTemplate = "title",
                sourceRef = firstEndpointRefByRole["detail"] ?: firstEndpointRefOverall,
                confidence = 0.5,
                role = "detail",
            )
        }
        if ((supportsSearch || supportsDetail) && evidence("canonicalId", evidence).derivationKind == "missing") {
            setDerivedFallback(
                field = evidence("canonicalId", evidence),
                valueTemplate = "canonical",
                sourceRef = firstEndpointRefByRole["detail"] ?: firstEndpointRefOverall,
                confidence = 0.5,
                role = "detail",
            )
        }

        val result = JSONArray()
        fieldOrder.forEach { fieldName ->
            val row = evidence(fieldName, evidence)
            if (row.sourceRef.isBlank()) {
                row.sourceRef = firstEndpointRefOverall
            }
            result.put(
                JSONObject().apply {
                    put("fieldName", fieldName)
                    put("valueTemplate", row.valueTemplate ?: JSONObject.NULL)
                    put("sourceKind", row.sourceKind)
                    put("sourceRef", row.sourceRef)
                    put("observedInRoles", JSONArray(row.observedInRoles.toList()))
                    put("derivationKind", row.derivationKind)
                    put("confidence", row.confidence)
                },
            )
        }

        val rowsByField = mutableMapOf<String, JSONObject>()
        for (i in 0 until result.length()) {
            val row = result.optJSONObject(i) ?: continue
            rowsByField[row.optString("fieldName")] = row
        }

        val searchDefaultTemplate = JSONObject().apply {
            put("title", "title")
            put("poster", "poster")
            put("canonicalId", "canonicalId")
            put("playbackHint", "playbackHint")
            put("itemType", "itemType")
            put("type", "type")
            put("genre", "genre")
            put("year", "year")
        }
        if (supportsSearch) {
            val searchRow = rowsByField["searchMapping"]
            val searchTemplate = searchRow?.opt("valueTemplate")
            val searchMissing =
                searchRow == null ||
                    searchRow.optString("derivationKind") == "missing" ||
                    searchTemplate == null ||
                    searchTemplate == JSONObject.NULL
            if (searchMissing) {
                val sourceRef = firstEndpointRefByRole["search"] ?: firstEndpointRefOverall
                searchRow?.put("valueTemplate", searchDefaultTemplate)
                searchRow?.put("sourceKind", "derived")
                searchRow?.put("sourceRef", sourceRef)
                searchRow?.put("derivationKind", "derived")
                searchRow?.put("confidence", 0.6)
                searchRow?.put("observedInRoles", JSONArray().put("search"))
            }
        }

        val searchTemplateForFallback = rowsByField["searchMapping"]?.opt("valueTemplate")
        val detailFallbackTemplate = JSONObject().apply {
            put("title", "title")
            put("description", "description")
            put("backdrop", "backdrop")
            put("canonicalId", "canonicalId")
            put("playbackHint", "playbackHint")
            put("itemType", "itemType")
            put("type", "type")
            put("genre", "genre")
            put("year", "year")
        }
        val detailRow = rowsByField["detailMapping"]
        val detailTemplate = detailRow?.opt("valueTemplate")
        val detailMissing =
            detailRow != null &&
                (detailRow.optString("derivationKind") == "missing" ||
                    detailTemplate == null ||
                    detailTemplate == JSONObject.NULL)
        if (detailMissing) {
            val fallbackTemplate =
                when {
                    searchTemplateForFallback != null && searchTemplateForFallback != JSONObject.NULL -> searchTemplateForFallback
                    else -> detailFallbackTemplate
                }
            val sourceRef = firstEndpointRefByRole["detail"] ?: firstEndpointRefByRole["search"] ?: firstEndpointRefOverall
            detailRow.put("valueTemplate", fallbackTemplate)
            detailRow.put("sourceKind", "derived")
            detailRow.put("sourceRef", sourceRef)
            detailRow.put("derivationKind", "derived")
            detailRow.put("confidence", 0.62)
            detailRow.put("observedInRoles", JSONArray().put("detail"))
        }
        return result
    }

    private fun isTargetResponse(response: ResponseEvent, targetSiteId: String): Boolean {
        val hostClass = response.hostClass.lowercase(Locale.ROOT)
        if (hostClass.startsWith("target_")) return true
        val hints = targetHostHints(targetSiteId)
        val host = response.normalizedHost.lowercase(Locale.ROOT)
        if (hints.any { hint -> host == hint || host.endsWith(".$hint") }) return true
        val url = response.url.lowercase(Locale.ROOT)
        return hints.any { hint -> url.contains("://$hint/") || url.contains("://www.$hint/") }
    }

    private fun buildStructuredRoleMappingTemplate(
        roleFields: Map<String, Pair<String, Double>>?,
        keys: List<String>,
    ): JSONObject? {
        if (roleFields.isNullOrEmpty()) return null
        val payload = JSONObject()
        keys.forEach { key ->
            val path = roleFields[key]?.first?.trim().orEmpty()
            if (path.isNotEmpty()) {
                payload.put(key, path)
            }
        }
        return payload.takeIf { it.length() > 0 }
    }

    private fun inferJsonSourceKind(path: String, operation: String, routeKind: String): String {
        val loweredPath = path.lowercase(Locale.ROOT)
        val loweredOperation = operation.lowercase(Locale.ROOT)
        val loweredRouteKind = routeKind.lowercase(Locale.ROOT)
        return when {
            loweredPath.startsWith("data.") -> "graphql_json"
            loweredOperation.contains("graphql") -> "graphql_json"
            loweredRouteKind.contains("graphql") -> "graphql_json"
            else -> "rest_json"
        }
    }

    private fun normalizeTemplatePath(path: String): String {
        val normalized = path.trim()
        if (normalized.isBlank()) return ""
        return normalized
            .replace(Regex("\\[(\\d+)]"), "[]")
            .replace(Regex("\\[]\\[]+"), "[]")
    }

    private fun looksLikeCatalogItemTypeSignal(path: String, value: String): Boolean {
        val loweredPath = path.lowercase(Locale.ROOT)
        val loweredValue = value.trim().lowercase(Locale.ROOT)
        if (loweredValue.isBlank()) return false
        if (
            loweredPath.contains("token_type") ||
            loweredPath.contains("grant_type") ||
            loweredPath.contains("content-type")
        ) {
            return false
        }
        if (loweredValue.contains("bearer")) return false

        if (
            loweredValue in setOf("live", "live_collection", "series", "show", "episode", "movie", "film") ||
            loweredValue.contains("livestream")
        ) {
            return true
        }

        val pathLooksLikeType = listOf(
            "itemtype",
            "item_type",
            "teasertype",
            "teaser_type",
            "mediatype",
            "media_type",
            "programtype",
            "program_type",
            "contenttype",
            "content_type",
            "format",
            "genre",
        ).any { key -> loweredPath.contains(key) }
        if (!pathLooksLikeType) return false

        return loweredValue.contains("live") ||
            loweredValue.contains("episode") ||
            loweredValue.contains("series") ||
            loweredValue.contains("show") ||
            loweredValue.contains("season") ||
            loweredValue.contains("movie") ||
            loweredValue.contains("film") ||
            loweredValue.contains("feature")
    }

    private fun setFieldEvidence(
        field: FieldEvidence,
        valueTemplate: Any,
        sourceKind: String,
        sourceRef: String,
        role: String,
        confidence: Double,
        derivationKind: String = "direct",
    ) {
        val normalizedTemplate =
            when (valueTemplate) {
                is String -> valueTemplate.trim().take(300)
                else -> valueTemplate
            }
        val shouldReplace =
                field.derivationKind == "missing" ||
                confidence > field.confidence + 0.02 ||
                (
                    confidence >= field.confidence - 0.01 &&
                        field.sourceRef.isBlank() &&
                        sourceRef.isNotBlank()
                )
        if (shouldReplace) {
            field.valueTemplate = normalizedTemplate
            field.sourceKind = sourceKind
            field.sourceRef = sourceRef
            field.derivationKind = derivationKind
            field.confidence = confidence
        }
        if (role.isNotBlank()) field.observedInRoles += role
    }

    private fun setDerivedFallback(
        field: FieldEvidence,
        valueTemplate: Any,
        sourceRef: String,
        confidence: Double,
        role: String,
    ) {
        setFieldEvidence(
            field = field,
            valueTemplate = valueTemplate,
            sourceKind = "derived",
            sourceRef = sourceRef,
            role = role,
            confidence = confidence,
            derivationKind = "derived",
        )
    }

    private fun evidence(name: String, evidence: Map<String, FieldEvidence>): FieldEvidence {
        return evidence[name] ?: FieldEvidence()
    }

    private fun inferStreamContainers(responses: List<ResponseEvent>): List<String> {
        val out = linkedSetOf<String>()
        responses.forEach { response ->
            val lowerPath = response.normalizedPath.lowercase(Locale.ROOT)
            when {
                lowerPath.endsWith(".mp4") -> out += "mp4"
                lowerPath.endsWith(".webm") -> out += "webm"
                lowerPath.endsWith(".ts") || lowerPath.endsWith(".m4s") -> out += "ts"
                lowerPath.endsWith(".aac") -> out += "aac"
            }
        }
        if (out.isEmpty()) out += "unknown"
        return out.toList()
    }

    private fun inferManifestKinds(responses: List<ResponseEvent>): List<String> {
        val out = linkedSetOf<String>()
        responses.forEach { response ->
            val lowered = "${response.url} ${response.mimeType}".lowercase(Locale.ROOT)
            when {
                lowered.contains(".m3u8") || lowered.contains("mpegurl") -> out += "hls"
                lowered.contains(".mpd") || lowered.contains("dash+xml") -> out += "dash"
            }
        }
        if (out.isEmpty()) out += "none"
        return out.toList()
    }

    private fun selectPlaybackEndpointId(endpoints: Collection<EndpointAggregate>): String? {
        val candidates = endpoints
            .filter { it.role == "playbackResolver" || it.role == "playback_resolver" }
        if (candidates.isEmpty()) return null
        return candidates
            .sortedWith(
                compareByDescending<EndpointAggregate> { playbackEndpointScore(it) }
                    .thenByDescending { it.confidence }
                    .thenBy { it.endpointId.lowercase(Locale.ROOT) },
            )
            .first()
            .endpointId
    }

    private fun playbackEndpointScore(endpoint: EndpointAggregate): Int {
        val normalizedPath = endpoint.normalizedPath.lowercase(Locale.ROOT)
        val normalizedOperation = endpoint.requestOperation.lowercase(Locale.ROOT)
        var score = 0
        if (endpoint.method == "GET") score += 10
        if (normalizedOperation.contains("playback_manifest_fetch")) score += 60
        if (normalizedPath.endsWith(".m3u8") || normalizedPath.endsWith(".mpd")) score += 50
        if (normalizedPath.contains("/ptmd/") || normalizedPath.contains("/tmd/")) score += 140
        if ("canonical" in endpoint.requiredQueryParams) score += 50
        if (normalizedOperation.contains("playback_history")) score -= 140
        if (normalizedPath.contains("/usage-data/user-histories/")) score -= 180
        if (isPlaybackNoiseEndpoint(endpoint)) score -= 200
        if (endpoint.method == "OPTIONS" || endpoint.method == "PATCH") score -= 40
        score -= endpoint.requiredHeaders.size * 8
        return score
    }

    private fun requiredTokenInputs(
        provenanceInputs: Set<String>,
        endpoints: Collection<EndpointAggregate>,
    ): JSONArray {
        val out = JSONArray()
        provenanceInputs.sorted().forEach { inputName ->
            val requiredFor = endpoints
                .filter { endpoint -> inputName in endpoint.requiredProvenanceInputs }
                .map { it.endpointId }
                .distinct()
                .sorted()
            val lower = inputName.lowercase(Locale.ROOT)
            val confidentiality =
                when {
                    lower.contains("password") ||
                        lower.contains("client_secret") ||
                        lower.contains("code_verifier") ||
                        lower.contains("refresh_token") ||
                        lower.contains("access_token") ||
                        lower.contains("id_token") ||
                        lower.contains("assertion") ||
                        lower.contains("otp") ||
                        lower.contains("pin") -> "secret"
                    lower.contains("auth") ||
                        lower.contains("token") ||
                        lower.contains("authorization") ||
                        lower.startsWith("cookies.") -> "hash_only"
                    else -> "non_secret"
                }
            out.put(
                JSONObject().apply {
                    put("inputName", inputName)
                    put("requiredFor", JSONArray(requiredFor.take(20)))
                    put("provenanceRef", "prov:${normalizeProvenanceName(inputName)}")
                    put("confidentiality", confidentiality)
                },
            )
        }
        return out
    }

    private fun isRefreshEndpointCandidate(endpoint: EndpointAggregate): Boolean {
        val candidate = "${endpoint.requestOperation} ${endpoint.normalizedPath} ${endpoint.endpointId}"
            .lowercase(Locale.ROOT)
        return candidate.contains("refresh") || candidate.contains("token_refresh") || candidate.contains("/refresh")
    }

    private fun isLoginEndpointCandidate(endpoint: EndpointAggregate): Boolean {
        val candidate = "${endpoint.requestOperation} ${endpoint.normalizedPath} ${endpoint.endpointId}"
            .lowercase(Locale.ROOT)
        if (isRefreshEndpointCandidate(endpoint)) return false
        return candidate.contains("login") ||
            candidate.contains("signin") ||
            candidate.contains("authorize") ||
            candidate.contains("oidc") ||
            candidate.contains("oauth/token") ||
            candidate.contains("/token")
    }

    private fun loginEndpointScore(endpoint: EndpointAggregate): Int {
        val candidate = "${endpoint.requestOperation} ${endpoint.normalizedPath} ${endpoint.endpointId}"
            .lowercase(Locale.ROOT)
        var score = 0
        if (candidate.contains("auth_login") || candidate.contains("/login") || candidate.contains("signin")) score += 160
        if (candidate.contains("oidc") || candidate.contains("oauth/token") || candidate.contains("authorize")) score += 110
        if (candidate.contains("/token")) score += 40
        if (candidate.contains("refresh")) score -= 140
        score += endpoint.requiredBodyFields.size * 12
        score += endpoint.requiredQueryParams.size * 6
        return score
    }

    private fun isValidationEndpointCandidate(endpoint: EndpointAggregate): Boolean {
        val candidate = "${endpoint.requestOperation} ${endpoint.normalizedPath} ${endpoint.endpointId}"
            .lowercase(Locale.ROOT)
        if (isRefreshEndpointCandidate(endpoint)) return false
        return candidate.contains("validate") ||
            candidate.contains("validation") ||
            candidate.contains("userinfo") ||
            candidate.contains("userdetails") ||
            candidate.contains("session") ||
            candidate.contains("profile") ||
            candidate.contains("/me")
    }

    private data class TokenLifecycleInsights(
        val ttlHints: List<String>,
        val refreshTriggers: List<String>,
    )

    private fun collectTokenLifecycleInsights(
        runtimeRoot: File,
        responses: List<ResponseEvent>,
    ): TokenLifecycleInsights {
        val ttlHints = linkedSetOf<String>()
        val refreshTriggers = linkedSetOf<String>()
        responses.forEach { response ->
            val role = inferRole(response.phaseId, response.operation, response.normalizedPath, response.url)
            if (response.statusCode == 401 || response.statusCode == 403) {
                refreshTriggers += "refresh_trigger:http_${response.statusCode}"
            }
            val isAuthLike = role == "auth" || role == "refresh" ||
                response.operation.contains("auth", ignoreCase = true) ||
                response.operation.contains("token", ignoreCase = true) ||
                response.operation.contains("refresh", ignoreCase = true)
            if (!isAuthLike) return@forEach
            val body = readResponseBody(runtimeRoot, response)
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: runCatching { JSONArray(body) }.getOrNull()
                ?: return@forEach
            val leaves = mutableListOf<Pair<String, Any?>>()
            collectJsonLeaves(json, "", leaves)
            leaves.forEach { (path, _) ->
                val lowered = path.lowercase(Locale.ROOT)
                when {
                    lowered.contains("expires_in") -> ttlHints += "expires_in"
                    lowered.endsWith(".exp") || lowered == "exp" -> ttlHints += "exp"
                    lowered.contains("expiresat") || lowered.contains("expires_at") || lowered.contains("expiry") -> ttlHints += "expires_at"
                    lowered.contains("ttl") -> ttlHints += "ttl"
                }
            }
            if (isRefreshEndpointCandidate(
                    EndpointAggregate(
                        endpointId = "insight",
                        role = role,
                        method = "GET",
                        normalizedHost = response.normalizedHost,
                        normalizedPath = response.normalizedPath,
                        requestOperation = response.operation,
                    ),
                )
            ) {
                refreshTriggers += "refresh_endpoint_observed"
            }
        }
        return TokenLifecycleInsights(
            ttlHints = ttlHints.toList().sorted(),
            refreshTriggers = refreshTriggers.toList().sorted(),
        )
    }

    private fun normalizeProvenanceName(name: String): String {
        return name
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_', '.', '-')
            .ifBlank { "value" }
    }

    private fun namedRequirements(names: Set<String>, status: String, cookie: Boolean = false): JSONArray {
        val out = JSONArray()
        names.sorted().forEach { name ->
            val reference = if (cookie) "cookies.$name" else name
            out.put(
                JSONObject().apply {
                    put("name", name)
                    put("status", status)
                    put("provenanceRef", if (status == "required_proven") "prov:${normalizeProvenanceName(reference)}" else JSONObject.NULL)
                },
            )
        }
        return out
    }

    private fun inferAuthMode(
        requiresLogin: Boolean,
        requiredHeaders: Set<String>,
        requiredCookies: Set<String>,
        requiresBrowserSession: Boolean,
    ): String {
        if (!requiresLogin) return "none"
        return when {
            requiresBrowserSession -> "browser_required"
            requiredHeaders.isNotEmpty() && requiredCookies.isNotEmpty() -> "hybrid"
            requiredHeaders.isNotEmpty() -> "token"
            requiredCookies.isNotEmpty() -> "cookie"
            else -> "browser_required"
        }
    }

    private fun hasAuthArtifactSignals(
        requiredHeaders: Set<String>,
        requiredCookies: Set<String>,
        tokenInputs: Set<String>,
    ): Boolean {
        val markers = listOf(
            "auth",
            "token",
            "bearer",
            "refresh",
            "jwt",
            "sessionid",
            "sessid",
            "id_token",
            "access_token",
            "refresh_token",
            "authorization",
            "xsrf",
            "csrf",
        )
        fun isAuthLike(name: String): Boolean {
            val normalized = name.trim().lowercase(Locale.ROOT)
            if (normalized.isBlank()) return false
            return markers.any { marker ->
                normalized == marker ||
                    normalized.contains("_$marker") ||
                    normalized.contains("-$marker") ||
                    normalized.contains(".$marker") ||
                    normalized.contains(marker)
            }
        }
        return requiredHeaders.any(::isAuthLike) ||
            requiredCookies.any(::isAuthLike) ||
            tokenInputs.any(::isAuthLike)
    }

    private fun isStableRequiredInputName(rawName: String): Boolean {
        val name = rawName.trim().lowercase(Locale.ROOT)
        if (name.isBlank()) return false
        val volatileMarkers = listOf(
            "ts",
            "timestamp",
            "nonce",
            "request_id",
            "trace_id",
            "span_id",
            "cache_bust",
            "cb",
            "_",
        )
        return volatileMarkers.none { marker ->
            name == marker || name.endsWith(".$marker") || name.contains("_$marker") || name.contains(".$marker")
        }
    }

    private fun buildEndpointTemplates(
        role: String,
        normalizedPath: String,
        requestOperation: String,
        requiredQueryParams: Set<String>,
        optionalQueryParams: Set<String>,
        requiredBodyFields: Set<String>,
        optionalBodyFields: Set<String>,
        requiredProvenanceInputs: Set<String>,
    ): EndpointTemplatePayload {
        val pathTemplatePayload = buildPathTemplate(
            role = role,
            normalizedPath = normalizedPath,
            requestOperation = requestOperation,
        )
        val queryTemplate = JSONObject()
        val bodyTemplate = JSONObject()
        val placeholders = JSONArray()
        val seenPlaceholders = linkedSetOf<String>()
        val sanitizedRequiredQuery = requiredQueryParams
            .map { sanitizePlaceholderName(it) }
            .filter { it.isNotBlank() }
            .toSet()
        val sanitizedRequiredBody = requiredBodyFields
            .map { sanitizePlaceholderName(it) }
            .filter { it.isNotBlank() }
            .toSet()

        pathTemplatePayload.pathPlaceholders.forEach { name ->
            val key = "path:$name"
            if (seenPlaceholders.add(key)) {
                placeholders.put(
                    JSONObject().apply {
                        put("name", name)
                        put("location", "path")
                        put("required", true)
                        put("valueType", "string")
                        put("defaultTemplate", JSONObject.NULL)
                    },
                )
            }
        }

        val allQuery = (requiredQueryParams + optionalQueryParams).sortedBy { it.lowercase(Locale.ROOT) }
        allQuery.forEach { name ->
            val normalized = sanitizePlaceholderName(name)
            if (normalized.isBlank()) return@forEach
            queryTemplate.put(normalized, placeholderToken(normalized))
            val key = "query:$normalized"
            if (seenPlaceholders.add(key)) {
                placeholders.put(
                    JSONObject().apply {
                        put("name", normalized)
                        put("location", "query")
                        put("required", normalized in sanitizedRequiredQuery)
                        put("valueType", "string")
                        put("defaultTemplate", JSONObject.NULL)
                    },
                )
            }
        }

        val allBody = (requiredBodyFields + optionalBodyFields).sortedBy { it.lowercase(Locale.ROOT) }
        allBody.forEach { name ->
            val normalized = sanitizePlaceholderName(name)
            if (normalized.isBlank()) return@forEach
            bodyTemplate.put(normalized, placeholderToken(normalized))
            val key = "body:$normalized"
            if (seenPlaceholders.add(key)) {
                placeholders.put(
                    JSONObject().apply {
                        put("name", normalized)
                        put("location", "body")
                        put("required", normalized in sanitizedRequiredBody)
                        put("valueType", "string")
                        put("defaultTemplate", JSONObject.NULL)
                    },
                )
            }
        }

        requiredProvenanceInputs
            .map { sanitizePlaceholderName(it) }
            .filter { it.isNotBlank() }
            .sortedBy { it.lowercase(Locale.ROOT) }
            .forEach { name ->
                val isCookieProvenance = name.startsWith("cookies.")
                val isHeaderProvenance = name in setOf("authorization", "api-auth")
                val pathExists = name in pathTemplatePayload.pathPlaceholders
                val queryExists = queryTemplate.has(name)
                val bodyExists = bodyTemplate.has(name)
                val location = when {
                    isCookieProvenance -> "cookie"
                    isHeaderProvenance -> "header"
                    pathExists -> "path"
                    queryExists -> "query"
                    bodyExists -> "body"
                    else -> "unresolved"
                }
                val key = "$location:$name"
                if (seenPlaceholders.add(key)) {
                    placeholders.put(
                        JSONObject().apply {
                            put("name", name)
                            put("location", location)
                            put("required", true)
                            put("valueType", "string")
                            put("defaultTemplate", JSONObject.NULL)
                        },
                    )
                }
            }

        return EndpointTemplatePayload(
            pathTemplate = pathTemplatePayload.pathTemplate,
            queryTemplate = queryTemplate,
            bodyTemplate = bodyTemplate,
            variablePlaceholders = placeholders,
        )
    }

    private data class PathTemplatePayload(
        val pathTemplate: String,
        val pathPlaceholders: Set<String>,
    )

    private fun buildPathTemplate(
        role: String,
        normalizedPath: String,
        requestOperation: String,
    ): PathTemplatePayload {
        val segments = normalizedPath.trim().trim('/').split('/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (segments.isEmpty()) {
            return PathTemplatePayload(pathTemplate = "/", pathPlaceholders = emptySet())
        }
        val placeholders = linkedSetOf<String>()
        var dynamicCounter = 0
        val templatedSegments = segments.mapIndexed { index, segment ->
            if (!shouldTemplatePathSegment(role, requestOperation, segment, index, segments.lastIndex)) {
                return@mapIndexed segment
            }
            val placeholder = inferPathPlaceholderName(
                role = role,
                segment = segment,
                segmentIndex = index,
                dynamicIndex = dynamicCounter++,
            )
            placeholders += placeholder
            placeholderToken(placeholder)
        }
        return PathTemplatePayload(
            pathTemplate = "/" + templatedSegments.joinToString("/"),
            pathPlaceholders = placeholders,
        )
    }

    private fun shouldTemplatePathSegment(
        role: String,
        requestOperation: String,
        segment: String,
        segmentIndex: Int,
        lastIndex: Int,
    ): Boolean {
        val normalized = segment.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank()) return false
        if (normalized in setOf(
                "api",
                "graphql",
                "home",
                "search",
                "detail",
                "playback",
                "resolver",
                "manifest",
                "auth",
                "oauth",
                "login",
                "refresh",
                "userinfo",
                "session",
                "content",
                "v1",
                "v2",
                "v3",
            )
        ) {
            return false
        }
        if (normalized.matches(Regex("^[0-9]+$")) && normalized.length >= 2) return true
        if (normalized.matches(Regex("^[0-9a-f]{8,}$"))) return true
        if (normalized.matches(Regex("^[0-9a-f]{8}-[0-9a-f-]{12,}$"))) return true
        val hasDigit = normalized.any { it.isDigit() }
        val longToken = normalized.length >= 10 && normalized.any { it.isLetter() }
        if (hasDigit && longToken) return true
        val operation = requestOperation.lowercase(Locale.ROOT)
        val idLikeSlug = (normalized.contains('_') || normalized.contains('-')) && normalized.any { it.isDigit() }
        if (idLikeSlug && normalized.length >= 4) return true
        if (role in setOf("detail", "playbackResolver", "playback_resolver") &&
            segmentIndex == lastIndex &&
            normalized.length >= 4 &&
            normalized !in setOf("index", "list")
        ) {
            return true
        }
        if (operation.contains("detail") && segmentIndex == lastIndex && normalized.length >= 4) {
            return true
        }
        return false
    }

    private fun inferPathPlaceholderName(
        role: String,
        segment: String,
        segmentIndex: Int,
        dynamicIndex: Int,
    ): String {
        val normalized = segment.trim().lowercase(Locale.ROOT)
        val roleLower = role.lowercase(Locale.ROOT)
        val preferred = when {
            roleLower.contains("detail") || roleLower.contains("playback") -> "canonical"
            normalized.contains("episode") -> "episodeId"
            normalized.contains("video") -> "videoId"
            normalized.contains("asset") -> "assetId"
            normalized.contains("channel") -> "channelId"
            normalized.matches(Regex("^[0-9]+$")) -> "id"
            else -> "pathParam${segmentIndex + 1 + dynamicIndex}"
        }
        return sanitizePlaceholderName(preferred).ifBlank { "pathParam${segmentIndex + 1}" }
    }

    private fun sanitizePlaceholderName(raw: String): String {
        return raw
            .trim()
            .replace(Regex("[^A-Za-z0-9._\\[\\]-]+"), "_")
            .trim('_', '.', '-')
            .ifBlank { "" }
            .take(120)
    }

    private fun placeholderToken(name: String): String = "\${$name}"

    private fun templateKindForRole(role: String, path: String): String {
        val lowered = path.lowercase(Locale.ROOT)
        return when {
            lowered == "/graphql" || lowered.contains("/graphql/") -> "graphql"
            lowered.endsWith(".m3u8") || lowered.endsWith(".mpd") -> "manifest"
            role == "playbackResolver" || role == "playback_resolver" -> "resolver"
            role == "auth" || role == "refresh" -> "rest_json"
            else -> "rest_json"
        }
    }

    private fun writeSourcePluginBundleZip(
        zipPath: File,
        sourcePipelineBundlePath: File,
        siteRuntimeModelPath: File,
        manifestPath: File,
    ) {
        ZipOutputStream(FileOutputStream(zipPath)).use { zip ->
            writeZipEntry(zip, "source_pipeline_bundle.json", sourcePipelineBundlePath.readBytes())
            writeZipEntry(zip, "site_runtime_model.json", siteRuntimeModelPath.readBytes())
            writeZipEntry(zip, "manifest.json", manifestPath.readBytes())
        }
    }

    private fun writeZipEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.time = 0L
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun toHeaderMap(raw: Any?): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val obj = raw as? JSONObject ?: return out
        obj.keys().forEach { key ->
            val value = obj.optString(key)
            if (key.isNotBlank() && value.isNotBlank()) {
                out[key.lowercase(Locale.ROOT)] = value
            }
        }
        return out
    }

    private fun parseCookieNames(rawCookie: String): Set<String> {
        val names = linkedSetOf<String>()
        rawCookie.split(';').forEach { part ->
            val name = part.substringBefore('=').trim().lowercase(Locale.ROOT)
            if (name.isNotBlank()) names += name
        }
        return names
    }

    private fun inferProvenanceInputsFromHeaders(headers: Map<String, String>): Set<String> {
        val out = linkedSetOf<String>()
        headers.forEach { (name, value) ->
            when (name.lowercase(Locale.ROOT)) {
                "authorization" -> out += "authorization"
                "api-auth", "x-api-auth", "x-auth-token" -> out += "api-auth"
                "cookie" -> {
                    parseCookieNames(value).forEach { cookieName ->
                        out += "cookies.$cookieName"
                    }
                }
            }
        }
        return out
    }

    private fun inferAuthProvenanceInputsFromRequest(
        role: String,
        operation: String,
        normalizedPath: String,
        queryParamNames: Set<String>,
        bodyFieldNames: Set<String>,
    ): Set<String> {
        val context = "$role $operation $normalizedPath".lowercase(Locale.ROOT)
        val authLike =
            role == "auth" ||
                role == "refresh" ||
                listOf("auth", "token", "login", "logout", "oidc", "openid", "oauth", "refresh", "identity")
                    .any { marker -> context.contains(marker) }
        if (!authLike) return emptySet()

        val out = linkedSetOf<String>()
        (queryParamNames + bodyFieldNames).forEach { rawName ->
            normalizeAuthProvenanceInputName(rawName)?.let { normalized ->
                out += normalized
            }
        }
        return out
    }

    private fun normalizeAuthProvenanceInputName(rawName: String): String? {
        val normalized =
            rawName
                .trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9._-]+"), "_")
                .trim('_', '.', '-')
        if (normalized.isBlank()) return null
        return when {
            normalized == "authorization" -> "authorization"
            normalized == "api-auth" || normalized == "api_auth" || normalized.contains("api_auth") -> "api-auth"
            normalized.contains("code_verifier") -> "code_verifier"
            normalized == "code" || normalized.endsWith("_code") -> "code"
            normalized.contains("refresh_token") -> "refresh_token"
            normalized.contains("access_token") -> "access_token"
            normalized.contains("id_token") -> "id_token"
            normalized.contains("client_id") -> "client_id"
            normalized.contains("client_secret") -> "client_secret"
            normalized.contains("session_token") -> "session_token"
            normalized.contains("password") -> "password"
            normalized.contains("username") -> "username"
            normalized == "email" || normalized.endsWith("_email") -> "email"
            normalized.contains("pin") -> "pin"
            normalized.contains("otp") -> "otp"
            normalized.contains("nonce") -> "nonce"
            normalized == "state" || normalized.endsWith("_state") -> "state"
            else -> null
        }
    }

    private fun inferTargetSiteId(requests: List<RequestEvent>): String {
        val topHost = requests
            .map { it.normalizedHost }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            .orEmpty()
        return if (topHost.isBlank()) {
            "unknown_target"
        } else {
            normalizeTargetSiteId(topHost)
        }
    }

    private fun normalizeTargetSiteId(raw: String): String {
        val value = raw.trim().lowercase(Locale.ROOT)
        if (value.isBlank()) return "unknown_target"
        return value
            .removePrefix("www.")
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_', '.', '-')
            .ifBlank { "unknown_target" }
    }

    private fun normalizePhaseId(raw: String): String {
        return when (raw.trim()) {
            "home_probe", "search_probe", "detail_probe", "playback_probe", "auth_probe", "background_noise" -> raw.trim()
            "unscoped" -> "background_noise"
            else -> "background_noise"
        }
    }

    private fun roleFromPhase(phaseId: String): String {
        return when (phaseId) {
            "home_probe" -> "home"
            "search_probe" -> "search"
            "detail_probe" -> "detail"
            "playback_probe" -> "playbackResolver"
            "auth_probe" -> "auth"
            else -> "helper"
        }
    }

    private fun inferRole(phaseId: String, operation: String, normalizedPath: String, url: String): String {
        val lowered = "$operation $normalizedPath $url".lowercase(Locale.ROOT)
        if (looksLikeNonContentOperation(lowered) || isLikelyStaticAssetPath(normalizedPath, url)) {
            return "helper"
        }
        if (lowered.contains("playback") || lowered.contains("resolver") || lowered.contains("manifest") || lowered.contains(".m3u8") || lowered.contains(".mpd") || lowered.contains("/ptmd/") || lowered.contains("/tmd/")) {
            return "playbackResolver"
        }
        if (lowered.contains("refresh") || lowered.contains("token_refresh") || lowered.contains("/refresh")) {
            return "refresh"
        }
        if (lowered.contains("auth") || lowered.contains("token") || lowered.contains("login")) {
            return "auth"
        }
        if (lowered.contains("config") || lowered.contains("/settings") || lowered.contains("/configuration")) {
            return "config"
        }
        if (lowered.contains("detail") || lowered.contains("episode") || lowered.contains("/content/") || lowered.contains("/video/") || lowered.contains("/serien/") || lowered.contains("/filme/")) {
            return "detail"
        }
        if (lowered.contains("search") || lowered.contains("query") || lowered.contains("suggest")) {
            return "search"
        }
        val byPhase = roleFromPhase(phaseId)
        if (byPhase != "helper") return byPhase
        return if (lowered == "/" || lowered.contains("home")) "home" else "helper"
    }

    private fun normalizedUrl(url: String, payload: JSONObject): Pair<String, String> {
        val host = payload.optString("normalized_host").takeIf { it.isNotBlank() }
        val path = payload.optString("normalized_path").takeIf { it.isNotBlank() }
        if (host != null && path != null) return host.lowercase(Locale.ROOT) to path

        val parsed = runCatching { URI(url) }.getOrNull()
        val normalizedHost = (parsed?.host ?: "").trim().lowercase(Locale.ROOT)
        val normalizedPath = (parsed?.path ?: "/").ifBlank { "/" }
        return normalizedHost to normalizedPath
    }

    private fun readResponseBody(runtimeRoot: File, response: ResponseEvent): String {
        val bodyRef = response.bodyRef.trim()
        if (bodyRef.isNotBlank()) {
            val fullBody = readBodyFromRef(runtimeRoot, bodyRef)
            if (fullBody.isNotBlank()) return fullBody
        }

        val preview = response.bodyPreview.trim()
        return preview
    }

    private fun readBodyFromRef(runtimeRoot: File, bodyRef: String): String {
        val candidates = mutableListOf<File>()
        val bodyRefFile = File(bodyRef)
        if (bodyRefFile.isAbsolute) candidates += bodyRefFile
        val bodyRefTail = bodyRef.substringAfterLast('/')
        if (bodyRefTail.isNotBlank()) {
            candidates += File(runtimeRoot, "response_store/$bodyRefTail")
            candidates += File(runtimeRoot, bodyRefTail)
        }
        candidates += File(runtimeRoot, bodyRef.removePrefix("files/runtime-toolkit/").trimStart('/'))

        val file = candidates.firstOrNull { it.exists() && it.isFile && it.length() > 0L } ?: return ""
        return runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
    }

    private fun scoreEndpointsByObservedResponses(
        responses: List<ResponseEvent>,
        roleByEndpoint: Map<String, String>,
        responseEndpointRefByEventId: Map<String, String>,
    ): Map<String, Double> {
        val scoreById = linkedMapOf<String, Double>()
        responses.forEach { response ->
            if (response.statusCode !in 200..399) return@forEach
            val endpointRef = responseEndpointRefByEventId[response.eventId].orEmpty()
            if (endpointRef.isBlank()) return@forEach
            val role = roleByEndpoint[endpointRef].orEmpty()
            if (role.isBlank()) return@forEach
            if (isLikelyNonContentResponse(response)) return@forEach
            val mime = response.mimeType.lowercase(Locale.ROOT)
            val operation = response.operation.lowercase(Locale.ROOT)
            val path = response.normalizedPath.lowercase(Locale.ROOT)
            var score = 1.0
            if (mime.contains("json")) score += 3.0
            if (path == "/graphql" || path.contains("/graphql/")) score += 3.5
            if (response.normalizedHost.lowercase(Locale.ROOT).startsWith("api.")) score += 1.2
            if (operation.contains("search") || operation.contains("detail") || operation.contains("catalog")) score += 0.8
            if (role == "playbackResolver" && (path.contains("/ptmd/") || path.contains("/tmd/") || path.contains("manifest"))) score += 2.0
            scoreById[endpointRef] = (scoreById[endpointRef] ?: 0.0) + score
        }
        return scoreById
    }

    private fun looksLikeNonContentOperation(loweredOperationContext: String): Boolean {
        return listOf(
            "asset_fetch",
            "tracking_event",
            "telemetry",
            "analytics",
            "beacon",
            "pixel",
            "cmp",
            "consent",
        ).any { marker -> loweredOperationContext.contains(marker) }
    }

    private fun isLikelyStaticAssetPath(normalizedPath: String, url: String): Boolean {
        val path = normalizedPath.trim().lowercase(Locale.ROOT)
        val loweredUrl = url.lowercase(Locale.ROOT)
        if (path.isBlank()) return false
        if (path == "/") return false
        if (path == "/graphql" || path.contains("/graphql/")) return false
        if (path.contains("/ptmd/") || path.contains("/tmd/")) return false
        if (path.startsWith("/_next/static/") || path.contains("/_next/image")) return true
        if (path.startsWith("/assets/")) return true
        if (path.contains("/fotobase-webdelivery/images/")) return true
        if (path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".map")) return true
        if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".svg") || path.endsWith(".ico")) return true
        if (path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") || path.endsWith(".otf")) return true
        if (path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".m4s") || path.endsWith(".ts")) return true
        if (path.endsWith(".vtt") || path.endsWith(".srt") || path.endsWith(".ttml") || path.endsWith(".webvtt")) return true
        if (path.endsWith("/geo.txt")) return true
        if (
            loweredUrl.contains("tracksrv") ||
            loweredUrl.contains("sentry") ||
            loweredUrl.contains("epg-image") ||
            loweredUrl.contains("google-analytics") ||
            loweredUrl.contains("googletagmanager")
        ) {
            return true
        }
        if (loweredUrl.contains("/_next/static/")) return true
        return false
    }

    private fun isLikelyNonContentResponse(response: ResponseEvent): Boolean {
        val mime = response.mimeType.lowercase(Locale.ROOT)
        if (mime.startsWith("image/") || mime.contains("font") || mime.contains("javascript") || mime.contains("text/css")) return true
        val loweredOperationContext = "${response.operation} ${response.routeKind} ${response.normalizedPath} ${response.url}".lowercase(Locale.ROOT)
        if (looksLikeNonContentOperation(loweredOperationContext)) return true
        return isLikelyStaticAssetPath(response.normalizedPath, response.url)
    }

    private fun normalizePhaseRelevanceForExport(phaseIds: Set<String>, role: String): List<String> {
        val normalized = phaseIds
            .map { normalizePhaseId(it) }
            .filter { it in finalPhaseSet }
        if (normalized.isNotEmpty()) {
            return normalized.distinct()
        }
        return when (role) {
            "home" -> listOf("home_probe")
            "search" -> listOf("search_probe")
            "detail" -> listOf("detail_probe")
            "playbackResolver", "playback_resolver" -> listOf("playback_probe")
            "auth", "refresh" -> listOf("auth_probe")
            else -> emptyList()
        }
    }

    private fun isPlaybackNoiseEndpoint(endpoint: EndpointAggregate): Boolean {
        val path = endpoint.normalizedPath.lowercase(Locale.ROOT)
        val lowered = "${endpoint.requestOperation} ${endpoint.normalizedHost} $path".lowercase(Locale.ROOT)
        if (path.contains("/usage-data/") || path.contains("/user-histories/")) return true
        if (path.endsWith("/geo.txt") || lowered.contains("geo.txt")) return true
        if (path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".aac") || path.endsWith(".mp4")) return true
        if (path.endsWith(".vtt") || path.endsWith(".srt") || path.endsWith(".ttml") || path.endsWith(".webvtt")) return true
        if (lowered.contains("subtitle") || lowered.contains("captions")) return true
        return false
    }

    private fun isNoiseEndpoint(endpoint: EndpointAggregate): Boolean {
        val host = endpoint.normalizedHost.lowercase(Locale.ROOT)
        val path = endpoint.normalizedPath.lowercase(Locale.ROOT)
        val loweredContext = "${endpoint.requestOperation} $host $path".lowercase(Locale.ROOT)
        if (looksLikeNonContentOperation(loweredContext)) return true
        if (isLikelyStaticAssetPath(path, "https://$host$path")) return true
        if (host.contains("analytics") || host.contains("measurement") || host.contains("metrics")) return true
        if (host.contains("sentry") || host.contains("tracksrv") || host.contains("googletagmanager")) return true
        if (path.contains("/usage-data/") || path.contains("/user-histories/")) return true
        if (path.endsWith("/geo.txt")) return true
        if (path.contains("/nmrodam")) return true
        return false
    }

    private fun endpointRoleSignalScore(endpoint: EndpointAggregate): Double {
        val context = "${endpoint.requestOperation} ${endpoint.normalizedPath}".lowercase(Locale.ROOT)
        val path = endpoint.normalizedPath.lowercase(Locale.ROOT)
        return when (endpoint.role) {
            "home" -> {
                var score = 0.0
                if (path == "/") score += 4.0
                if (context.contains("home") || context.contains("start")) score += 2.5
                if (context.contains("cluster") || context.contains("rail") || context.contains("recommend")) score += 2.5
                if (context.contains("collection") || context.contains("teaser") || context.contains("stage")) score += 1.5
                if (context.contains("detail") || context.contains("search") || context.contains("playback")) score -= 2.0
                score
            }
            "search" -> {
                var score = 0.0
                if (context.contains("search") || context.contains("query") || context.contains("suggest")) score += 3.0
                if (path.contains("/search")) score += 2.0
                score
            }
            "detail" -> {
                var score = 0.0
                if (context.contains("detail") || context.contains("content") || context.contains("episode")) score += 2.5
                if (path.contains("/content/") || path.contains("/video/") || path.contains("/episode/")) score += 2.0
                score
            }
            "playbackResolver", "playback_resolver" -> {
                var score = 0.0
                if (path.contains("/ptmd/") || path.contains("/tmd/")) score += 4.0
                if (context.contains("resolver") || context.contains("playback")) score += 2.5
                if (path.endsWith(".m3u8") || path.endsWith(".mpd")) score += 1.0
                if (context.contains("manifest")) score += 0.5
                if (isPlaybackNoiseEndpoint(endpoint)) score -= 5.0
                score
            }
            "auth", "refresh" -> {
                var score = 0.0
                if (isRefreshEndpointCandidate(endpoint)) score += 2.5
                if (isLoginEndpointCandidate(endpoint)) score += 2.0
                if (isValidationEndpointCandidate(endpoint)) score += 1.5
                score
            }
            "config" -> {
                if (context.contains("config") || context.contains("settings")) 1.0 else 0.0
            }
            else -> 0.0
        }
    }

    private fun endpointScoreForRole(
        endpoint: EndpointAggregate,
        responseScore: Double,
    ): Double {
        var score = responseScore
        score += endpoint.confidence * 5.0
        score += endpoint.requestEvidenceCount * 0.4
        score += endpoint.responseOkCount * 0.3
        if (endpoint.hostClassSignals.any { it.startsWith("target_") }) score += 1.5
        if (endpoint.responseMimeTypes.any { it.contains("json") }) score += 1.0
        if (endpoint.responseMimeTypes.any { it.contains("html") }) score -= 0.4
        if (endpoint.method == "OPTIONS") score -= 2.0
        if (isNoiseEndpoint(endpoint)) score -= 4.0
        score += endpointRoleSignalScore(endpoint)
        val phaseAligned = normalizePhaseRelevanceForExport(endpoint.phaseRelevance, endpoint.role).any {
            roleFromPhase(it) == endpoint.role || (endpoint.role == "playback_resolver" && it == "playback_probe")
        }
        if (phaseAligned) score += 1.0 else score -= 0.5
        return score
    }

    private data class EndpointSelection(
        val endpoints: List<EndpointAggregate>,
        val warnings: List<String>,
    )

    private fun selectFinalEndpoints(
        endpoints: Collection<EndpointAggregate>,
        responses: List<ResponseEvent>,
        overrides: RuntimeToolkitTelemetry.EndpointOverrides?,
    ): EndpointSelection {
        val warnings = linkedSetOf<String>()
        val endpointById = endpoints.associateBy { it.endpointId }
        val roleByEndpoint = endpoints.associate { it.endpointId to it.role }
        val responseEndpointRefByEventId = buildMap<String, String> {
            endpoints.forEach { endpoint ->
                endpoint.responseEventIds.forEach { eventId ->
                    putIfAbsent(eventId, endpoint.endpointId)
                }
            }
        }
        val responseScoreByEndpoint = scoreEndpointsByObservedResponses(
            responses = responses,
            roleByEndpoint = roleByEndpoint,
            responseEndpointRefByEventId = responseEndpointRefByEventId,
        )
        val candidates = endpoints
            .filter { it.role in finalRoleSet }
            .filter { shouldIncludeEndpointInBundle(it) }
            .filterNot { isNoiseEndpoint(it) }
            .toMutableList()
        if (candidates.isEmpty()) {
            warnings += "NOISE_ENDPOINTS_DROPPED"
            candidates += endpoints.filter { !looksLikeNonContentOperation("${it.requestOperation} ${it.normalizedHost} ${it.normalizedPath}") }
        }

        val excludedByUser = overrides?.excludedEndpoints.orEmpty()
        if (excludedByUser.isNotEmpty()) {
            val before = candidates.size
            candidates.removeAll { it.endpointId in excludedByUser }
            if (before != candidates.size) {
                warnings += "USER_ENDPOINT_EXCLUSIONS_APPLIED"
            }
        }

        val homeEvidence = responses.any { response ->
            val inferred = inferRole(response.phaseId, response.operation, response.normalizedPath, response.url)
            response.statusCode in 200..399 && (response.routeKind == "home" || inferred == "home")
        }

        if (homeEvidence && candidates.none { it.role == "home" }) {
            val homeCandidates = candidates
                .filter { it.role == "helper" || it.role == "detail" || it.role == "search" }
                .filterNot { isPlaybackNoiseEndpoint(it) }
                .sortedByDescending { endpointScoreForRole(it, responseScoreByEndpoint[it.endpointId] ?: 0.0) }
            if (homeCandidates.isNotEmpty()) {
                homeCandidates.first().role = "home"
                warnings += "HOME_ROLE_RECOVERED"
            } else {
                warnings += "HOME_ROLE_NOT_PROVEN"
                warnings += "SUPPORTS_HOME_SYNC_DOWNGRADED"
            }
        }

        val selectedOverrides = overrides?.selectedEndpointByRole.orEmpty()
        val pinnedByRole = linkedMapOf<String, EndpointAggregate>()
        selectedOverrides.forEach { (role, endpointId) ->
            if (endpointId.isBlank()) return@forEach
            if (endpointId in excludedByUser) return@forEach
            val rawEndpoint = endpointById[endpointId] ?: return@forEach
            if (rawEndpoint.role != role) return@forEach
            if (!shouldIncludeEndpointInBundle(rawEndpoint) || isNoiseEndpoint(rawEndpoint)) {
                warnings += "USER_OVERRIDE_DROPPED_NOISE"
                return@forEach
            }
            val candidate = candidates.firstOrNull { it.endpointId == endpointId } ?: return@forEach
            pinnedByRole[role] = candidate
        }
        if (pinnedByRole.isNotEmpty()) {
            warnings += "USER_ENDPOINT_OVERRIDE_APPLIED"
        }

        val selected = mutableListOf<EndpointAggregate>()
        roleExportCaps.forEach { (role, cap) ->
            val roleCandidates = candidates.filter { it.role == role }
            if (roleCandidates.isEmpty() || cap <= 0) return@forEach
            val sorted = roleCandidates.sortedWith(
                compareByDescending<EndpointAggregate> { endpointScoreForRole(it, responseScoreByEndpoint[it.endpointId] ?: 0.0) }
                    .thenByDescending { it.confidence }
                    .thenBy { it.endpointId.lowercase(Locale.ROOT) },
            )
            if (sorted.size > cap) {
                warnings += "ENDPOINT_CAP_APPLIED:${role}"
            }
            val pinned = pinnedByRole[role]
            if (pinned != null) {
                selected += pinned
                if (cap > 1) {
                    selected += sorted.filter { it.endpointId != pinned.endpointId }.take(cap - 1)
                }
            } else {
                selected += sorted.take(cap)
            }
        }

        if (selected.size < candidates.size) {
            warnings += "NOISE_ENDPOINTS_DROPPED"
        }

        return EndpointSelection(
            endpoints = selected.distinctBy { it.endpointId },
            warnings = warnings.toList().sorted(),
        )
    }

    private fun shouldIncludeEndpointInBundle(endpoint: EndpointAggregate): Boolean {
        val host = endpoint.normalizedHost.lowercase(Locale.ROOT)
        val path = endpoint.normalizedPath
        val loweredContext = "${endpoint.requestOperation} $host $path".lowercase(Locale.ROOT)
        if (
            host.contains("sentry") ||
            host.contains("tracksrv") ||
            host.contains("epg-image") ||
            host.contains("segments.") ||
            host.contains("analytics") ||
            host.contains("measurement") ||
            host.contains("metrics")
        ) {
            return false
        }
        if (path.contains("/usage-data/") || path.contains("/user-histories/")) return false
        if (path.endsWith("/geo.txt")) return false
        if (path.contains("/nmrodam")) return false
        if (looksLikeNonContentOperation(loweredContext)) return false
        if (isLikelyStaticAssetPath(path, "https://$host$path")) return false
        if (endpoint.role == "playbackResolver" || endpoint.role == "playback_resolver") {
            if (isPlaybackNoiseEndpoint(endpoint)) return false
        }
        return true
    }

    private fun looksLikeGenreSignal(path: String, value: String): Boolean {
        val loweredPath = path.lowercase(Locale.ROOT)
        val loweredValue = value.trim().lowercase(Locale.ROOT)
        if (loweredValue.isBlank()) return false
        val pathLooksLikeGenre =
            loweredPath.contains("genre") ||
                loweredPath.contains("category") ||
                loweredPath.contains("section")
        if (!pathLooksLikeGenre) return false
        return loweredValue.any { ch -> ch.isLetter() } && loweredValue.length in 3..80
    }

    private fun looksLikeYearSignal(path: String, value: String): Boolean {
        val loweredPath = path.lowercase(Locale.ROOT)
        if (!listOf("year", "release", "airdate", "editorialdate", "broadcast").any { loweredPath.contains(it) }) {
            return false
        }
        val match = Regex("(19\\d{2}|20\\d{2})").find(value) ?: return false
        val year = match.value.toIntOrNull() ?: return false
        return year in 1900..2100
    }

    private fun collectJsonLeaves(value: Any?, path: String, out: MutableList<Pair<String, Any?>>) {
        when (value) {
            is JSONObject -> {
                value.keys().forEach { key ->
                    val childPath = if (path.isBlank()) key else "$path.$key"
                    collectJsonLeaves(value.opt(key), childPath, out)
                }
            }

            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val childPath = if (path.isBlank()) "[$i]" else "$path[$i]"
                    collectJsonLeaves(value.opt(i), childPath, out)
                }
            }

            else -> out += path to value
        }
    }

    private fun htmlTitle(html: String): String {
        val regex = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return regex.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun htmlOgImage(html: String): String {
        val regex = Regex(
            "<meta[^>]+property=[\\\"']og:image[\\\"'][^>]+content=[\\\"']([^\\\"']+)[\\\"']",
            RegexOption.IGNORE_CASE,
        )
        return regex.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun firstHost(endpointTemplates: JSONArray): String {
        for (i in 0 until endpointTemplates.length()) {
            val endpoint = endpointTemplates.optJSONObject(i) ?: continue
            val host = endpoint.optString("normalizedHost")
            if (host.isNotBlank()) return host
        }
        return ""
    }

    private fun deterministicGeneratedAt(observedTimestamps: List<String>): String {
        val normalized = observedTimestamps
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .sorted()
        return normalized.lastOrNull() ?: "1970-01-01T00:00:00Z"
    }

    private fun shortHash(value: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
