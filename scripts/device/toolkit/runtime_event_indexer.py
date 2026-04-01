#!/usr/bin/env python3
"""Incremental runtime log/perf indexer for FishIT Runtime Toolkit."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import pathlib
import re
from typing import Dict, Iterable, List, Tuple

STATE_NAME = "indexer_state.json"
EVENTS_NAME = "runtime_events.jsonl"
SYNC_SUMMARY_NAME = "runtime_sync_summary.json"
PERF_SUMMARY_NAME = "runtime_perf_summary.json"
HEALTH_SUMMARY_NAME = "runtime_health.json"

THREADTIME_RE = re.compile(
    r"^(?P<date>\d\d-\d\d)\s+(?P<time>\d\d:\d\d:\d\d\.\d+)\s+"
    r"(?P<pid>\d+)\s+(?P<tid>\d+)\s+(?P<level>[VDIWEAF])\s+(?P<tag>[^:]+):\s(?P<msg>.*)$"
)

TRANSITION_RE = re.compile(r"\b(ENQUEUED|RUNNING|STABILIZING|SUCCESS|FAILED|READY|CANCELLED)\b")


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def ensure_dirs(runtime_dir: pathlib.Path) -> Dict[str, pathlib.Path]:
    events_dir = runtime_dir / "events"
    rollups_dir = runtime_dir / "rollups"
    perf_dir = runtime_dir / "perf"
    guard_dir = runtime_dir / "guard"
    events_dir.mkdir(parents=True, exist_ok=True)
    rollups_dir.mkdir(parents=True, exist_ok=True)
    return {
        "events": events_dir,
        "rollups": rollups_dir,
        "perf": perf_dir,
        "guard": guard_dir,
    }


def load_state(state_path: pathlib.Path) -> Dict[str, object]:
    if not state_path.exists():
        return {"files": {}, "eventCounts": {}, "severityCounts": {}, "lastTransitions": []}
    try:
        return json.loads(state_path.read_text(encoding="utf-8"))
    except Exception:
        return {"files": {}, "eventCounts": {}, "severityCounts": {}, "lastTransitions": []}


def save_json(path: pathlib.Path, payload: Dict[str, object]) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")


def log_files(logcat_dir: pathlib.Path) -> List[pathlib.Path]:
    chunks = sorted((logcat_dir / "chunks").glob("logcat_*.txt"))
    current = logcat_dir / "current.log"
    if current.exists() and current.is_file() and current not in chunks:
        chunks.append(current)
    return chunks


def normalize_message(msg: str) -> str:
    return " ".join(msg.strip().split())


def categorize(msg: str, tag: str) -> str:
    text = f"{tag} {msg}".lower()
    if (
        "tg_join_" in text
        or "tg_scope_binding_" in text
        or "tg_postsync_" in text
        or "join_targeted_chat" in text
        or "joinchat" in text
    ):
        return "join"
    if "sync" in text or "workmanager" in text or "catalogsync" in text:
        return "sync"
    if "ui_jank_frame" in text or "jank" in text or "framestats" in text:
        return "jank"
    if "xtream" in text:
        return "xtream"
    if "telegram" in text or "telethon" in text:
        return "telegram"
    if "error" in text or "exception" in text or "failed" in text:
        return "error"
    return "other"


def parse_line(line: str) -> Dict[str, str] | None:
    m = THREADTIME_RE.match(line)
    if not m:
        return None
    payload = m.groupdict()
    payload["msg"] = normalize_message(payload["msg"])
    return payload


def update_counter(bucket: Dict[str, int], key: str) -> None:
    bucket[key] = int(bucket.get(key, 0)) + 1


def collect_new_events(
    files: Iterable[pathlib.Path],
    state: Dict[str, object],
    events_path: pathlib.Path,
) -> Tuple[int, Dict[str, int], Dict[str, int], List[Dict[str, str]]]:
    file_state = state.setdefault("files", {})
    event_counts: Dict[str, int] = dict(state.get("eventCounts") or {})
    severity_counts: Dict[str, int] = dict(state.get("severityCounts") or {})
    transitions: List[Dict[str, str]] = list(state.get("lastTransitions") or [])

    new_events = 0
    with events_path.open("a", encoding="utf-8") as out:
        for path in files:
            key = str(path)
            offset = int((file_state.get(key) or {}).get("offset", 0))
            size = path.stat().st_size
            if size < offset:
                offset = 0

            with path.open("r", encoding="utf-8", errors="replace") as handle:
                handle.seek(offset)
                while True:
                    line = handle.readline()
                    if not line:
                        break
                    parsed = parse_line(line.rstrip("\n"))
                    if parsed is None:
                        continue

                    category = categorize(parsed["msg"], parsed["tag"])
                    level = parsed["level"]
                    update_counter(event_counts, category)
                    update_counter(severity_counts, level)

                    transition_match = TRANSITION_RE.search(parsed["msg"])
                    transition = transition_match.group(1) if transition_match else ""
                    event = {
                        "indexedAtUtc": utc_now(),
                        "category": category,
                        "level": level,
                        "tag": parsed["tag"].strip(),
                        "message": parsed["msg"],
                        "threadtime": f"{parsed['date']} {parsed['time']}",
                    }
                    if transition:
                        event["transition"] = transition
                        transitions.append(
                            {
                                "indexedAtUtc": event["indexedAtUtc"],
                                "transition": transition,
                                "message": parsed["msg"],
                                "tag": parsed["tag"].strip(),
                            }
                        )
                    out.write(json.dumps(event, ensure_ascii=True) + "\n")
                    new_events += 1

                new_offset = handle.tell()
                file_state[key] = {
                    "offset": new_offset,
                    "size": size,
                    "updatedAtUtc": utc_now(),
                }

    if len(transitions) > 200:
        transitions = transitions[-200:]

    state["eventCounts"] = event_counts
    state["severityCounts"] = severity_counts
    state["lastTransitions"] = transitions
    return new_events, event_counts, severity_counts, transitions


def perf_summary(perf_dir: pathlib.Path) -> Dict[str, object]:
    samples = sorted(perf_dir.glob("*_meta.json"))
    mem = sorted(perf_dir.glob("*_meminfo.txt"))
    gfx = sorted(perf_dir.glob("*_gfxinfo.txt"))
    top = sorted(perf_dir.glob("*_top.txt"))

    latest = samples[-1] if samples else None
    latest_payload = None
    if latest:
        try:
            latest_payload = json.loads(latest.read_text(encoding="utf-8"))
        except Exception:
            latest_payload = None

    return {
        "schemaVersion": "1.0",
        "generatedAtUtc": utc_now(),
        "sampleCounts": {
            "meta": len(samples),
            "meminfo": len(mem),
            "gfxinfo": len(gfx),
            "top": len(top),
        },
        "latestSample": latest_payload,
    }


def load_guard_heartbeat(guard_dir: pathlib.Path) -> Dict[str, object] | None:
    heartbeat = guard_dir / "heartbeat.json"
    if not heartbeat.exists():
        return None
    try:
        return json.loads(heartbeat.read_text(encoding="utf-8"))
    except Exception:
        return None


def runtime_health(
    state: Dict[str, object],
    adb_host: str,
    adb_port: str,
    app_id: str,
    guard_heartbeat: Dict[str, object] | None,
) -> Dict[str, object]:
    event_counts = dict(state.get("eventCounts") or {})
    severity_counts = dict(state.get("severityCounts") or {})
    transitions = list(state.get("lastTransitions") or [])
    return {
        "schemaVersion": "1.0",
        "generatedAtUtc": utc_now(),
        "runtime": {
            "adbHost": adb_host,
            "adbPort": adb_port,
            "appId": app_id,
        },
        "eventCounts": event_counts,
        "severityCounts": severity_counts,
        "lastTransitions": transitions[-25:],
        "guardHeartbeat": guard_heartbeat,
    }


def sync_summary(
    state: Dict[str, object],
    new_events: int,
) -> Dict[str, object]:
    event_counts = dict(state.get("eventCounts") or {})
    severity_counts = dict(state.get("severityCounts") or {})
    transitions = list(state.get("lastTransitions") or [])
    return {
        "schemaVersion": "1.0",
        "generatedAtUtc": utc_now(),
        "newEventsIndexed": new_events,
        "countsByCategory": event_counts,
        "countsBySeverity": severity_counts,
        "recentTransitions": transitions[-40:],
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="FishIT runtime incremental event indexer")
    parser.add_argument("--runtime-dir", required=True)
    parser.add_argument("--app-id", required=True)
    parser.add_argument("--adb-host", default="127.0.0.1")
    parser.add_argument("--adb-port", default="5037")
    parser.add_argument("--device", default="")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    runtime_dir = pathlib.Path(args.runtime_dir)
    paths = ensure_dirs(runtime_dir)

    state_path = paths["events"] / STATE_NAME
    events_path = paths["events"] / EVENTS_NAME

    state = load_state(state_path)
    files = log_files(runtime_dir / "logcat")

    new_events, event_counts, severity_counts, transitions = collect_new_events(files, state, events_path)

    state["lastIndexedAtUtc"] = utc_now()
    state["indexedFiles"] = [str(p) for p in files]
    state["eventCounts"] = event_counts
    state["severityCounts"] = severity_counts
    state["lastTransitions"] = transitions[-200:]
    save_json(state_path, state)

    sync_payload = sync_summary(state, new_events)
    perf_payload = perf_summary(paths["perf"])
    health_payload = runtime_health(
        state=state,
        adb_host=args.adb_host,
        adb_port=args.adb_port,
        app_id=args.app_id,
        guard_heartbeat=load_guard_heartbeat(paths["guard"]),
    )

    save_json(paths["rollups"] / SYNC_SUMMARY_NAME, sync_payload)
    save_json(paths["rollups"] / PERF_SUMMARY_NAME, perf_payload)
    save_json(paths["rollups"] / HEALTH_SUMMARY_NAME, health_payload)

    print(
        json.dumps(
            {
                "indexedAtUtc": sync_payload["generatedAtUtc"],
                "newEvents": new_events,
                "files": len(files),
                "eventCategories": event_counts,
            },
            ensure_ascii=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
