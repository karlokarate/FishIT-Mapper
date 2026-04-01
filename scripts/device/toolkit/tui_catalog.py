#!/usr/bin/env python3
"""Contract-backed command catalog for Mapper-Toolkit TUI."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Tuple


@dataclass(frozen=True)
class ScreenSpec:
    id: str
    title: str


@dataclass(frozen=True)
class ParamSpec:
    key: str
    label: str
    type: str
    required: bool
    flag: str
    positional: bool
    takes_value: bool
    default: str
    choices: Tuple[str, ...]
    example: str


@dataclass(frozen=True)
class CommandSpec:
    id: str
    screen: str
    title: str
    description: str
    safety: str
    argv: Tuple[str, ...]
    params: Tuple[ParamSpec, ...]


@dataclass(frozen=True)
class Catalog:
    version: int
    catalog_id: str
    screens: Tuple[ScreenSpec, ...]
    commands: Tuple[CommandSpec, ...]


def repo_root_from_file(file_path: Path) -> Path:
    return file_path.resolve().parents[3]


def load_json(path: Path) -> Dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _normalize_param(raw: Dict[str, Any]) -> ParamSpec:
    return ParamSpec(
        key=str(raw.get("key") or ""),
        label=str(raw.get("label") or raw.get("key") or ""),
        type=str(raw.get("type") or "string"),
        required=bool(raw.get("required", False)),
        flag=str(raw.get("flag") or ""),
        positional=bool(raw.get("positional", False)),
        takes_value=bool(raw.get("takes_value", True)),
        default=str(raw.get("default") or ""),
        choices=tuple(str(item) for item in raw.get("choices", [])),
        example=str(raw.get("example") or ""),
    )


def _normalize_command(raw: Dict[str, Any]) -> CommandSpec:
    params = tuple(_normalize_param(item) for item in raw.get("params", []))
    return CommandSpec(
        id=str(raw.get("id") or ""),
        screen=str(raw.get("screen") or ""),
        title=str(raw.get("title") or ""),
        description=str(raw.get("description") or ""),
        safety=str(raw.get("safety") or "normal"),
        argv=tuple(str(part) for part in raw.get("argv", [])),
        params=params,
    )


def load_catalog(repo_root: Path) -> Catalog:
    path = repo_root / "contracts" / "mapper_toolkit_tui_catalog.json"
    payload = load_json(path)

    screens = tuple(
        ScreenSpec(id=str(item.get("id") or ""), title=str(item.get("title") or ""))
        for item in payload.get("screens", [])
    )
    commands = tuple(_normalize_command(item) for item in payload.get("commands", []))

    catalog = Catalog(
        version=int(payload.get("version") or 0),
        catalog_id=str(payload.get("catalog_id") or ""),
        screens=screens,
        commands=commands,
    )

    validate_catalog(catalog)
    return catalog


def validate_catalog(catalog: Catalog) -> None:
    if catalog.version != 1:
        raise ValueError("catalog.version must be 1")
    if not catalog.catalog_id:
        raise ValueError("catalog_id is required")
    if not catalog.screens:
        raise ValueError("catalog.screens must not be empty")
    if not catalog.commands:
        raise ValueError("catalog.commands must not be empty")

    screen_ids = [screen.id for screen in catalog.screens]
    if len(set(screen_ids)) != len(screen_ids):
        raise ValueError("catalog.screens contains duplicate ids")

    command_ids = [cmd.id for cmd in catalog.commands]
    if len(set(command_ids)) != len(command_ids):
        raise ValueError("catalog.commands contains duplicate ids")

    for cmd in catalog.commands:
        if not cmd.id:
            raise ValueError("command.id is required")
        if cmd.screen not in screen_ids:
            raise ValueError(f"command {cmd.id} has unknown screen '{cmd.screen}'")
        if not cmd.title:
            raise ValueError(f"command {cmd.id} title is required")
        if cmd.safety not in {"normal", "destructive"}:
            raise ValueError(f"command {cmd.id} safety must be normal|destructive")
        if not cmd.argv:
            raise ValueError(f"command {cmd.id} must define argv")

        for param in cmd.params:
            if not param.key:
                raise ValueError(f"command {cmd.id} has param without key")
            if param.type not in {"string", "int", "choice"}:
                raise ValueError(
                    f"command {cmd.id} param {param.key} type must be string|int|choice"
                )
            if not param.positional and not param.flag:
                raise ValueError(
                    f"command {cmd.id} param {param.key} needs flag or positional=true"
                )
            if param.type == "choice" and not param.choices:
                raise ValueError(
                    f"command {cmd.id} param {param.key} choice type needs choices"
                )


def catalog_by_screen(catalog: Catalog) -> Dict[str, List[CommandSpec]]:
    grouped: Dict[str, List[CommandSpec]] = {screen.id: [] for screen in catalog.screens}
    for cmd in catalog.commands:
        grouped.setdefault(cmd.screen, []).append(cmd)
    return grouped


def load_cli_surface(repo_root: Path) -> Dict[str, List[str]]:
    path = repo_root / "contracts" / "runtime_toolkit_v2.json"
    payload = load_json(path)
    raw = payload.get("cli_surface", {})
    result: Dict[str, List[str]] = {}
    for group, actions in raw.items():
        result[str(group)] = [str(action) for action in actions]
    return result


def _catalog_cli_action_key(argv: Tuple[str, ...]) -> Tuple[str, str]:
    if not argv:
        return ("", "")
    if len(argv) == 1:
        return ("root", argv[0])

    group = argv[0]
    action = argv[1]

    if group == "capture" and action == "mitm":
        suffix = argv[2] if len(argv) > 2 else ""
        return (group, f"mitm_{suffix}" if suffix else "mitm")

    return (group, action)


def catalog_cli_surface(catalog: Catalog) -> Dict[str, List[str]]:
    mapped: Dict[str, List[str]] = {}
    for cmd in catalog.commands:
        group, action = _catalog_cli_action_key(cmd.argv)
        if not group or not action:
            continue
        mapped.setdefault(group, [])
        if action not in mapped[group]:
            mapped[group].append(action)
    return mapped


def cli_surface_gaps(catalog: Catalog, cli_surface: Dict[str, List[str]]) -> Dict[str, List[str]]:
    mapped = catalog_cli_surface(catalog)
    gaps: Dict[str, List[str]] = {}
    for group, actions in cli_surface.items():
        missing = [action for action in actions if action not in mapped.get(group, [])]
        if missing:
            gaps[group] = missing
    return gaps


def matrix_rows_for_screen(catalog: Catalog, screen_id: str) -> List[Dict[str, str]]:
    rows: List[Dict[str, str]] = []
    for cmd in catalog.commands:
        if cmd.screen != screen_id:
            continue

        params = []
        for param in cmd.params:
            name = param.flag if param.flag else param.key
            params.append(name)

        example_parts = list(cmd.argv)
        for param in cmd.params:
            if param.positional:
                value = param.default or param.example or f"<{param.key}>"
                example_parts.append(value)
            elif not param.takes_value:
                example_parts.append(param.flag)
            else:
                value = param.default or param.example or f"<{param.key}>"
                example_parts.extend([param.flag, value])

        rows.append(
            {
                "id": cmd.id,
                "title": cmd.title,
                "description": cmd.description,
                "safety": cmd.safety,
                "params": ", ".join(params),
                "example": " ".join(part for part in example_parts if part),
            }
        )
    return rows


def build_argv(base_cli: Path, command: CommandSpec, values: Dict[str, str]) -> List[str]:
    argv = [str(base_cli)] + list(command.argv)

    for param in command.params:
        raw_value = values.get(param.key, "")
        value = str(raw_value).strip()

        if not value:
            value = param.default

        if param.required and not value:
            raise ValueError(f"Missing required parameter: {param.label}")

        if not value:
            continue

        if param.type == "int":
            try:
                int(value)
            except ValueError as exc:
                raise ValueError(f"{param.label} must be an integer") from exc

        if param.type == "choice" and param.choices and value not in param.choices:
            choices = ", ".join(param.choices)
            raise ValueError(f"{param.label} must be one of: {choices}")

        if param.positional:
            argv.append(value)
        elif not param.takes_value:
            argv.append(param.flag)
        else:
            argv.extend([param.flag, value])

    return argv


def all_screen_ids(catalog: Catalog) -> Tuple[str, ...]:
    return tuple(screen.id for screen in catalog.screens)


def all_screen_titles(catalog: Catalog) -> Dict[str, str]:
    return {screen.id: screen.title for screen in catalog.screens}


def list_actions(catalog: Catalog, screen_id: str, query: str = "") -> List[CommandSpec]:
    normalized = query.strip().lower()
    items = [cmd for cmd in catalog.commands if cmd.screen == screen_id]
    if not normalized:
        return items

    out: List[CommandSpec] = []
    for cmd in items:
        hay = f"{cmd.title} {cmd.description} {cmd.id}".lower()
        if normalized in hay:
            out.append(cmd)
    return out
