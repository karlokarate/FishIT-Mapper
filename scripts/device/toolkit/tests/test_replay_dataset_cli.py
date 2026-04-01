#!/usr/bin/env python3
"""Unit tests for Mapper-Toolkit replay dataset workflow."""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path

TOOLKIT_DIR = Path(__file__).resolve().parents[1]
if str(TOOLKIT_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLKIT_DIR))

from runtime_dataset_cli import (  # type: ignore  # noqa: E402
    build_replay_baseline,
    create_named_baseline,
    do_replay,
    evaluate_replay_diff,
)


class ReplayDatasetCliTests(unittest.TestCase):
    def _baseline_rows(self):
        return [
            {
                "schema_version": 1,
                "run_id": "mapper_test_run",
                "event_id": "req_base_1",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T11:00:00Z",
                "trace_id": "trace_replay",
                "span_id": "",
                "action_id": "action_login",
                "payload": {
                    "url": "https://api.example.com/auth/login",
                    "method": "POST",
                    "headers": {
                        "authorization": "Bearer test",
                        "x-client": "mapper"
                    }
                }
            },
            {
                "schema_version": 1,
                "run_id": "mapper_test_run",
                "event_id": "resp_base_1",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-01T11:00:01Z",
                "trace_id": "trace_replay",
                "span_id": "",
                "action_id": "action_login",
                "payload": {
                    "url": "https://api.example.com/auth/login",
                    "status": 200,
                    "mime": "application/json",
                    "body_preview": "{\"token\":\"abc\",\"user\":{\"id\":1,\"name\":\"A\"}}"
                }
            }
        ]

    def _current_rows(self):
        rows = deepcopy(self._baseline_rows())
        rows[1]["event_id"] = "resp_cur_1"
        rows[1]["payload"]["body_preview"] = "{\"session\":\"new\",\"account\":{\"uuid\":\"u1\",\"tier\":\"pro\"}}"
        return rows

    def test_evaluate_replay_diff_is_deterministic(self) -> None:
        baseline = build_replay_baseline(
            rows=self._baseline_rows(),
            baseline_name="auth-loop-main",
            preset="auth-loop",
            source_meta={"incident_id": "", "preset": "auth-loop", "trace_id": "", "action_id": "", "event_id": ""},
        )
        current = build_replay_baseline(
            rows=self._current_rows(),
            baseline_name="auth-loop-main",
            preset="auth-loop",
            source_meta={"incident_id": "", "preset": "auth-loop", "trace_id": "", "action_id": "", "event_id": ""},
        )

        first = evaluate_replay_diff(baseline, current)
        second = evaluate_replay_diff(baseline, current)

        self.assertEqual(first.get("failed_count"), second.get("failed_count"))
        self.assertEqual(first.get("failed_by_severity"), second.get("failed_by_severity"))
        self.assertEqual(first.get("checks"), second.get("checks"))

    def test_do_replay_diff_ci_strict_blocks_on_critical_failure(self) -> None:
        baseline_rows = self._baseline_rows()
        current_rows = self._current_rows()

        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            (runtime_dir / "events").mkdir(parents=True, exist_ok=True)
            events_path = runtime_dir / "events" / "runtime_events.jsonl"
            with events_path.open("w", encoding="utf-8") as handle:
                for row in current_rows:
                    handle.write(json.dumps(row, ensure_ascii=True) + "\n")

            baseline = build_replay_baseline(
                rows=baseline_rows,
                baseline_name="auth-loop-main",
                preset="auth-loop",
                source_meta={"incident_id": "", "preset": "auth-loop", "trace_id": "", "action_id": "", "event_id": ""},
            )
            create_named_baseline(runtime_dir, baseline)

            args = argparse.Namespace(
                baseline_name="auth-loop-main",
                incident_id="",
                preset="",
                trace_id="",
                action_id="",
                event_id="",
                limit=0,
                ci_strict=True,
                seed_path="",
                timeout_ms=15000,
            )
            rc = do_replay("diff", current_rows, args, runtime_dir)
            self.assertEqual(rc, 2)


if __name__ == "__main__":
    unittest.main()
