#!/usr/bin/env python3
"""Unit tests for Mapper-Toolkit TUI catalog and runner helpers."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

TOOLKIT_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = Path(__file__).resolve().parents[4]
if str(TOOLKIT_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLKIT_DIR))

from main import use_side_matrix  # type: ignore  # noqa: E402
from tui_catalog import (  # type: ignore  # noqa: E402
    build_argv,
    catalog_by_screen,
    cli_surface_gaps,
    load_catalog,
    load_cli_surface,
    matrix_rows_for_screen,
)


def command_by_id(catalog, command_id: str):
    for command in catalog.commands:
        if command.id == command_id:
            return command
    raise AssertionError(f"missing command id: {command_id}")


class MapperToolkitTuiCatalogTests(unittest.TestCase):
    def setUp(self) -> None:
        self.catalog = load_catalog(REPO_ROOT)

    def test_screen_to_matrix_commands_are_complete(self) -> None:
        grouped = catalog_by_screen(self.catalog)
        screen_ids = {screen.id for screen in self.catalog.screens}
        self.assertEqual(set(grouped.keys()), screen_ids)
        for screen_id in screen_ids:
            self.assertGreater(len(grouped[screen_id]), 0, f"screen '{screen_id}' has no commands")
            matrix_rows = matrix_rows_for_screen(self.catalog, screen_id)
            self.assertGreater(len(matrix_rows), 0, f"screen '{screen_id}' has no matrix rows")

    def test_cli_surface_contract_has_no_gaps(self) -> None:
        gaps = cli_surface_gaps(self.catalog, load_cli_surface(REPO_ROOT))
        self.assertEqual(gaps, {})

    def test_required_and_typed_parameters_are_validated(self) -> None:
        cli = REPO_ROOT / "scripts" / "device" / "mapper-toolkit.sh"

        raw_cmd = command_by_id(self.catalog, "responses.raw")
        with self.assertRaisesRegex(ValueError, "Missing required parameter"):
            build_argv(cli, raw_cmd, {})

        sample_cmd = command_by_id(self.catalog, "responses.sample")
        with self.assertRaisesRegex(ValueError, "must be an integer"):
            build_argv(cli, sample_cmd, {"limit": "abc"})

    def test_flag_parameter_commands_emit_flag_without_value(self) -> None:
        cli = REPO_ROOT / "scripts" / "device" / "mapper-toolkit.sh"
        purge_cmd = command_by_id(self.catalog, "housekeeping.purge")
        argv = build_argv(cli, purge_cmd, {"yes": "yes"})
        self.assertEqual(argv[-1], "--yes")
        self.assertNotEqual(argv[-2:], ["--yes", "yes"])

    def test_destructive_commands_are_marked(self) -> None:
        destructive_ids = sorted(cmd.id for cmd in self.catalog.commands if cmd.safety == "destructive")
        self.assertIn("housekeeping.purge", destructive_ids)

    def test_matrix_mode_switches_by_terminal_width(self) -> None:
        self.assertTrue(use_side_matrix(width=180, matrix_visible=True, min_width=145))
        self.assertFalse(use_side_matrix(width=120, matrix_visible=True, min_width=145))
        self.assertFalse(use_side_matrix(width=180, matrix_visible=False, min_width=145))

    def test_triage_commands_present(self) -> None:
        command_ids = {cmd.id for cmd in self.catalog.commands}
        expected = {
            "triage.start",
            "triage.tail",
            "triage.focus",
            "triage.anomalies",
            "triage.bookmark",
            "triage.incident_pack",
            "triage.stop",
        }
        self.assertTrue(expected.issubset(command_ids))

    def test_scope_probe_and_provenance_commands_present(self) -> None:
        command_ids = {cmd.id for cmd in self.catalog.commands}
        expected = {
            "settings.scope_set",
            "settings.scope_show",
            "session.probe_start_phase",
            "session.probe_stop_phase",
            "session.probe_status",
            "trace.provenance_query",
            "trace.provenance_graph",
            "trace.provenance_export",
            "settings.provenance_mark",
            "headers.infer_required_active",
        }
        self.assertTrue(expected.issubset(command_ids))

    def test_replay_commands_present(self) -> None:
        command_ids = {cmd.id for cmd in self.catalog.commands}
        expected = {
            "replay.seed",
            "replay.baseline_create",
            "replay.baseline_list",
            "replay.run",
            "replay.diff",
            "replay.report",
        }
        self.assertTrue(expected.issubset(command_ids))


if __name__ == "__main__":
    unittest.main()
