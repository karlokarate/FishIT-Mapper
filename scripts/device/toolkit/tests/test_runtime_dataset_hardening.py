#!/usr/bin/env python3
"""Unit tests for capture/correlation hardening paths in runtime_dataset_cli."""

from __future__ import annotations

import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

TOOLKIT_DIR = Path(__file__).resolve().parents[1]
if str(TOOLKIT_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLKIT_DIR))

from runtime_dataset_cli import (  # type: ignore  # noqa: E402
    build_body_store,
    build_correlation,
    build_field_matrix,
    build_provenance_registry,
    build_replay_requirements,
    build_required_headers,
    build_required_headers_active_replay,
    build_response_store_index,
    build_provenance_graph,
    ensure_derived,
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
            self.assertEqual(by_event["resp_json"].get("body_capture_policy"), "candidate_full")
            self.assertEqual(by_event["resp_html"].get("body_capture_policy"), "candidate_full")

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


if __name__ == "__main__":
    unittest.main()
