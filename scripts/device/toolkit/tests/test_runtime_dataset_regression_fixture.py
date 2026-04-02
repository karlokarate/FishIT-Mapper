#!/usr/bin/env python3
"""Fixture-driven regression tests for runtime collector cutover behavior."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

TOOLKIT_DIR = Path(__file__).resolve().parents[1]
if str(TOOLKIT_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLKIT_DIR))

from runtime_dataset_cli import ensure_derived, normalize_runtime_rows, read_jsonl  # type: ignore  # noqa: E402


FIXTURE_PATH = Path(__file__).resolve().parent / "fixtures" / "runtime_regression_zdf.jsonl"


class RuntimeDatasetRegressionFixtureTests(unittest.TestCase):
    def _fixture_rows(self):
        return read_jsonl(FIXTURE_PATH)

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
            paths = ensure_derived(runtime_dir, rows)

            response_index = json.loads(paths["response_index"].read_text(encoding="utf-8"))
            items = {str(item.get("event_id") or ""): item for item in response_index.get("items", [])}
            playback_item = items["resp_playback"]
            self.assertTrue(playback_item.get("capture_truncated"))
            self.assertEqual(int(playback_item.get("capture_limit_bytes") or 0), 16 * 1024 * 1024)
            self.assertEqual(int(playback_item.get("original_content_length") or 0), 20000000)
            self.assertEqual(int(playback_item.get("stored_size_bytes") or 0), 16 * 1024 * 1024)

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


if __name__ == "__main__":
    unittest.main()
