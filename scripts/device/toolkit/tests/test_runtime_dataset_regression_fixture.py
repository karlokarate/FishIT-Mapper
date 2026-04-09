#!/usr/bin/env python3
"""Fixture-driven regression tests for runtime collector cutover behavior."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

TOOLKIT_DIR = Path(__file__).resolve().parents[1]
if str(TOOLKIT_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLKIT_DIR))

from runtime_dataset_cli import (  # type: ignore  # noqa: E402
    ensure_derived,
    normalize_runtime_rows,
    read_jsonl,
    replay_http_execute_deterministic,
)


FIXTURE_PATH = Path(__file__).resolve().parent / "fixtures" / "runtime_regression_zdf.jsonl"
TRUNCATION_FIXTURE_PATH = Path(__file__).resolve().parent / "fixtures" / "runtime_truncation_regression.jsonl"


class RuntimeDatasetRegressionFixtureTests(unittest.TestCase):
    def _fixture_rows(self):
        return read_jsonl(FIXTURE_PATH)

    def _truncation_fixture_rows(self):
        return read_jsonl(TRUNCATION_FIXTURE_PATH)

    def test_zdf_fixture_resolves_phase_windows_and_target_hosts(self) -> None:
        rows = self._fixture_rows()
        normalized = normalize_runtime_rows(rows)
        by_event = {str(row.get("event_id") or ""): row for row in normalized}

        self.assertEqual(by_event["req_home"]["payload"].get("phase_id"), "home_probe")
        self.assertEqual(by_event["req_search"]["payload"].get("phase_id"), "search_probe")
        self.assertEqual(by_event["req_detail"]["payload"].get("phase_id"), "detail_probe")
        self.assertEqual(by_event["req_playback"]["payload"].get("phase_id"), "playback_probe")
        self.assertEqual(by_event["req_auth"]["payload"].get("phase_id"), "auth_probe")

        self.assertEqual(by_event["req_home"]["payload"].get("host_class"), "target_document")
        self.assertEqual(by_event["req_search"]["payload"].get("host_class"), "target_api")
        self.assertEqual(by_event["req_detail"]["payload"].get("host_class"), "target_api")
        self.assertEqual(by_event["req_playback"]["payload"].get("host_class"), "target_playback")
        self.assertEqual(by_event["req_auth"]["payload"].get("host_class"), "target_api")

        self.assertNotEqual(by_event["req_home"]["payload"].get("host_class"), "background_noise")
        self.assertNotEqual(by_event["req_search"]["payload"].get("host_class"), "background_noise")
        self.assertNotEqual(by_event["req_playback"]["payload"].get("host_class"), "background_noise")

    def test_fixture_reindex_emits_truncation_and_extraction_attempts(self) -> None:
        rows = self._fixture_rows()
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            paths = ensure_derived(runtime_dir, rows, replay_executor=replay_http_execute_deterministic)

            response_index = json.loads(paths["response_index"].read_text(encoding="utf-8"))
            items = {str(item.get("event_id") or ""): item for item in response_index.get("items", [])}
            playback_item = items["resp_playback"]
            self.assertTrue(playback_item.get("capture_truncated"))
            self.assertEqual(int(playback_item.get("capture_limit_bytes") or 0), 16 * 1024 * 1024)
            self.assertEqual(int(playback_item.get("original_content_length") or 0), 20000000)
            self.assertEqual(int(playback_item.get("captured_size_bytes") or 0), 16 * 1024 * 1024)
            self.assertGreater(int(playback_item.get("stored_size_bytes") or 0), 0)

            extraction_events = read_jsonl(paths["extraction_events"])
            self.assertGreater(len(extraction_events), 1)
            for row in extraction_events[:5]:
                payload = row.get("payload", {})
                self.assertIn("source_ref", payload)
                self.assertIn("phase_id", payload)
                self.assertIn("host_class", payload)
                self.assertIn("extraction_kind", payload)
                self.assertIn("success", payload)
                self.assertIn("extracted_field_count", payload)
                self.assertIn("confidence_summary", payload)

            field_matrix = json.loads(paths["field_matrix"].read_text(encoding="utf-8"))
            self.assertGreater(int(field_matrix.get("extraction_event_count") or 0), 1)

            endpoint_candidates = json.loads(paths["endpoint_candidates"].read_text(encoding="utf-8"))
            for candidate in endpoint_candidates.get("candidates", []):
                host_class = str(candidate.get("host_class") or "")
                self.assertNotIn(host_class, {"google_noise", "analytics_noise", "browser_bootstrap"})

    def test_truncation_fixture_marks_4mb_candidate_caps_and_required_failures(self) -> None:
        rows = self._truncation_fixture_rows()
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            paths = ensure_derived(runtime_dir, rows, replay_executor=replay_http_execute_deterministic)
            responses = read_jsonl(paths["responses_normalized"])

            for row in responses:
                stored_size = int(row.get("stored_size_bytes") or 0)
                policy = str(row.get("body_capture_policy") or "")
                candidate_rel = str(row.get("candidate_relevance") or "")
                if stored_size == 4194304 and candidate_rel in {"required_candidate", "signal_candidate"}:
                    self.assertTrue(row.get("capture_truncated"), row.get("event_id"))
                    self.assertEqual(int(row.get("capture_limit_bytes") or 0), 4194304, row.get("event_id"))
                    self.assertEqual(str(row.get("truncation_reason") or ""), "body_size_limit", row.get("event_id"))
                if policy == "full_candidate_required":
                    self.assertTrue(
                        (not row.get("capture_truncated"))
                        or bool(str(row.get("capture_failure") or "").strip()),
                        row.get("event_id"),
                    )

            by_event = {str(row.get("event_id") or ""): row for row in responses}
            self.assertTrue(by_event["resp_graphql"].get("capture_truncated"))
            self.assertEqual(str(by_event["resp_graphql"].get("capture_failure") or ""), "required_body_truncated")
            self.assertEqual(str(by_event["resp_manifest"].get("body_capture_policy") or ""), "full_candidate_required")
            self.assertFalse(by_event["resp_manifest"].get("capture_truncated"))

            truncation_events = read_jsonl(paths["truncation_events"])
            self.assertGreaterEqual(len(truncation_events), 1)
            payloads = [row.get("payload", {}) for row in truncation_events]
            self.assertTrue(any(str(payload.get("response_id") or "") == "resp_graphql" for payload in payloads))

    def test_provider_export_generated_with_templates_and_minimized_requirements(self) -> None:
        rows = self._fixture_rows()
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            paths = ensure_derived(runtime_dir, rows, replay_executor=replay_http_execute_deterministic)
            provider_export = json.loads(paths["provider_draft_export"].read_text(encoding="utf-8"))
            self.assertTrue(str(provider_export.get("export_id") or "").startswith("provider_export_"))
            templates = {str(item.get("endpoint_role") or ""): item for item in provider_export.get("endpoint_templates", [])}
            self.assertIn("home", templates)
            self.assertIn("search", templates)
            self.assertIn("detail", templates)
            self.assertIn("playback_resolver", templates)

            replay_by_role = {str(item.get("endpoint_role") or ""): item for item in provider_export.get("replay_requirements", [])}
            self.assertIn("search", replay_by_role)
            self.assertGreaterEqual(len(replay_by_role["search"].get("required_headers", [])), 1)
            normalized = normalize_runtime_rows(rows)
            raw_search_headers = set()
            for row in normalized:
                if row.get("event_type") != "network_request_event":
                    continue
                payload = row.get("payload", {})
                if not isinstance(payload, dict):
                    continue
                operation = str(payload.get("request_operation") or "").lower()
                if "search" not in operation:
                    continue
                headers = payload.get("headers")
                if not isinstance(headers, dict):
                    continue
                raw_search_headers.update({str(name).lower() for name in headers.keys() if name})
            required_search_headers = {str(name).lower() for name in replay_by_role["search"].get("required_headers", []) if name}
            self.assertTrue(required_search_headers.issubset(raw_search_headers))
            self.assertLess(len(required_search_headers), len(raw_search_headers))

            for item in provider_export.get("endpoint_templates", []):
                host = str(item.get("normalized_host") or "")
                self.assertNotIn("google", host)
                self.assertNotIn("doubleclick", host)

            source_pipeline_bundle = json.loads(paths["source_pipeline_bundle"].read_text(encoding="utf-8"))
            self.assertIn("bundleDescriptor", source_pipeline_bundle)
            self.assertIn("capabilities", source_pipeline_bundle)
            self.assertIn("endpointTemplates", source_pipeline_bundle)
            self.assertIn("replayRequirements", source_pipeline_bundle)
            self.assertIn("sessionAuth", source_pipeline_bundle)
            self.assertIn("playback", source_pipeline_bundle)
            required_top_level = {
                "$schema",
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
            }
            allowed_top_level = set(required_top_level).union({"selectionModel", "syncModel"})
            self.assertTrue(required_top_level.issubset(set(source_pipeline_bundle.keys())))
            self.assertEqual(sorted(set(source_pipeline_bundle.keys()).difference(allowed_top_level)), [])

            descriptor = source_pipeline_bundle.get("bundleDescriptor", {})
            self.assertEqual(int(descriptor.get("compatibleRuntimeModelVersion") or 0), 1)
            self.assertTrue(str(descriptor.get("compatibleCapabilitySchemaVersion") or "").startswith("1."))
            plugin_api_range = descriptor.get("compatiblePluginApiRange", {})
            self.assertTrue(str(plugin_api_range.get("min") or "").startswith("1."))
            self.assertTrue(str(plugin_api_range.get("max") or "").startswith("1."))
            self.assertNotIn(str(descriptor.get("sourceKey") or "").strip().lower(), {"xtream", "telegram", "io"})

            capabilities = source_pipeline_bundle.get("capabilities", {})
            endpoint_templates = [item for item in source_pipeline_bundle.get("endpointTemplates", []) if isinstance(item, dict)]
            replay_requirements = [item for item in source_pipeline_bundle.get("replayRequirements", []) if isinstance(item, dict)]
            replay_refs = {str(item.get("endpointRef") or "") for item in replay_requirements}
            endpoint_role_by_id = {
                str(item.get("endpointId") or ""): str(item.get("role") or "")
                for item in endpoint_templates
                if str(item.get("endpointId") or "")
            }

            if capabilities.get("supportsGlobalSearch"):
                self.assertTrue(any(role == "search" for role in endpoint_role_by_id.values()))
                self.assertTrue(any(endpoint_id in replay_refs for endpoint_id, role in endpoint_role_by_id.items() if role == "search"))
                for endpoint in endpoint_templates:
                    if str(endpoint.get("role") or "") != "search":
                        continue
                    self.assertIn(str(endpoint.get("templateKind") or ""), {"graphql", "rest_json", "resolver", "manifest", "config"})
                    self.assertNotEqual(str(endpoint.get("normalizedHost") or "").strip(), "")
                    self.assertNotEqual(endpoint.get("phaseRelevance"), ["background_noise"])
            if capabilities.get("supportsDetailEnrichment"):
                self.assertTrue(any(role == "detail" for role in endpoint_role_by_id.values()))
                self.assertTrue(any(endpoint_id in replay_refs for endpoint_id, role in endpoint_role_by_id.items() if role == "detail"))
                for endpoint in endpoint_templates:
                    if str(endpoint.get("role") or "") != "detail":
                        continue
                    self.assertIn(str(endpoint.get("templateKind") or ""), {"graphql", "rest_json", "resolver", "manifest", "config"})
                    self.assertNotEqual(str(endpoint.get("normalizedHost") or "").strip(), "")
                    self.assertNotEqual(endpoint.get("phaseRelevance"), ["background_noise"])
            if capabilities.get("supportsPlayback"):
                playback_ids = [endpoint_id for endpoint_id, role in endpoint_role_by_id.items() if role in {"playbackResolver", "playback_resolver"}]
                self.assertTrue(playback_ids)
                playback_ref = str(source_pipeline_bundle.get("playback", {}).get("playbackEndpointRef") or "")
                self.assertIn(playback_ref, playback_ids)
                self.assertIn(playback_ref, replay_refs)
                playback_endpoint = next(
                    item
                    for item in endpoint_templates
                    if str(item.get("endpointId") or "") == playback_ref
                )
                self.assertIn(str(playback_endpoint.get("templateKind") or ""), {"graphql", "rest_json", "resolver", "manifest", "config"})
                placeholder_names = {
                    str(item.get("name") or "")
                    for item in playback_endpoint.get("variablePlaceholders", [])
                    if isinstance(item, dict)
                }
                self.assertTrue({"canonical", "ptmd_template"}.intersection(placeholder_names))
            if capabilities.get("supportsHomeSync"):
                sync_model = source_pipeline_bundle.get("syncModel", {})
                home_refs = [str(item) for item in sync_model.get("homeEndpointRefs", []) if str(item)]
                self.assertTrue(home_refs)
                for ref in home_refs:
                    self.assertEqual(endpoint_role_by_id.get(ref), "home")
                    self.assertIn(ref, replay_refs)
                    endpoint = next(item for item in endpoint_templates if str(item.get("endpointId") or "") == ref)
                    self.assertIn(str(endpoint.get("templateKind") or ""), {"graphql", "rest_json", "config"})
                    self.assertNotEqual(endpoint.get("phaseRelevance"), ["background_noise"])

            fields_by_name = {
                str(item.get("fieldName") or ""): item
                for item in source_pipeline_bundle.get("fieldMappings", [])
                if isinstance(item, dict)
            }
            if capabilities.get("supportsGlobalSearch") or capabilities.get("supportsDetailEnrichment"):
                self.assertIn("canonicalId", fields_by_name)
                self.assertIn("title", fields_by_name)
                self.assertNotEqual(str(fields_by_name["canonicalId"].get("derivationKind") or "missing"), "missing")
                self.assertNotEqual(str(fields_by_name["title"].get("derivationKind") or "missing"), "missing")
            if capabilities.get("supportsPlayback"):
                self.assertIn("playbackHint", fields_by_name)
                self.assertNotEqual(str(fields_by_name["playbackHint"].get("derivationKind") or "missing"), "missing")

            bundle_zip = paths["source_plugin_bundle_zip"]
            self.assertTrue(bundle_zip.exists())
            with zipfile.ZipFile(bundle_zip, "r") as archive:
                names = set(archive.namelist())
                zipped_manifest = json.loads(archive.read("manifest.json").decode("utf-8"))
            self.assertIn("source_pipeline_bundle.json", names)
            self.assertIn("site_runtime_model.json", names)
            self.assertIn("manifest.json", names)
            self.assertEqual(str(zipped_manifest.get("mainContract") or ""), "source_pipeline_bundle.json")

    def test_provider_export_serialization_is_byte_stable_for_same_fixture(self) -> None:
        rows = self._fixture_rows()
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            paths_first = ensure_derived(runtime_dir, rows, replay_executor=replay_http_execute_deterministic)
            first_bytes = paths_first["provider_draft_export"].read_bytes()
            paths_second = ensure_derived(runtime_dir, rows, replay_executor=replay_http_execute_deterministic)
            second_bytes = paths_second["provider_draft_export"].read_bytes()
            self.assertEqual(first_bytes, second_bytes)

    def test_provider_export_warnings_include_truncation_and_browser_context_when_present(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_warn",
                "event_id": "phase_search",
                "event_type": "probe_phase_event",
                "ts_utc": "2026-04-02T12:00:00Z",
                "trace_id": "trace_warn",
                "span_id": "",
                "action_id": "action_phase",
                "payload": {"phase_id": "search_probe", "transition": "start"},
            },
            {
                "schema_version": 1,
                "run_id": "run_warn",
                "event_id": "req_warn",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-02T12:00:01Z",
                "trace_id": "trace_warn",
                "span_id": "",
                "action_id": "action_warn",
                "payload": {
                    "request_id": "req_warn",
                    "phase_id": "search_probe",
                    "request_operation": "search_request",
                    "url": "https://api.zdf.de/v1/search?q=planet",
                    "method": "GET",
                    "headers": {
                        "accept": "application/json",
                        "authorization": "Bearer redacted",
                        "cookie": "sid=abc",
                        "referer": "https://www.zdf.de/",
                        "origin": "https://www.zdf.de",
                    },
                    "host_class": "target",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_warn",
                "event_id": "resp_warn",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-02T12:00:02Z",
                "trace_id": "trace_warn",
                "span_id": "",
                "action_id": "action_warn",
                "payload": {
                    "request_id": "req_warn",
                    "response_id": "resp_warn",
                    "phase_id": "search_probe",
                    "url": "https://api.zdf.de/v1/search?q=planet",
                    "method": "GET",
                    "status_code": 200,
                    "mime_type": "application/json",
                    "headers": {"content-type": "application/json", "content-length": "10000000"},
                    "body_preview": "{\"results\":[]}",
                    "capture_truncated": True,
                    "capture_limit_bytes": 4194304,
                    "stored_size_bytes": 4194304,
                    "host_class": "target",
                },
            },
        ]
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            paths = ensure_derived(runtime_dir, rows, replay_executor=replay_http_execute_deterministic)
            provider_export = json.loads(paths["provider_draft_export"].read_text(encoding="utf-8"))
            warning_text = " ".join([str(item) for item in provider_export.get("warnings", [])]).lower()
            self.assertIn("truncation", warning_text)
            self.assertIn("browser-context", warning_text)


if __name__ == "__main__":
    unittest.main()
