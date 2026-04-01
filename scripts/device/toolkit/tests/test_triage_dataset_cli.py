#!/usr/bin/env python3
"""Unit tests for Mapper-Toolkit triage dataset workflow."""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
import unittest
from pathlib import Path

TOOLKIT_DIR = Path(__file__).resolve().parents[1]
if str(TOOLKIT_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLKIT_DIR))

from runtime_dataset_cli import (  # type: ignore  # noqa: E402
    apply_preset_filters,
    detect_triage_alerts,
    ensure_derived,
    incident_bundle,
)


class TriageDatasetCliTests(unittest.TestCase):
    def _sample_rows(self):
        return [
            {
                "schema_version": 1,
                "run_id": "mapper_test_run",
                "event_id": "req_1",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T10:00:00Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_login",
                "payload": {
                    "url": "https://api.example.com/auth/login",
                    "method": "POST",
                    "headers": {"authorization": "Bearer old"},
                },
            },
            {
                "schema_version": 1,
                "run_id": "mapper_test_run",
                "event_id": "resp_401",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-01T10:00:01Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_login",
                "payload": {
                    "url": "https://api.example.com/auth/login",
                    "status": 401,
                    "mime": "application/json",
                    "body_preview": "{\"error\":\"unauthorized\"}",
                    "response_store_path": "resp_401.json",
                },
            },
            {
                "schema_version": 1,
                "run_id": "mapper_test_run",
                "event_id": "auth_1",
                "event_type": "auth_event",
                "ts_utc": "2026-04-01T10:00:02Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_login",
                "payload": {"event": "token_refreshed"},
            },
            {
                "schema_version": 1,
                "run_id": "mapper_test_run",
                "event_id": "resp_200",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-01T10:00:03Z",
                "trace_id": "trace_1",
                "span_id": "",
                "action_id": "action_login",
                "payload": {
                    "url": "https://api.example.com/auth/login",
                    "status": 200,
                    "mime": "application/json",
                    "body_preview": "{\"ok\":true,\"token\":\"new\"}",
                    "response_store_path": "resp_200.json",
                },
            },
        ]

    def test_detect_triage_alerts_auth_loop(self) -> None:
        alerts = detect_triage_alerts(self._sample_rows())
        rule_ids = {str(alert.get("rule_id") or "") for alert in alerts}
        self.assertIn("auth_loop", rule_ids)

    def test_apply_preset_filters_resolves_known_preset(self) -> None:
        filtered, preset = apply_preset_filters(self._sample_rows(), "auth-loop")
        self.assertEqual(preset.get("id"), "auth-loop")
        self.assertGreaterEqual(len(filtered), 1)

    def test_incident_bundle_contains_core_files(self) -> None:
        rows = self._sample_rows()
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            (runtime_dir / "events").mkdir(parents=True, exist_ok=True)
            (runtime_dir / "response_store").mkdir(parents=True, exist_ok=True)
            (runtime_dir / "response_store" / "resp_401.json").write_text("{\"error\":\"unauthorized\"}\n", encoding="utf-8")
            (runtime_dir / "response_store" / "resp_200.json").write_text("{\"ok\":true}\n", encoding="utf-8")

            ensure_derived(runtime_dir, rows)
            args = argparse.Namespace(
                preset="",
                trace_id="trace_1",
                action_id="action_login",
                event_id="",
                incident_id="INC_TEST_001",
                limit=100,
            )
            bundle = incident_bundle(runtime_dir, rows, args)
            self.assertTrue((bundle / "events.jsonl").exists())
            self.assertTrue((bundle / "incident_report.json").exists())
            report = json.loads((bundle / "incident_report.json").read_text(encoding="utf-8"))
            self.assertEqual(report.get("incident_id"), "INC_TEST_001")


if __name__ == "__main__":
    unittest.main()
