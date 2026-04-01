#!/usr/bin/env python3
"""Build static-first runtime command matrix for FishIT device control."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import pathlib
import re
from typing import Dict, List

ROUTES_RE = re.compile(r"const\s+val\s+(\w+)\s*=\s*\"([^\"]+)\"")
BENCHMARK_EXTRA_RE = re.compile(r"const\s+val\s+(EXTRA_BENCHMARK_[A-Z_]+)\s*=\s*\"([^\"]+)\"")
SETTER_RE = re.compile(r"fun\s+(set[A-Za-z0-9_]+)\s*\(([^)]*)\)")


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_routes(app_nav_host: pathlib.Path) -> List[Dict[str, str]]:
    text = app_nav_host.read_text(encoding="utf-8")
    rows = []
    for name, value in ROUTES_RE.findall(text):
        rows.append({"id": name, "route": value})
    return rows


def parse_benchmark_extras(main_activity: pathlib.Path) -> List[Dict[str, str]]:
    text = main_activity.read_text(encoding="utf-8")
    return [{"const": name, "key": value} for name, value in BENCHMARK_EXTRA_RE.findall(text)]


def parse_settings_actions(perf_flags_repo: pathlib.Path) -> List[Dict[str, str]]:
    text = perf_flags_repo.read_text(encoding="utf-8")
    actions: List[Dict[str, str]] = []
    for fun, args in SETTER_RE.findall(text):
        if not fun.startswith("set"):
            continue
        key = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", fun[3:]).lower()
        actions.append(
            {
                "key": key,
                "method": fun,
                "signature": f"{fun}({args.strip()})",
            }
        )
    return actions


def normalize(text: str) -> str:
    import unicodedata

    folded = unicodedata.normalize("NFKD", text)
    folded = "".join(ch for ch in folded if not unicodedata.combining(ch))
    folded = folded.lower().strip()
    folded = re.sub(r"\s+", " ", folded)
    return folded


def tile_selectors_from_fallback(fallback: pathlib.Path) -> List[Dict[str, object]]:
    if not fallback.exists():
        return []

    payload = json.loads(fallback.read_text(encoding="utf-8"))
    out: List[Dict[str, object]] = []
    for screen in payload.get("screens", []):
        screen_id = screen.get("screenId")
        controls = screen.get("controlClickables", [])
        probable_tiles = [
            row
            for row in controls
            if isinstance(row, dict)
            and int(row.get("centerY", 0) or 0) > 900
            and int(row.get("area", 0) or 0) >= 70_000
            and row.get("label")
        ]
        probable_tiles.sort(key=lambda x: (x.get("centerY", 0), x.get("centerX", 0)))

        by_row: Dict[int, List[Dict[str, object]]] = {}
        row_idx = 0
        last_y = None
        for tile in probable_tiles:
            y = int(tile.get("centerY", 0) or 0)
            if last_y is None or abs(y - last_y) > 180:
                row_idx += 1
                by_row[row_idx] = []
                last_y = y
            by_row[row_idx].append(tile)

        for r, tiles in by_row.items():
            for c, tile in enumerate(tiles, start=1):
                label = str(tile.get("label", "")).strip()
                out.append(
                    {
                        "screen": screen_id,
                        "row": r,
                        "col": c,
                        "label": label,
                        "labelNormalized": normalize(label),
                        "anchorId": tile.get("anchorId"),
                        "bounds": tile.get("bounds"),
                        "center": {
                            "x": tile.get("centerX"),
                            "y": tile.get("centerY"),
                        },
                    }
                )
    return out


def fallback_selectors(fallback: pathlib.Path) -> Dict[str, object]:
    if not fallback.exists():
        return {"available": False, "reason": "ui_anchor_matrix_missing"}

    payload = json.loads(fallback.read_text(encoding="utf-8"))
    quick = {}
    for screen in payload.get("screens", []):
        screen_id = screen.get("screenId")
        quick_targets = screen.get("quickTargets", {})
        quick[screen_id] = quick_targets

    return {
        "available": True,
        "source": str(fallback),
        "generatedAtUtc": payload.get("generatedAtUtc"),
        "quickTargets": quick,
    }


def direct_actions(routes: List[Dict[str, str]], extras: List[Dict[str, str]]) -> List[Dict[str, object]]:
    route_map = {r["id"]: r["route"] for r in routes}
    actions = [
        {
            "id": "open_home",
            "type": "startDestination",
            "route": route_map.get("HOME", "home"),
            "intentExtra": "benchmark.startDestination",
        },
        {
            "id": "open_library",
            "type": "startDestination",
            "route": route_map.get("LIBRARY", "library"),
            "intentExtra": "benchmark.startDestination",
        },
        {
            "id": "open_settings",
            "type": "startDestination",
            "route": route_map.get("SETTINGS", "settings"),
            "intentExtra": "benchmark.startDestination",
        },
        {
            "id": "open_detail",
            "type": "startDestinationPattern",
            "routePattern": route_map.get("DETAIL_PATTERN", "detail/{mediaId}/{sourceType}"),
            "intentExtra": "benchmark.startDestination",
            "args": ["workKey", "sourceType"],
        },
        {
            "id": "open_player_direct_url",
            "type": "extra",
            "intentExtra": "benchmark.directSourceUrl",
        },
    ]
    if extras:
        actions[0]["knownExtras"] = extras
    return actions


def input_strategies() -> List[Dict[str, object]]:
    return [
        {
            "priority": 1,
            "name": "direct_command_ingress",
            "description": "Debug broadcast command ingress for route/settings/inspect-work operations.",
        },
        {
            "priority": 2,
            "name": "start_destination_route",
            "description": "MainActivity benchmark.startDestination route launch.",
        },
        {
            "priority": 3,
            "name": "adb_keyboard_paste",
            "description": "ADB text input/paste for deterministic form values.",
        },
        {
            "priority": 4,
            "name": "ui_anchor_tap_fallback",
            "description": "Fallback to matrix/regex tap when direct route or setting command is unavailable.",
        },
    ]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build FishIT runtime command matrix")
    parser.add_argument("--repo-root", default=".")
    parser.add_argument(
        "--output",
        default="scripts/device/anchors/command_matrix.latest.json",
    )
    parser.add_argument(
        "--fallback-matrix",
        default="scripts/device/anchors/ui_anchor_matrix_latest.json",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    repo_root = pathlib.Path(args.repo_root).resolve()
    output = (repo_root / args.output).resolve()
    fallback_matrix = (repo_root / args.fallback_matrix).resolve()

    app_nav_host = repo_root / "app-v2/src/main/java/com/fishit/player/v2/navigation/AppNavHost.kt"
    main_activity = repo_root / "apps/mapper-app/app/src/main/java/info/plateaukao/einkbro/activity/BrowserActivity.kt"
    perf_flags_repo = repo_root / "feature/settings/src/main/java/com/fishit/player/feature/settings/PerformanceFlagsRepository.kt"

    routes = parse_routes(app_nav_host)
    extras = parse_benchmark_extras(main_activity)
    settings_actions = parse_settings_actions(perf_flags_repo)
    tiles = tile_selectors_from_fallback(fallback_matrix)

    payload = {
        "schemaVersion": "1.0",
        "generatedAtUtc": utc_now(),
        "sources": {
            "routes": str(app_nav_host.relative_to(repo_root)),
            "benchmarkExtras": str(main_activity.relative_to(repo_root)),
            "settingsRepo": str(perf_flags_repo.relative_to(repo_root)),
            "fallbackMatrix": str(fallback_matrix.relative_to(repo_root)) if fallback_matrix.exists() else None,
        },
        "routes": routes,
        "directActions": direct_actions(routes, extras),
        "settingsActions": settings_actions,
        "tileSelectors": tiles,
        "fallbackSelectors": fallback_selectors(fallback_matrix),
        "matcherModes": {
            "rawRegex": "Direct regex matching on visible labels.",
            "normalized": "Diacritics/emoji-insensitive folded matcher using NFKD + lowercase + whitespace collapse.",
        },
        "inputStrategies": input_strategies(),
    }

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    print(str(output))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
