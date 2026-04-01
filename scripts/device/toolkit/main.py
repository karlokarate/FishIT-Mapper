#!/usr/bin/env python3
"""Mapper-Toolkit Python Textual TUI entrypoint."""

from __future__ import annotations

import argparse
import asyncio
import json
import subprocess
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional, cast

from tui_catalog import (
    Catalog,
    CommandSpec,
    ParamSpec,
    all_screen_ids,
    all_screen_titles,
    build_argv,
    cli_surface_gaps,
    list_actions,
    load_catalog,
    load_cli_surface,
    matrix_rows_for_screen,
    repo_root_from_file,
)


def use_side_matrix(width: int, matrix_visible: bool, min_width: int) -> bool:
    return matrix_visible and width >= min_width


SEVERITY_ORDER = {"critical": 0, "high": 1, "medium": 2, "low": 3}


def run_contract_check(repo_root: Path) -> int:
    catalog = load_catalog(repo_root)
    cli_surface = load_cli_surface(repo_root)
    gaps = cli_surface_gaps(catalog, cli_surface)

    payload = {
        "catalog_id": catalog.catalog_id,
        "version": catalog.version,
        "screen_count": len(catalog.screens),
        "command_count": len(catalog.commands),
        "cli_surface_gaps": gaps,
    }
    print(json.dumps(payload, ensure_ascii=True, indent=2))
    return 0 if not gaps else 1


def _choices_help(param: ParamSpec) -> str:
    if not param.choices:
        return ""
    return " | ".join(param.choices)


def run_tui(repo_root: Path) -> int:
    try:
        from textual.app import App, ComposeResult
        from textual.binding import Binding
        from textual.containers import Container, Horizontal, Vertical
        from textual.screen import ModalScreen, Screen
        from textual.widgets import Button, DataTable, Footer, Header, Input, Label, Static
    except Exception as exc:  # pragma: no cover - runtime dependency check
        print(
            "ERROR: Python Textual is not installed. "
            "Run scripts/device/mapper-toolkit.sh bootstrap",
            file=sys.stderr,
        )
        print(f"import_error={exc}", file=sys.stderr)
        return 2

    class HelpModal(ModalScreen[None]):
        BINDINGS = [Binding("escape", "dismiss", "Close")]

        def compose(self) -> ComposeResult:
            help_text = (
                "Mapper-Toolkit TUI Hotkeys\n\n"
                "F1  Help\n"
                "F2  Toggle Matrix\n"
                "F5  Refresh\n"
                "/   Focus Search\n"
                "Tab Shift+Tab  Focus next/prev\n"
                "Arrow keys      Navigate tables\n"
                "Enter           Select row / run action\n"
                "Space           Triage freeze/unfreeze (triage screen)\n"
                "B/O/E           Triage bookmark / open chain / export incident\n"
                "Esc             Close dialog\n"
            )
            with Container(id="modal"):
                yield Label("Help")
                yield Static(help_text, id="modal-text")
                yield Button("Close", id="close-help", variant="primary")

        def on_button_pressed(self, event: Button.Pressed) -> None:
            if event.button.id == "close-help":
                self.dismiss(None)

    class ConfirmModal(ModalScreen[bool]):
        BINDINGS = [Binding("escape", "dismiss_false", "Cancel")]

        def __init__(self, title: str, body: str) -> None:
            super().__init__()
            self._title = title
            self._body = body

        def compose(self) -> ComposeResult:
            with Container(id="modal"):
                yield Label(self._title)
                yield Static(self._body, id="modal-text")
                with Horizontal(id="modal-buttons"):
                    yield Button("Cancel", id="cancel", variant="default")
                    yield Button("Confirm", id="confirm", variant="error")

        def action_dismiss_false(self) -> None:
            self.dismiss(False)

        def on_button_pressed(self, event: Button.Pressed) -> None:
            self.dismiss(event.button.id == "confirm")

    class ParamModal(ModalScreen[Optional[Dict[str, str]]]):
        BINDINGS = [Binding("escape", "cancel", "Cancel")]

        def __init__(self, command: CommandSpec) -> None:
            super().__init__()
            self.command = command
            self.input_ids: List[str] = []

        def compose(self) -> ComposeResult:
            with Container(id="modal"):
                yield Label(f"{self.command.title} Parameters")
                for param in self.command.params:
                    label = param.label
                    if not param.required:
                        label += " (optional)"

                    default_value = param.default
                    if not default_value and param.type == "choice" and param.choices:
                        default_value = param.choices[0]

                    placeholder_parts = []
                    if param.example:
                        placeholder_parts.append(f"e.g. {param.example}")
                    choices = _choices_help(param)
                    if choices:
                        placeholder_parts.append(f"choices: {choices}")
                    if not param.takes_value:
                        placeholder_parts.append("leave as 'yes' to include flag")

                    input_id = f"param-{param.key}"
                    self.input_ids.append(input_id)

                    yield Label(label, classes="param-label")
                    yield Input(
                        value=default_value,
                        placeholder="; ".join(placeholder_parts),
                        id=input_id,
                    )

                with Horizontal(id="modal-buttons"):
                    yield Button("Cancel", id="cancel", variant="default")
                    yield Button("Run", id="run", variant="success")

        def on_mount(self) -> None:
            if self.input_ids:
                self.query_one(f"#{self.input_ids[0]}", Input).focus()
            else:
                self.query_one("#run", Button).focus()

        def action_cancel(self) -> None:
            self.dismiss(None)

        def on_button_pressed(self, event: Button.Pressed) -> None:
            if event.button.id == "cancel":
                self.dismiss(None)
                return

            values: Dict[str, str] = {}
            for param in self.command.params:
                values[param.key] = self.query_one(f"#param-{param.key}", Input).value
            self.dismiss(values)

    class MatrixModal(ModalScreen[None]):
        BINDINGS = [Binding("escape", "dismiss", "Close")]

        def __init__(self, catalog: Catalog, screen_id: str, screen_title: str) -> None:
            super().__init__()
            self.catalog = catalog
            self.screen_id = screen_id
            self.screen_title = screen_title

        def compose(self) -> ComposeResult:
            with Container(id="modal-large"):
                yield Label(f"Command Matrix: {self.screen_title}")
                yield DataTable(id="matrix-modal-table")
                yield Button("Close", id="close-matrix", variant="primary")

        def on_mount(self) -> None:
            table = self.query_one("#matrix-modal-table", DataTable)
            table.cursor_type = "row"
            table.add_columns("Command", "Description", "Safety", "Params", "Example")
            for row in matrix_rows_for_screen(self.catalog, self.screen_id):
                table.add_row(row["title"], row["description"], row["safety"], row["params"], row["example"])

        def on_button_pressed(self, event: Button.Pressed) -> None:
            if event.button.id == "close-matrix":
                self.dismiss(None)

    class ToolkitSectionScreen(Screen[None]):
        BINDINGS = [
            Binding("f1", "show_help", "Help"),
            Binding("f2", "toggle_matrix", "Matrix"),
            Binding("f5", "refresh_current", "Refresh"),
            Binding("/", "focus_search", "Search", show=False),
            Binding("ctrl+r", "run_selected", "Run", show=False),
            Binding("space", "toggle_freeze", "Freeze", show=False),
            Binding("b", "bookmark_selected_alert", "Bookmark", show=False),
            Binding("o", "open_correlated_chain", "Chain", show=False),
            Binding("e", "export_incident", "Incident", show=False),
        ]

        MATRIX_SIDE_MIN_WIDTH = 145

        def __init__(self, section_id: str) -> None:
            super().__init__()
            self.section_id = section_id
            self.search_query = ""
            self.visible_actions: List[CommandSpec] = []
            self.last_output = ""
            self.triage_frozen = False
            self.triage_alert_rows: List[Dict[str, str]] = []

        @property
        def toolkit_app(self) -> "MapperToolkitApp":
            return cast("MapperToolkitApp", self.app)

        def compose(self) -> ComposeResult:
            yield Header(show_clock=True)
            with Horizontal(id="layout"):
                with Vertical(id="left"):
                    yield Label("Screens")
                    yield DataTable(id="screens")
                    yield Button("Go To Screen", id="goto-screen", variant="primary")
                    yield Button("Run Selected", id="run-selected", variant="success")
                    yield Button("Refresh", id="refresh", variant="default")
                with Vertical(id="center"):
                    yield Label(self.toolkit_app.screen_titles.get(self.section_id, self.section_id), id="section-title")
                    yield Input(placeholder="Search actions (press /)", id="search")
                    yield Label("Actions")
                    yield DataTable(id="actions")
                    if self.section_id == "triage":
                        yield Label("Alert Hotlist")
                        yield DataTable(id="triage-alerts")
                    yield Label("Output")
                    yield Static("Ready.", id="output")
                    status_line = "F1 Help | F2 Matrix | F5 Refresh | Ctrl+R Run"
                    if self.section_id == "triage":
                        status_line += " | Space Freeze | B Bookmark | O Chain | E Incident"
                    yield Static(status_line, id="status-line")
                with Vertical(id="matrix-pane"):
                    yield Label("Command Matrix")
                    yield DataTable(id="matrix")
            yield Footer()

        def on_mount(self) -> None:
            self._setup_tables()
            self._refresh_screen_table()
            self._refresh_actions_table(keep_cursor=False)
            self._refresh_matrix_table()
            self._reload_output()
            self._sync_matrix_pane()
            self._refresh_triage_hotlist()
            self._tick_triage()

            if self.section_id == "triage":
                self.set_interval(2.0, self._tick_triage)

            if self.section_id == self.toolkit_app.screen_ids[0]:
                if self.toolkit_app.gaps:
                    self.toolkit_app.append_output(
                        self.section_id,
                        "WARNING: catalog/cli drift detected. Run: main.py --check",
                    )
                else:
                    self.toolkit_app.append_output(
                        self.section_id,
                        "Mapper-Toolkit TUI ready. Navigate screens with arrows + Enter.",
                    )
                self._reload_output()

        def _setup_tables(self) -> None:
            screens = self.query_one("#screens", DataTable)
            actions = self.query_one("#actions", DataTable)
            matrix = self.query_one("#matrix", DataTable)

            for table in (screens, actions, matrix):
                table.cursor_type = "row"

            screens.add_columns("Screen")
            actions.add_columns("Action", "Description", "Params", "Safety", "Last")
            matrix.add_columns("Command", "Description", "Safety", "Params", "Example")
            if self.section_id == "triage":
                alerts = self.query_one("#triage-alerts", DataTable)
                alerts.cursor_type = "row"
                alerts.add_columns("Severity", "Rule", "Trace", "Action", "Title")

        def _refresh_screen_table(self) -> None:
            table = self.query_one("#screens", DataTable)
            table.clear()
            for screen_id in self.toolkit_app.screen_ids:
                table.add_row(self.toolkit_app.screen_titles.get(screen_id, screen_id))
            table.move_cursor(row=self.toolkit_app.screen_ids.index(self.section_id), column=0)

        def _refresh_actions_table(self, keep_cursor: bool = True) -> None:
            table = self.query_one("#actions", DataTable)
            previous = table.cursor_row if keep_cursor else 0

            self.visible_actions = list_actions(self.toolkit_app.catalog, self.section_id, self.search_query)
            table.clear()
            for command in self.visible_actions:
                status = self.toolkit_app.action_status.get(command.id, "-")
                param_names = ", ".join(param.label for param in command.params)
                table.add_row(command.title, command.description, param_names, command.safety, status)

            if self.visible_actions:
                row = max(0, min(previous, len(self.visible_actions) - 1))
                table.move_cursor(row=row, column=0)

        def _refresh_matrix_table(self) -> None:
            table = self.query_one("#matrix", DataTable)
            table.clear()
            for row in matrix_rows_for_screen(self.toolkit_app.catalog, self.section_id):
                table.add_row(row["title"], row["description"], row["safety"], row["params"], row["example"])

        def _selected_action(self) -> Optional[CommandSpec]:
            if not self.visible_actions:
                return None
            table = self.query_one("#actions", DataTable)
            row = table.cursor_row
            if row < 0 or row >= len(self.visible_actions):
                return None
            return self.visible_actions[row]

        def _reload_output(self) -> None:
            self.last_output = self.toolkit_app.screen_output.get(self.section_id, "Ready.")
            self.query_one("#output", Static).update(self.last_output)

        def _refresh_triage_hotlist(self) -> None:
            if self.section_id != "triage":
                return
            table = self.query_one("#triage-alerts", DataTable)
            alerts_path = self.toolkit_app.repo_root / "logs" / "device" / "mapper-toolkit" / "current" / "triage_alerts.jsonl"
            rows: List[Dict[str, str]] = []
            if alerts_path.exists():
                for line in alerts_path.read_text(encoding="utf-8", errors="replace").splitlines():
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        payload = json.loads(line)
                    except Exception:
                        continue
                    if not isinstance(payload, dict):
                        continue
                    rows.append(
                        {
                            "severity": str(payload.get("severity") or ""),
                            "rule_id": str(payload.get("rule_id") or ""),
                            "trace_id": str(payload.get("trace_id") or ""),
                            "action_id": str(payload.get("action_id") or ""),
                            "title": str(payload.get("title") or ""),
                            "event_id": str(payload.get("event_id") or ""),
                            "ts_utc": str(payload.get("ts_utc") or ""),
                        }
                    )
            rows.sort(
                key=lambda item: (
                    SEVERITY_ORDER.get(item.get("severity", "").lower(), 99),
                    item.get("ts_utc", ""),
                )
            )
            self.triage_alert_rows = rows[:200]
            previous = table.cursor_row
            table.clear()
            for row in self.triage_alert_rows:
                table.add_row(
                    row["severity"],
                    row["rule_id"],
                    row["trace_id"],
                    row["action_id"],
                    row["title"],
                )
            if self.triage_alert_rows:
                clamped = max(0, min(previous, len(self.triage_alert_rows) - 1))
                table.move_cursor(row=clamped, column=0)

        def _selected_triage_alert(self) -> Optional[Dict[str, str]]:
            if self.section_id != "triage" or not self.triage_alert_rows:
                return None
            table = self.query_one("#triage-alerts", DataTable)
            row = table.cursor_row
            if row < 0 or row >= len(self.triage_alert_rows):
                return None
            return self.triage_alert_rows[row]

        def _tick_triage(self) -> None:
            if self.section_id != "triage":
                return
            self._refresh_triage_hotlist()
            if self.triage_frozen:
                return
            runtime_root = self.toolkit_app.repo_root / "logs" / "device" / "mapper-toolkit" / "current"
            events_path = runtime_root / "events" / "runtime_events.jsonl"
            alerts_path = runtime_root / "triage_alerts.jsonl"
            event_lines: List[str] = []
            alert_lines: List[str] = []
            if events_path.exists():
                event_lines = events_path.read_text(encoding="utf-8", errors="replace").splitlines()[-30:]
            if alerts_path.exists():
                alert_lines = alerts_path.read_text(encoding="utf-8", errors="replace").splitlines()[-20:]
            merged = ["[Live Stream] latest runtime events + triage alerts", ""] + event_lines + ["", "[Alerts]"] + alert_lines
            self.toolkit_app.screen_output[self.section_id] = "\n".join(line for line in merged if line is not None)[-12000:]
            self._reload_output()

        def _sync_matrix_pane(self) -> None:
            pane = self.query_one("#matrix-pane", Vertical)
            if use_side_matrix(self.size.width, self.toolkit_app.matrix_visible, self.MATRIX_SIDE_MIN_WIDTH):
                pane.remove_class("hidden")
            else:
                pane.add_class("hidden")

        async def _run_selected_action(self) -> None:
            command = self._selected_action()
            if command is None:
                self.toolkit_app.append_output(self.section_id, "No action selected.")
                self._reload_output()
                return

            values: Dict[str, str] = {}
            if command.params:
                values = await self.app.push_screen_wait(ParamModal(command))
                if values is None:
                    self.toolkit_app.append_output(self.section_id, f"Canceled: {command.title}")
                    self._reload_output()
                    return

            if command.safety == "destructive":
                confirmed = await self.app.push_screen_wait(
                    ConfirmModal(
                        "Destructive command",
                        f"Execute '{command.title}'? This may delete runtime artifacts.",
                    )
                )
                if not confirmed:
                    self.toolkit_app.append_output(
                        self.section_id,
                        f"Canceled destructive action: {command.title}",
                    )
                    self._reload_output()
                    return

            await self.toolkit_app.execute_command(self.section_id, command, values)
            self._refresh_actions_table()
            self._reload_output()

        def _selected_screen_id(self) -> Optional[str]:
            table = self.query_one("#screens", DataTable)
            row = table.cursor_row
            if row < 0 or row >= len(self.toolkit_app.screen_ids):
                return None
            return self.toolkit_app.screen_ids[row]

        def _go_to_selected_screen(self) -> None:
            target = self._selected_screen_id()
            if target:
                self.toolkit_app.open_section(target)

        async def on_button_pressed(self, event: Button.Pressed) -> None:
            if event.button.id == "run-selected":
                await self._run_selected_action()
            elif event.button.id == "refresh":
                self.action_refresh_current()
            elif event.button.id == "goto-screen":
                self._go_to_selected_screen()

        async def on_data_table_row_selected(self, event: DataTable.RowSelected) -> None:
            if event.data_table.id == "screens":
                self._go_to_selected_screen()
            elif event.data_table.id == "actions":
                await self._run_selected_action()
            elif event.data_table.id == "triage-alerts":
                alert = self._selected_triage_alert()
                if alert:
                    self.toolkit_app.append_output(
                        self.section_id,
                        (
                            "Selected alert:\n"
                            f"severity={alert['severity']} rule={alert['rule_id']} "
                            f"trace_id={alert['trace_id']} action_id={alert['action_id']}"
                        ),
                    )
                    self._reload_output()

        def on_input_changed(self, event: Input.Changed) -> None:
            if event.input.id == "search":
                self.search_query = event.value
                self._refresh_actions_table()

        def on_resize(self) -> None:
            self._sync_matrix_pane()

        async def action_show_help(self) -> None:
            self.app.push_screen(HelpModal())

        async def action_toggle_matrix(self) -> None:
            if self.size.width >= self.MATRIX_SIDE_MIN_WIDTH:
                self.toolkit_app.matrix_visible = not self.toolkit_app.matrix_visible
                self._sync_matrix_pane()
            else:
                self.app.push_screen(
                    MatrixModal(
                        self.toolkit_app.catalog,
                        self.section_id,
                        self.toolkit_app.screen_titles[self.section_id],
                    )
                )

        def action_focus_search(self) -> None:
            self.query_one("#search", Input).focus()

        def action_refresh_current(self) -> None:
            self._refresh_screen_table()
            self._refresh_actions_table()
            self._refresh_matrix_table()
            self.toolkit_app.append_output(self.section_id, "Refreshed current screen data.")
            self._reload_output()

        async def action_run_selected(self) -> None:
            await self._run_selected_action()

        def action_toggle_freeze(self) -> None:
            if self.section_id != "triage":
                return
            self.triage_frozen = not self.triage_frozen
            state = "ON" if self.triage_frozen else "OFF"
            self.toolkit_app.append_output(self.section_id, f"Triage freeze: {state}")
            self._reload_output()

        async def action_bookmark_selected_alert(self) -> None:
            if self.section_id != "triage":
                return
            alert = self._selected_triage_alert()
            if not alert:
                self.toolkit_app.append_output(self.section_id, "No alert selected for bookmark.")
                self._reload_output()
                return
            command = self.toolkit_app.command_by_id("triage.bookmark")
            if not command:
                return
            values = {
                "event_id": alert.get("event_id", ""),
                "trace_id": alert.get("trace_id", ""),
                "action_id": alert.get("action_id", ""),
                "note": f"from triage hotlist: {alert.get('rule_id', '')}",
            }
            await self.toolkit_app.execute_command(self.section_id, command, values)

        async def action_open_correlated_chain(self) -> None:
            if self.section_id != "triage":
                return
            alert = self._selected_triage_alert()
            if not alert:
                self.toolkit_app.append_output(self.section_id, "No alert selected to open chain.")
                self._reload_output()
                return
            command = self.toolkit_app.command_by_id("triage.focus")
            if not command:
                return
            values = {
                "preset": "",
                "trace_id": alert.get("trace_id", ""),
                "action_id": alert.get("action_id", ""),
                "screen_id": "",
                "domain": "",
                "path": "",
                "limit": "400",
            }
            await self.toolkit_app.execute_command(self.section_id, command, values)

        async def action_export_incident(self) -> None:
            if self.section_id != "triage":
                return
            alert = self._selected_triage_alert()
            command = self.toolkit_app.command_by_id("triage.incident_pack")
            if not command:
                return
            values = {
                "incident_id": "",
                "preset": "",
                "event_id": alert.get("event_id", "") if alert else "",
                "trace_id": alert.get("trace_id", "") if alert else "",
                "action_id": alert.get("action_id", "") if alert else "",
                "limit": "500",
            }
            await self.toolkit_app.execute_command(self.section_id, command, values)

    class MapperToolkitApp(App[None]):
        TITLE = "Mapper-Toolkit"
        SUB_TITLE = "Runtime Control TUI (Multi-Screen)"

        CSS = """
        #layout { height: 1fr; }
        #left { width: 26; border: round $accent; padding: 1; }
        #center { width: 1fr; border: round $panel; padding: 1; }
        #matrix-pane { width: 52; border: round $boost; padding: 1; }
        #matrix-pane.hidden { display: none; }
        #output { height: 12; border: round $panel-darken-1; padding: 1; overflow: auto; }
        #status-line { height: auto; color: $text-muted; }
        #section-title { text-style: bold; margin-bottom: 1; }
        #search { margin-bottom: 1; }
        DataTable { height: 1fr; }
        #modal, #modal-large {
          width: 84;
          max-width: 96%;
          height: auto;
          border: round $accent;
          background: $surface;
          padding: 1 2;
        }
        #modal-large { width: 140; max-width: 98%; height: 90%; }
        #modal-text { margin: 1 0; }
        #modal-buttons { height: auto; margin-top: 1; }
        .param-label { margin-top: 1; }
        """

        def __init__(self, root: Path) -> None:
            super().__init__()
            self.repo_root = root
            self.cli_path = self.repo_root / "scripts" / "device" / "mapper-toolkit.sh"
            self.catalog = load_catalog(self.repo_root)
            self.cli_surface = load_cli_surface(self.repo_root)
            self.gaps = cli_surface_gaps(self.catalog, self.cli_surface)

            self.screen_ids = list(all_screen_ids(self.catalog))
            self.screen_titles = all_screen_titles(self.catalog)
            self.matrix_visible = True
            self.action_status: Dict[str, str] = {}
            self.screen_output: Dict[str, str] = {}
            self.section_screens: Dict[str, ToolkitSectionScreen] = {}

        def on_mount(self) -> None:
            for screen_id in self.screen_ids:
                section_screen = ToolkitSectionScreen(screen_id)
                self.section_screens[screen_id] = section_screen
                self.install_screen(section_screen, name=screen_id)
            self.switch_screen(self.screen_ids[0])

        def open_section(self, section_id: str) -> None:
            if section_id not in self.section_screens:
                return
            self.switch_screen(section_id)

        def command_by_id(self, command_id: str) -> Optional[CommandSpec]:
            for command in self.catalog.commands:
                if command.id == command_id:
                    return command
            return None

        def append_output(self, section_id: str, text: str) -> None:
            existing = self.screen_output.get(section_id, "")
            merged = (existing + "\n\n" + text).strip() if existing else text.strip()
            if len(merged) > 12000:
                merged = merged[-12000:]
            self.screen_output[section_id] = merged

        def refresh_active_section(self) -> None:
            active_screen = self.screen
            if isinstance(active_screen, ToolkitSectionScreen):
                active_screen._refresh_actions_table()
                active_screen._reload_output()

        async def execute_command(self, section_id: str, command: CommandSpec, values: Dict[str, str]) -> None:
            try:
                argv = build_argv(self.cli_path, command, values)
            except Exception as exc:
                self.action_status[command.id] = "invalid params"
                self.append_output(section_id, f"Parameter error for {command.title}: {exc}")
                self.refresh_active_section()
                return

            self.action_status[command.id] = "running..."
            self.refresh_active_section()

            start = time.monotonic()
            completed = await asyncio.to_thread(
                subprocess.run,
                argv,
                cwd=str(self.repo_root),
                capture_output=True,
                text=True,
            )
            duration = time.monotonic() - start

            status = "ok" if completed.returncode == 0 else "error"
            self.action_status[command.id] = f"{status} ({duration:.1f}s)"

            body = (
                f"Action: {command.title}\n"
                f"Exit code: {completed.returncode}\n"
                f"Duration: {duration:.2f}s\n"
                f"Artifacts hint: logs/device/mapper-toolkit/current\n\n"
                f"STDOUT:\n{completed.stdout.strip() or '(empty)'}\n\n"
                f"STDERR:\n{completed.stderr.strip() or '(empty)'}"
            )
            self.screen_output[section_id] = body
            self.refresh_active_section()

    app = MapperToolkitApp(repo_root)
    app.run()
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Mapper-Toolkit Textual TUI")
    parser.add_argument("--check", action="store_true", help="Validate TUI catalog contracts")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    repo_root = repo_root_from_file(Path(__file__))

    if args.check:
        return run_contract_check(repo_root)

    return run_tui(repo_root)


if __name__ == "__main__":
    raise SystemExit(main())
