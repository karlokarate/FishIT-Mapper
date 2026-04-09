package dev.fishit.mapper.wave01.debug

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class RuntimeToolkitSourcePipelineExporterTest {

    @Test
    fun exporter_generates_host_compatible_source_pipeline_bundle_and_zip_container() {
        val runtimeRoot = Files.createTempDirectory("rtk_source_bundle_test").toFile()
        val eventsFile = File(runtimeRoot, "events/runtime_events.jsonl").apply {
            parentFile?.mkdirs()
            writeText(buildRuntimeEventsFixture(), Charsets.UTF_8)
        }
        assertTrue(eventsFile.exists())
        assertTrue(eventsFile.length() > 0L)

        val artifacts = RuntimeToolkitSourcePipelineExporter.ensureSourcePipelineArtifacts(
            runtimeRoot = runtimeRoot,
            targetSiteHint = "zdf.de",
        )

        assertTrue(artifacts.sourcePipelineBundlePath.exists())
        assertTrue(artifacts.siteRuntimeModelPath.exists())
        assertTrue(artifacts.manifestPath.exists())
        assertTrue(artifacts.sourcePluginBundleZipPath.exists())
        assertTrue(File(runtimeRoot, "site_profile.draft.json").exists())
        assertTrue(File(runtimeRoot, "provider_draft_export.json").exists())
        assertTrue(File(runtimeRoot, "fishit_provider_draft.json").exists())
        assertTrue(File(runtimeRoot, "endpoint_templates.json").exists())
        assertTrue(File(runtimeRoot, "endpoint_candidates.json").exists())
        assertTrue(File(runtimeRoot, "field_matrix.json").exists())
        assertTrue(File(runtimeRoot, "replay_requirements.json").exists())
        assertTrue(File(runtimeRoot, "auth_draft.json").exists())
        assertTrue(File(runtimeRoot, "playback_draft.json").exists())
        assertTrue(File(runtimeRoot, "replay_seed.json").exists())
        assertTrue(File(runtimeRoot, "replay_bundle.json").exists())
        assertTrue(File(runtimeRoot, "response_index.json").exists())
        assertTrue(File(runtimeRoot, "fixture_manifest.json").exists())

        val bundle = JSONObject(artifacts.sourcePipelineBundlePath.readText(Charsets.UTF_8))
        val manifest = JSONObject(artifacts.manifestPath.readText(Charsets.UTF_8))

        val requiredTopLevel = setOf(
            "\$schema",
            "bundleDescriptor",
            "capabilities",
            "endpointTemplates",
            "replayRequirements",
            "sessionAuth",
            "playback",
            "fieldMappings",
            "constraintsBudgets",
            "warnings",
            "confidence",
        )
        val allowedTopLevel = requiredTopLevel + setOf("selectionModel", "syncModel")
        val topLevelKeys = keySet(bundle)
        assertTrue(requiredTopLevel.all { it in topLevelKeys })
        assertTrue(topLevelKeys.all { it in allowedTopLevel })

        val descriptor = bundle.getJSONObject("bundleDescriptor")
        assertEquals(1, descriptor.optInt("compatibleRuntimeModelVersion", -1))
        assertEquals("1.0.0", descriptor.optString("compatibleCapabilitySchemaVersion"))
        val pluginApiRange = descriptor.optJSONObject("compatiblePluginApiRange") ?: JSONObject()
        assertEquals("1.0.0", pluginApiRange.optString("min"))
        assertEquals("1.x", pluginApiRange.optString("max"))

        val capabilities = bundle.getJSONObject("capabilities")
        assertTrue(capabilities.optBoolean("supportsHomeSync"))
        assertTrue(capabilities.optBoolean("supportsGlobalSearch"))
        assertTrue(capabilities.optBoolean("supportsDetailEnrichment"))
        assertTrue(capabilities.optBoolean("supportsPlayback"))

        val endpointTemplates = bundle.getJSONArray("endpointTemplates")
        val endpointIds = mutableListOf<String>()
        val endpointRoleById = linkedMapOf<String, String>()
        forEachObject(endpointTemplates) { endpoint ->
            val endpointId = endpoint.optString("endpointId")
            if (endpointId.isNotBlank()) {
                endpointIds += endpointId
                endpointRoleById[endpointId] = endpoint.optString("role")
            }
        }
        val sortedEndpointIds = endpointIds.sortedBy { it.lowercase() }
        assertEquals(sortedEndpointIds, endpointIds)
        assertTrue(endpointRoleById.values.any { it == "home" })
        assertTrue(endpointRoleById.values.any { it == "search" })
        assertTrue(endpointRoleById.values.any { it == "detail" })
        assertTrue(endpointRoleById.values.any { it == "playbackResolver" || it == "playback_resolver" })

        val replayRequirements = bundle.getJSONArray("replayRequirements")
        val replayEndpointRefs = mutableSetOf<String>()
        forEachObject(replayRequirements) { item ->
            val endpointRef = item.optString("endpointRef")
            if (endpointRef.isNotBlank()) replayEndpointRefs += endpointRef
        }

        val searchEndpointIds = endpointRoleById.filterValues { it == "search" }.keys
        val detailEndpointIds = endpointRoleById.filterValues { it == "detail" }.keys
        val homeEndpointIds = endpointRoleById.filterValues { it == "home" }.keys
        val playbackEndpointIds = endpointRoleById.filterValues { it == "playbackResolver" || it == "playback_resolver" }.keys
        assertTrue(searchEndpointIds.any { it in replayEndpointRefs })
        assertTrue(detailEndpointIds.any { it in replayEndpointRefs })
        assertTrue(homeEndpointIds.any { it in replayEndpointRefs })

        val playback = bundle.getJSONObject("playback")
        val playbackRef = playback.optString("playbackEndpointRef")
        assertTrue(playbackRef.isNotBlank())
        assertTrue(playbackRef in playbackEndpointIds)
        assertTrue(playbackRef in replayEndpointRefs)

        val syncModel = bundle.optJSONObject("syncModel")
        assertNotNull(syncModel)
        val homeRefs = syncModel!!.optJSONArray("homeEndpointRefs") ?: JSONArray()
        assertTrue(homeRefs.length() > 0)
        for (i in 0 until homeRefs.length()) {
            val endpointRef = homeRefs.optString(i)
            assertTrue(endpointRef in homeEndpointIds)
            assertTrue(endpointRef in replayEndpointRefs)
        }

        val fieldMappings = bundle.getJSONArray("fieldMappings")
        val titleMapping = findFieldMapping(fieldMappings, "title")
        val canonicalIdMapping = findFieldMapping(fieldMappings, "canonicalId")
        val playbackHintMapping = findFieldMapping(fieldMappings, "playbackHint")
        assertNotNull(titleMapping)
        assertNotNull(canonicalIdMapping)
        assertNotNull(playbackHintMapping)
        assertFalse(titleMapping!!.optString("derivationKind", "missing") == "missing")
        assertFalse(canonicalIdMapping!!.optString("derivationKind", "missing") == "missing")
        assertFalse(playbackHintMapping!!.optString("derivationKind", "missing") == "missing")
        forEachObject(fieldMappings) { row ->
            val template = row.opt("valueTemplate")?.toString().orEmpty()
            if (template.isNotBlank() && !template.trimStart().startsWith("{")) {
                assertFalse("numeric array index in template: $template", Regex("\\[\\d+]").containsMatchIn(template))
            }
        }

        val providerExport = JSONObject(File(runtimeRoot, "provider_draft_export.json").readText(Charsets.UTF_8))
        val providerRequiredTopLevel = setOf(
            "export_id",
            "export_schema_version",
            "target_site_id",
            "generated_at",
            "source_runtime_export_id",
            "confidence_summary",
            "capability_class",
            "endpoint_templates",
            "replay_requirements",
            "field_matrix",
            "auth_draft",
            "playback_draft",
            "warnings",
            "known_limitations",
            "fishit_player_contract",
        )
        assertTrue(providerRequiredTopLevel.all { key -> providerExport.has(key) })

        val replaySeed = JSONObject(File(runtimeRoot, "replay_seed.json").readText(Charsets.UTF_8))
        val replaySeedSteps = replaySeed.optJSONArray("steps") ?: JSONArray()
        assertTrue(replaySeedSteps.length() > 0)

        val replayBundle = JSONObject(File(runtimeRoot, "replay_bundle.json").readText(Charsets.UTF_8))
        assertTrue(replayBundle.has("replay_seed"))

        val responseIndex = JSONObject(File(runtimeRoot, "response_index.json").readText(Charsets.UTF_8))
        val responseItems = responseIndex.optJSONArray("items") ?: JSONArray()
        assertTrue(responseItems.length() > 0)

        assertEquals("source_pipeline_bundle.json", manifest.optString("mainContract"))
        assertEquals("site_runtime_model.json", manifest.optString("siteRuntimeModel"))

        ZipFile(artifacts.sourcePluginBundleZipPath).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue("source_pipeline_bundle.json" in names)
            assertTrue("site_runtime_model.json" in names)
            assertTrue("manifest.json" in names)
        }
    }

    @Test
    fun exporter_is_byte_stable_for_identical_runtime_events() {
        val runtimeRoot = Files.createTempDirectory("rtk_source_bundle_determinism").toFile()
        val eventsFile = File(runtimeRoot, "events/runtime_events.jsonl").apply {
            parentFile?.mkdirs()
            writeText(buildRuntimeEventsFixture(), Charsets.UTF_8)
        }
        assertTrue(eventsFile.exists())

        RuntimeToolkitSourcePipelineExporter.ensureSourcePipelineArtifacts(
            runtimeRoot = runtimeRoot,
            targetSiteHint = "zdf.de",
        )
        val firstSourceBundle = File(runtimeRoot, "source_pipeline_bundle.json").readBytes()
        val firstProviderExport = File(runtimeRoot, "provider_draft_export.json").readBytes()
        val firstManifest = File(runtimeRoot, "manifest.json").readBytes()
        val firstZip = File(runtimeRoot, "exports/source_plugin_bundle.zip").readBytes()

        RuntimeToolkitSourcePipelineExporter.ensureSourcePipelineArtifacts(
            runtimeRoot = runtimeRoot,
            targetSiteHint = "zdf.de",
        )
        val secondSourceBundle = File(runtimeRoot, "source_pipeline_bundle.json").readBytes()
        val secondProviderExport = File(runtimeRoot, "provider_draft_export.json").readBytes()
        val secondManifest = File(runtimeRoot, "manifest.json").readBytes()
        val secondZip = File(runtimeRoot, "exports/source_plugin_bundle.zip").readBytes()

        assertTrue(firstSourceBundle.contentEquals(secondSourceBundle))
        assertTrue(firstProviderExport.contentEquals(secondProviderExport))
        assertTrue(firstManifest.contentEquals(secondManifest))
        assertTrue(firstZip.contentEquals(secondZip))
    }

    @Test
    fun exporter_derives_refresh_endpoint_and_auth_replay_requirements() {
        val runtimeRoot = Files.createTempDirectory("rtk_source_bundle_auth_refresh").toFile()
        val eventsFile = File(runtimeRoot, "events/runtime_events.jsonl").apply {
            parentFile?.mkdirs()
            val base = buildRuntimeEventsFixture()
            val refreshRequest = requestEvent(
                eventId = "evt_req_auth_refresh",
                requestId = "req_auth_refresh",
                phaseId = "auth_probe",
                url = "https://www.zdf.de/api/auth/refresh?device=android",
                normalizedPath = "/api/auth/refresh",
                operation = "auth_token_refresh",
                method = "POST",
                headers = mapOf(
                    "authorization" to "Bearer stale-token",
                    "cookie" to "sid=abc",
                    "content-type" to "application/json",
                ),
                extraPayload = JSONObject().apply {
                    put("body_preview", """{"refreshToken":"rt_1","grant_type":"refresh_token"}""")
                },
            )
            writeText(base + refreshRequest + "\n", Charsets.UTF_8)
        }
        assertTrue(eventsFile.exists())
        assertTrue(eventsFile.length() > 0L)

        val artifacts = RuntimeToolkitSourcePipelineExporter.ensureSourcePipelineArtifacts(
            runtimeRoot = runtimeRoot,
            targetSiteHint = "zdf.de",
        )
        val bundle = JSONObject(artifacts.sourcePipelineBundlePath.readText(Charsets.UTF_8))
        val sessionAuth = bundle.getJSONObject("sessionAuth")
        val refreshEndpointRef = sessionAuth.optString("refreshEndpointRef")
        assertTrue(refreshEndpointRef.isNotBlank())
        val validationEndpointRef = sessionAuth.optString("validationEndpointRef")
        assertTrue(validationEndpointRef.isNotBlank())

        val replayByEndpointRef = linkedMapOf<String, JSONObject>()
        forEachObject(bundle.getJSONArray("replayRequirements")) { replay ->
            val endpointRef = replay.optString("endpointRef")
            if (endpointRef.isNotBlank()) {
                replayByEndpointRef[endpointRef] = replay
            }
        }
        val refreshReplay = replayByEndpointRef[refreshEndpointRef]
        assertNotNull(refreshReplay)

        val requiredQueryParams = mutableSetOf<String>()
        forEachObject(refreshReplay!!.optJSONArray("requiredQueryParams") ?: JSONArray()) { param ->
            val name = param.optString("name")
            if (name.isNotBlank()) requiredQueryParams += name
        }
        assertTrue("device" in requiredQueryParams)

        val requiredBodyFields = mutableSetOf<String>()
        forEachObject(refreshReplay.optJSONArray("requiredBodyFields") ?: JSONArray()) { field ->
            val name = field.optString("name")
            if (name.isNotBlank()) requiredBodyFields += name
        }
        assertTrue("refreshToken" in requiredBodyFields)
        assertTrue("grant_type" in requiredBodyFields)

        val requiredHeaders = mutableSetOf<String>()
        forEachObject(refreshReplay.optJSONArray("requiredHeaders") ?: JSONArray()) { header ->
            val name = header.optString("name")
            if (name.isNotBlank()) requiredHeaders += name
        }
        assertTrue("authorization" in requiredHeaders)

        val tokenInputNames = mutableSetOf<String>()
        forEachObject(sessionAuth.optJSONArray("requiredTokenInputs") ?: JSONArray()) { token ->
            val inputName = token.optString("inputName")
            if (inputName.isNotBlank()) tokenInputNames += inputName
        }
        assertTrue("authorization" in tokenInputNames)
    }

    @Test
    fun exporter_replay_seed_filters_noise_and_non_get_requests() {
        val runtimeRoot = Files.createTempDirectory("rtk_source_bundle_replay_filter").toFile()
        val eventsFile = File(runtimeRoot, "events/runtime_events.jsonl").apply {
            parentFile?.mkdirs()
            val base = buildRuntimeEventsFixture().trimEnd()
            val googleNoisePost = requestEvent(
                eventId = "evt_req_google_noise_post",
                requestId = "req_google_noise_post",
                phaseId = "background_noise",
                url = "https://www.google.com/gen_204?x=1",
                normalizedPath = "/gen_204",
                operation = "telemetry",
                method = "POST",
                headers = mapOf("accept" to "*/*"),
                extraPayload = JSONObject().apply {
                    put("normalized_host", "www.google.com")
                    put("host_class", "background_noise")
                },
            )
            val googleNoiseGet = requestEvent(
                eventId = "evt_req_google_noise_get",
                requestId = "req_google_noise_get",
                phaseId = "background_noise",
                url = "https://www.google.com/async/hpba?x=1",
                normalizedPath = "/async/hpba",
                operation = "telemetry",
                method = "GET",
                headers = mapOf("accept" to "*/*"),
                extraPayload = JSONObject().apply {
                    put("normalized_host", "www.google.com")
                    put("host_class", "background_noise")
                },
            )
            writeText(
                listOf(base, googleNoisePost, googleNoiseGet).joinToString(separator = "\n", postfix = "\n"),
                Charsets.UTF_8,
            )
        }
        assertTrue(eventsFile.exists())
        assertTrue(eventsFile.length() > 0L)

        RuntimeToolkitSourcePipelineExporter.ensureSourcePipelineArtifacts(
            runtimeRoot = runtimeRoot,
            targetSiteHint = "zdf.de",
        )
        val replaySeed = JSONObject(File(runtimeRoot, "replay_seed.json").readText(Charsets.UTF_8))
        val replaySeedSteps = replaySeed.optJSONArray("steps") ?: JSONArray()
        assertTrue(replaySeedSteps.length() > 0)
        forEachObject(replaySeedSteps) { step ->
            val url = step.optString("url")
            val method = step.optString("method")
            assertTrue(url.contains("zdf.de"))
            assertEquals("GET", method)
        }
    }

    @Test
    fun exporter_prefers_catalog_item_type_and_ignores_auth_token_type_noise() {
        val runtimeRoot = Files.createTempDirectory("rtk_source_bundle_item_type").toFile()
        val eventsFile = File(runtimeRoot, "events/runtime_events.jsonl").apply {
            parentFile?.mkdirs()
            val lines = listOf(
                requestEvent(
                    eventId = "evt_req_detail_live",
                    requestId = "req_detail_live",
                    phaseId = "detail_probe",
                    url = "https://www.zdf.de/api/detail/live-1",
                    normalizedPath = "/api/detail/live-1",
                    operation = "detail",
                    headers = mapOf("accept" to "application/json"),
                ),
                responseEvent(
                    eventId = "evt_res_detail_live",
                    requestId = "req_detail_live",
                    phaseId = "detail_probe",
                    url = "https://www.zdf.de/api/detail/live-1",
                    normalizedPath = "/api/detail/live-1",
                    bodyPreview = """{"data":{"videos":{"nodes":[{"title":"Live Werk","canonical":"live-1","currentMediaType":"LIVE"}]}}}""",
                ),
                requestEvent(
                    eventId = "evt_req_auth_token",
                    requestId = "req_auth_token",
                    phaseId = "auth_probe",
                    url = "https://www.zdf.de/api/auth/token",
                    normalizedPath = "/api/auth/token",
                    operation = "auth_token",
                    method = "POST",
                    headers = mapOf("accept" to "application/json"),
                ),
                responseEvent(
                    eventId = "evt_res_auth_token",
                    requestId = "req_auth_token",
                    phaseId = "auth_probe",
                    url = "https://www.zdf.de/api/auth/token",
                    normalizedPath = "/api/auth/token",
                    bodyPreview = """{"access_token":"abc","token_type":"Bearer"}""",
                ),
                responseEvent(
                    eventId = "evt_res_google_noise",
                    requestId = "req_google_noise",
                    phaseId = "background_noise",
                    url = "https://www.google.com/",
                    normalizedPath = "/",
                    bodyPreview = """<html><head><title>Google</title></head><body>noise</body></html>""",
                    normalizedHost = "www.google.com",
                    hostClass = "background_noise",
                ),
            )
            writeText(lines.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
        }
        assertTrue(eventsFile.exists())

        val artifacts = RuntimeToolkitSourcePipelineExporter.ensureSourcePipelineArtifacts(
            runtimeRoot = runtimeRoot,
            targetSiteHint = "zdf.de",
        )
        val bundle = JSONObject(artifacts.sourcePipelineBundlePath.readText(Charsets.UTF_8))
        val fieldMappings = bundle.getJSONArray("fieldMappings")
        val itemType = findFieldMapping(fieldMappings, "itemType")
        val title = findFieldMapping(fieldMappings, "title")

        assertNotNull(itemType)
        val itemTypeTemplate = itemType!!.optString("valueTemplate")
        assertTrue(itemTypeTemplate.isNotBlank())
        assertTrue(itemTypeTemplate.lowercase().contains("currentmediatype"))
        assertFalse(itemTypeTemplate.lowercase().contains("token_type"))
        assertFalse(itemTypeTemplate.lowercase().contains("bearer"))
        assertNotNull(title)
        val titleTemplate = title!!.optString("valueTemplate")
        assertTrue(titleTemplate.isNotBlank())
        assertTrue(titleTemplate.lowercase().contains("title"))
        assertFalse(titleTemplate.equals("google", ignoreCase = true))
    }

    @Test
    fun exporter_captures_oidc_auth_provenance_inputs_for_player_auth_flow() {
        val runtimeRoot = Files.createTempDirectory("rtk_source_bundle_oidc_auth").toFile()
        val eventsFile = File(runtimeRoot, "events/runtime_events.jsonl").apply {
            parentFile?.mkdirs()
            val lines = listOf(
                requestEvent(
                    eventId = "evt_req_oidc_token",
                    requestId = "req_oidc_token",
                    phaseId = "auth_probe",
                    url = "https://auth.example.com/oauth/token?client_id=app-123&code=abc123&state=s1",
                    normalizedPath = "/oauth/token",
                    operation = "oidc_token_exchange",
                    method = "POST",
                    headers = mapOf(
                        "content-type" to "application/json",
                        "accept" to "application/json",
                    ),
                    extraPayload = JSONObject().apply {
                        put(
                            "body_preview",
                            """{"code_verifier":"verifier-1","refresh_token":"rt-1","client_secret":"sec-1","nonce":"n-1"}""",
                        )
                    },
                ),
                responseEvent(
                    eventId = "evt_res_oidc_token",
                    requestId = "req_oidc_token",
                    phaseId = "auth_probe",
                    url = "https://auth.example.com/oauth/token?client_id=app-123&code=abc123&state=s1",
                    normalizedPath = "/oauth/token",
                    bodyPreview = """{"access_token":"at-1","id_token":"id-1","refresh_token":"rt-1"}""",
                    normalizedHost = "auth.example.com",
                    hostClass = "target_primary",
                ),
            )
            writeText(lines.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
        }
        assertTrue(eventsFile.exists())

        val artifacts = RuntimeToolkitSourcePipelineExporter.ensureSourcePipelineArtifacts(
            runtimeRoot = runtimeRoot,
            targetSiteHint = "example.com",
        )
        val bundle = JSONObject(artifacts.sourcePipelineBundlePath.readText(Charsets.UTF_8))
        val sessionAuth = bundle.getJSONObject("sessionAuth")
        val tokenInputs = sessionAuth.optJSONArray("requiredTokenInputs") ?: JSONArray()
        val tokenByName = linkedMapOf<String, JSONObject>()
        forEachObject(tokenInputs) { token ->
            val inputName = token.optString("inputName")
            if (inputName.isNotBlank()) tokenByName[inputName] = token
        }

        assertTrue("client_id" in tokenByName.keys)
        assertTrue("code" in tokenByName.keys)
        assertTrue("code_verifier" in tokenByName.keys)
        assertTrue("refresh_token" in tokenByName.keys)
        assertTrue("client_secret" in tokenByName.keys)
        assertTrue("nonce" in tokenByName.keys)
        assertTrue("state" in tokenByName.keys)

        assertEquals("non_secret", tokenByName.getValue("client_id").optString("confidentiality"))
        assertEquals("secret", tokenByName.getValue("code_verifier").optString("confidentiality"))
        assertEquals("secret", tokenByName.getValue("refresh_token").optString("confidentiality"))
        assertEquals("secret", tokenByName.getValue("client_secret").optString("confidentiality"))

        val endpointTemplates = bundle.getJSONArray("endpointTemplates")
        val authEndpointId = (0 until endpointTemplates.length())
            .asSequence()
            .mapNotNull { endpointTemplates.optJSONObject(it) }
            .firstOrNull { endpoint -> endpoint.optString("role") == "auth" }
            ?.optString("endpointId")
            .orEmpty()
        assertTrue(authEndpointId.isNotBlank())

        val replay = bundle.getJSONArray("replayRequirements")
        val authReplay = (0 until replay.length())
            .asSequence()
            .mapNotNull { replay.optJSONObject(it) }
            .firstOrNull { row -> row.optString("endpointRef") == authEndpointId }
        assertNotNull(authReplay)

        val authProvenanceInputs = mutableSetOf<String>()
        val requiredProvenanceInputs = authReplay!!.optJSONArray("requiredProvenanceInputs") ?: JSONArray()
        for (idx in 0 until requiredProvenanceInputs.length()) {
            val value = requiredProvenanceInputs.optString(idx)
            if (value.isNotBlank()) authProvenanceInputs += value
        }
        assertTrue("client_id" in authProvenanceInputs)
        assertTrue("code" in authProvenanceInputs)
        assertTrue("code_verifier" in authProvenanceInputs)
        assertTrue("refresh_token" in authProvenanceInputs)
    }

    private fun buildRuntimeEventsFixture(): String {
        val lines = listOf(
            requestEvent(
                eventId = "evt_req_home",
                requestId = "req_home",
                phaseId = "home_probe",
                url = "https://www.zdf.de/api/home",
                normalizedPath = "/api/home",
                operation = "home_feed",
                headers = mapOf("accept" to "application/json"),
            ),
            responseEvent(
                eventId = "evt_res_home",
                requestId = "req_home",
                phaseId = "home_probe",
                url = "https://www.zdf.de/api/home",
                normalizedPath = "/api/home",
                bodyPreview = """{"items":[{"title":"Home Teaser","canonicalId":"cid_1"}]}""",
            ),
            requestEvent(
                eventId = "evt_req_search",
                requestId = "req_search",
                phaseId = "search_probe",
                url = "https://www.zdf.de/api/search?q=heute",
                normalizedPath = "/api/search",
                operation = "search",
                headers = mapOf("accept" to "application/json"),
            ),
            responseEvent(
                eventId = "evt_res_search",
                requestId = "req_search",
                phaseId = "search_probe",
                url = "https://www.zdf.de/api/search?q=heute",
                normalizedPath = "/api/search",
                bodyPreview = """{"results":[{"title":"Search Title","canonicalId":"cid_1","description":"Search Description"}]}""",
            ),
            requestEvent(
                eventId = "evt_req_detail",
                requestId = "req_detail",
                phaseId = "detail_probe",
                url = "https://www.zdf.de/api/detail/cid_1",
                normalizedPath = "/api/detail/cid_1",
                operation = "detail",
                headers = mapOf("accept" to "application/json"),
            ),
            responseEvent(
                eventId = "evt_res_detail",
                requestId = "req_detail",
                phaseId = "detail_probe",
                url = "https://www.zdf.de/api/detail/cid_1",
                normalizedPath = "/api/detail/cid_1",
                bodyPreview = """{"item":{"title":"Detail Title","canonicalId":"cid_1","playbackHint":"https://cdn.zdf.de/live/master.m3u8"}}""",
            ),
            requestEvent(
                eventId = "evt_req_playback",
                requestId = "req_playback",
                phaseId = "playback_probe",
                url = "https://www.zdf.de/api/playback/resolver?canonical=cid_1",
                normalizedPath = "/api/playback/resolver",
                operation = "playback_resolver",
                headers = mapOf(
                    "authorization" to "Bearer abc",
                    "cookie" to "sid=abc; uid=42",
                    "origin" to "https://www.zdf.de",
                    "referer" to "https://www.zdf.de/detail/cid_1",
                ),
            ),
            responseEvent(
                eventId = "evt_res_playback",
                requestId = "req_playback",
                phaseId = "playback_probe",
                url = "https://www.zdf.de/api/playback/resolver?canonical=cid_1",
                normalizedPath = "/api/playback/resolver",
                bodyPreview = """{"manifestUrl":"https://cdn.zdf.de/live/master.m3u8","streamUrl":"https://cdn.zdf.de/live/master.m3u8"}""",
            ),
        )
        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    private fun requestEvent(
        eventId: String,
        requestId: String,
        phaseId: String,
        url: String,
        normalizedPath: String,
        operation: String,
        headers: Map<String, String>,
        method: String = "GET",
        extraPayload: JSONObject? = null,
    ): String {
        val headerJson = JSONObject()
        headers.forEach { (name, value) -> headerJson.put(name, value) }
        return JSONObject().apply {
            put("event_id", eventId)
            put("event_type", "network_request_event")
            put(
                "payload",
                JSONObject().apply {
                    put("request_id", requestId)
                    put("phase_id", phaseId)
                    put("method", method)
                    put("url", url)
                    put("normalized_host", "www.zdf.de")
                    put("normalized_path", normalizedPath)
                    put("request_operation", operation)
                    put("host_class", "target_primary")
                    put("headers", headerJson)
                    put("target_site_id", "zdf.de")
                    extraPayload?.keys()?.forEach { key ->
                        put(key, extraPayload.opt(key))
                    }
                },
            )
        }.toString()
    }

    private fun responseEvent(
        eventId: String,
        requestId: String,
        phaseId: String,
        url: String,
        normalizedPath: String,
        bodyPreview: String,
        normalizedHost: String = "www.zdf.de",
        hostClass: String = "target_primary",
    ): String {
        return JSONObject().apply {
            put("event_id", eventId)
            put("event_type", "network_response_event")
            put(
                "payload",
                JSONObject().apply {
                    put("request_id", requestId)
                    put("response_id", "res_$requestId")
                    put("phase_id", phaseId)
                    put("status_code", 200)
                    put("url", url)
                    put("normalized_host", normalizedHost)
                    put("normalized_path", normalizedPath)
                    put("host_class", hostClass)
                    put("mime_type", "application/json")
                    put("body_preview", bodyPreview)
                    put("target_site_id", "zdf.de")
                },
            )
        }.toString()
    }

    private fun keySet(jsonObject: JSONObject): Set<String> {
        val keys = linkedSetOf<String>()
        val iterator = jsonObject.keys()
        while (iterator.hasNext()) {
            keys += iterator.next()
        }
        return keys
    }

    private fun forEachObject(array: JSONArray, block: (JSONObject) -> Unit) {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            block(item)
        }
    }

    private fun findFieldMapping(array: JSONArray, fieldName: String): JSONObject? {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString("fieldName") == fieldName) return item
        }
        return null
    }
}
