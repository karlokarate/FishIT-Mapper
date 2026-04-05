#!/usr/bin/env python3
"""Integration smoke tests for runtime dataset CLI scoped run/finalization behavior."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOLKIT_DIR = Path(__file__).resolve().parents[1]
CLI_PATH = TOOLKIT_DIR / "runtime_dataset_cli.py"


def write_events(runtime_dir: Path, rows):
    events_dir = runtime_dir / "events"
    events_dir.mkdir(parents=True, exist_ok=True)
    target = events_dir / "runtime_events.jsonl"
    with target.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=True) + "\n")
    return target


def run_cli(*args: str, runtime_dir: Path) -> subprocess.CompletedProcess:
    cmd = [sys.executable, str(CLI_PATH), *args, "--runtime-dir", str(runtime_dir)]
    return subprocess.run(cmd, check=False, capture_output=True, text=True)


def phase_fixture_rows(run_id: str = "run_scoped"):
    rows = []
    for idx, phase in enumerate(["home_probe", "search_probe", "detail_probe", "playback_probe"]):
        rows.append(
            {
                "schema_version": 1,
                "run_id": run_id,
                "event_id": f"phase_{phase}",
                "event_type": "probe_phase_event",
                "ts_utc": f"2026-04-01T12:00:0{idx}Z",
                "trace_id": "trace_phase",
                "span_id": "",
                "action_id": "action_phase",
                "payload": {"phase_id": phase, "transition": "start"},
            }
        )

    def req_resp(event_suffix: str, phase: str, url: str):
        return [
            {
                "schema_version": 1,
                "run_id": run_id,
                "event_id": f"req_{event_suffix}",
                "event_type": "network_request_event",
                "ts_utc": "2026-04-01T12:01:00Z",
                "trace_id": f"trace_{event_suffix}",
                "span_id": "",
                "action_id": f"action_{event_suffix}",
                "payload": {
                    "request_id": f"req_{event_suffix}",
                    "phase_id": phase,
                    "url": url,
                    "method": "GET",
                    "headers": {"accept": "application/json", "authorization": "Bearer x"},
                    "host_class": "target",
                },
            },
            {
                "schema_version": 1,
                "run_id": run_id,
                "event_id": f"resp_{event_suffix}",
                "event_type": "network_response_event",
                "ts_utc": "2026-04-01T12:01:01Z",
                "trace_id": f"trace_{event_suffix}",
                "span_id": "",
                "action_id": f"action_{event_suffix}",
                "payload": {
                    "request_id": f"req_{event_suffix}",
                    "response_id": f"resp_{event_suffix}",
                    "phase_id": phase,
                    "url": url,
                    "method": "GET",
                    "status_code": 200,
                    "mime_type": "application/json",
                    "headers": {"content-type": "application/json", "content-length": "24"},
                    "body_preview": "{\"results\":[{\"id\":1}]}",
                    "host_class": "target",
                },
            },
        ]

    rows.extend(req_resp("home", "home_probe", "https://api.example.com/v1/home"))
    rows.extend(req_resp("search", "search_probe", "https://api.example.com/v1/search?q=a"))
    rows.extend(req_resp("detail", "detail_probe", "https://api.example.com/v1/detail/1"))
    rows.extend(req_resp("playback", "playback_probe", "https://api.example.com/v1/playback/resolver?id=1"))
    return rows


class RuntimeDatasetIntegrationSmokeTests(unittest.TestCase):
    def test_scoped_run_validate_emits_all_phase_markers(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            rows = [
                {
                    "schema_version": 1,
                    "run_id": "run_old",
                    "event_id": "old_req",
                    "event_type": "network_request_event",
                    "ts_utc": "2026-04-01T11:59:00Z",
                    "trace_id": "trace_old",
                    "span_id": "",
                    "action_id": "action_old",
                    "payload": {"request_id": "old_req", "url": "https://api.example.com/old", "method": "GET", "headers": {}},
                }
            ]
            rows.extend(phase_fixture_rows(run_id="run_scoped"))
            write_events(runtime_dir, rows)

            result = run_cli("housekeeping", "validate", runtime_dir=runtime_dir)
            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads((runtime_dir / "pipeline_ready_report.json").read_text(encoding="utf-8"))
            self.assertTrue(report.get("pipeline_ready"))
            gate = report.get("gates", {}).get("phase_completeness_gate", {})
            self.assertTrue(gate.get("passed"))
            for phase in ["home_probe", "search_probe", "detail_probe", "playback_probe"]:
                self.assertIn(phase, gate.get("seen_phases", []))

    def test_reindex_emits_non_empty_artifacts_when_raw_valid(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            write_events(runtime_dir, phase_fixture_rows())
            result = run_cli("housekeeping", "reindex", runtime_dir=runtime_dir)
            self.assertEqual(result.returncode, 0, result.stderr)
            required = [
                "events.jsonl",
                "extraction_events.jsonl",
                "requests.normalized.jsonl",
                "responses.normalized.jsonl",
                "endpoint_candidates.json",
                "field_matrix.json",
                "required_headers_report.json",
                "replay_requirements.json",
                "provider_draft_export.json",
                "source_pipeline_bundle.json",
                "site_runtime_model.json",
                "manifest.json",
                "mission_export_summary.json",
            ]
            for name in required:
                path = runtime_dir / name
                self.assertTrue(path.exists(), name)
                self.assertGreater(path.stat().st_size, 0, name)
            bundle_zip = runtime_dir / "exports" / "source_plugin_bundle.zip"
            self.assertTrue(bundle_zip.exists())
            self.assertGreater(bundle_zip.stat().st_size, 0)

    def test_headers_infer_required_active_includes_truncation_visibility(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            rows = [
                {
                    "schema_version": 1,
                    "run_id": "run_active_headers",
                    "event_id": "phase_home",
                    "event_type": "probe_phase_event",
                    "ts_utc": "2026-04-01T12:00:00Z",
                    "trace_id": "trace_phase",
                    "span_id": "",
                    "action_id": "action_phase",
                    "payload": {"phase_id": "home_probe", "transition": "start"},
                }
            ]
            write_events(runtime_dir, rows)
            result = run_cli("headers", "infer-required-active", runtime_dir=runtime_dir)
            self.assertEqual(result.returncode, 0, result.stderr)
            payload = json.loads(result.stdout or "{}")
            replay = payload.get("replay_requirements", {})
            self.assertIn("truncation_summary", replay)
            self.assertIn("total_truncated_responses", replay.get("truncation_summary", {}))

    def test_mapping_source_pipeline_bundle_emits_player_contract_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            write_events(runtime_dir, phase_fixture_rows())
            result = run_cli("mapping", "source-pipeline-bundle", runtime_dir=runtime_dir)
            self.assertEqual(result.returncode, 0, result.stderr)
            payload = json.loads(result.stdout or "{}")
            self.assertIn("bundleDescriptor", payload)
            self.assertIn("endpointTemplates", payload)
            self.assertIn("replayRequirements", payload)
            self.assertIn("sessionAuth", payload)
            self.assertIn("playback", payload)

    def test_reindex_preserves_wizard_and_anchor_events(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            rows = [
                {
                    "schema_version": 1,
                    "run_id": "run_wizard",
                    "event_id": "mission_selected",
                    "event_type": "mission_event",
                    "ts_utc": "2026-04-02T09:59:59Z",
                    "trace_id": "trace_wiz",
                    "span_id": "",
                    "action_id": "action_wiz",
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
                },
                {
                    "schema_version": 1,
                    "run_id": "run_wizard",
                    "event_id": "wiz_start",
                    "event_type": "wizard_event",
                    "ts_utc": "2026-04-02T10:00:00Z",
                    "trace_id": "trace_wiz",
                    "span_id": "",
                    "action_id": "action_wiz",
                    "payload": {
                        "operation": "wizard_started",
                        "mission_id": "FISHIT_PIPELINE",
                        "wizard_step_id": "target_url_input",
                        "saturation_state": "INCOMPLETE",
                        "phase_id": "background_noise",
                        "target_site_id": "zdf_de",
                    },
                },
                {
                    "schema_version": 1,
                    "run_id": "run_wizard",
                    "event_id": "anchor_created",
                    "event_type": "overlay_anchor_event",
                    "ts_utc": "2026-04-02T10:00:01Z",
                    "trace_id": "trace_wiz",
                    "span_id": "",
                    "action_id": "action_wiz",
                    "payload": {
                        "operation": "overlay_anchor_created",
                        "anchor_id": "anchor_1",
                        "name": "search_input",
                        "anchor_type": "search_input",
                        "phase_id": "search_probe",
                        "target_site_id": "zdf_de",
                    },
                },
            ]
            rows.extend(phase_fixture_rows(run_id="run_wizard"))
            write_events(runtime_dir, rows)
            result = run_cli("housekeeping", "reindex", runtime_dir=runtime_dir)
            self.assertEqual(result.returncode, 0, result.stderr)
            events_path = runtime_dir / "events.jsonl"
            self.assertTrue(events_path.exists())
            event_types = []
            with events_path.open("r", encoding="utf-8") as handle:
                for line in handle:
                    row = json.loads(line)
                    event_types.append(row.get("event_type"))
            self.assertIn("mission_event", event_types)
            self.assertIn("wizard_event", event_types)
            self.assertIn("overlay_anchor_event", event_types)

    def test_mission_summary_reports_readiness_and_missing_requirements(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            rows = [
                {
                    "schema_version": 1,
                    "run_id": "run_mission",
                    "event_id": "mission_selected",
                    "event_type": "mission_event",
                    "ts_utc": "2026-04-02T10:01:00Z",
                    "trace_id": "trace_mission",
                    "span_id": "",
                    "action_id": "action_mission",
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
            rows.extend(phase_fixture_rows(run_id="run_mission"))
            write_events(runtime_dir, rows)
            result = run_cli(
                "housekeeping",
                "mission-summary",
                "--mission-id",
                "FISHIT_PIPELINE",
                runtime_dir=runtime_dir,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            payload = json.loads(result.stdout or "{}")
            self.assertIn(payload.get("export_readiness"), {"NOT_READY", "PARTIAL", "BLOCKED", "READY"})
            self.assertIn("missing_required_steps", payload)
            self.assertIn("missing_required_files", payload)
            self.assertIn("required_file_aliases", payload)
            self.assertIn("gate_results", payload)
            self.assertIn("failed_gates", payload)
            self.assertIn("hard_gates_passed", payload)
            summary_path = runtime_dir / "mission_export_summary.json"
            self.assertTrue(summary_path.exists())

    def test_mission_summary_strict_readiness_returns_non_zero_when_not_ready(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            rows = [
                {
                    "schema_version": 1,
                    "run_id": "run_strict",
                    "event_id": "mission_selected",
                    "event_type": "mission_event",
                    "ts_utc": "2026-04-02T10:01:00Z",
                    "trace_id": "trace_strict",
                    "span_id": "",
                    "action_id": "action_strict",
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
            write_events(runtime_dir, rows)
            result = run_cli(
                "housekeeping",
                "mission-summary",
                "--mission-id",
                "FISHIT_PIPELINE",
                "--strict-readiness",
                runtime_dir=runtime_dir,
            )
            self.assertEqual(result.returncode, 2, result.stderr)

    def test_api_mapping_mission_summary_uses_mission_specific_requirements(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            rows = [
                {
                    "schema_version": 1,
                    "run_id": "run_api_mapping",
                    "event_id": "mission_selected",
                    "event_type": "mission_event",
                    "ts_utc": "2026-04-02T10:01:00Z",
                    "trace_id": "trace_api_mapping",
                    "span_id": "",
                    "action_id": "action_api_mapping",
                    "payload": {
                        "operation": "mission_selected",
                        "mission_id": "API_MAPPING",
                        "wizard_step_id": "target_url_input",
                        "saturation_state": "INCOMPLETE",
                        "phase_id": "background_noise",
                        "target_site_id": "zdf_de",
                        "export_readiness": "NOT_READY",
                        "reason": "launcher_selection",
                    },
                }
            ]
            rows.extend(phase_fixture_rows(run_id="run_api_mapping"))
            write_events(runtime_dir, rows)
            result = run_cli(
                "housekeeping",
                "reindex",
                "--mission-id",
                "API_MAPPING",
                runtime_dir=runtime_dir,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            summary = json.loads((runtime_dir / "mission_export_summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary.get("mission_id"), "API_MAPPING")
            required_steps = set(summary.get("required_steps") or [])
            self.assertIn("search_probe_step", required_steps)
            self.assertNotIn("playback_probe_step", required_steps)

    def test_standalone_mission_summary_uses_home_detail_required_steps(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            rows = [
                {
                    "schema_version": 1,
                    "run_id": "run_standalone",
                    "event_id": "mission_selected",
                    "event_type": "mission_event",
                    "ts_utc": "2026-04-02T10:01:00Z",
                    "trace_id": "trace_standalone",
                    "span_id": "",
                    "action_id": "action_standalone",
                    "payload": {
                        "operation": "mission_selected",
                        "mission_id": "STANDALONE_APP",
                        "wizard_step_id": "target_url_input",
                        "saturation_state": "INCOMPLETE",
                        "phase_id": "background_noise",
                        "target_site_id": "zdf_de",
                        "export_readiness": "NOT_READY",
                        "reason": "launcher_selection",
                    },
                }
            ]
            rows.extend(phase_fixture_rows(run_id="run_standalone"))
            write_events(runtime_dir, rows)
            result = run_cli(
                "housekeeping",
                "reindex",
                "--mission-id",
                "STANDALONE_APP",
                runtime_dir=runtime_dir,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            summary = json.loads((runtime_dir / "mission_export_summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary.get("mission_id"), "STANDALONE_APP")
            required_steps = set(summary.get("required_steps") or [])
            self.assertIn("home_probe_step", required_steps)
            self.assertIn("detail_probe_step", required_steps)
            self.assertNotIn("search_probe_step", required_steps)
            self.assertNotIn("playback_probe_step", required_steps)
            required_files = set(summary.get("required_files") or [])
            self.assertIn("webapp_runtime_draft", required_files)

    def test_replay_bundle_mission_summary_uses_home_only_required_steps(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            rows = [
                {
                    "schema_version": 1,
                    "run_id": "run_replay_bundle",
                    "event_id": "mission_selected",
                    "event_type": "mission_event",
                    "ts_utc": "2026-04-02T10:01:00Z",
                    "trace_id": "trace_replay_bundle",
                    "span_id": "",
                    "action_id": "action_replay_bundle",
                    "payload": {
                        "operation": "mission_selected",
                        "mission_id": "REPLAY_BUNDLE",
                        "wizard_step_id": "target_url_input",
                        "saturation_state": "INCOMPLETE",
                        "phase_id": "background_noise",
                        "target_site_id": "zdf_de",
                        "export_readiness": "NOT_READY",
                        "reason": "launcher_selection",
                    },
                }
            ]
            rows.extend(phase_fixture_rows(run_id="run_replay_bundle"))
            write_events(runtime_dir, rows)
            result = run_cli(
                "housekeeping",
                "reindex",
                "--mission-id",
                "REPLAY_BUNDLE",
                runtime_dir=runtime_dir,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            summary = json.loads((runtime_dir / "mission_export_summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary.get("mission_id"), "REPLAY_BUNDLE")
            required_steps = set(summary.get("required_steps") or [])
            self.assertIn("home_probe_step", required_steps)
            self.assertNotIn("search_probe_step", required_steps)
            self.assertNotIn("detail_probe_step", required_steps)
            required_files = set(summary.get("required_files") or [])
            self.assertIn("replay_bundle", required_files)
            self.assertIn("fixture_manifest", required_files)

    def test_candidate_body_capture_reads_full_json_html_from_store(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            runtime_dir = Path(tmp)
            response_store = runtime_dir / "response_store"
            response_store.mkdir(parents=True, exist_ok=True)
            json_body = "{\"data\":{\"title\":\"Long title\",\"items\":[1,2,3,4,5]}}"
            html_body = "<html><head><title>Long HTML</title></head><body>ok</body></html>"
            (response_store / "resp_json.bin").write_text(json_body, encoding="utf-8")
            (response_store / "resp_html.bin").write_text(html_body, encoding="utf-8")
            rows = [
                {
                    "schema_version": 1,
                    "run_id": "run_store",
                    "event_id": "req_json",
                    "event_type": "network_request_event",
                    "ts_utc": "2026-04-01T12:05:00Z",
                    "trace_id": "trace_store",
                    "span_id": "",
                    "action_id": "action_store",
                    "payload": {
                        "request_id": "req_json",
                        "phase_id": "search_probe",
                        "url": "https://api.example.com/v1/search?q=x",
                        "method": "GET",
                        "headers": {"accept": "application/json"},
                        "host_class": "target",
                    },
                },
                {
                    "schema_version": 1,
                    "run_id": "run_store",
                    "event_id": "resp_json",
                    "event_type": "network_response_event",
                    "ts_utc": "2026-04-01T12:05:01Z",
                    "trace_id": "trace_store",
                    "span_id": "",
                    "action_id": "action_store",
                    "payload": {
                        "request_id": "req_json",
                        "response_id": "resp_json",
                        "phase_id": "search_probe",
                        "url": "https://api.example.com/v1/search?q=x",
                        "method": "GET",
                        "status_code": 200,
                        "mime_type": "application/json",
                        "headers": {"content-type": "application/json"},
                        "response_store_path": "resp_json.bin",
                        "host_class": "target",
                    },
                },
                {
                    "schema_version": 1,
                    "run_id": "run_store",
                    "event_id": "req_html",
                    "event_type": "network_request_event",
                    "ts_utc": "2026-04-01T12:05:02Z",
                    "trace_id": "trace_store",
                    "span_id": "",
                    "action_id": "action_store",
                    "payload": {
                        "request_id": "req_html",
                        "phase_id": "home_probe",
                        "url": "https://www.example.com/home",
                        "method": "GET",
                        "headers": {"accept": "text/html"},
                        "host_class": "target",
                    },
                },
                {
                    "schema_version": 1,
                    "run_id": "run_store",
                    "event_id": "resp_html",
                    "event_type": "network_response_event",
                    "ts_utc": "2026-04-01T12:05:03Z",
                    "trace_id": "trace_store",
                    "span_id": "",
                    "action_id": "action_store",
                    "payload": {
                        "request_id": "req_html",
                        "response_id": "resp_html",
                        "phase_id": "home_probe",
                        "url": "https://www.example.com/home",
                        "method": "GET",
                        "status_code": 200,
                        "mime_type": "text/html",
                        "headers": {"content-type": "text/html"},
                        "response_store_path": "resp_html.bin",
                        "host_class": "target",
                    },
                },
            ]
            write_events(runtime_dir, rows)
            result = run_cli("housekeeping", "reindex", runtime_dir=runtime_dir)
            self.assertEqual(result.returncode, 0, result.stderr)
            index = json.loads((runtime_dir / "response_index.json").read_text(encoding="utf-8"))
            items = {str(item.get("event_id") or ""): item for item in index.get("items", [])}
            self.assertGreaterEqual(int(items["resp_json"].get("size_bytes") or 0), len(json_body))
            self.assertGreaterEqual(int(items["resp_html"].get("size_bytes") or 0), len(html_body))
            self.assertEqual(items["resp_json"].get("body_capture_policy"), "full_candidate_required")
            self.assertIn(items["resp_html"].get("body_capture_policy"), {"full_candidate", "full_candidate_required"})


if __name__ == "__main__":
    unittest.main()
