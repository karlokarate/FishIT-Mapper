#!/usr/bin/env python3
"""Unit tests for capture/correlation hardening paths in runtime_dataset_cli."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

TOOLKIT_DIR = Path(__file__).resolve().parents[1]
if str(TOOLKIT_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLKIT_DIR))

from runtime_dataset_cli import (  # type: ignore  # noqa: E402
    build_correlation,
    build_field_matrix,
    build_required_headers,
    build_response_store_index,
    build_provenance_graph,
    pipeline_quality_report,
)


class RuntimeDatasetHardeningTests(unittest.TestCase):
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
