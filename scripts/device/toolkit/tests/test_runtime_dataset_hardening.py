#!/usr/bin/env python3
"""Unit tests for capture/correlation hardening paths in runtime_dataset_cli."""

from __future__ import annotations

import json
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qsl, urlparse

TOOLKIT_DIR = Path(__file__).resolve().parents[1]
if str(TOOLKIT_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLKIT_DIR))

from runtime_dataset_cli import (  # type: ignore  # noqa: E402
    body_capture_decision,
    build_body_store,
    build_correlation,
    build_endpoint_candidates,
    build_field_matrix,
    build_provider_draft_export,
    build_provenance_registry,
    build_replay_requirements,
    build_required_headers,
    build_required_headers_active_replay,
    build_response_store_index,
    build_provenance_graph,
    ensure_derived,
    mission_required_artifacts,
    read_jsonl,
    normalize_runtime_rows,
    pipeline_quality_report,
)


class RuntimeDatasetHardeningTests(unittest.TestCase):
    def _start_header_cookie_server(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                required = self.headers.get("X-Required")
                cookie_header = self.headers.get("Cookie") or ""
                has_sid = any(chunk.strip().startswith("sid=") for chunk in cookie_header.split(";"))
                if required == "1" and has_sid:
                    body = b'{"ok":true}'
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
                self.send_response(403)
                self.end_headers()

            def log_message(self, _format, *args):  # noqa: A003
                return

        server = HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        return server, thread

    def test_host_classification_resolves_target_and_google_noise(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "req_target",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T12:00:00Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_1",
                "payload": {
                    "request_id": "req_target",
                    "phase_id": "home_probe",
                    "host_class": "target",
                    "url": "https://api.example.com/v1/home",
                    "method": "GET",
                    "headers": {},
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "req_google",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T12:00:01Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_1",
                "payload": {
                    "request_id": "req_google",
                    "phase_id": "home_probe",
                    "host_class": "target",
                    "url": "https://www.google-analytics.com/g/collect?v=2",
                    "method": "GET",
                    "headers": {},
                },
            },
        ]
        normalized = normalize_runtime_rows(rows)
        by_event_id = {str(row.get("event_id")): row for row in normalized}
        self.assertEqual(by_event_id["req_target"]["payload"].get("host_class"), "target_api")
        self.assertEqual(by_event_id["req_google"]["payload"].get("host_class"), "google_noise")

    def test_host_classification_keeps_explicit_target_when_target_family_is_capped(self) -> None:
        rows = []
        hosts = [
            ("api.main-a.example.net", 5),
            ("api.main-b.example.net", 4),
            ("api.main-c.example.net", 3),
            ("api.rare-d.example.net", 1),
        ]
        ts = 0
        for host, count in hosts:
            for idx in range(count):
                rows.append(
                    {
                        "schema_version": 1,
                        "run_id": "run_target_cap",
                        "event_id": f"req_{host}_{idx}",
                        "event_type": "network_request_event",
                        "ts_utc": f"2026-04-01T12:00:{ts:02d}Z",
                        "trace_id": "trace_target_cap",
                        "span_id": "",
                        "action_id": "action_target_cap",
                        "payload": {
                            "request_id": f"req_{host}_{idx}",
                            "phase_id": "home_probe",
                            "host_class": "target",
                            "url": f"https://{host}/v1/home",
                            "method": "GET",
                            "headers": {},
                        },
                    }
                )
                ts += 1

        normalized = normalize_runtime_rows(rows)
        rare_rows = [
            row
            for row in normalized
            if str(row.get("event_id") or "").startswith("req_api.rare-d.example.net_")
        ]
        self.assertTrue(rare_rows)
        for row in rare_rows:
            self.assertEqual(row.get("payload", {}).get("host_class"), "target_api")

    def test_host_classification_family_fallback_keeps_rare_same_site_target(self) -> None:
        rows = []
        ts = 0
        for host, count in [("api.a.example.net", 5), ("api.b.example.net", 4), ("api.c.example.net", 3)]:
            for idx in range(count):
                rows.append(
                    {
                        "schema_version": 1,
                        "run_id": "run_family_fallback",
                        "event_id": f"req_{host}_{idx}",
                        "event_type": "network_request_event",
                        "ts_utc": f"2026-04-01T12:10:{ts:02d}Z",
                        "trace_id": "trace_family_fallback",
                        "span_id": "",
                        "action_id": "action_family_fallback",
                        "payload": {
                            "request_id": f"req_{host}_{idx}",
                            "phase_id": "home_probe",
                            "host_class": "target",
                            "url": f"https://{host}/v1/home",
                            "method": "GET",
                            "headers": {},
                        },
                    }
                )
                ts += 1

        rows.append(
            {
                "schema_version": 1,
                "run_id": "run_family_fallback",
                "event_id": "req_rare_same_site",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T12:10:59Z",
                "trace_id": "trace_family_fallback",
                "span_id": "",
                "action_id": "action_family_fallback",
                "payload": {
                    "request_id": "req_rare_same_site",
                    "phase_id": "home_probe",
                    "host_class": "unknown",
                    "url": "https://api.rare.example.net/v1/detail",
                    "method": "GET",
                    "headers": {},
                },
            }
        )

        normalized = normalize_runtime_rows(rows)
        rare = next(row for row in normalized if str(row.get("event_id")) == "req_rare_same_site")
        self.assertEqual(rare.get("payload", {}).get("host_class"), "target_api")

    def test_host_classification_keeps_zdf_hosts_out_of_background_noise(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_zdf",
                "event_id": "req_www_zdf",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T12:20:00Z",
                "trace_id": "trace_zdf",
                "span_id": "",
                "action_id": "action_zdf",
                "payload": {
                    "request_id": "req_www_zdf",
                    "phase_id": "home_probe",
                    "host_class": "target",
                    "url": "https://www.zdf.de/",
                    "method": "GET",
                    "headers": {},
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_zdf",
                "event_id": "req_api_zdf",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T12:20:01Z",
                "trace_id": "trace_zdf",
                "span_id": "",
                "action_id": "action_zdf",
                "payload": {
                    "request_id": "req_api_zdf",
                    "phase_id": "search_probe",
                    "host_class": "target",
                    "url": "https://api.zdf.de/v1/search?q=abc",
                    "method": "GET",
                    "headers": {},
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_zdf",
                "event_id": "req_playback_zdf",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T12:20:02Z",
                "trace_id": "trace_zdf",
                "span_id": "",
                "action_id": "action_zdf",
                "payload": {
                    "request_id": "req_playback_zdf",
                    "phase_id": "playback_probe",
                    "host_class": "target",
                    "request_operation": "playback_manifest_fetch",
                    "url": "https://nrodlzdf-a.akamaihd.net/path/master.m3u8",
                    "method": "GET",
                    "headers": {},
                },
            },
        ]
        normalized = normalize_runtime_rows(rows)
        by_event_id = {str(row.get("event_id")): row for row in normalized}
        self.assertEqual(by_event_id["req_www_zdf"]["payload"].get("host_class"), "target_document")
        self.assertEqual(by_event_id["req_api_zdf"]["payload"].get("host_class"), "target_api")
        self.assertEqual(by_event_id["req_playback_zdf"]["payload"].get("host_class"), "target_playback")
        for event_id in ["req_www_zdf", "req_api_zdf", "req_playback_zdf"]:
            self.assertNotEqual(by_event_id[event_id]["payload"].get("host_class"), "background_noise")

    def test_phase_assignment_resolves_unscoped(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "req_search",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T12:00:00Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_1",
                "payload": {
                    "request_id": "req_search",
                    "phase_id": "unscoped",
                    "url": "https://api.example.com/v1/search?q=something",
                    "method": "GET",
                    "headers": {},
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "req_bg",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T12:00:01Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_1",
                "payload": {
                    "request_id": "req_bg",
                    "phase_id": "unscoped",
                    "url": "",
                    "method": "POST",
                    "headers": {},
                },
            },
        ]
        normalized = normalize_runtime_rows(rows)
        by_event_id = {str(row.get("event_id")): row for row in normalized}
        self.assertEqual(by_event_id["req_search"]["payload"].get("phase_id"), "search_probe")
        self.assertEqual(by_event_id["req_bg"]["payload"].get("phase_id"), "background_noise")

    def test_phase_assignment_upgrades_background_when_marker_window_active(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "phase_search",
                "event_type": "probe_phase_event",
                "ts_utc": "2026-04-01T12:00:00Z",
                "trace_id": "trace_phase",
                "span_id": "",
                "action_id": "action_phase",
                "payload": {"phase_id": "search_probe", "transition": "start"},
            },
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "req_search_bg",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T12:00:01Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_1",
                "payload": {
                    "request_id": "req_search_bg",
                    "phase_id": "background_noise",
                    "request_operation": "search_request",
                    "url": "https://api.example.com/v1/search?q=something",
                    "method": "GET",
                    "headers": {},
                },
            },
        ]
        normalized = normalize_runtime_rows(rows)
        by_event_id = {str(row.get("event_id")): row for row in normalized}
        self.assertEqual(by_event_id["req_search_bg"]["payload"].get("phase_id"), "search_probe")

    def test_extraction_event_contract_is_normalized(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "extract_1",
                "event_type": "extraction_event",
                "ts_utc": "2026-04-01T12:00:02Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_1",
                "payload": {
                    "operation": "js_bridge_network_event_failed",
                    "source": "webview_js_bridge",
                    "phase_id": "home_probe",
                },
            }
        ]
        normalized = normalize_runtime_rows(rows)
        payload = normalized[0].get("payload", {})
        self.assertEqual(payload.get("operation"), "js_bridge_network_event_failed")
        self.assertEqual(payload.get("phase_id"), "home_probe")
        self.assertEqual(payload.get("host_class"), "ignored")
        self.assertEqual(payload.get("extraction_kind"), "runtime_event")
        self.assertFalse(payload.get("success"))
        self.assertEqual(payload.get("extracted_field_count"), 0)
        self.assertEqual(payload.get("confidence_summary"), "none")
        self.assertEqual(payload.get("source_ref"), "extract_1")

    def test_truncation_event_preserves_explicit_host_class_without_url(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "trunc_1",
                "event_type": "truncation_event",
                "ts_utc": "2026-04-01T12:00:03Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_1",
                "payload": {
                    "request_id": "req_1",
                    "response_id": "resp_1",
                    "phase_id": "detail_probe",
                    "host_class": "target_api",
                    "normalized_host": "api.zdf.de",
                    "normalized_path": "/tmd/2/path",
                    "mime_type": "application/json",
                    "capture_truncated": True,
                    "capture_limit_bytes": 4194304,
                    "stored_size_bytes": 4194304,
                    "truncation_reason": "body_size_limit",
                },
            }
        ]
        normalized = normalize_runtime_rows(rows)
        payload = normalized[0].get("payload", {})
        self.assertEqual(payload.get("host_class"), "target_api")
        self.assertEqual(payload.get("normalized_host"), "api.zdf.de")
        self.assertEqual(payload.get("normalized_path"), "/tmd/2/path")
        self.assertEqual(payload.get("phase_id"), "detail_probe")

    def test_candidate_document_requirement_is_explicit_marker_only(self) -> None:
        base_row = {
            "schema_version": 1,
            "run_id": "run_1",
            "event_id": "resp_html",
            "event_type": "network_response_event",
            "ts_utc": "2026-04-01T12:00:03Z",
            "trace_id": "trace_1",
            "span_id": "",
            "action_id": "action_1",
            "payload": {
                "request_id": "req_1",
                "response_id": "resp_html",
                "phase_id": "detail_probe",
                "host_class": "target_document",
                "url": "https://www.example.com/detail/1",
                "method": "GET",
                "status_code": 200,
                "mime_type": "text/html",
                "headers": {"content-type": "text/html"},
                "body_preview": "<html><title>A</title></html>",
            },
        }
        normalized = normalize_runtime_rows([base_row])
        without_marker = body_capture_decision(normalized[0])
        self.assertEqual(without_marker.get("body_capture_policy"), "full_candidate")

        with_marker_row = json.loads(json.dumps(base_row))
        with_marker_row["event_id"] = "resp_html_marker"
        with_marker_row["payload"]["candidate_document"] = True
        normalized_with_marker = normalize_runtime_rows([with_marker_row])
        with_marker = body_capture_decision(normalized_with_marker[0])
        self.assertEqual(with_marker.get("body_capture_policy"), "full_candidate_required")

    def test_truncated_signal_candidate_does_not_emit_required_body_failure(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "resp_signal",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-01T12:00:04Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_1",
                "payload": {
                    "request_id": "req_signal",
                    "response_id": "resp_signal",
                    "phase_id": "home_probe",
                    "host_class": "target_document",
                    "url": "https://www.example.com/",
                    "method": "GET",
                    "status_code": 200,
                    "mime_type": "text/html",
                    "headers": {"content-type": "text/html", "content-length": "6000000"},
                    "body_preview": "<html><title>A</title></html>",
                    "capture_truncated": True,
                    "capture_limit_bytes": 4194304,
                    "body_capture_policy": "truncated_candidate",
                    "capture_failure": "",
                },
            }
        ]
        normalized = normalize_runtime_rows(rows)
        payload = normalized[0].get("payload", {})
        self.assertTrue(payload.get("capture_truncated"))
        self.assertEqual(payload.get("body_capture_policy"), "truncated_candidate")
        self.assertEqual(str(payload.get("capture_failure") or ""), "")

    def test_truncation_semantics_are_explicit(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "resp_trunc",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-01T12:00:02Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_1",
                "payload": {
                    "request_id": "req_1",
                    "response_id": "resp_trunc",
                    "phase_id": "home_probe",
                    "host_class": "target",
                    "url": "https://api.example.com/v1/home",
                    "method": "GET",
                    "status_code": 200,
                    "mime_type": "application/json",
                    "headers": {"content-length": "900000"},
                    "body_preview": "{\"items\":[1]}",
                    "capture_truncated": True,
                    "captured_body_bytes": 524288,
                    "response_size_bytes": 524288,
                },
            }
        ]
        normalized = normalize_runtime_rows(rows)
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            index_payload = build_response_store_index(normalized, runtime_dir=runtime_dir)
            item = index_payload.get("items", [])[0]
            self.assertTrue(item.get("capture_truncated"))
            self.assertEqual(item.get("capture_limit_bytes"), 524288)
            self.assertEqual(item.get("content_length_header"), "900000")

    def test_provenance_registry_captures_dynamic_inputs(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "req_prov",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T12:01:00Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_1",
                "payload": {
                    "request_id": "req_prov",
                    "phase_id": "detail_probe",
                    "host_class": "target",
                    "url": "https://api.example.com/v1/detail?id=1",
                    "method": "GET",
                    "headers": {
                        "zdf-app-id": "app-123",
                        "api-auth": "auth-token",
                        "Cookie": "sid=abc123; pref=de",
                        "Referer": "https://www.example.com/detail/1",
                        "Origin": "https://www.example.com",
                    },
                },
            }
        ]
        normalized = normalize_runtime_rows(rows)
        registry = build_provenance_registry(normalized)
        names = {str(entry.get("name") or "") for entry in registry.get("entries", [])}
        self.assertIn("zdf-app-id", names)
        self.assertIn("api-auth", names)
        self.assertTrue(any(name.startswith("cookies.") for name in names))
        self.assertIn("referer", names)
        self.assertIn("origin", names)

    def test_request_response_linkage_propagates_request_fingerprint(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "req_fp",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T12:01:00Z",
                "trace_id": "trace_link",
                "span_id": "",
                "action_id": "action_link",
                "payload": {
                    "request_id": "req_fp",
                    "phase_id": "detail_probe",
                    "host_class": "target",
                    "url": "https://api.example.com/v1/detail?id=1",
                    "method": "GET",
                    "headers": {"accept": "application/json"},
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "resp_fp",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-01T12:01:01Z",
                "trace_id": "trace_link",
                "span_id": "",
                "action_id": "action_link",
                "payload": {
                    "request_id": "req_fp",
                    "phase_id": "unscoped",
                    "url": "https://api.example.com/v1/detail?id=1",
                    "method": "GET",
                    "status_code": 200,
                    "mime_type": "application/json",
                    "headers": {"content-type": "application/json"},
                    "body_preview": "{\"item\":{\"id\":\"a\"}}",
                },
            },
        ]
        normalized = normalize_runtime_rows(rows)
        by_event = {str(row.get("event_id") or ""): row for row in normalized}
        req_payload = by_event["req_fp"]["payload"]
        resp_payload = by_event["resp_fp"]["payload"]
        self.assertTrue(str(req_payload.get("request_fingerprint") or ""))
        self.assertEqual(req_payload.get("request_id"), resp_payload.get("request_id"))
        self.assertEqual(req_payload.get("request_fingerprint"), resp_payload.get("request_fingerprint"))
        self.assertEqual(resp_payload.get("phase_id"), "detail_probe")

    def test_pipeline_smoke_scoped_run_emits_all_phase_markers_and_non_empty_exports(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_old",
                "event_id": "old_req",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T10:00:00Z",
                "trace_id": "trace_old",
                "span_id": "",
                "action_id": "action_old",
                "payload": {
                    "request_id": "old_req",
                    "phase_id": "home_probe",
                    "url": "https://api.example.com/old",
                    "method": "GET",
                    "headers": {},
                },
            },
        ]
        rows.extend(
            [
                {
                    "schema_version": 1,
                    "run_id": "run_new",
                    "event_id": f"phase_{phase}",
                    "event_type": "probe_phase_event",
                    "ts_utc": f"2026-04-01T12:00:0{idx}Z",
                    "trace_id": "trace_new",
                    "span_id": "",
                    "action_id": "action_phase",
                    "payload": {"phase_id": phase, "transition": "start"},
                }
                for idx, phase in enumerate(["home_probe", "search_probe", "detail_probe", "playback_probe"])
            ]
        )
        rows.extend(
            [
                {
                    "schema_version": 1,
                    "run_id": "run_new",
                    "event_id": "req_home",
                    "event_type": "network_request_event",
                    "ts_utc": "2026-04-01T12:00:10Z",
                    "trace_id": "trace_home",
                    "span_id": "",
                    "action_id": "action_home",
                    "payload": {
                        "request_id": "req_home",
                        "phase_id": "home_probe",
                        "url": "https://api.example.com/v1/home",
                        "method": "GET",
                        "headers": {"accept": "application/json", "authorization": "Bearer x"},
                    },
                },
                {
                    "schema_version": 1,
                    "run_id": "run_new",
                    "event_id": "resp_home",
                    "event_type": "network_response_event",
                    "ts_utc": "2026-04-01T12:00:11Z",
                    "trace_id": "trace_home",
                    "span_id": "",
                    "action_id": "action_home",
                    "payload": {
                        "request_id": "req_home",
                        "phase_id": "home_probe",
                        "url": "https://api.example.com/v1/home",
                        "method": "GET",
                        "status_code": 200,
                        "mime_type": "application/json",
                        "headers": {"content-length": "20", "content-type": "application/json"},
                        "body_preview": "{\"home\":[{\"id\":1}]}",
                    },
                },
                {
                    "schema_version": 1,
                    "run_id": "run_new",
                    "event_id": "req_search",
                    "event_type": "network_request_event",
                    "ts_utc": "2026-04-01T12:00:12Z",
                    "trace_id": "trace_search",
                    "span_id": "",
                    "action_id": "action_search",
                    "payload": {
                        "request_id": "req_search",
                        "phase_id": "search_probe",
                        "url": "https://api.example.com/v1/search?q=abc",
                        "method": "GET",
                        "headers": {"accept": "application/json", "authorization": "Bearer x"},
                    },
                },
                {
                    "schema_version": 1,
                    "run_id": "run_new",
                    "event_id": "resp_search",
                    "event_type": "network_response_event",
                    "ts_utc": "2026-04-01T12:00:13Z",
                    "trace_id": "trace_search",
                    "span_id": "",
                    "action_id": "action_search",
                    "payload": {
                        "request_id": "req_search",
                        "phase_id": "search_probe",
                        "url": "https://api.example.com/v1/search?q=abc",
                        "method": "GET",
                        "status_code": 200,
                        "mime_type": "application/json",
                        "headers": {"content-length": "24", "content-type": "application/json"},
                        "body_preview": "{\"results\":[{\"title\":\"A\"}]}",
                    },
                },
                {
                    "schema_version": 1,
                    "run_id": "run_new",
                    "event_id": "req_detail",
                    "event_type": "network_request_event",
                    "ts_utc": "2026-04-01T12:00:14Z",
                    "trace_id": "trace_detail",
                    "span_id": "",
                    "action_id": "action_detail",
                    "payload": {
                        "request_id": "req_detail",
                        "phase_id": "detail_probe",
                        "url": "https://api.example.com/v1/detail/42",
                        "method": "GET",
                        "headers": {"accept": "application/json", "authorization": "Bearer x"},
                    },
                },
                {
                    "schema_version": 1,
                    "run_id": "run_new",
                    "event_id": "resp_detail",
                    "event_type": "network_response_event",
                    "ts_utc": "2026-04-01T12:00:15Z",
                    "trace_id": "trace_detail",
                    "span_id": "",
                    "action_id": "action_detail",
                    "payload": {
                        "request_id": "req_detail",
                        "phase_id": "detail_probe",
                        "url": "https://api.example.com/v1/detail/42",
                        "method": "GET",
                        "status_code": 200,
                        "mime_type": "application/json",
                        "headers": {"content-length": "33", "content-type": "application/json"},
                        "body_preview": "{\"item\":{\"canonicalId\":\"42\"}}",
                    },
                },
                {
                    "schema_version": 1,
                    "run_id": "run_new",
                    "event_id": "req_playback",
                    "event_type": "network_request_event",
                    "ts_utc": "2026-04-01T12:00:16Z",
                    "trace_id": "trace_playback",
                    "span_id": "",
                    "action_id": "action_playback",
                    "payload": {
                        "request_id": "req_playback",
                        "phase_id": "playback_probe",
                        "url": "https://api.example.com/v1/playback/resolver?id=42",
                        "method": "GET",
                        "headers": {"accept": "application/json", "authorization": "Bearer x"},
                    },
                },
                {
                    "schema_version": 1,
                    "run_id": "run_new",
                    "event_id": "resp_playback",
                    "event_type": "network_response_event",
                    "ts_utc": "2026-04-01T12:00:17Z",
                    "trace_id": "trace_playback",
                    "span_id": "",
                    "action_id": "action_playback",
                    "payload": {
                        "request_id": "req_playback",
                        "phase_id": "playback_probe",
                        "url": "https://api.example.com/v1/playback/resolver?id=42",
                        "method": "GET",
                        "status_code": 200,
                        "mime_type": "application/json",
                        "headers": {"content-length": "40", "content-type": "application/json"},
                        "body_preview": "{\"playback\":{\"manifest\":\"https://cdn/x.m3u8\"}}",
                    },
                },
            ]
        )

        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            report = pipeline_quality_report(runtime_dir, rows)
            self.assertTrue(report.get("pipeline_ready"))
            gates = report.get("gates", {})
            self.assertTrue(gates.get("phase_completeness_gate", {}).get("passed"))
            self.assertTrue(gates.get("latest_non_empty_gate", {}).get("passed"))

            normalized = normalize_runtime_rows([row for row in rows if row.get("run_id") == "run_new"], runtime_dir=runtime_dir)
            derived = ensure_derived(runtime_dir, normalized)
            required_paths = [
                "events_ssot",
                "requests_normalized",
                "responses_normalized",
                "correlation",
                "required_headers",
                "required_cookies",
                "replay_requirements",
                "endpoint_candidates",
                "field_matrix",
                "provenance_registry",
            ]
            for key in required_paths:
                target = derived[key]
                self.assertTrue(target.exists(), key)
                self.assertGreater(target.stat().st_size, 0, key)

    def test_candidate_body_capture_uses_full_store_payload(self) -> None:
        json_body = "{\"data\":{\"title\":\"Big Payload\",\"items\":[1,2,3,4]}}"
        html_body = "<html><head><title>Example</title></head><body>ok</body></html>"
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "resp_json",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-01T12:02:00Z",
                "trace_id": "trace_2",
                "span_id": "",
                "action_id": "action_2",
                "payload": {
                    "request_id": "req_json",
                    "response_id": "resp_json",
                    "phase_id": "search_probe",
                    "host_class": "target",
                    "url": "https://api.example.com/v1/search?q=test",
                    "method": "GET",
                    "status_code": 200,
                    "mime_type": "application/json",
                    "headers": {},
                    "body_preview": "{\"data\":{}}",
                    "response_store_path": "resp_json.bin",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "resp_html",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-01T12:02:01Z",
                "trace_id": "trace_2",
                "span_id": "",
                "action_id": "action_2",
                "payload": {
                    "request_id": "req_html",
                    "response_id": "resp_html",
                    "phase_id": "home_probe",
                    "host_class": "target",
                    "url": "https://www.example.com/home",
                    "method": "GET",
                    "status_code": 200,
                    "mime_type": "text/html",
                    "headers": {},
                    "body_preview": "<html></html>",
                    "response_store_path": "resp_html.bin",
                },
            },
        ]
        normalized = normalize_runtime_rows(rows)
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            (runtime_dir / "response_store").mkdir(parents=True, exist_ok=True)
            (runtime_dir / "response_store" / "resp_json.bin").write_text(json_body, encoding="utf-8")
            (runtime_dir / "response_store" / "resp_html.bin").write_text(html_body, encoding="utf-8")
            payload = build_body_store(normalized, runtime_dir=runtime_dir)
            index = payload.get("index", {})
            items = index.get("items", [])
            self.assertEqual(len(items), 2)
            by_event = {str(item.get("event_id")): item for item in items}
            self.assertGreaterEqual(int(by_event["resp_json"].get("size_bytes") or 0), len(json_body))
            self.assertGreaterEqual(int(by_event["resp_html"].get("size_bytes") or 0), len(html_body))
            self.assertEqual(by_event["resp_json"].get("body_capture_policy"), "full_candidate_required")
            self.assertIn(by_event["resp_html"].get("body_capture_policy"), {"full_candidate", "full_candidate_required"})

    def test_candidate_graphql_json_never_truncates_under_normal_limits(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_graphql",
                "event_id": "resp_graphql",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-02T12:00:00Z",
                "trace_id": "trace_graphql",
                "span_id": "",
                "action_id": "action_graphql",
                "payload": {
                    "request_id": "req_graphql",
                    "response_id": "resp_graphql",
                    "phase_id": "search_probe",
                    "host_class": "target",
                    "url": "https://api.example.com/graphql?operationName=SearchQuery",
                    "method": "POST",
                    "status_code": 200,
                    "mime_type": "application/json",
                    "headers": {"content-length": "120"},
                    "stored_size_bytes": 120,
                    "capture_truncated": False,
                    "body_preview": "{\"data\":{\"search\":[{\"title\":\"A\"}]}}",
                },
            }
        ]
        normalized = normalize_runtime_rows(rows)
        payload = normalized[0]["payload"]
        self.assertFalse(payload.get("capture_truncated"))
        self.assertEqual(payload.get("body_capture_policy"), "full_candidate_required")
        self.assertEqual(payload.get("truncation_reason"), "")

    def test_manifest_never_truncates_under_normal_limits(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_manifest",
                "event_id": "resp_manifest",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-02T12:00:01Z",
                "trace_id": "trace_manifest",
                "span_id": "",
                "action_id": "action_manifest",
                "payload": {
                    "request_id": "req_manifest",
                    "response_id": "resp_manifest",
                    "phase_id": "playback_probe",
                    "host_class": "target",
                    "url": "https://cdn.example.com/path/master.m3u8",
                    "method": "GET",
                    "status_code": 200,
                    "mime_type": "application/vnd.apple.mpegurl",
                    "headers": {"content-length": "220"},
                    "stored_size_bytes": 220,
                    "capture_truncated": False,
                    "body_preview": "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1200000\nhttps://cdn.example.com/child.m3u8\n",
                },
            }
        ]
        normalized = normalize_runtime_rows(rows)
        payload = normalized[0]["payload"]
        self.assertFalse(payload.get("capture_truncated"))
        self.assertEqual(payload.get("body_capture_policy"), "full_candidate_required")

    def test_media_segment_resolves_to_metadata_only_policy(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_media",
                "event_id": "resp_seg",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-02T12:00:02Z",
                "trace_id": "trace_media",
                "span_id": "",
                "action_id": "action_media",
                "payload": {
                    "request_id": "req_seg",
                    "response_id": "resp_seg",
                    "phase_id": "playback_probe",
                    "host_class": "target_playback",
                    "url": "https://cdn.example.com/v/segment_0001.m4s",
                    "method": "GET",
                    "status_code": 200,
                    "mime_type": "video/mp4",
                    "headers": {"content-length": "8192"},
                    "stored_size_bytes": 0,
                    "capture_truncated": False,
                },
            }
        ]
        normalized = normalize_runtime_rows(rows)
        payload = normalized[0]["payload"]
        self.assertIn(payload.get("body_capture_policy"), {"metadata_only", "skipped_media_segment"})
        self.assertEqual(payload.get("candidate_relevance"), "non_candidate")

    def test_legacy_4mb_cap_is_always_marked_truncated(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_cap",
                "event_id": "resp_cap",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-02T12:00:03Z",
                "trace_id": "trace_cap",
                "span_id": "",
                "action_id": "action_cap",
                "payload": {
                    "request_id": "req_cap",
                    "response_id": "resp_cap",
                    "phase_id": "search_probe",
                    "host_class": "target",
                    "url": "https://api.example.com/graphql?operationName=Search",
                    "method": "POST",
                    "status_code": 200,
                    "mime_type": "application/json",
                    "headers": {"content-type": "application/json"},
                    "stored_size_bytes": 4194304,
                    "capture_truncated": False,
                },
            }
        ]
        normalized = normalize_runtime_rows(rows)
        payload = normalized[0]["payload"]
        self.assertTrue(payload.get("capture_truncated"))
        self.assertEqual(int(payload.get("capture_limit_bytes") or 0), 4194304)
        self.assertEqual(payload.get("truncation_reason"), "body_size_limit")

    def test_truncated_body_is_visible_to_extraction_and_replay(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_trunc_visibility",
                "event_id": "req_trunc",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-02T12:00:04Z",
                "trace_id": "trace_trunc",
                "span_id": "",
                "action_id": "action_trunc",
                "payload": {
                    "request_id": "req_trunc",
                    "phase_id": "search_probe",
                    "host_class": "target",
                    "url": "https://api.example.com/graphql?operationName=Search",
                    "method": "POST",
                    "headers": {"accept": "application/json"},
                    "request_operation": "search_graphql_query",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_trunc_visibility",
                "event_id": "resp_trunc",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-02T12:00:05Z",
                "trace_id": "trace_trunc",
                "span_id": "",
                "action_id": "action_trunc",
                "payload": {
                    "request_id": "req_trunc",
                    "response_id": "resp_trunc",
                    "phase_id": "search_probe",
                    "host_class": "target",
                    "url": "https://api.example.com/graphql?operationName=Search",
                    "method": "POST",
                    "status_code": 200,
                    "mime_type": "application/json",
                    "headers": {"content-length": "9000000"},
                    "stored_size_bytes": 4194304,
                    "capture_truncated": False,
                    "body_preview": "{\"data\":",
                },
            },
        ]
        normalized = normalize_runtime_rows(rows)
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            paths = ensure_derived(runtime_dir, normalized)
            field_matrix = json.loads(paths["field_matrix"].read_text(encoding="utf-8"))
            self.assertGreaterEqual(int(field_matrix.get("skipped_truncated_events") or 0), 1)
            replay_requirements = json.loads(paths["replay_requirements"].read_text(encoding="utf-8"))
            self.assertGreaterEqual(int(replay_requirements.get("truncation_summary", {}).get("total_truncated_responses") or 0), 1)
            truncation_events = read_jsonl(paths["truncation_events"])
            self.assertGreaterEqual(len(truncation_events), 1)

    def test_duplicate_large_html_body_reuses_body_ref(self) -> None:
        large_html = "<html><head><title>X</title></head><body>" + ("A" * (600 * 1024)) + "</body></html>"
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_dedupe_html",
                "event_id": "resp_html_1",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-02T12:00:06Z",
                "trace_id": "trace_html",
                "span_id": "",
                "action_id": "action_html",
                "payload": {
                    "request_id": "req_html_1",
                    "response_id": "resp_html_1",
                    "phase_id": "home_probe",
                    "host_class": "target_document",
                    "url": "https://www.example.com/a",
                    "method": "GET",
                    "status_code": 200,
                    "mime_type": "text/html",
                    "headers": {"content-length": str(len(large_html.encode("utf-8")))},
                    "response_store_path": "resp_html_1.bin",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_dedupe_html",
                "event_id": "resp_html_2",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-02T12:00:07Z",
                "trace_id": "trace_html",
                "span_id": "",
                "action_id": "action_html",
                "payload": {
                    "request_id": "req_html_2",
                    "response_id": "resp_html_2",
                    "phase_id": "home_probe",
                    "host_class": "target_document",
                    "url": "https://www.example.com/b",
                    "method": "GET",
                    "status_code": 200,
                    "mime_type": "text/html",
                    "headers": {"content-length": str(len(large_html.encode("utf-8")))},
                    "response_store_path": "resp_html_2.bin",
                },
            },
        ]
        normalized = normalize_runtime_rows(rows)
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            (runtime_dir / "response_store").mkdir(parents=True, exist_ok=True)
            (runtime_dir / "response_store" / "resp_html_1.bin").write_text(large_html, encoding="utf-8")
            (runtime_dir / "response_store" / "resp_html_2.bin").write_text(large_html, encoding="utf-8")
            payload = build_body_store(normalized, runtime_dir=runtime_dir)
            index = payload.get("index", {})
            self.assertEqual(int(index.get("unique_blob_count") or 0), 1)
            items = {str(item.get("event_id")): item for item in index.get("items", [])}
            self.assertEqual(items["resp_html_1"].get("body_ref"), items["resp_html_2"].get("body_ref"))
            self.assertTrue(items["resp_html_2"].get("dedup_reused"))

    def test_field_matrix_reads_response_store_payload_when_preview_missing(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "resp_1",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-01T12:00:01Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_1",
                "payload": {
                    "request_id": "req_1",
                    "response_id": "resp_1",
                    "url": "https://api.example.com/v1/home",
                    "method": "GET",
                    "status_code": 200,
                    "mime": "application/json",
                    "response_store_path": "files/runtime-toolkit/response_store/resp_1.bin",
                },
            }
        ]
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            (runtime_dir / "response_store").mkdir(parents=True, exist_ok=True)
            (runtime_dir / "response_store" / "resp_1.bin").write_text(
                "{\"data\":{\"item\":{\"id\":123}}}\n",
                encoding="utf-8",
            )

            matrix = build_field_matrix(rows, runtime_dir=runtime_dir)
            keys = {item.get("field") for item in matrix.get("keys", []) if isinstance(item, dict)}
            self.assertIn("data.item.id", keys)

    def test_response_store_index_normalizes_paths(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "resp_2",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-01T12:00:02Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_1",
                "payload": {
                    "request_id": "req_2",
                    "response_id": "resp_2",
                    "url": "https://api.example.com/v1/profile",
                    "method": "GET",
                    "status": 200,
                    "response_store_path": "files/runtime-toolkit/response_store/resp_2.bin",
                },
            }
        ]
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            (runtime_dir / "response_store").mkdir(parents=True, exist_ok=True)
            (runtime_dir / "response_store" / "resp_2.bin").write_text("{}", encoding="utf-8")

            index_payload = build_response_store_index(rows, runtime_dir=runtime_dir)
            self.assertEqual(len(index_payload.get("items", [])), 1)
            item = index_payload["items"][0]
            self.assertEqual(item.get("path"), "resp_2.bin")
            self.assertTrue(item.get("exists"))

    def test_correlation_links_request_and_response_via_request_id(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "req_1",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T12:00:00Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_1",
                "payload": {
                    "request_id": "req_1",
                    "url": "https://api.example.com/v1/search",
                    "method": "GET",
                    "headers": {},
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "resp_1",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-01T12:00:01Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_1",
                "payload": {
                    "request_id": "req_1",
                    "response_id": "resp_1",
                    "url": "https://api.example.com/v1/search",
                    "method": "GET",
                    "status_code": 200,
                    "headers": {},
                },
            },
        ]
        correlation = build_correlation(rows)
        self.assertEqual(len(correlation), 1)
        chain = correlation[0]
        self.assertEqual(chain.get("matched_request_count"), 1)
        pairs = chain.get("request_response_pairs", [])
        self.assertEqual(len(pairs), 1)
        self.assertEqual(len(pairs[0].get("responses", [])), 1)

    def test_pipeline_quality_report_auto_discovers_primary_host(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "phase_home",
                "event_type": "probe_phase_event",
                "ts_utc": "2026-04-01T11:59:55Z",
                "trace_id": "trace_phase",
                "span_id": "",
                "action_id": "phase_action",
                "payload": {"phase_id": "home_probe", "transition": "start"},
            },
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "phase_search",
                "event_type": "probe_phase_event",
                "ts_utc": "2026-04-01T11:59:56Z",
                "trace_id": "trace_phase",
                "span_id": "",
                "action_id": "phase_action",
                "payload": {"phase_id": "search_probe", "transition": "start"},
            },
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "phase_detail",
                "event_type": "probe_phase_event",
                "ts_utc": "2026-04-01T11:59:57Z",
                "trace_id": "trace_phase",
                "span_id": "",
                "action_id": "phase_action",
                "payload": {"phase_id": "detail_probe", "transition": "start"},
            },
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "phase_playback",
                "event_type": "probe_phase_event",
                "ts_utc": "2026-04-01T11:59:58Z",
                "trace_id": "trace_phase",
                "span_id": "",
                "action_id": "phase_action",
                "payload": {"phase_id": "playback_probe", "transition": "start"},
            },
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "req_3",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T12:00:00Z",
                "trace_id": "trace_2",
                "span_id": "",
                "action_id": "action_2",
                "payload": {
                    "request_id": "req_3",
                    "url": "https://api.example.com/v1/feed",
                    "method": "GET",
                    "headers": {"authorization": "Bearer x"},
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "resp_3",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-01T12:00:01Z",
                "trace_id": "trace_2",
                "span_id": "",
                "action_id": "action_2",
                "payload": {
                    "request_id": "req_3",
                    "response_id": "resp_3",
                    "url": "https://api.example.com/v1/feed",
                    "method": "GET",
                    "status_code": 200,
                    "mime": "application/json",
                    "headers": {"content-type": "application/json"},
                    "body_preview": "{\"feed\":[{\"id\":1}]}",
                },
            },
        ]
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            (runtime_dir / "events").mkdir(parents=True, exist_ok=True)
            report = pipeline_quality_report(runtime_dir, rows)
            self.assertEqual(report.get("quality_host"), "api.example.com")
            self.assertTrue(report.get("pipeline_ready"))
            self.assertIsInstance(report, dict)

    def test_required_headers_active_mode_builds_minimal_sets(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "req_a",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T12:00:00Z",
                "trace_id": "trace_a",
                "span_id": "",
                "action_id": "action_a",
                "payload": {
                    "request_id": "req_a",
                    "url": "https://api.example.com/v1/items",
                    "method": "GET",
                    "headers": {"authorization": "a", "x-app": "1", "accept": "application/json"},
                    "host_class": "target",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "req_b",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T12:00:01Z",
                "trace_id": "trace_b",
                "span_id": "",
                "action_id": "action_b",
                "payload": {
                    "request_id": "req_b",
                    "url": "https://api.example.com/v1/items",
                    "method": "GET",
                    "headers": {"authorization": "b", "x-app": "1"},
                    "host_class": "target",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "resp_a",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-01T12:00:02Z",
                "trace_id": "trace_a",
                "span_id": "",
                "action_id": "action_a",
                "payload": {"request_id": "req_a", "status_code": 200},
            },
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "resp_b",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-01T12:00:03Z",
                "trace_id": "trace_b",
                "span_id": "",
                "action_id": "action_b",
                "payload": {"request_id": "req_b", "status_code": 200},
            },
        ]
        payload = build_required_headers(rows, active_elimination=True)
        sets = payload.get("endpoint_minimal_sets", [])
        self.assertEqual(len(sets), 1)
        self.assertIn("authorization", sets[0].get("minimal_required_headers", []))
        self.assertIn("x-app", sets[0].get("minimal_required_headers", []))
        self.assertNotIn("accept", sets[0].get("minimal_required_headers", []))

    def test_active_replay_elimination_removes_optional_context(self) -> None:
        server, thread = self._start_header_cookie_server()
        try:
            base_url = f"http://127.0.0.1:{server.server_port}/probe"
            rows = [
                {
                    "schema_version": 1,
                    "run_id": "run_1",
                    "event_id": "req_replay_1",
                    "event_type": "network_request_event",
                    "ts_utc": "2026-04-01T12:10:00Z",
                    "trace_id": "trace_replay",
                    "span_id": "",
                    "action_id": "action_replay",
                    "payload": {
                        "request_id": "req_replay_1",
                        "url": base_url,
                        "method": "GET",
                        "phase_id": "home_probe",
                        "host_class": "target",
                        "headers": {
                            "X-Required": "1",
                            "X-Optional": "drop-me",
                            "Cookie": "sid=abc123; theme=dark",
                        },
                    },
                },
                {
                    "schema_version": 1,
                    "run_id": "run_1",
                    "event_id": "resp_replay_1",
                    "event_type": "network_response_event",
                    "ts_utc": "2026-04-01T12:10:01Z",
                    "trace_id": "trace_replay",
                    "span_id": "",
                    "action_id": "action_replay",
                    "payload": {
                        "request_id": "req_replay_1",
                        "response_id": "resp_replay_1",
                        "url": base_url,
                        "method": "GET",
                        "phase_id": "home_probe",
                        "status_code": 200,
                        "mime_type": "application/json",
                        "headers": {"content-type": "application/json"},
                        "body_preview": "{\"ok\":true}",
                    },
                },
            ]
            normalized = normalize_runtime_rows(rows)
            payload = build_required_headers_active_replay(normalized, timeout_ms=3000)
            self.assertEqual(payload.get("inference_mode"), "active_http_replay")
            endpoint_sets = payload.get("endpoint_minimal_sets", [])
            self.assertEqual(len(endpoint_sets), 1)
            item = endpoint_sets[0]
            self.assertEqual(item.get("validation_mode"), "active_http_replay")
            self.assertIn("x-required", item.get("minimal_required_headers", []))
            self.assertNotIn("x-optional", item.get("minimal_required_headers", []))
            self.assertIn("sid", item.get("minimal_required_cookies", []))
            self.assertNotIn("theme", item.get("minimal_required_cookies", []))
        finally:
            server.shutdown()
            thread.join(timeout=2.0)
            server.server_close()

    def test_build_replay_requirements_prefers_active_http_replay(self) -> None:
        server, thread = self._start_header_cookie_server()
        try:
            base_url = f"http://127.0.0.1:{server.server_port}/probe"
            rows = [
                {
                    "schema_version": 1,
                    "run_id": "run_1",
                    "event_id": "req_replay_reqs_1",
                    "event_type": "network_request_event",
                    "ts_utc": "2026-04-01T12:20:00Z",
                    "trace_id": "trace_replay_requirements",
                    "span_id": "",
                    "action_id": "action_replay_requirements",
                    "payload": {
                        "request_id": "req_replay_reqs_1",
                        "url": base_url,
                        "method": "GET",
                        "phase_id": "home_probe",
                        "host_class": "target",
                        "headers": {
                            "X-Required": "1",
                            "X-Optional": "drop-me",
                            "Cookie": "sid=abc123; theme=dark",
                        },
                    },
                },
                {
                    "schema_version": 1,
                    "run_id": "run_1",
                    "event_id": "resp_replay_reqs_1",
                    "event_type": "network_response_event",
                    "ts_utc": "2026-04-01T12:20:01Z",
                    "trace_id": "trace_replay_requirements",
                    "span_id": "",
                    "action_id": "action_replay_requirements",
                    "payload": {
                        "request_id": "req_replay_reqs_1",
                        "response_id": "resp_replay_reqs_1",
                        "url": base_url,
                        "method": "GET",
                        "phase_id": "home_probe",
                        "status_code": 200,
                        "mime_type": "application/json",
                        "headers": {"content-type": "application/json"},
                        "body_preview": "{\"ok\":true}",
                    },
                },
            ]
            normalized = normalize_runtime_rows(rows)
            payload = build_replay_requirements(normalized, prefer_active_replay=True, timeout_ms=3000)
            self.assertEqual(payload.get("inference_mode"), "active_http_replay")
            operations = payload.get("operations", {})
            self.assertIn("home", operations)
            endpoints = operations.get("home", [])
            self.assertEqual(len(endpoints), 1)
            endpoint = endpoints[0]
            self.assertEqual(endpoint.get("validation_mode"), "active_http_replay")
            self.assertIn("x-required", endpoint.get("required_headers", []))
            self.assertNotIn("x-optional", endpoint.get("required_headers", []))
            self.assertIn("sid", endpoint.get("required_cookies", []))
            self.assertNotIn("theme", endpoint.get("required_cookies", []))
        finally:
            server.shutdown()
            thread.join(timeout=2.0)
            server.server_close()

    def test_active_replay_skips_unsupported_scheme(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "req_custom_scheme",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T12:30:00Z",
                "trace_id": "trace_custom_scheme",
                "span_id": "",
                "action_id": "action_custom_scheme",
                "payload": {
                    "request_id": "req_custom_scheme",
                    "url": "blob:https://www.example.com/abc",
                    "method": "GET",
                    "phase_id": "home_probe",
                    "host_class": "target",
                    "headers": {"x-required": "1"},
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "resp_custom_scheme",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-01T12:30:01Z",
                "trace_id": "trace_custom_scheme",
                "span_id": "",
                "action_id": "action_custom_scheme",
                "payload": {
                    "request_id": "req_custom_scheme",
                    "response_id": "resp_custom_scheme",
                    "url": "blob:https://www.example.com/abc",
                    "method": "GET",
                    "phase_id": "home_probe",
                    "status_code": 200,
                    "mime_type": "application/json",
                    "headers": {"content-type": "application/json"},
                },
            },
        ]
        normalized = normalize_runtime_rows(rows)
        payload = build_required_headers_active_replay(normalized, timeout_ms=3000)
        endpoint_sets = payload.get("endpoint_minimal_sets", [])
        self.assertEqual(len(endpoint_sets), 1)
        self.assertEqual(
            endpoint_sets[0].get("validation_mode"),
            "active_http_replay_skipped_unsupported_scheme",
        )

    def _provider_export_rows(self):
        return [
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "phase_home",
                "event_type": "probe_phase_event",
                "ts_utc": "2026-04-02T12:00:00Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_phase",
                "payload": {"phase_id": "home_probe", "transition": "start", "operation": "home_probe_start"},
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "req_home",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-02T12:00:01Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_home",
                "payload": {
                    "request_id": "req_home",
                    "phase_id": "home_probe",
                    "request_operation": "home_bootstrap",
                    "url": "https://www.zdf.de/",
                    "method": "GET",
                    "headers": {"accept": "text/html"},
                    "host_class": "target",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "resp_home",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-02T12:00:02Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_home",
                "payload": {
                    "request_id": "req_home",
                    "response_id": "resp_home",
                    "phase_id": "home_probe",
                    "url": "https://www.zdf.de/",
                    "method": "GET",
                    "status_code": 200,
                    "mime_type": "text/html",
                    "headers": {"content-type": "text/html"},
                    "body_preview": "<html><head><title>ZDF Home</title></head><body>home</body></html>",
                    "host_class": "target",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "phase_search",
                "event_type": "probe_phase_event",
                "ts_utc": "2026-04-02T12:00:03Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_phase",
                "payload": {"phase_id": "search_probe", "transition": "start", "operation": "search_probe_start"},
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "req_search_1",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-02T12:00:04Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_search",
                "payload": {
                    "request_id": "req_search_1",
                    "phase_id": "search_probe",
                    "request_operation": "search_request",
                    "url": "https://api.zdf.de/v1/search?q=planet&page=1",
                    "method": "GET",
                    "headers": {
                        "accept": "application/json",
                        "authorization": "Bearer redacted",
                        "x-required": "1",
                        "x-optional": "yes",
                        "sec-fetch-mode": "cors",
                        "cookie": "sid=abc; _ga=track",
                        "referer": "https://www.zdf.de/",
                        "origin": "https://www.zdf.de",
                    },
                    "host_class": "target",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "resp_search_1",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-02T12:00:05Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_search",
                "payload": {
                    "request_id": "req_search_1",
                    "response_id": "resp_search_1",
                    "phase_id": "search_probe",
                    "url": "https://api.zdf.de/v1/search?q=planet&page=1",
                    "method": "GET",
                    "status_code": 200,
                    "mime_type": "application/json",
                    "headers": {"content-type": "application/json"},
                    "body_preview": "{\"results\":[{\"title\":\"Planet\"}]}",
                    "host_class": "target",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "req_search_2",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-02T12:00:06Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_search",
                "payload": {
                    "request_id": "req_search_2",
                    "phase_id": "search_probe",
                    "request_operation": "search_request",
                    "url": "https://api.zdf.de/v1/search?q=planet&page=2",
                    "method": "GET",
                    "headers": {
                        "accept": "application/json",
                        "authorization": "Bearer redacted",
                        "x-required": "1",
                        "cookie": "sid=abc",
                        "referer": "https://www.zdf.de/",
                        "origin": "https://www.zdf.de",
                    },
                    "host_class": "target",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "resp_search_2",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-02T12:00:07Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_search",
                "payload": {
                    "request_id": "req_search_2",
                    "response_id": "resp_search_2",
                    "phase_id": "search_probe",
                    "url": "https://api.zdf.de/v1/search?q=planet&page=2",
                    "method": "GET",
                    "status_code": 200,
                    "mime_type": "application/json",
                    "headers": {"content-type": "application/json"},
                    "body_preview": "{\"results\":[{\"title\":\"Planet 2\"}]}",
                    "host_class": "target",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "phase_detail",
                "event_type": "probe_phase_event",
                "ts_utc": "2026-04-02T12:00:08Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_phase",
                "payload": {"phase_id": "detail_probe", "transition": "start", "operation": "detail_probe_start"},
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "req_detail",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-02T12:00:09Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_detail",
                "payload": {
                    "request_id": "req_detail",
                    "phase_id": "detail_probe",
                    "request_operation": "detail_graphql_query",
                    "url": "https://api.zdf.de/v1/detail/42",
                    "method": "GET",
                    "headers": {"accept": "application/json"},
                    "host_class": "target",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "resp_detail",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-02T12:00:10Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_detail",
                "payload": {
                    "request_id": "req_detail",
                    "response_id": "resp_detail",
                    "phase_id": "detail_probe",
                    "url": "https://api.zdf.de/v1/detail/42",
                    "method": "GET",
                    "status_code": 200,
                    "mime_type": "application/json",
                    "headers": {"content-type": "application/json"},
                    "body_preview": "{\"item\":{\"title\":\"Episode\",\"canonicalId\":\"c42\",\"playback\":{\"manifest\":\"https://nrodlzdf-a.akamaihd.net/master.m3u8\"}}}",
                    "host_class": "target",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "phase_playback",
                "event_type": "probe_phase_event",
                "ts_utc": "2026-04-02T12:00:11Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_phase",
                "payload": {"phase_id": "playback_probe", "transition": "start", "operation": "playback_probe_start"},
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "req_manifest",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-02T12:00:12Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_playback",
                "payload": {
                    "request_id": "req_manifest",
                    "phase_id": "playback_probe",
                    "request_operation": "playback_manifest_fetch",
                    "url": "https://nrodlzdf-a.akamaihd.net/stream/master.m3u8",
                    "method": "GET",
                    "headers": {"accept": "application/vnd.apple.mpegurl", "authorization": "Bearer redacted", "cookie": "sid=abc"},
                    "host_class": "target",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "resp_manifest",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-02T12:00:13Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_playback",
                "payload": {
                    "request_id": "req_manifest",
                    "response_id": "resp_manifest",
                    "phase_id": "playback_probe",
                    "url": "https://nrodlzdf-a.akamaihd.net/stream/master.m3u8",
                    "method": "GET",
                    "status_code": 200,
                    "mime_type": "application/vnd.apple.mpegurl",
                    "headers": {"content-type": "application/vnd.apple.mpegurl"},
                    "body_preview": "#EXTM3U",
                    "host_class": "target",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "req_segment",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-02T12:00:14Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_playback",
                "payload": {
                    "request_id": "req_segment",
                    "phase_id": "playback_probe",
                    "request_operation": "playback_media_segment",
                    "url": "https://nrodlzdf-a.akamaihd.net/stream/seg-1.m4s",
                    "method": "GET",
                    "headers": {"range": "bytes=0-1024", "cookie": "sid=abc"},
                    "host_class": "target",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "resp_segment",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-02T12:00:15Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_playback",
                "payload": {
                    "request_id": "req_segment",
                    "response_id": "resp_segment",
                    "phase_id": "playback_probe",
                    "url": "https://nrodlzdf-a.akamaihd.net/stream/seg-1.m4s",
                    "method": "GET",
                    "status_code": 200,
                    "mime_type": "video/mp4",
                    "headers": {"content-type": "video/mp4"},
                    "host_class": "target",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "phase_auth",
                "event_type": "probe_phase_event",
                "ts_utc": "2026-04-02T12:00:16Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_phase",
                "payload": {"phase_id": "auth_probe", "transition": "start", "operation": "auth_probe_start"},
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "req_auth",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-02T12:00:17Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_auth",
                "payload": {
                    "request_id": "req_auth",
                    "phase_id": "auth_probe",
                    "request_operation": "auth_token_refresh",
                    "url": "https://api.zdf.de/v1/auth/refresh",
                    "method": "POST",
                    "headers": {"authorization": "Bearer secret", "content-type": "application/json"},
                    "body": "{\"refreshToken\":\"abc\"}",
                    "host_class": "target",
                },
            },
            {
                "schema_version": 1,
                "run_id": "run_provider",
                "event_id": "resp_auth",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-02T12:00:18Z",
                "trace_id": "trace_provider",
                "span_id": "",
                "action_id": "action_auth",
                "payload": {
                    "request_id": "req_auth",
                    "response_id": "resp_auth",
                    "phase_id": "auth_probe",
                    "url": "https://api.zdf.de/v1/auth/refresh",
                    "method": "POST",
                    "status_code": 200,
                    "mime_type": "application/json",
                    "headers": {"content-type": "application/json"},
                    "body_preview": "{\"token\":\"new\"}",
                    "host_class": "target",
                },
            },
        ]

    def _provider_export_payload(self):
        rows = self._provider_export_rows()
        normalized = normalize_runtime_rows(rows)
        endpoint_candidates = build_endpoint_candidates(normalized)
        required_headers = build_required_headers_active_replay(
            normalized,
            timeout_ms=2000,
            replay_executor=lambda **kwargs: {
                "success": ("x-required" in kwargs.get("headers", {}))
                and ("sid=" in str(kwargs.get("headers", {}).get("cookie", ""))),
                "status_code": 200 if ("x-required" in kwargs.get("headers", {})) and ("sid=" in str(kwargs.get("headers", {}).get("cookie", ""))) else 403,
                "error": "",
            },
        )
        replay_requirements = build_replay_requirements(normalized, prefer_active_replay=False)
        field_matrix = build_field_matrix(normalized)
        provenance_registry = build_provenance_registry(normalized)
        export = build_provider_draft_export(
            normalized,
            endpoint_candidates=endpoint_candidates,
            replay_requirements=replay_requirements,
            required_headers_payload=required_headers,
            field_matrix=field_matrix,
            provenance_registry=provenance_registry,
        )
        return export

    def test_provider_endpoint_template_normalization(self) -> None:
        export = self._provider_export_payload()
        templates = {str(item.get("endpoint_role") or ""): item for item in export.get("endpoint_templates", [])}
        self.assertIn("search", templates)
        search_tpl = templates["search"]
        self.assertEqual(search_tpl.get("normalized_host"), "api.zdf.de")
        self.assertEqual(search_tpl.get("normalized_path"), "/v1/search")
        self.assertEqual(search_tpl.get("method"), "GET")
        query_tpl = search_tpl.get("stable_query_template", {})
        self.assertIn("q", query_tpl)

    def test_provider_export_target_site_id_is_stable_and_canonical(self) -> None:
        rows = normalize_runtime_rows(
            [
                {
                    "schema_version": 1,
                    "run_id": "run_target_site",
                    "event_id": "req_home",
                    "event_type": "network_request_event",
                    "ts_utc": "2026-04-02T12:00:00Z",
                    "trace_id": "trace_target_site",
                    "span_id": "",
                    "action_id": "action_home",
                    "payload": {
                        "request_id": "req_home",
                        "phase_id": "home_probe",
                        "request_operation": "home_request",
                        "url": "https://www.zdf.de/",
                        "method": "GET",
                        "headers": {"accept": "application/json"},
                        "host_class": "target_document",
                    },
                },
                {
                    "schema_version": 1,
                    "run_id": "run_target_site",
                    "event_id": "resp_home",
                    "event_type": "network_response_event",
                    "ts_utc": "2026-04-02T12:00:01Z",
                    "trace_id": "trace_target_site",
                    "span_id": "",
                    "action_id": "action_home",
                    "payload": {
                        "request_id": "req_home",
                        "response_id": "resp_home",
                        "phase_id": "home_probe",
                        "url": "https://www.zdf.de/",
                        "method": "GET",
                        "status_code": 200,
                        "mime_type": "application/json",
                        "headers": {"content-type": "application/json"},
                        "body_preview": "{\"ok\":true}",
                        "host_class": "target_document",
                    },
                },
                {
                    "schema_version": 1,
                    "run_id": "run_target_site",
                    "event_id": "req_search",
                    "event_type": "network_request_event",
                    "ts_utc": "2026-04-02T12:00:02Z",
                    "trace_id": "trace_target_site",
                    "span_id": "",
                    "action_id": "action_search",
                    "payload": {
                        "request_id": "req_search",
                        "phase_id": "search_probe",
                        "request_operation": "search_request",
                        "url": "https://api.zdf.de/v1/search?q=planet",
                        "method": "GET",
                        "headers": {"accept": "application/json"},
                        "host_class": "target_api",
                    },
                },
                {
                    "schema_version": 1,
                    "run_id": "run_target_site",
                    "event_id": "resp_search",
                    "event_type": "network_response_event",
                    "ts_utc": "2026-04-02T12:00:03Z",
                    "trace_id": "trace_target_site",
                    "span_id": "",
                    "action_id": "action_search",
                    "payload": {
                        "request_id": "req_search",
                        "response_id": "resp_search",
                        "phase_id": "search_probe",
                        "url": "https://api.zdf.de/v1/search?q=planet",
                        "method": "GET",
                        "status_code": 200,
                        "mime_type": "application/json",
                        "headers": {"content-type": "application/json"},
                        "body_preview": "{\"items\":[]}",
                        "host_class": "target_api",
                    },
                },
            ]
        )
        required_headers_payload = {
            "schema_version": 1,
            "inference_mode": "active_http_replay",
            "endpoint_minimal_sets": [
                {
                    "operation": "home",
                    "phase_id": "home_probe",
                    "method": "GET",
                    "host": "www.zdf.de",
                    "path": "/",
                    "request_ids": ["req_home"],
                    "candidate_headers": ["accept"],
                    "minimal_required_headers": ["accept"],
                    "candidate_cookies": [],
                    "minimal_required_cookies": [],
                    "validation_mode": "active_http_replay",
                    "elimination_mode": "active_http_replay_iterative",
                },
                {
                    "operation": "search",
                    "phase_id": "search_probe",
                    "method": "GET",
                    "host": "api.zdf.de",
                    "path": "/v1/search",
                    "request_ids": ["req_search"],
                    "candidate_headers": ["accept"],
                    "minimal_required_headers": ["accept"],
                    "candidate_cookies": [],
                    "minimal_required_cookies": [],
                    "validation_mode": "active_http_replay",
                    "elimination_mode": "active_http_replay_iterative",
                },
            ],
        }
        export = build_provider_draft_export(
            rows,
            endpoint_candidates={"candidates": []},
            replay_requirements=build_replay_requirements(rows, prefer_active_replay=False),
            required_headers_payload=required_headers_payload,
            field_matrix=build_field_matrix(rows),
            provenance_registry=build_provenance_registry(rows),
        )
        self.assertEqual(export.get("target_site_id"), "zdf.de")
        descriptor = export.get("fishit_player_contract", {}).get("external_provider_descriptor", {})
        self.assertEqual(descriptor.get("target_site_id"), "zdf.de")

    def test_active_replay_minimizes_query_params_and_body_fields(self) -> None:
        rows = normalize_runtime_rows(
            [
                {
                    "schema_version": 1,
                    "run_id": "run_active_min",
                    "event_id": "req_active",
                    "event_type": "network_request_event",
                    "ts_utc": "2026-04-02T12:00:00Z",
                    "trace_id": "trace_active_min",
                    "span_id": "",
                    "action_id": "action_active",
                    "payload": {
                        "request_id": "req_active",
                        "phase_id": "search_probe",
                        "request_operation": "search_request",
                        "url": "http://127.0.0.1:54321/v1/search?q=planet&lang=de&token=abc",
                        "method": "POST",
                        "headers": {
                            "x-required": "1",
                            "x-optional": "yes",
                            "cookie": "sid=abc",
                            "content-type": "application/json",
                        },
                        "body": "{\"required\":\"yes\",\"optional\":\"x\"}",
                        "host_class": "target_api",
                    },
                },
                {
                    "schema_version": 1,
                    "run_id": "run_active_min",
                    "event_id": "resp_active",
                    "event_type": "network_response_event",
                    "ts_utc": "2026-04-02T12:00:01Z",
                    "trace_id": "trace_active_min",
                    "span_id": "",
                    "action_id": "action_active",
                    "payload": {
                        "request_id": "req_active",
                        "response_id": "resp_active",
                        "phase_id": "search_probe",
                        "url": "http://127.0.0.1:54321/v1/search?q=planet&lang=de&token=abc",
                        "method": "POST",
                        "status_code": 200,
                        "mime_type": "application/json",
                        "headers": {"content-type": "application/json"},
                        "body_preview": "{\"ok\":true}",
                        "host_class": "target_api",
                    },
                },
            ]
        )

        def stub_executor(**kwargs):
            headers = {str(name).lower(): str(value) for name, value in kwargs.get("headers", {}).items()}
            parsed = urlparse(str(kwargs.get("url") or ""))
            query_keys = {str(name) for name, _ in parse_qsl(parsed.query, keep_blank_values=True)}
            body_bytes = kwargs.get("body") or b""
            body_keys = set()
            if body_bytes:
                try:
                    body_obj = json.loads(body_bytes.decode("utf-8"))
                    if isinstance(body_obj, dict):
                        body_keys = {str(name) for name in body_obj.keys()}
                except Exception:
                    body_keys = set()
            success = headers.get("x-required") == "1" and "q" in query_keys and "required" in body_keys
            return {"success": success, "status_code": 200 if success else 403, "error": ""}

        payload = build_required_headers_active_replay(
            rows,
            timeout_ms=2000,
            allow_unsafe_methods=True,
            replay_executor=stub_executor,
        )
        endpoint_sets = list(payload.get("endpoint_minimal_sets") or [])
        self.assertEqual(len(endpoint_sets), 1)
        endpoint = endpoint_sets[0]
        self.assertEqual(endpoint.get("minimal_required_query_params"), ["q"])
        self.assertEqual(endpoint.get("minimal_required_body_fields"), ["required"])
        self.assertIn("lang", set(endpoint.get("candidate_query_params") or []))
        self.assertIn("optional", set(endpoint.get("candidate_body_fields") or []))
        steps = list(endpoint.get("elimination_steps") or [])
        self.assertTrue(any(str(step.get("dimension") or "") == "query_param" for step in steps))
        self.assertTrue(any(str(step.get("dimension") or "") == "body_field" for step in steps))

    def test_provider_replay_minimizer_removes_non_required_headers(self) -> None:
        export = self._provider_export_payload()
        replay_by_role = {str(item.get("endpoint_role") or ""): item for item in export.get("replay_requirements", [])}
        search_req = replay_by_role["search"]
        self.assertIn("x-required", search_req.get("required_headers", []))
        self.assertNotIn("x-optional", search_req.get("required_headers", []))
        self.assertIn("x-optional", search_req.get("optional_headers", []))

    def test_provider_required_provenance_inputs_are_exported(self) -> None:
        export = self._provider_export_payload()
        replay_by_role = {str(item.get("endpoint_role") or ""): item for item in export.get("replay_requirements", [])}
        search_req = replay_by_role["search"]
        provenance_inputs = set(search_req.get("required_provenance_inputs", []))
        self.assertIn("cookies.sid", provenance_inputs)
        auth_req = replay_by_role["auth_or_refresh"]
        self.assertIn("api-auth", set(auth_req.get("required_provenance_inputs", [])))

    def test_provider_playback_export_prefers_manifest_over_segment_noise(self) -> None:
        export = self._provider_export_payload()
        playback = export.get("playback_draft", {})
        self.assertIn(playback.get("manifest_kind_detected"), {"hls", "dash"})
        self.assertNotIn("range", playback.get("manifest_required_headers", []))
        self.assertNotIn(".m4s", " ".join(playback.get("stream_container_hints", [])))

    def test_provider_auth_export_never_emits_raw_token_values(self) -> None:
        export = self._provider_export_payload()
        auth = export.get("auth_draft", {})
        joined = json.dumps(auth, ensure_ascii=True).lower()
        self.assertNotIn("bearer secret", joined)
        for item in auth.get("provenance_backed_token_inputs", []):
            self.assertNotIn("bearer", str(item).lower())

    def test_provenance_graph_builds_edges(self) -> None:
        rows = [
            {
                "schema_version": 1,
                "run_id": "run_1",
                "event_id": "prov_1",
                "event_type": "provenance_event",
                "ts_utc": "2026-04-01T12:00:00Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_1",
                "payload": {
                    "entity_type": "header",
                    "entity_key": "authorization",
                    "produced_by": "resp_1",
                    "consumed_by": "req_2",
                    "derived_from": ["token_source"],
                    "phase_id": "playback_probe",
                },
            }
        ]
        graph = build_provenance_graph(rows)
        self.assertEqual(graph.get("node_count"), 2)
        self.assertGreaterEqual(graph.get("edge_count", 0), 2)

    def test_mission_event_normalization_sets_required_fields(self) -> None:
        rows = normalize_runtime_rows(
            [
                {
                    "schema_version": 1,
                    "run_id": "run_mission_evt",
                    "event_id": "mission_1",
                    "event_type": "mission_event",
                    "ts_utc": "2026-04-02T12:00:00Z",
                    "trace_id": "trace_mission_evt",
                    "span_id": "",
                    "action_id": "action_mission_evt",
                    "payload": {
                        "operation": "mission_selected",
                        "mission_id": "FISHIT_PIPELINE",
                        "wizard_step_id": "target_url_input",
                        "saturation_state": "INCOMPLETE",
                        "phase_id": "background_noise",
                        "target_site_id": "zdf_de",
                        "export_readiness": "NOT_READY",
                        "reason": "launcher_selection",
                    },
                }
            ]
        )
        payload = rows[0].get("payload", {})
        self.assertEqual(payload.get("operation"), "mission_selected")
        self.assertEqual(payload.get("mission_id"), "FISHIT_PIPELINE")
        self.assertEqual(payload.get("wizard_step_id"), "target_url_input")
        self.assertEqual(payload.get("export_readiness"), "NOT_READY")
        self.assertEqual(payload.get("host_class"), "ignored")

    def test_api_mapping_required_artifact_aliases_are_deterministic(self) -> None:
        artifacts = mission_required_artifacts("API_MAPPING")
        by_id = {str(item.get("id") or ""): item for item in artifacts}
        self.assertIn("site_runtime_model", by_id)
        self.assertIn("endpoint_templates", by_id)
        self.assertIn("replay_bundle", by_id)
        self.assertIn("runtime_events", by_id)
        self.assertIn("site_profile.draft.json", by_id["site_runtime_model"].get("paths", []))
        self.assertIn("endpoint_candidates.json", by_id["endpoint_templates"].get("paths", []))
        self.assertIn("replay_seed.json", by_id["replay_bundle"].get("paths", []))

    def test_standalone_required_artifact_aliases_include_webapp_runtime_draft(self) -> None:
        artifacts = mission_required_artifacts("STANDALONE_APP")
        by_id = {str(item.get("id") or ""): item for item in artifacts}
        self.assertIn("webapp_runtime_draft", by_id)
        self.assertIn("webapp_runtime_draft.json", by_id["webapp_runtime_draft"].get("paths", []))
        self.assertIn("provider_draft_export.json", by_id["webapp_runtime_draft"].get("paths", []))

    def test_replay_bundle_required_artifact_aliases_include_fixture_outputs(self) -> None:
        artifacts = mission_required_artifacts("REPLAY_BUNDLE")
        by_id = {str(item.get("id") or ""): item for item in artifacts}
        self.assertIn("replay_bundle", by_id)
        self.assertIn("runtime_events", by_id)
        self.assertIn("response_index", by_id)
        self.assertIn("fixture_manifest", by_id)
        self.assertIn("replay_bundle.json", by_id["replay_bundle"].get("paths", []))
        self.assertIn("fixture_manifest.json", by_id["fixture_manifest"].get("paths", []))


if __name__ == "__main__":
    unittest.main()
