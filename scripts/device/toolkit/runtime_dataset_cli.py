#!/usr/bin/env python3
"""Mapper-Toolkit dataset operations for trace/cookies/headers/responses/mapping/replay."""

from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import tarfile
import urllib.error
import urllib.request
from collections import Counter, defaultdict
from typing import Any, Dict, Iterable, List, Optional, Tuple
from urllib.parse import parse_qsl, urlparse


SEVERITY_RANK = {"critical": 0, "high": 1, "medium": 2, "low": 3}

PHASE_HOME = "home_probe"
PHASE_SEARCH = "search_probe"
PHASE_DETAIL = "detail_probe"
PHASE_PLAYBACK = "playback_probe"
PHASE_AUTH = "auth_probe"
PHASE_BACKGROUND = "background_noise"

VALID_PHASES = {
    PHASE_HOME,
    PHASE_SEARCH,
    PHASE_DETAIL,
    PHASE_PLAYBACK,
    PHASE_AUTH,
    PHASE_BACKGROUND,
}

SCORABLE_PHASES = {PHASE_HOME, PHASE_SEARCH, PHASE_DETAIL, PHASE_PLAYBACK, PHASE_AUTH}

PHASE_PRIORITY = {
    PHASE_PLAYBACK: 6,
    PHASE_DETAIL: 5,
    PHASE_SEARCH: 4,
    PHASE_HOME: 3,
    PHASE_AUTH: 2,
    PHASE_BACKGROUND: 1,
}

HOST_CLASS_TARGET_DOCUMENT = "target_document"
HOST_CLASS_TARGET_API = "target_api"
HOST_CLASS_TARGET_PLAYBACK = "target_playback"
HOST_CLASS_TARGET_ASSET = "target_asset"
HOST_CLASS_BROWSER_BOOTSTRAP = "browser_bootstrap"
HOST_CLASS_GOOGLE_NOISE = "google_noise"
HOST_CLASS_ANALYTICS_NOISE = "analytics_noise"
HOST_CLASS_BACKGROUND_NOISE = "background_noise"
HOST_CLASS_IGNORED = "ignored"

HOST_SCOPE_BUCKETS = [
    HOST_CLASS_TARGET_DOCUMENT,
    HOST_CLASS_TARGET_API,
    HOST_CLASS_TARGET_PLAYBACK,
    HOST_CLASS_TARGET_ASSET,
    HOST_CLASS_BROWSER_BOOTSTRAP,
    HOST_CLASS_GOOGLE_NOISE,
    HOST_CLASS_ANALYTICS_NOISE,
    HOST_CLASS_BACKGROUND_NOISE,
    HOST_CLASS_IGNORED,
]

CANONICAL_HOST_CLASSES = set(HOST_SCOPE_BUCKETS)

LEGACY_HOST_CLASS_ALIASES = {
    "target": HOST_CLASS_TARGET_DOCUMENT,
    "provider_bootstrap": HOST_CLASS_TARGET_DOCUMENT,
    "ad_noise": HOST_CLASS_ANALYTICS_NOISE,
    "external_noise": HOST_CLASS_BACKGROUND_NOISE,
    "unknown": HOST_CLASS_BACKGROUND_NOISE,
}

ACTIVE_REPLAY_ROLLUP_TIMEOUT_MS = 2_000

NON_SIGNAL_HOST_CLASSES = {
    HOST_CLASS_TARGET_ASSET,
    HOST_CLASS_BROWSER_BOOTSTRAP,
    HOST_CLASS_GOOGLE_NOISE,
    HOST_CLASS_ANALYTICS_NOISE,
    HOST_CLASS_BACKGROUND_NOISE,
    HOST_CLASS_IGNORED,
}

EXTRACTION_ELIGIBLE_HOST_CLASSES = {
    HOST_CLASS_TARGET_DOCUMENT,
    HOST_CLASS_TARGET_API,
    HOST_CLASS_TARGET_PLAYBACK,
}

KNOWN_TARGET_PLAYBACK_HOST_SUFFIXES: Dict[str, Tuple[str, ...]] = {
    "zdf.de": (
        "akamaihd.net",
        "akamaized.net",
        "zdf.de",
        "zdf-cdn.de",
    ),
}

PROVENANCE_TARGET_NAMES = {
    "zdf-app-id": ("zdf-app-id", "x-zdf-app-id"),
    "api-auth": ("api-auth", "x-api-auth", "authorization"),
    "userSegment": ("usersegment", "x-usersegment"),
    "abGroup": ("abgroup", "x-ab-group"),
    "cookies": ("cookie",),
    "referer": ("referer",),
    "origin": ("origin",),
}

BODY_ACTION_STORE_FULL = "STORE_FULL"
BODY_ACTION_STORE_FULL_REQUIRED = "STORE_FULL_REQUIRED"
BODY_ACTION_STORE_TRUNCATED = "STORE_TRUNCATED"
BODY_ACTION_STORE_METADATA_ONLY = "STORE_METADATA_ONLY"
BODY_ACTION_SKIP_BODY = "SKIP_BODY"

BODY_CAPTURE_POLICY_FULL_CANDIDATE = "full_candidate"
BODY_CAPTURE_POLICY_FULL_CANDIDATE_REQUIRED = "full_candidate_required"
BODY_CAPTURE_POLICY_TRUNCATED_CANDIDATE = "truncated_candidate"
BODY_CAPTURE_POLICY_METADATA_ONLY = "metadata_only"
BODY_CAPTURE_POLICY_SKIPPED_MEDIA_SEGMENT = "skipped_media_segment"
BODY_CAPTURE_POLICY_SKIP_BODY = "skip_body"

CANDIDATE_RELEVANCE_REQUIRED = "required_candidate"
CANDIDATE_RELEVANCE_SIGNAL = "signal_candidate"
CANDIDATE_RELEVANCE_NON_CANDIDATE = "non_candidate"

TRUNCATION_REASON_BODY_SIZE_LIMIT = "body_size_limit"
TRUNCATION_REASON_STREAM_POLICY = "stream_capture_policy"
TRUNCATION_REASON_MANUAL_CAP = "manual_cap"

FOUR_MB_BYTES = 4 * 1024 * 1024
SIXTEEN_MB_BYTES = 16 * 1024 * 1024
LARGE_HTML_DEDUPE_THRESHOLD_BYTES = 512 * 1024


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def default_runtime_dir() -> pathlib.Path:
    return pathlib.Path(__file__).resolve().parents[3] / "logs/device/mapper-toolkit/current"


def repo_root() -> pathlib.Path:
    return pathlib.Path(__file__).resolve().parents[3]


def load_contract_json(path: pathlib.Path) -> Dict[str, Any]:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def triage_rules_contract() -> Dict[str, Any]:
    return load_contract_json(repo_root() / "contracts" / "triage_rules.json")


def usecase_presets_contract() -> Dict[str, Any]:
    return load_contract_json(repo_root() / "contracts" / "usecase_presets.json")


def replay_assertions_contract() -> Dict[str, Any]:
    return load_contract_json(repo_root() / "contracts" / "replay_assertions.json")


def replay_baselines_contract() -> Dict[str, Any]:
    return load_contract_json(repo_root() / "contracts" / "replay_baselines.json")


def alert_paths(runtime_dir: pathlib.Path) -> Dict[str, pathlib.Path]:
    return {
        "triage_alerts": runtime_dir / "triage_alerts.jsonl",
        "triage_bookmarks": runtime_dir / "triage_bookmarks.json",
        "triage_state": runtime_dir / "triage_session_state.json",
        "incident_root": runtime_dir / "incident_bundle",
    }


def replay_paths(runtime_dir: pathlib.Path) -> Dict[str, pathlib.Path]:
    root = runtime_dir / "replay"
    return {
        "root": root,
        "baseline_root": root / "baselines",
        "manifest": runtime_dir / "baseline_manifest.json",
        "results": runtime_dir / "replay_results.jsonl",
        "diff": runtime_dir / "baseline_diff.json",
        "report": runtime_dir / "replay_report.json",
    }


def events_path(runtime_dir: pathlib.Path) -> pathlib.Path:
    return runtime_dir / "events" / "runtime_events.jsonl"


def read_jsonl(path: pathlib.Path) -> List[Dict[str, Any]]:
    if not path.exists():
        return []
    rows: List[Dict[str, Any]] = []
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                payload = json.loads(line)
                if isinstance(payload, dict):
                    rows.append(payload)
            except Exception:
                continue
    return rows


def write_json(path: pathlib.Path, payload: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(payload, ensure_ascii=True, indent=2) + "\n"
    atomic_write_text(path, text)


def write_jsonl(path: pathlib.Path, rows: Iterable[Dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = []
    for row in rows:
        lines.append(json.dumps(row, ensure_ascii=True) + "\n")
    atomic_write_text(path, "".join(lines))


def atomic_write_text(path: pathlib.Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.tmp.{os.getpid()}")
    tmp.write_text(text, encoding="utf-8")
    tmp.replace(path)


def event_payload(row: Dict[str, Any]) -> Dict[str, Any]:
    payload = row.get("payload")
    if isinstance(payload, dict):
        return payload
    return {}


def safe_int(value: Any, default: int = 0) -> int:
    if value is None:
        return default
    try:
        return int(str(value).strip())
    except Exception:
        return default


def normalize_phase_id(value: Any) -> str:
    phase = str(value or "").strip()
    if phase in VALID_PHASES:
        return phase
    if phase == "unscoped":
        return PHASE_BACKGROUND
    return ""


def normalize_host_class_value(value: Any) -> str:
    host_class = str(value or "").strip().lower()
    if not host_class:
        return HOST_CLASS_BACKGROUND_NOISE
    if host_class in CANONICAL_HOST_CLASSES:
        return host_class
    mapped = LEGACY_HOST_CLASS_ALIASES.get(host_class)
    if mapped:
        return mapped
    if host_class in {"ignored", "ignore"}:
        return HOST_CLASS_IGNORED
    return HOST_CLASS_BACKGROUND_NOISE


def normalize_host(host: str) -> str:
    return host.lower().strip().strip(".")


def normalized_url_components(url: str) -> Tuple[str, str, str, str]:
    if not url:
        return ("", "", "", "")
    try:
        parsed = urlparse(url)
    except Exception:
        return ("", "", "", "")
    scheme = str(parsed.scheme or "").lower()
    host = normalize_host(str(parsed.netloc or ""))
    path = str(parsed.path or "/")
    query = str(parsed.query or "")
    if not host and scheme in {"blob", "view-source"}:
        nested = run_parse_url(path)
        if nested:
            nested_scheme, nested_host, nested_path, nested_query = nested
            host = nested_host
            if nested_path:
                path = nested_path
            if nested_query:
                query = nested_query
    if not path.startswith("/"):
        path = f"/{path}"
    return (scheme, host, path, query)


def run_parse_url(raw: str) -> Optional[Tuple[str, str, str, str]]:
    try:
        parsed = urlparse(raw)
    except Exception:
        return None
    if not parsed.netloc:
        return None
    nested_scheme = str(parsed.scheme or "").lower()
    nested_host = normalize_host(str(parsed.netloc or ""))
    nested_path = str(parsed.path or "/")
    nested_query = str(parsed.query or "")
    return (nested_scheme, nested_host, nested_path, nested_query)


def normalized_url_for_parts(scheme: str, host: str, path: str, query: str) -> str:
    if not scheme and not host:
        return path or ""
    base = f"{scheme}://{host}{path or '/'}"
    if query:
        return f"{base}?{query}"
    return base


def parse_query_params(url: str) -> Dict[str, List[str]]:
    if not url:
        return {}
    try:
        parsed = urlparse(url)
    except Exception:
        return {}
    out: Dict[str, List[str]] = defaultdict(list)
    for key, value in parse_qsl(str(parsed.query or ""), keep_blank_values=True):
        out[str(key)].append(str(value))
    return dict(out)


def canonical_target_site_id(host: str) -> str:
    host = normalize_host(host)
    if not host:
        return "unknown_target"
    parts = host.split(".")
    if len(parts) >= 2:
        return ".".join(parts[-2:])
    return host


def looks_like_google_noise(host: str, url: str) -> bool:
    host = normalize_host(host)
    value = f"{host} {url}".lower()
    hints = [
        "google.",
        "google-",
        "gstatic.com",
        "googlesyndication",
        "googleadservices",
        "doubleclick.net",
        "googletagmanager",
    ]
    return any(hint in value for hint in hints)


def looks_like_analytics_noise(host: str, url: str) -> bool:
    value = f"{host} {url}".lower()
    hints = [
        "analytics",
        "telemetry",
        "segment.io",
        "newrelic",
        "sentry",
        "metrics",
        "pixel",
    ]
    return any(hint in value for hint in hints)


def looks_like_asset(url: str, path: str, source: str = "", mime_type: str = "") -> bool:
    lower = f"{url} {path} {source} {mime_type}".lower()
    hints = [
        "_next/static",
        ".woff",
        ".woff2",
        ".ttf",
        ".png",
        ".jpg",
        ".jpeg",
        ".css",
        ".svg",
        ".gif",
        ".webp",
        "favicon",
    ]
    return any(hint in lower for hint in hints)


def looks_like_browser_bootstrap(url: str, path: str, source: str) -> bool:
    lower = f"{url} {path} {source}".lower()
    hints = [
        "_next/static",
        ".woff",
        ".woff2",
        ".ttf",
        ".png",
        ".jpg",
        ".jpeg",
        ".css",
        "favicon",
        "chrome://",
        "about:blank",
    ]
    return any(hint in lower for hint in hints)


def looks_like_playback(url: str, path: str, operation: str = "", classification: str = "", mime_type: str = "") -> bool:
    lower = f"{url} {path} {operation} {classification} {mime_type}".lower()
    tokens = (
        "playback",
        "resolver",
        "manifest",
        ".m3u8",
        ".mpd",
        "stream",
        "/tmd/",
        "/ptmd/",
        "seamless-view-entries",
        "playbackhistory",
    )
    return any(token in lower for token in tokens)


def looks_like_target_api(url: str, path: str, operation: str = "", classification: str = "", mime_type: str = "") -> bool:
    lower = f"{url} {path} {operation} {classification} {mime_type}".lower()
    tokens = (
        "/api/",
        "/v1/",
        "/v2/",
        "graphql",
        "operationname=",
        "search",
        "detail",
        "episode",
        "query=",
        "suggest",
        "auth",
        "token",
        "json",
    )
    return any(token in lower for token in tokens)


def infer_phase_from_hints(url: str, method: str, operation: str, classification: str) -> str:
    lower = f"{url} {method} {operation} {classification}".lower()
    if any(token in lower for token in ("playback", "resolver", ".m3u8", ".mpd", "stream")):
        return PHASE_PLAYBACK
    if any(token in lower for token in ("search", "query=", "q=", "suggest")):
        return PHASE_SEARCH
    if any(token in lower for token in ("detail", "episode", "content/", "asset/")):
        return PHASE_DETAIL
    if any(token in lower for token in ("auth", "token", "login", "refresh", "session")):
        return PHASE_AUTH
    if method == "GET":
        return PHASE_HOME
    return PHASE_BACKGROUND


def pick_dominant_phase(counter: Counter[str]) -> str:
    if not counter:
        return PHASE_BACKGROUND
    ordered = sorted(
        counter.items(),
        key=lambda item: (item[1], PHASE_PRIORITY.get(item[0], 0)),
        reverse=True,
    )
    return str(ordered[0][0])


def reduce_headers(headers: Dict[str, str]) -> Dict[str, str]:
    if not headers:
        return {}
    reduced: Dict[str, str] = {}
    for key, value in headers.items():
        lower = str(key).lower().strip()
        if not lower:
            continue
        if lower in {"cookie", "set-cookie", "authorization"}:
            reduced[lower] = "__redacted__"
            continue
        compact = str(value).strip()
        if len(compact) > 120:
            compact = compact[:120]
        reduced[lower] = compact
    return reduced


def infer_graphql_operation_name(payload: Dict[str, Any]) -> str:
    op = str(payload.get("graphql_operation_name") or payload.get("graphqlOperationName") or "")
    if op:
        return op
    body_preview = str(payload.get("body_preview") or payload.get("body") or "")
    if body_preview:
        try:
            loaded = json.loads(body_preview)
            if isinstance(loaded, dict):
                candidate = str(loaded.get("operationName") or "")
                if candidate:
                    return candidate
        except Exception:
            pass
    query = str(payload.get("query") or "")
    if "graphql" in query.lower():
        return "graphql_query"
    return ""


def summarize_request_body(payload: Dict[str, Any]) -> Dict[str, Any]:
    body = payload.get("body")
    preview = payload.get("body_preview")
    text = ""
    if isinstance(body, str) and body:
        text = body
    elif isinstance(preview, str) and preview:
        text = preview
    if not text:
        size = safe_int(payload.get("body_size_bytes") or payload.get("bodySizeBytes"), default=0)
        return {"present": size > 0, "size_bytes": max(size, 0), "kind": "empty"}
    trimmed = text.strip()
    kind = "text"
    if trimmed.startswith("{") or trimmed.startswith("["):
        kind = "json"
    elif trimmed.startswith("<"):
        kind = "markup"
    return {
        "present": True,
        "size_bytes": len(text.encode("utf-8", errors="replace")),
        "kind": kind,
        "preview": trimmed[:200],
    }


def event_url(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    for key in ("url", "request_url", "requestUrl", "target_url", "targetUrl"):
        val = payload.get(key)
        if isinstance(val, str) and val:
            return val
    return ""


def event_method(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    for key in ("method", "http_method", "httpMethod"):
        val = payload.get(key)
        if isinstance(val, str) and val:
            return val.upper()
    return ""


def event_status(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    for key in ("status", "status_code", "statusCode", "http_status", "httpStatus"):
        val = payload.get(key)
        if val is None:
            continue
        return str(val)
    return ""


def event_phase_id(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    for key in ("phase_id", "phaseId"):
        val = payload.get(key)
        if isinstance(val, str) and val:
            normalized = normalize_phase_id(val)
            if normalized:
                return normalized
    inferred = infer_phase_from_hints(
        url=event_url(row),
        method=event_method(row),
        operation=event_request_operation(row),
        classification=event_request_classification(row),
    )
    return inferred or PHASE_BACKGROUND


def event_host_class(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    for key in ("host_class", "hostClass"):
        val = payload.get(key)
        if isinstance(val, str) and val:
            return normalize_host_class_value(val)
    return HOST_CLASS_BACKGROUND_NOISE


def event_source(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    val = payload.get("source")
    if isinstance(val, str) and val:
        return val
    val = payload.get("capture_channel")
    if isinstance(val, str) and val:
        return val
    return ""


def request_is_response_observable(row: Dict[str, Any]) -> bool:
    payload = event_payload(row)
    explicit = payload.get("response_observable")
    if isinstance(explicit, bool):
        return explicit
    source = event_source(row).lower()
    if source.startswith("webview_js_bridge"):
        return True
    if source.startswith("native_replay_"):
        return True
    if source.startswith("webview_main_frame_html"):
        return True
    if "okhttp" in source:
        return True
    if source.startswith("webview"):
        return False
    return True


def dedup_event_is_observable(row: Dict[str, Any]) -> bool:
    payload = event_payload(row)
    explicit = payload.get("response_observable")
    if isinstance(explicit, bool):
        return explicit
    source = str(payload.get("source") or "").lower()
    if source.startswith("webview"):
        return False
    return True


def event_request_fingerprint(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    for key in ("request_fingerprint", "requestFingerprint"):
        val = payload.get(key)
        if isinstance(val, str) and val:
            return val
    return ""


def event_dedup_of(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    for key in ("dedup_of", "dedupOf"):
        val = payload.get(key)
        if isinstance(val, str) and val:
            return val
    return ""


def event_mime(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    for key in ("mime", "mime_type", "mimeType", "content_type", "contentType", "response_mime"):
        val = payload.get(key)
        if isinstance(val, str) and val:
            return val
    return ""


def event_request_classification(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    for key in ("request_classification", "response_classification", "classification"):
        val = payload.get(key)
        if isinstance(val, str) and val:
            return val
    return ""


def event_request_operation(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    for key in ("request_operation", "response_operation", "operation"):
        val = payload.get(key)
        if isinstance(val, str) and val:
            return val
    return ""


def parse_boolish(value: Any, default: bool = False) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered in {"1", "true", "yes", "y", "ok"}:
            return True
        if lowered in {"0", "false", "no", "n"}:
            return False
    return default


def infer_extraction_success(operation: str, extracted_field_count: int) -> bool:
    if extracted_field_count > 0:
        return True
    lowered = operation.strip().lower()
    return not ("fail" in lowered or "error" in lowered or lowered.endswith("_failed"))


def infer_extraction_kind(operation: str) -> str:
    lowered = operation.strip().lower()
    if "field_matrix" in lowered:
        return "field_matrix"
    if "playback" in lowered:
        return "playback"
    if "detail" in lowered:
        return "detail"
    if "search" in lowered:
        return "search"
    if "auth" in lowered:
        return "auth"
    return "runtime_event"


def infer_extraction_confidence(extracted_field_count: int, success: bool) -> str:
    if not success or extracted_field_count <= 0:
        return "none"
    if extracted_field_count >= 6:
        return "high"
    if extracted_field_count >= 3:
        return "medium"
    return "low"


def event_semantic_labels(row: Dict[str, Any]) -> List[str]:
    payload = event_payload(row)
    val = payload.get("semantic_labels")
    if isinstance(val, list):
        out: List[str] = []
        for item in val:
            if isinstance(item, str) and item:
                out.append(item)
        return out
    return []


def event_action_name(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    val = payload.get("action_name")
    if isinstance(val, str) and val:
        return val
    return ""


def compact_ui_action_events(events: List[Dict[str, Any]], dedup_window_seconds: float = 0.75) -> List[Dict[str, Any]]:
    if not events:
        return []

    out: List[Dict[str, Any]] = []
    last_signature: Tuple[str, str, str, str, str] = ("", "", "", "", "")
    last_ts = 0.0

    for row in sorted(events, key=event_sort_key):
        payload = event_payload(row)
        signature = (
            event_action_name(row),
            str(payload.get("result") or ""),
            str(payload.get("screen_id") or payload.get("screenId") or ""),
            str(payload.get("tab_id") or payload.get("tabId") or ""),
            str(payload.get("phase_id") or payload.get("phaseId") or ""),
        )
        ts = ts_to_epoch_seconds(str(row.get("ts_utc") or ""))
        is_duplicate = (
            bool(out)
            and signature == last_signature
            and (
                last_ts <= 0.0
                or ts <= 0.0
                or abs(ts - last_ts) <= dedup_window_seconds
            )
        )
        if is_duplicate:
            continue

        out.append(row)
        last_signature = signature
        last_ts = ts

    return out


def event_request_id(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    for key in ("request_id", "requestId", "req_id"):
        val = payload.get(key)
        if isinstance(val, str) and val:
            return val
    if str(row.get("event_type") or "") == "network_request_event":
        event_id = str(row.get("event_id") or "")
        return event_id
    return ""


def event_response_id(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    for key in ("response_id", "responseId", "resp_id"):
        val = payload.get(key)
        if isinstance(val, str) and val:
            return val
    if str(row.get("event_type") or "") == "network_response_event":
        event_id = str(row.get("event_id") or "")
        return event_id
    return ""


def event_headers(row: Dict[str, Any]) -> Dict[str, str]:
    payload = event_payload(row)
    val = payload.get("headers")
    if isinstance(val, dict):
        out: Dict[str, str] = {}
        for k, v in val.items():
            out[str(k)] = str(v)
        return out
    return {}


def event_body_preview(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    for key in ("body_preview", "bodyPreview", "body", "response_body_preview"):
        val = payload.get(key)
        if isinstance(val, str) and val:
            return val
    return ""


def event_response_store_path(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    for key in ("response_store_path", "responseStorePath", "response_store_ref"):
        val = payload.get(key)
        if isinstance(val, str) and val:
            return normalize_response_store_path(val)
    return ""


def normalize_response_store_path(raw_path: str) -> str:
    normalized = raw_path.replace("\\", "/").strip()
    if not normalized:
        return ""
    marker = "response_store/"
    if marker in normalized:
        return normalized.split(marker, 1)[1].lstrip("/")
    return pathlib.Path(normalized).name


def load_runtime_state(runtime_dir: Optional[pathlib.Path]) -> Dict[str, Any]:
    if runtime_dir is None:
        return {}
    state_file = runtime_dir / "runtime_state.json"
    if not state_file.exists():
        return {}
    try:
        payload = json.loads(state_file.read_text(encoding="utf-8"))
    except Exception:
        return {}
    if isinstance(payload, dict):
        return payload
    return {}


def discover_target_hosts(rows: List[Dict[str, Any]], runtime_dir: Optional[pathlib.Path]) -> List[str]:
    state = load_runtime_state(runtime_dir)
    runtime_cfg = state.get("runtime") if isinstance(state.get("runtime"), dict) else {}
    host_family = str(runtime_cfg.get("targetHostFamily") or "")
    hosts: List[str] = []
    if host_family:
        split = re.split(r"[,\s;]+", host_family)
        hosts = [normalize_host(item) for item in split if normalize_host(item)]
    if hosts:
        return sorted(set(hosts))

    counter: Counter[str] = Counter()
    for row in rows:
        if str(row.get("event_type") or "") not in {"network_request_event", "network_response_event"}:
            continue
        url = event_url(row)
        _, host, _, _ = normalized_url_components(url)
        if not host:
            continue
        raw_existing = str(event_payload(row).get("host_class") or event_payload(row).get("hostClass") or "").strip()
        if raw_existing:
            existing = normalize_host_class_value(raw_existing)
            if existing not in {
                HOST_CLASS_TARGET_DOCUMENT,
                HOST_CLASS_TARGET_API,
                HOST_CLASS_TARGET_PLAYBACK,
                HOST_CLASS_TARGET_ASSET,
            }:
                continue
        if looks_like_google_noise(host, url):
            continue
        counter[host] += 1
    return [host for host, _ in counter.most_common(3)]


def is_target_host(host: str, target_hosts: List[str]) -> bool:
    host = normalize_host(host)
    if not host:
        return False
    host_site_id = canonical_target_site_id(host)
    for target in target_hosts:
        normalized_target = normalize_host(target)
        if not normalized_target:
            continue
        if host == normalized_target or host.endswith(f".{normalized_target}"):
            return True
        # Family-level fallback prevents rare same-site target hosts from being
        # misclassified as external_noise when top-N host discovery is capped.
        if canonical_target_site_id(normalized_target) == host_site_id:
            return True
    return False


def resolve_host_class(
    host: str,
    path: str,
    url: str,
    source: str,
    target_hosts: List[str],
    target_site_id: str,
    original_host_class: str,
    operation: str,
    classification: str,
    mime_type: str,
) -> str:
    # Deterministic precedence:
    # 1) invalid host => ignored
    # 2) target playback
    # 3) target api
    # 4) target asset
    # 5) target fallback document
    # 6) browser bootstrap
    # 7) google noise
    # 8) analytics noise
    # 9) background fallback
    normalized_original = normalize_host_class_value(original_host_class)
    if not host:
        return HOST_CLASS_IGNORED if normalized_original == HOST_CLASS_IGNORED else HOST_CLASS_IGNORED

    is_target = is_target_host(host, target_hosts)
    if not is_target:
        known_suffixes = KNOWN_TARGET_PLAYBACK_HOST_SUFFIXES.get(target_site_id, tuple())
        if known_suffixes and looks_like_playback(
            url=url,
            path=path,
            operation=operation,
            classification=classification,
            mime_type=mime_type,
        ):
            is_target = any(host == suffix or host.endswith(f".{suffix}") for suffix in known_suffixes)

    if is_target:
        if looks_like_playback(
            url=url,
            path=path,
            operation=operation,
            classification=classification,
            mime_type=mime_type,
        ):
            return HOST_CLASS_TARGET_PLAYBACK
        if looks_like_target_api(
            url=url,
            path=path,
            operation=operation,
            classification=classification,
            mime_type=mime_type,
        ):
            return HOST_CLASS_TARGET_API
        if looks_like_asset(url=url, path=path, source=source, mime_type=mime_type):
            return HOST_CLASS_TARGET_ASSET
        return HOST_CLASS_TARGET_DOCUMENT

    if looks_like_browser_bootstrap(url=url, path=path, source=source):
        return HOST_CLASS_BROWSER_BOOTSTRAP
    if looks_like_google_noise(host, url):
        return HOST_CLASS_GOOGLE_NOISE
    if looks_like_analytics_noise(host, url):
        return HOST_CLASS_ANALYTICS_NOISE
    if normalized_original == HOST_CLASS_IGNORED:
        return HOST_CLASS_IGNORED
    return HOST_CLASS_BACKGROUND_NOISE


def canonical_request_key(payload: Dict[str, Any], scheme: str, host: str, path: str, query: str) -> str:
    fingerprint = str(payload.get("request_fingerprint") or payload.get("requestFingerprint") or "").strip()
    if fingerprint:
        return f"fp:{fingerprint}"
    method = str(payload.get("method") or payload.get("http_method") or "GET").upper()
    body_summary = summarize_request_body(payload)
    compact_headers = sorted(reduce_headers(event_headers({"payload": payload})).keys())
    return stable_hash(
        {
            "method": method,
            "scheme": scheme,
            "host": host,
            "path": path,
            "query": query,
            "headers": compact_headers,
            "body_kind": body_summary.get("kind"),
            "operation": str(payload.get("request_operation") or ""),
        }
    )


def request_flags(payload: Dict[str, Any], phase_id: str, url: str) -> Dict[str, bool]:
    lowered = f"{url} {payload.get('request_operation') or ''} {payload.get('graphql_operation_name') or ''}".lower()
    return {
        "auth": phase_id == PHASE_AUTH or "auth" in lowered or "token" in lowered,
        "playback": phase_id == PHASE_PLAYBACK or any(token in lowered for token in ("playback", ".m3u8", ".mpd", "resolver")),
        "search": phase_id == PHASE_SEARCH or "search" in lowered or "query=" in lowered,
        "detail": phase_id == PHASE_DETAIL or "detail" in lowered or "content/" in lowered,
    }


PHASE_MARKER_ALIASES: Dict[str, Tuple[str, str]] = {
    "home_probe_start": (PHASE_HOME, "start"),
    "search_probe_start": (PHASE_SEARCH, "start"),
    "detail_probe_start": (PHASE_DETAIL, "start"),
    "playback_probe_start": (PHASE_PLAYBACK, "start"),
    "auth_probe_start": (PHASE_AUTH, "start"),
    "probe_end": (PHASE_BACKGROUND, "stop"),
}

SPECIALIZED_PHASES = {PHASE_SEARCH, PHASE_DETAIL, PHASE_PLAYBACK, PHASE_AUTH}


def marker_alias_for_row(row: Dict[str, Any]) -> Optional[Tuple[str, str]]:
    payload = event_payload(row)
    candidates = [
        str(payload.get("operation") or "").strip(),
        str(payload.get("action_name") or "").strip(),
        str(payload.get("event_name") or "").strip(),
        str(payload.get("marker") or "").strip(),
    ]
    for value in candidates:
        lowered = value.lower()
        if lowered in PHASE_MARKER_ALIASES:
            return PHASE_MARKER_ALIASES[lowered]
    return None


def inferred_phase_for_row(row: Dict[str, Any]) -> str:
    return infer_phase_from_hints(
        url=event_url(row),
        method=event_method(row),
        operation=event_request_operation(row),
        classification=event_request_classification(row),
    )


def pick_phase_for_event(
    row: Dict[str, Any],
    current_phase: str,
    seen_special_phase: bool,
    target_hosts: List[str],
) -> str:
    payload = event_payload(row)
    raw_phase = str(payload.get("phase_id") or payload.get("phaseId") or "").strip()
    explicit = normalize_phase_id(raw_phase)
    if raw_phase == "unscoped":
        explicit = ""
    if explicit and explicit != PHASE_BACKGROUND:
        return explicit

    inferred = inferred_phase_for_row(row)
    if current_phase and current_phase != PHASE_BACKGROUND:
        # Active marker window wins over inference.
        return current_phase
    if inferred and inferred != PHASE_BACKGROUND:
        return inferred

    url = event_url(row)
    _, host, path, _ = normalized_url_components(url)
    if host and is_target_host(host, target_hosts):
        if (
            not seen_special_phase
            and not looks_like_playback(url=url, path=path, operation=event_request_operation(row), classification=event_request_classification(row))
        ):
            return PHASE_HOME
        if current_phase and current_phase != PHASE_BACKGROUND:
            return current_phase
        return PHASE_HOME
    return explicit if explicit else PHASE_BACKGROUND


def normalize_runtime_rows(rows: List[Dict[str, Any]], runtime_dir: Optional[pathlib.Path] = None) -> List[Dict[str, Any]]:
    normalized_rows: List[Dict[str, Any]] = [copy.deepcopy(row) for row in rows]
    normalized_rows.sort(key=event_sort_key)

    target_hosts = discover_target_hosts(normalized_rows, runtime_dir=runtime_dir)
    primary_host = target_hosts[0] if target_hosts else ""
    target_site_id = canonical_target_site_id(primary_host)

    active_phase = PHASE_BACKGROUND
    seen_special_phase = False
    canonical_request_id_by_key: Dict[str, str] = {}
    canonical_request_event_by_key: Dict[str, str] = {}
    request_id_aliases: Dict[str, str] = {}
    request_context_by_id: Dict[str, Dict[str, Any]] = {}

    for row in normalized_rows:
        payload = event_payload(row)
        if not payload:
            payload = {}
            row["payload"] = payload

        event_type = str(row.get("event_type") or "")
        transition = str(payload.get("transition") or "").strip().lower()
        alias_marker = marker_alias_for_row(row)
        if alias_marker:
            alias_phase, alias_transition = alias_marker
            payload["phase_marker_alias"] = str(payload.get("phase_marker_alias") or str(payload.get("operation") or ""))
            if alias_transition in {"start", "resume", "enter"}:
                active_phase = alias_phase
                if alias_phase in SPECIALIZED_PHASES:
                    seen_special_phase = True
            elif alias_transition in {"stop", "exit", "pause"}:
                active_phase = PHASE_BACKGROUND
            payload.setdefault("phase_id", alias_phase if alias_phase in VALID_PHASES else PHASE_BACKGROUND)

        url = event_url(row)
        scheme, host, path, query = normalized_url_components(url)
        request_operation = str(payload.get("request_operation") or payload.get("response_operation") or payload.get("operation") or "")
        request_classification = str(payload.get("request_classification") or payload.get("response_classification") or payload.get("classification") or "")
        mime_type = str(payload.get("mime_type") or payload.get("mime") or payload.get("content_type") or "")

        if event_type == "probe_phase_event":
            phase = normalize_phase_id(payload.get("phase_id") or payload.get("phaseId")) or active_phase or PHASE_BACKGROUND
            if transition in {"", "mark"}:
                transition = "mark"
            payload["transition"] = transition
            payload["phase_id"] = phase
            if transition in {"start", "resume", "enter"}:
                active_phase = phase
                if phase in SPECIALIZED_PHASES:
                    seen_special_phase = True
            elif transition in {"stop", "exit", "pause"}:
                active_phase = PHASE_BACKGROUND
            payload["target_site_id"] = target_site_id
            payload["normalized_host"] = ""
            payload["normalized_path"] = "/"
            payload["normalized_scheme"] = ""
            payload["host_class"] = HOST_CLASS_BACKGROUND_NOISE
            continue

        phase_id = pick_phase_for_event(
            row,
            current_phase=active_phase,
            seen_special_phase=seen_special_phase,
            target_hosts=target_hosts,
        )
        payload["phase_id"] = phase_id
        if phase_id in SPECIALIZED_PHASES:
            seen_special_phase = True

        payload_normalized_scheme = str(payload.get("normalized_scheme") or "").strip().lower() or scheme
        payload_normalized_host = normalize_host(str(payload.get("normalized_host") or "").strip()) or host
        payload_normalized_path = str(payload.get("normalized_path") or "").strip() or path or "/"
        if not payload_normalized_path.startswith("/"):
            payload_normalized_path = f"/{payload_normalized_path}"
        payload["normalized_scheme"] = payload_normalized_scheme
        payload["normalized_host"] = payload_normalized_host
        payload["normalized_path"] = payload_normalized_path
        payload["normalized_url"] = str(
            payload.get("normalized_url")
            or normalized_url_for_parts(payload_normalized_scheme, payload_normalized_host, payload_normalized_path, query)
        )
        payload["target_site_id"] = target_site_id

        explicit_host_class_raw = payload.get("host_class")
        if explicit_host_class_raw is None:
            explicit_host_class_raw = payload.get("hostClass")
        explicit_host_class = (
            normalize_host_class_value(explicit_host_class_raw)
            if str(explicit_host_class_raw or "").strip()
            else ""
        )
        preserve_derived_host_class = event_type in {"extraction_event", "truncation_event"} and (
            explicit_host_class in CANONICAL_HOST_CLASSES
        )
        if preserve_derived_host_class:
            host_class = explicit_host_class
        else:
            host_class = resolve_host_class(
                host=payload_normalized_host,
                path=payload_normalized_path,
                url=url,
                source=event_source(row),
                target_hosts=target_hosts,
                target_site_id=target_site_id,
                original_host_class=event_host_class(row),
                operation=request_operation,
                classification=request_classification,
                mime_type=mime_type,
            )
        payload["host_class"] = host_class

        if event_type == "extraction_event":
            operation = str(payload.get("operation") or "runtime_event").strip() or "runtime_event"
            extracted_field_count = max(safe_int(payload.get("extracted_field_count"), default=0), 0)
            success = parse_boolish(
                payload.get("success"),
                default=infer_extraction_success(operation, extracted_field_count),
            )
            source_ref = str(payload.get("source_ref") or "").strip()
            if not source_ref:
                source_ref = (
                    str(payload.get("source_event_id") or "").strip()
                    or str(payload.get("request_id") or "").strip()
                    or str(payload.get("response_id") or "").strip()
                    or str(payload.get("url") or "").strip()
                    or str(row.get("event_id") or "").strip()
                    or f"runtime:{operation}"
                )
            extraction_kind = str(payload.get("extraction_kind") or "").strip() or infer_extraction_kind(operation)
            confidence_summary = str(payload.get("confidence_summary") or "").strip() or infer_extraction_confidence(
                extracted_field_count,
                success,
            )
            payload["operation"] = operation
            payload["source_ref"] = source_ref
            payload["phase_id"] = phase_id
            payload["host_class"] = host_class
            payload["extraction_kind"] = extraction_kind
            payload["success"] = success
            payload["extracted_field_count"] = extracted_field_count
            payload["confidence_summary"] = confidence_summary
            continue

        if event_type == "network_request_event":
            method = event_method(row) or "GET"
            payload["method"] = method
            payload["query_params"] = parse_query_params(url)
            payload["request_operation"] = str(payload.get("request_operation") or payload.get("operation") or "")
            payload["graphql_operation_name"] = infer_graphql_operation_name(payload)
            payload["request_body_summary"] = summarize_request_body(payload)
            payload["headers_reduced"] = reduce_headers(event_headers(row))
            payload["correlation_refs"] = {
                "trace_id": str(row.get("trace_id") or ""),
                "action_id": str(row.get("action_id") or ""),
                "event_id": str(row.get("event_id") or ""),
            }
            payload["request_flags"] = request_flags(payload, phase_id=phase_id, url=url)
            payload["request_fingerprint"] = str(
                payload.get("request_fingerprint")
                or payload.get("requestFingerprint")
                or stable_hash(
                    {
                        "method": method,
                        "scheme": scheme,
                        "host": host,
                        "path": path,
                        "query": query,
                        "request_operation": payload.get("request_operation"),
                        "graphql_operation_name": payload.get("graphql_operation_name"),
                        "body_kind": (payload.get("request_body_summary") or {}).get("kind"),
                    }
                )
            )

            original_request_id = str(payload.get("request_id") or payload.get("requestId") or payload.get("req_id") or row.get("event_id") or "").strip()
            if not original_request_id:
                original_request_id = f"req_auto_{stable_hash([row.get('event_id'), url, method])[:16]}"
            payload["original_request_id"] = original_request_id
            key = canonical_request_key(payload, scheme=scheme, host=host, path=path, query=query)
            canonical_id = canonical_request_id_by_key.get(key)
            if not canonical_id:
                canonical_id = original_request_id
                canonical_request_id_by_key[key] = canonical_id
                canonical_request_event_by_key[key] = str(row.get("event_id") or canonical_id)
            else:
                canonical_event_id = canonical_request_event_by_key.get(key, "")
                if canonical_event_id and canonical_event_id != str(row.get("event_id") or ""):
                    payload["dedup_of"] = canonical_event_id
            payload["request_id"] = canonical_id
            request_id_aliases[original_request_id] = canonical_id
            request_context_by_id[canonical_id] = {
                "request_fingerprint": str(payload.get("request_fingerprint") or ""),
                "request_operation": str(payload.get("request_operation") or ""),
                "graphql_operation_name": str(payload.get("graphql_operation_name") or ""),
                "phase_id": phase_id,
                "host_class": host_class,
                "normalized_scheme": scheme,
                "normalized_host": host,
                "normalized_path": path,
                "target_site_id": target_site_id,
            }

        if event_type == "network_response_event":
            raw_request_id = str(payload.get("request_id") or payload.get("requestId") or payload.get("req_id") or "").strip()
            canonical_request_id = request_id_aliases.get(raw_request_id, raw_request_id)
            if not canonical_request_id:
                fallback_key = canonical_request_key(payload, scheme=scheme, host=host, path=path, query=query)
                canonical_request_id = canonical_request_id_by_key.get(fallback_key, "")
            if canonical_request_id:
                payload["request_id"] = canonical_request_id
            request_context = request_context_by_id.get(canonical_request_id, {})
            if phase_id == PHASE_BACKGROUND:
                linked_phase = str(request_context.get("phase_id") or "")
                if linked_phase and linked_phase != PHASE_BACKGROUND:
                    phase_id = linked_phase
                    payload["phase_id"] = linked_phase
            if normalize_host_class_value(payload.get("host_class")) == HOST_CLASS_BACKGROUND_NOISE:
                linked_host_class = str(request_context.get("host_class") or "")
                if linked_host_class:
                    payload["host_class"] = linked_host_class

            response_id = str(payload.get("response_id") or payload.get("responseId") or payload.get("resp_id") or row.get("event_id") or "").strip()
            if not response_id:
                response_id = f"resp_auto_{stable_hash([row.get('event_id'), url])[:16]}"
            payload["response_id"] = response_id

            status_code = safe_int(payload.get("status_code") or payload.get("status") or payload.get("http_status"), default=0)
            if status_code:
                payload["status_code"] = status_code
            mime_type = str(payload.get("mime_type") or payload.get("mime") or payload.get("content_type") or "")
            if mime_type:
                payload["mime_type"] = mime_type

            headers = event_headers(row)
            content_length = str(headers.get("content-length") or headers.get("Content-Length") or payload.get("content_length_header") or "")
            payload["content_length_header"] = content_length
            original_content_length = safe_int(
                payload.get("original_content_length")
                or payload.get("response_content_length")
                or content_length,
                default=0,
            )
            payload["original_content_length"] = original_content_length if original_content_length > 0 else 0
            stored_size_bytes = safe_int(
                payload.get("stored_size_bytes")
                or payload.get("response_size_bytes")
                or payload.get("captured_body_bytes"),
                default=0,
            )
            if stored_size_bytes <= 0 and isinstance(payload.get("body_preview"), str):
                stored_size_bytes = len(str(payload.get("body_preview")).encode("utf-8", errors="replace"))
            payload["stored_size_bytes"] = stored_size_bytes

            body_ref = str(payload.get("body_ref") or payload.get("response_store_path") or payload.get("responseStorePath") or "").strip()
            payload["body_ref"] = body_ref
            rel_response_store_path = normalize_response_store_path(body_ref) if body_ref else event_response_store_path(row)
            if runtime_dir and rel_response_store_path:
                body_path = runtime_dir / "response_store" / rel_response_store_path
                if body_path.exists() and body_path.is_file():
                    try:
                        stored_size_bytes = int(body_path.stat().st_size)
                        payload["stored_size_bytes"] = stored_size_bytes
                    except Exception:
                        pass

            decision = body_capture_decision(row)
            payload["body_capture_policy"] = str(payload.get("body_capture_policy") or decision.get("body_capture_policy") or BODY_CAPTURE_POLICY_METADATA_ONLY)
            payload["capture_reason"] = str(payload.get("capture_reason") or decision.get("capture_reason") or "")
            payload["candidate_relevance"] = str(payload.get("candidate_relevance") or decision.get("candidate_relevance") or CANDIDATE_RELEVANCE_NON_CANDIDATE)

            capture_truncated = parse_boolish(payload.get("capture_truncated"), default=False)
            capture_limit_bytes = safe_int(payload.get("capture_limit_bytes") or payload.get("captured_body_bytes"), default=0)
            if not capture_truncated and original_content_length > 0 and stored_size_bytes > 0 and stored_size_bytes < original_content_length:
                capture_truncated = True
            if (
                not capture_truncated
                and stored_size_bytes == FOUR_MB_BYTES
                and payload.get("candidate_relevance") in {CANDIDATE_RELEVANCE_REQUIRED, CANDIDATE_RELEVANCE_SIGNAL}
                and payload.get("body_capture_policy")
                not in {BODY_CAPTURE_POLICY_METADATA_ONLY, BODY_CAPTURE_POLICY_SKIPPED_MEDIA_SEGMENT, BODY_CAPTURE_POLICY_SKIP_BODY}
            ):
                capture_truncated = True
            if capture_truncated and capture_limit_bytes <= 0 and stored_size_bytes > 0:
                capture_limit_bytes = FOUR_MB_BYTES if stored_size_bytes == FOUR_MB_BYTES else stored_size_bytes
            capture_failure = str(payload.get("capture_failure") or "").strip()
            if capture_failure in {"required_body_truncated", "required_body_missing"} and not capture_truncated:
                capture_truncated = True
                if capture_limit_bytes <= 0 and stored_size_bytes > 0:
                    capture_limit_bytes = stored_size_bytes
            payload["capture_truncated"] = capture_truncated
            payload["capture_limit_bytes"] = capture_limit_bytes if capture_truncated else 0
            truncation_reason = str(payload.get("truncation_reason") or "").strip()
            if capture_truncated and not truncation_reason:
                if capture_limit_bytes == FOUR_MB_BYTES:
                    truncation_reason = TRUNCATION_REASON_BODY_SIZE_LIMIT
                elif payload.get("body_capture_policy") in {BODY_CAPTURE_POLICY_METADATA_ONLY, BODY_CAPTURE_POLICY_SKIPPED_MEDIA_SEGMENT}:
                    truncation_reason = TRUNCATION_REASON_STREAM_POLICY
                else:
                    truncation_reason = TRUNCATION_REASON_BODY_SIZE_LIMIT
            payload["truncation_reason"] = truncation_reason if capture_truncated else ""
            if capture_truncated and payload.get("body_capture_policy") == BODY_CAPTURE_POLICY_FULL_CANDIDATE_REQUIRED:
                payload["capture_failure"] = "required_body_truncated"
            payload["source_channel"] = event_source(row)
            payload["request_operation"] = str(
                payload.get("request_operation")
                or payload.get("response_operation")
                or payload.get("operation")
                or request_context.get("request_operation")
                or ""
            )
            payload["graphql_operation_name"] = str(
                payload.get("graphql_operation_name")
                or request_context.get("graphql_operation_name")
                or infer_graphql_operation_name(payload)
            )
            payload["request_fingerprint"] = str(
                payload.get("request_fingerprint")
                or payload.get("requestFingerprint")
                or request_context.get("request_fingerprint")
                or stable_hash(
                    {
                        "request_id": payload.get("request_id") or "",
                        "method": event_method(row) or "GET",
                        "host": host,
                        "path": path,
                    }
                )
            )
            payload["correlation_refs"] = {
                "trace_id": str(row.get("trace_id") or ""),
                "action_id": str(row.get("action_id") or ""),
                "event_id": str(row.get("event_id") or ""),
            }

        if event_type in {"auth_event", "cookie_event", "storage_event", "provenance_event"}:
            payload["target_site_id"] = target_site_id
            payload.setdefault("phase_id", phase_id)

    return normalized_rows


def event_normalized_host(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    value = str(payload.get("normalized_host") or "").strip().lower()
    if value:
        return value
    _, host, _, _ = normalized_url_components(event_url(row))
    return host


def event_normalized_path(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    value = str(payload.get("normalized_path") or "").strip()
    if value:
        return value
    _, _, path, _ = normalized_url_components(event_url(row))
    return path


def event_normalized_scheme(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    value = str(payload.get("normalized_scheme") or "").strip().lower()
    if value:
        return value
    scheme, _, _, _ = normalized_url_components(event_url(row))
    return scheme


def event_target_site_id(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    value = str(payload.get("target_site_id") or payload.get("targetSiteId") or "").strip()
    if value:
        return value
    return canonical_target_site_id(event_normalized_host(row))


def event_content_length_header(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    value = str(payload.get("content_length_header") or "").strip()
    if value:
        return value
    headers = event_headers(row)
    return str(headers.get("content-length") or headers.get("Content-Length") or "")


def event_original_content_length(row: Dict[str, Any]) -> int:
    payload = event_payload(row)
    value = safe_int(
        payload.get("original_content_length")
        or payload.get("response_content_length")
        or payload.get("content_length_header"),
        default=0,
    )
    if value > 0:
        return value
    header = event_content_length_header(row)
    return safe_int(header, default=0)


def event_stored_size_bytes(row: Dict[str, Any]) -> int:
    payload = event_payload(row)
    size = safe_int(payload.get("stored_size_bytes") or payload.get("response_size_bytes") or payload.get("captured_body_bytes"), default=0)
    if size > 0:
        return size
    preview = payload.get("body_preview")
    if isinstance(preview, str):
        return len(preview.encode("utf-8", errors="replace"))
    return 0


def event_capture_truncated(row: Dict[str, Any]) -> bool:
    payload = event_payload(row)
    return parse_boolish(payload.get("capture_truncated"), default=False)


def event_capture_limit_bytes(row: Dict[str, Any]) -> int:
    payload = event_payload(row)
    return safe_int(payload.get("capture_limit_bytes") or payload.get("captured_body_bytes"), default=0)


def event_truncation_reason(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    return str(payload.get("truncation_reason") or "").strip()


def event_body_capture_policy(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    return str(payload.get("body_capture_policy") or BODY_CAPTURE_POLICY_METADATA_ONLY).strip() or BODY_CAPTURE_POLICY_METADATA_ONLY


def event_candidate_relevance(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    value = str(payload.get("candidate_relevance") or "").strip().lower()
    if value in {
        CANDIDATE_RELEVANCE_REQUIRED,
        CANDIDATE_RELEVANCE_SIGNAL,
        CANDIDATE_RELEVANCE_NON_CANDIDATE,
    }:
        return value
    return CANDIDATE_RELEVANCE_NON_CANDIDATE


def event_source_channel(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    value = str(payload.get("source_channel") or "").strip()
    if value:
        return value
    return event_source(row)


def ts_to_epoch_seconds(ts: str) -> float:
    if not ts:
        return 0.0
    normalized = ts.replace("Z", "+00:00")
    try:
        return dt.datetime.fromisoformat(normalized).timestamp()
    except Exception:
        return 0.0


def rule_threshold(rule: Dict[str, Any], key: str, fallback: int) -> int:
    raw = rule.get("thresholds", {}).get(key)
    if raw is None:
        return fallback
    try:
        return int(raw)
    except Exception:
        return fallback


def stable_hash(payload: Any) -> str:
    encoded = json.dumps(payload, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def flatten_keys(value: Any, prefix: str = "") -> Iterable[str]:
    if isinstance(value, dict):
        for k, v in value.items():
            key = f"{prefix}.{k}" if prefix else str(k)
            yield key
            yield from flatten_keys(v, key)
    elif isinstance(value, list):
        for idx, item in enumerate(value[:8]):
            key = f"{prefix}[]" if prefix else "[]"
            yield key
            yield from flatten_keys(item, key)


def event_sort_key(row: Dict[str, Any]) -> Tuple[str, int]:
    ts = str(row.get("ts_utc") or "")
    mono_raw = row.get("ts_mono_ns", 0)
    try:
        mono = int(mono_raw)
    except Exception:
        mono = 0
    return (ts, mono)


def compact_request(row: Dict[str, Any]) -> Dict[str, Any]:
    payload = event_payload(row)
    query_params = payload.get("query_params")
    if not isinstance(query_params, dict):
        query_params = parse_query_params(event_url(row))
    headers_raw = event_headers(row)
    headers_reduced = payload.get("headers_reduced")
    if not isinstance(headers_reduced, dict):
        headers_reduced = reduce_headers(headers_raw)
    return {
        "event_id": str(row.get("event_id") or ""),
        "request_id": event_request_id(row),
        "canonical_request_id": str(payload.get("request_id") or event_request_id(row)),
        "original_request_id": str(payload.get("original_request_id") or ""),
        "request_fingerprint": event_request_fingerprint(row),
        "ts_utc": str(row.get("ts_utc") or ""),
        "url": event_url(row),
        "normalized_url": str(payload.get("normalized_url") or ""),
        "normalized_scheme": event_normalized_scheme(row),
        "normalized_host": event_normalized_host(row),
        "normalized_path": event_normalized_path(row),
        "target_site_id": event_target_site_id(row),
        "method": event_method(row) or "GET",
        "phase_id": event_phase_id(row),
        "host_class": event_host_class(row),
        "dedup_of": event_dedup_of(row),
        "classification": event_request_classification(row),
        "operation": event_request_operation(row),
        "graphql_operation_name": str(payload.get("graphql_operation_name") or ""),
        "headers_raw": headers_raw,
        "headers_reduced": headers_reduced,
        "query_params": query_params,
        "request_body_summary": payload.get("request_body_summary") or summarize_request_body(payload),
        "request_flags": payload.get("request_flags") or request_flags(payload, phase_id=event_phase_id(row), url=event_url(row)),
        "correlation_refs": payload.get("correlation_refs") or {
            "trace_id": str(row.get("trace_id") or ""),
            "action_id": str(row.get("action_id") or ""),
            "event_id": str(row.get("event_id") or ""),
        },
        "semantic_labels": event_semantic_labels(row),
        "screen_id": str(payload.get("screen_id") or payload.get("screenId") or ""),
        "tab_id": str(payload.get("tab_id") or payload.get("tabId") or ""),
    }


def compact_response(row: Dict[str, Any]) -> Dict[str, Any]:
    payload = event_payload(row)
    return {
        "event_id": str(row.get("event_id") or ""),
        "response_id": event_response_id(row),
        "request_id": event_request_id(row),
        "request_fingerprint": event_request_fingerprint(row),
        "ts_utc": str(row.get("ts_utc") or ""),
        "url": event_url(row),
        "normalized_url": str(payload.get("normalized_url") or ""),
        "normalized_scheme": event_normalized_scheme(row),
        "normalized_host": event_normalized_host(row),
        "normalized_path": event_normalized_path(row),
        "target_site_id": event_target_site_id(row),
        "method": event_method(row) or "GET",
        "status": event_status(row),
        "status_code": safe_int(payload.get("status_code") or payload.get("status"), default=0),
        "mime": event_mime(row),
        "mime_type": str(payload.get("mime_type") or event_mime(row)),
        "content_length_header": event_content_length_header(row),
        "original_content_length": event_original_content_length(row),
        "stored_size_bytes": event_stored_size_bytes(row),
        "body_ref": str(payload.get("body_ref") or event_response_store_path(row)),
        "capture_truncated": event_capture_truncated(row),
        "capture_limit_bytes": event_capture_limit_bytes(row),
        "truncation_reason": event_truncation_reason(row),
        "body_capture_policy": event_body_capture_policy(row),
        "candidate_relevance": event_candidate_relevance(row),
        "capture_failure": str(payload.get("capture_failure") or ""),
        "source_channel": event_source_channel(row),
        "request_operation": str(payload.get("request_operation") or event_request_operation(row)),
        "graphql_operation_name": str(payload.get("graphql_operation_name") or ""),
        "phase_id": event_phase_id(row),
        "host_class": event_host_class(row),
        "classification": event_request_classification(row),
        "operation": event_request_operation(row),
        "semantic_labels": event_semantic_labels(row),
        "response_store_path": event_response_store_path(row),
    }


def compact_cookie(row: Dict[str, Any]) -> Dict[str, Any]:
    payload = event_payload(row)
    return {
        "event_id": str(row.get("event_id") or ""),
        "ts_utc": str(row.get("ts_utc") or ""),
        "operation": str(payload.get("operation") or ""),
        "domain": str(payload.get("domain") or ""),
        "cookie_name": str(payload.get("cookie_name") or ""),
        "reason": str(payload.get("reason") or ""),
    }


def compact_auth(row: Dict[str, Any]) -> Dict[str, Any]:
    payload = event_payload(row)
    return {
        "event_id": str(row.get("event_id") or ""),
        "ts_utc": str(row.get("ts_utc") or ""),
        "operation": str(payload.get("operation") or ""),
        "status": event_status(row),
        "url": event_url(row),
    }


def build_correlation(rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    grouped: Dict[Tuple[str, str], List[Dict[str, Any]]] = defaultdict(list)
    for row in rows:
        trace_id = str(row.get("trace_id") or "")
        action_id = str(row.get("action_id") or "")
        if not trace_id or not action_id:
            continue
        grouped[(trace_id, action_id)].append(row)

    out: List[Dict[str, Any]] = []
    for (trace_id, action_id), events in grouped.items():
        ordered = sorted(events, key=event_sort_key)
        ui_raw = [e for e in ordered if e.get("event_type") == "ui_action_event"]
        ui = compact_ui_action_events(ui_raw)
        request_events_raw = [e for e in ordered if e.get("event_type") == "network_request_event"]
        request_events = []
        seen_request_ids: set = set()
        for req in request_events_raw:
            rid = event_request_id(req)
            if not rid:
                request_events.append(req)
                continue
            if rid in seen_request_ids:
                continue
            seen_request_ids.add(rid)
            request_events.append(req)
        response_events = [e for e in ordered if e.get("event_type") == "network_response_event"]
        cookies = [e for e in ordered if e.get("event_type") == "cookie_event"]
        auth_events = [e for e in ordered if e.get("event_type") == "auth_event"]
        provenance_events = [e for e in ordered if e.get("event_type") == "provenance_event"]
        dedup_events = [
            e for e in ordered
            if e.get("event_type") == "correlation_event"
            and str(event_payload(e).get("operation") or "") == "request_dedup"
        ]
        ui_action_counter = Counter(
            name for name in (event_action_name(item) for item in ui) if name
        )
        request_class_counter = Counter(
            item for item in (event_request_classification(req) for req in request_events) if item
        )
        response_class_counter = Counter(
            item for item in (event_request_classification(resp) for resp in response_events) if item
        )
        semantic_label_counter: Counter[str] = Counter()
        for req in request_events:
            semantic_label_counter.update(event_semantic_labels(req))
        for resp in response_events:
            semantic_label_counter.update(event_semantic_labels(resp))
        chain_kind = "generic"
        preferred_chain_classes = ["auth", "search", "category", "detail", "playback", "live", "config", "tracking", "asset"]
        for candidate in preferred_chain_classes:
            if request_class_counter.get(candidate, 0) > 0 or response_class_counter.get(candidate, 0) > 0:
                chain_kind = candidate
                break
        if chain_kind == "generic":
            ui_names = [name for name in ui_action_counter.keys()]
            if any("playback" in name for name in ui_names):
                chain_kind = "playback"
            elif any("search" in name for name in ui_names):
                chain_kind = "search"
            elif any("category" in name for name in ui_names):
                chain_kind = "category"
            elif any("auth" in name for name in ui_names):
                chain_kind = "auth"

        response_by_request_id: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
        for response in response_events:
            req_id = event_request_id(response)
            if req_id:
                response_by_request_id[req_id].append(response)

        request_pairs = []
        matched_request_ids = set()
        for request in request_events:
            req_id = event_request_id(request)
            matches = response_by_request_id.get(req_id, []) if req_id else []
            if matches:
                matched_request_ids.add(req_id)
            request_pairs.append(
                {
                    "request": compact_request(request),
                    "responses": [compact_response(resp) for resp in matches],
                    "status_chain": [event_status(resp) for resp in matches if event_status(resp)],
                }
            )

        unmatched_response_ids = []
        request_ids = {event_request_id(req) for req in request_events if event_request_id(req)}
        for response in response_events:
            req_id = event_request_id(response)
            if req_id and req_id not in request_ids:
                unmatched_response_ids.append(event_response_id(response))

        run_id = str(events[0].get("run_id") or "unknown")
        request_count = len(request_events)
        matched_count = len([rid for rid in request_ids if rid in matched_request_ids])
        response_coverage = round((matched_count / request_count), 4) if request_count > 0 else 0.0
        status_chain = [event_status(resp) for resp in response_events if event_status(resp)]
        any_success = any(status.startswith("2") for status in status_chain)
        result_status = "ok" if any_success else ("incomplete" if response_coverage < 1.0 else "error")
        phase_counter = Counter(
            phase_id
            for phase_id in (event_phase_id(event) for event in ordered)
            if phase_id
        )
        phase_id = pick_dominant_phase(phase_counter)
        host_class_counter = Counter(
            host_class
            for host_class in (event_host_class(event) for event in ordered)
            if host_class
        )
        host_scope = {bucket: int(host_class_counter.get(bucket, 0)) for bucket in HOST_SCOPE_BUCKETS}
        dedup_ref_count = len(dedup_events) + len(
            [request for request in request_events_raw if event_dedup_of(request)]
        )
        dedup_duplicate_count = 0
        for dedup_event in dedup_events:
            payload = event_payload(dedup_event)
            try:
                dedup_duplicate_count += int(payload.get("dedup_count") or 1)
            except Exception:
                dedup_duplicate_count += 1

        out.append(
            {
                "schema_version": 1,
                "run_id": run_id,
                "trace_id": trace_id,
                "action_id": action_id,
                "phase_id": phase_id,
                "chain_kind": chain_kind,
                "host_scope": host_scope,
                "generated_at_utc": utc_now(),
                "linked_event_ids": [str(e.get("event_id") or "") for e in ordered if e.get("event_id")],
                "ui_start": ui[0] if ui else None,
                "ui_end": ui[-1] if ui else None,
                "requests": [compact_request(req) for req in request_events],
                "responses": [compact_response(resp) for resp in response_events],
                "request_response_pairs": request_pairs,
                "cookie_transitions": [compact_cookie(cookie) for cookie in cookies],
                "auth_transitions": [compact_auth(auth) for auth in auth_events],
                "request_count": request_count,
                "response_count": len(response_events),
                "matched_request_count": matched_count,
                "response_coverage": response_coverage,
                "unmatched_response_ids": [item for item in unmatched_response_ids if item],
                "result_status": result_status,
                "provenance_refs": [str(event.get("event_id") or "") for event in provenance_events if event.get("event_id")],
                "dedup_stats": {
                    "dedup_reference_count": dedup_ref_count,
                    "dedup_duplicate_count": dedup_duplicate_count,
                    "canonical_request_count": request_count,
                },
                "ui_dedup_stats": {
                    "raw_ui_event_count": len(ui_raw),
                    "deduped_ui_event_count": len(ui),
                    "dropped_duplicate_count": max(len(ui_raw) - len(ui), 0),
                },
                "ui_action_names": [name for name, _ in ui_action_counter.most_common(20)],
                "ui_action_counts": dict(ui_action_counter),
                "request_classification_counts": dict(request_class_counter),
                "response_classification_counts": dict(response_class_counter),
                "semantic_label_counts": dict(semantic_label_counter),
                "status_chain": status_chain,
                "ui": ui[0] if ui else None,
                "network": [e for e in ordered if str(e.get("event_type", "")).startswith("network_")],
                "cookies": cookies,
            }
        )
    return out


def build_cookie_timeline(rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    cookie_events = [r for r in rows if r.get("event_type") == "cookie_event"]
    by_domain: Dict[str, int] = defaultdict(int)
    for row in cookie_events:
        domain = str(event_payload(row).get("domain") or "unknown")
        by_domain[domain] += 1
    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "total_events": len(cookie_events),
        "by_domain": dict(sorted(by_domain.items(), key=lambda x: (-x[1], x[0]))),
        "timeline": cookie_events[-500:],
    }


def is_noise_asset(url: str, mime: str = "") -> bool:
    lower_url = url.lower()
    lower_mime = (mime or "").lower()
    critical_hints = ("identity", "auth", "oauth", "token", "session", "api", "graphql", "usage-data", "tmd")
    if any(hint in lower_url for hint in critical_hints):
        return False

    if any(ext in lower_url for ext in (".woff", ".woff2", ".ttf", ".otf", "favicon", "sprite", ".png", ".jpg", ".jpeg", ".gif")):
        return True
    if lower_mime.startswith("font/") or lower_mime.startswith("image/"):
        return True
    return False


def is_signal_host_class(host_class: str) -> bool:
    normalized = str(host_class or "").strip().lower()
    if not normalized:
        return True
    return normalized not in NON_SIGNAL_HOST_CLASSES


def classify_candidate_type(row: Dict[str, Any]) -> str:
    phase_id = event_phase_id(row)
    url = event_url(row).lower()
    operation = event_request_operation(row).lower()
    classification = event_request_classification(row).lower()
    merged = f"{url} {operation} {classification}"
    if phase_id == PHASE_PLAYBACK or any(token in merged for token in ("playback", ".m3u8", ".mpd", "resolver")):
        return "playback_candidate"
    if phase_id == PHASE_DETAIL or any(token in merged for token in ("detail", "episode", "content/")):
        return "detail_candidate"
    if phase_id == PHASE_SEARCH or any(token in merged for token in ("search", "suggest", "query=")):
        return "search_candidate"
    if phase_id == PHASE_AUTH or any(token in merged for token in ("auth", "token", "refresh", "login")):
        return "auth_or_refresh_candidate"
    return "home_candidate"


def capture_policy_for_response(row: Dict[str, Any]) -> Tuple[str, str]:
    url = event_url(row).lower()
    mime = event_mime(row).lower()
    host_class = event_host_class(row)
    phase_id = event_phase_id(row)
    source = event_source_channel(row).lower()
    operation = event_request_operation(row).lower()
    classification = event_request_classification(row).lower()
    merged = f"{url} {mime} {operation} {classification} {source}"

    if not is_signal_host_class(host_class):
        return ("skip_full_body", "host_class_noise")
    if any(token in merged for token in (".m4s", ".ts", ".mp4", ".m4a", ".webm")):
        return ("skip_full_body", "media_segment")
    if any(token in merged for token in ("analytics", "telemetry", "pixel")):
        return ("skip_full_body", "analytics_noise")
    if any(token in merged for token in ("doubleclick", "adservice", "ads.")):
        return ("skip_full_body", "ad_traffic")
    if any(token in merged for token in ("graphql", "operationname")):
        return ("store_full_body", "graphql_json_candidate")
    if any(token in merged for token in ("config", "bootstrap", "init")):
        return ("store_full_body", "provider_bootstrap_document")
    if any(token in merged for token in (".m3u8", ".mpd", "manifest")):
        return ("store_full_body", "playback_manifest")
    if phase_id in {PHASE_SEARCH, PHASE_DETAIL, PHASE_PLAYBACK} and ("json" in mime or "html" in mime):
        return ("store_full_body", f"{phase_id}_payload")
    if "json" in mime:
        return ("store_full_body", "rest_json_candidate")
    if "html" in mime and ("main_frame" in source or "page_finished" in merged):
        return ("store_full_body", "main_html_document")
    if "xml" in mime:
        return ("store_full_body", "config_or_xml_document")
    return ("skip_full_body", "non_candidate_payload")


def derive_observed_replay_elimination(rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    requests = [r for r in rows if r.get("event_type") == "network_request_event"]
    request_by_id: Dict[str, Dict[str, Any]] = {}
    response_by_request_id: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for request in requests:
        request_id = event_request_id(request)
        if request_id and request_id not in request_by_id:
            request_by_id[request_id] = request
    for response in [r for r in rows if r.get("event_type") == "network_response_event"]:
        request_id = event_request_id(response)
        if request_id:
            response_by_request_id[request_id].append(response)

    per_endpoint_success: Dict[Tuple[str, str, str, str, str], List[Dict[str, Any]]] = defaultdict(list)
    per_endpoint_failure: Dict[Tuple[str, str, str, str, str], List[Dict[str, Any]]] = defaultdict(list)
    for request_id, request in request_by_id.items():
        url = event_url(request)
        if not url:
            continue
        if event_host_class(request) in NON_SIGNAL_HOST_CLASSES:
            continue
        parsed = urlparse(url)
        operation = replay_operation_for_row(request)
        endpoint_key = (
            operation,
            event_phase_id(request),
            event_method(request) or "GET",
            parsed.netloc or event_normalized_host(request) or "unknown",
            parsed.path or event_normalized_path(request) or "/",
        )
        headers_map = event_headers(request)
        headers = {header.lower() for header in headers_map.keys() if header}
        cookie_pairs = parse_cookie_pairs(str(headers_map.get("cookie") or headers_map.get("Cookie") or ""))
        cookie_names = {name for name, _ in cookie_pairs}
        if not headers and not cookie_names:
            continue
        responses = response_by_request_id.get(request_id, [])
        if not responses:
            continue
        observation = {
            "request_id": request_id,
            "headers": headers,
            "cookies": cookie_names,
            "status_codes": sorted({event_status(resp) for resp in responses if event_status(resp)}),
        }
        if any(event_status(resp).startswith("2") for resp in responses):
            per_endpoint_success[endpoint_key].append(observation)
        else:
            per_endpoint_failure[endpoint_key].append(observation)

    out: List[Dict[str, Any]] = []
    for key in sorted(set(list(per_endpoint_success.keys()) + list(per_endpoint_failure.keys()))):
        operation, phase_id, method, host, path = key
        success_sets = per_endpoint_success.get(key, [])
        failure_sets = per_endpoint_failure.get(key, [])
        if not success_sets:
            continue

        success_header_sets = [set(item.get("headers", set())) for item in success_sets]
        success_cookie_sets = [set(item.get("cookies", set())) for item in success_sets]
        candidate_headers = set.union(*success_header_sets) if success_header_sets else set()
        candidate_cookies = set.union(*success_cookie_sets) if success_cookie_sets else set()
        intersection_headers = set.intersection(*success_header_sets) if success_header_sets else set()
        intersection_cookies = set.intersection(*success_cookie_sets) if success_cookie_sets else set()
        minimal_headers = set(intersection_headers)
        minimal_cookies = set(intersection_cookies)

        seed = max(
            success_sets,
            key=lambda item: (len(item.get("headers", set())), len(item.get("cookies", set()))),
        )
        seed_headers = set(seed.get("headers", set()))
        seed_cookies = set(seed.get("cookies", set()))
        elimination_steps: List[Dict[str, Any]] = []

        for header_name in sorted(list(candidate_headers)):
            if header_name in intersection_headers:
                elimination_steps.append(
                    {
                        "dimension": "header",
                        "name": header_name,
                        "result": "kept_mandatory",
                        "reason": "observed_in_all_success_sets",
                    }
                )
                continue
            reduced_seed = set(seed_headers)
            reduced_seed.discard(header_name)
            supported = any(reduced_seed.issubset(item.get("headers", set())) for item in success_sets)
            elimination_steps.append(
                {
                    "dimension": "header",
                    "name": header_name,
                    "result": "removed_optional" if supported else "kept_seed",
                    "reason": "safe_seed_subset_match" if supported else "missing_in_seed_subset",
                }
            )

        for cookie_name in sorted(list(candidate_cookies)):
            if cookie_name in intersection_cookies:
                elimination_steps.append(
                    {
                        "dimension": "cookie",
                        "name": cookie_name,
                        "result": "kept_mandatory",
                        "reason": "observed_in_all_success_sets",
                    }
                )
                continue
            reduced_seed = set(seed_cookies)
            reduced_seed.discard(cookie_name)
            supported = any(reduced_seed.issubset(item.get("cookies", set())) for item in success_sets)
            elimination_steps.append(
                {
                    "dimension": "cookie",
                    "name": cookie_name,
                    "result": "removed_optional" if supported else "kept_seed",
                    "reason": "safe_seed_subset_match" if supported else "missing_in_seed_subset",
                }
            )

        out.append(
            {
                "operation": operation,
                "phase_id": phase_id,
                "method": method,
                "host": host,
                "path": path,
                "successful_chain_count": len(success_sets),
                "failed_chain_count": len(failure_sets),
                "candidate_headers": sorted(list(candidate_headers)),
                "minimal_required_headers": sorted(list(minimal_headers)),
                "candidate_cookies": sorted(list(candidate_cookies)),
                "minimal_required_cookies": sorted(list(minimal_cookies)),
                "intersection_headers": sorted(list(intersection_headers)),
                "intersection_cookies": sorted(list(intersection_cookies)),
                "seed_headers": sorted(list(seed_headers)),
                "seed_cookies": sorted(list(seed_cookies)),
                "request_ids": sorted([str(item.get("request_id") or "") for item in success_sets if item.get("request_id")]),
                "elimination_mode": "observed_replay_elimination_conservative",
                "validation_mode": "observed_success_chains",
                "elimination_steps": elimination_steps,
            }
        )
    return out


def replay_http_execute(
    *,
    url: str,
    method: str,
    headers: Dict[str, str],
    body: bytes,
    timeout_ms: int,
) -> Dict[str, Any]:
    request = urllib.request.Request(
        url=url,
        data=body if method in {"POST", "PUT", "PATCH", "DELETE"} else None,
        method=method,
    )
    for key, value in headers.items():
        if not key:
            continue
        request.add_header(key, value)

    timeout_seconds = max(timeout_ms, 1000) / 1000.0
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            status_code = int(response.getcode() or 0)
            return {
                "success": 200 <= status_code < 300,
                "status_code": status_code,
                "error": "",
            }
    except urllib.error.HTTPError as exc:
        return {
            "success": False,
            "status_code": int(getattr(exc, "code", 0) or 0),
            "error": str(exc.reason or exc),
        }
    except Exception as exc:
        return {
            "success": False,
            "status_code": 0,
            "error": str(exc),
        }


def is_non_replayable_host(host: str) -> bool:
    normalized = str(host or "").strip().lower()
    if not normalized:
        return True
    return (
        normalized == "example.com"
        or normalized.endswith(".example.com")
        or normalized.endswith(".example")
        or normalized.endswith(".invalid")
        or normalized.endswith(".test")
    )


def replay_requirements_from_endpoint_sets(
    endpoint_sets: List[Dict[str, Any]],
    inference_mode: str,
) -> Dict[str, Any]:
    operations: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for item in endpoint_sets:
        if not isinstance(item, dict):
            continue
        operation = str(item.get("operation") or "home")
        operations[operation].append(
            {
                "phase_id": item.get("phase_id"),
                "method": item.get("method"),
                "host": item.get("host"),
                "path": item.get("path"),
                "observed_success_count": int(item.get("successful_chain_count") or 0),
                "observed_failure_count": int(item.get("failed_chain_count") or 0),
                "required_headers": list(item.get("minimal_required_headers") or []),
                "required_cookies": list(item.get("minimal_required_cookies") or []),
                "candidate_headers": list(item.get("candidate_headers") or []),
                "candidate_cookies": list(item.get("candidate_cookies") or []),
                "elimination_mode": item.get("elimination_mode"),
                "validation_mode": item.get("validation_mode"),
                "elimination_steps": list(item.get("elimination_steps") or []),
            }
        )
    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "inference_mode": inference_mode,
        "operations": {operation: endpoints for operation, endpoints in sorted(operations.items())},
    }


def build_required_cookies_report_for_endpoint_sets(
    rows: List[Dict[str, Any]],
    endpoint_sets: List[Dict[str, Any]],
    inference_mode: str,
) -> Dict[str, Any]:
    base = build_required_cookies_report(rows)
    base["inference_mode"] = inference_mode
    base["endpoint_minimal_sets"] = [
        {
            "operation": item.get("operation"),
            "phase_id": item.get("phase_id"),
            "method": item.get("method"),
            "host": item.get("host"),
            "path": item.get("path"),
            "minimal_required_cookies": list(item.get("minimal_required_cookies") or []),
            "candidate_cookies": list(item.get("candidate_cookies") or []),
            "elimination_mode": item.get("elimination_mode"),
            "validation_mode": item.get("validation_mode"),
            "elimination_steps": [
                step
                for step in list(item.get("elimination_steps") or [])
                if isinstance(step, dict) and str(step.get("dimension") or "") == "cookie"
            ],
        }
        for item in endpoint_sets
        if isinstance(item, dict)
    ]
    return base


def build_required_headers_active_replay(
    rows: List[Dict[str, Any]],
    timeout_ms: int = 15_000,
    allow_unsafe_methods: bool = False,
    replay_executor: Optional[Any] = None,
) -> Dict[str, Any]:
    observed = derive_observed_replay_elimination(rows)
    passive = build_required_headers(rows, active_elimination=False)
    request_rows = [row for row in rows if row.get("event_type") == "network_request_event"]
    request_by_id: Dict[str, Dict[str, Any]] = {}
    for row in request_rows:
        request_id = event_request_id(row)
        if request_id and request_id not in request_by_id:
            request_by_id[request_id] = row

    def pick_seed_request(endpoint: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        for request_id in endpoint.get("request_ids", []):
            candidate = request_by_id.get(str(request_id))
            if candidate is not None:
                return candidate
        method = str(endpoint.get("method") or "GET").upper()
        host = str(endpoint.get("host") or "")
        path = str(endpoint.get("path") or "/")
        phase_id = str(endpoint.get("phase_id") or "")
        fallback: Optional[Dict[str, Any]] = None
        fallback_header_count = -1
        for row in request_rows:
            if event_host_class(row) in NON_SIGNAL_HOST_CLASSES:
                continue
            if (event_method(row) or "GET") != method:
                continue
            if event_normalized_host(row) != host:
                continue
            if event_normalized_path(row) != path:
                continue
            if event_phase_id(row) != phase_id:
                continue
            count = len(event_headers(row))
            if count > fallback_header_count:
                fallback = row
                fallback_header_count = count
        return fallback

    executor = replay_executor or replay_http_execute
    endpoint_sets: List[Dict[str, Any]] = []
    for endpoint in observed:
        item = dict(endpoint)
        item["elimination_steps"] = list(item.get("elimination_steps") or [])
        item["replay_attempted"] = False

        seed_row = pick_seed_request(item)
        if seed_row is None:
            item["validation_mode"] = "observed_success_chains_no_seed_request"
            item["elimination_mode"] = "observed_replay_elimination_conservative_fallback"
            endpoint_sets.append(item)
            continue

        method = (event_method(seed_row) or str(item.get("method") or "GET")).upper()
        if method not in {"GET", "HEAD", "OPTIONS"} and not allow_unsafe_methods:
            item["validation_mode"] = "observed_success_chains_unsafe_method_skipped"
            item["elimination_mode"] = "observed_replay_elimination_conservative_fallback"
            endpoint_sets.append(item)
            continue

        headers_raw = event_headers(seed_row)
        lowered_headers = {str(key).lower(): str(value) for key, value in headers_raw.items() if key}
        cookie_pairs = parse_cookie_pairs(str(lowered_headers.get("cookie") or ""))
        cookie_values = {name: value for name, value in cookie_pairs if name}
        header_values = {
            name: value
            for name, value in lowered_headers.items()
            if name not in {"cookie", "content-length", "host", "connection", "proxy-connection", "transfer-encoding"}
            and not name.startswith(":")
        }

        candidate_headers = set(str(name).lower() for name in item.get("candidate_headers", []) if name)
        candidate_cookies = set(str(name).lower() for name in item.get("candidate_cookies", []) if name)
        required_headers = set(header_values.keys())
        required_cookies = set(cookie_values.keys())
        if candidate_headers:
            required_headers &= candidate_headers
        if candidate_cookies:
            required_cookies &= candidate_cookies

        body_text = str(event_payload(seed_row).get("body") or event_payload(seed_row).get("body_preview") or "")
        body_bytes = body_text.encode("utf-8", errors="replace") if body_text else b""
        url = event_url(seed_row)
        if not url:
            item["validation_mode"] = "observed_success_chains_missing_url"
            item["elimination_mode"] = "observed_replay_elimination_conservative_fallback"
            endpoint_sets.append(item)
            continue
        parsed_seed = urlparse(url)
        seed_scheme = str(parsed_seed.scheme or "").lower()
        if seed_scheme not in {"http", "https"}:
            item["validation_mode"] = "active_http_replay_skipped_unsupported_scheme"
            item["elimination_mode"] = "observed_replay_elimination_conservative_fallback"
            endpoint_sets.append(item)
            continue
        seed_host = normalize_host(parsed_seed.netloc)
        if is_non_replayable_host(seed_host):
            item["validation_mode"] = "active_http_replay_skipped_non_replayable_host"
            item["elimination_mode"] = "observed_replay_elimination_conservative_fallback"
            endpoint_sets.append(item)
            continue

        def execute_subset(selected_headers: set, selected_cookies: set) -> Dict[str, Any]:
            subset_headers = {name: header_values[name] for name in sorted(selected_headers) if name in header_values}
            if selected_cookies:
                subset_headers["cookie"] = "; ".join(
                    [f"{name}={cookie_values.get(name, '')}" for name in sorted(selected_cookies) if name in cookie_values]
                )
            return executor(
                url=url,
                method=method,
                headers=subset_headers,
                body=body_bytes,
                timeout_ms=timeout_ms,
            )

        baseline_result = execute_subset(set(required_headers), set(required_cookies))
        item["replay_attempted"] = True
        item["replay_baseline"] = baseline_result
        if not bool(baseline_result.get("success")):
            item["validation_mode"] = "active_http_replay_baseline_failed_fallback_observed"
            item["elimination_mode"] = "observed_replay_elimination_conservative_fallback"
            item["elimination_steps"].append(
                {
                    "dimension": "baseline",
                    "name": "full_context",
                    "result": "failed",
                    "status_code": baseline_result.get("status_code"),
                    "error": baseline_result.get("error"),
                }
            )
            endpoint_sets.append(item)
            continue

        item["validation_mode"] = "active_http_replay"
        item["elimination_mode"] = "active_http_replay_iterative"
        item["elimination_steps"].append(
            {
                "dimension": "baseline",
                "name": "full_context",
                "result": "passed",
                "status_code": baseline_result.get("status_code"),
            }
        )

        for header_name in sorted(list(required_headers)):
            reduced_headers = set(required_headers)
            reduced_headers.discard(header_name)
            result = execute_subset(reduced_headers, set(required_cookies))
            removable = bool(result.get("success"))
            if removable:
                required_headers = reduced_headers
            item["elimination_steps"].append(
                {
                    "dimension": "header",
                    "name": header_name,
                    "result": "removed" if removable else "kept",
                    "status_code": result.get("status_code"),
                    "error": result.get("error"),
                }
            )

        for cookie_name in sorted(list(required_cookies)):
            reduced_cookies = set(required_cookies)
            reduced_cookies.discard(cookie_name)
            result = execute_subset(set(required_headers), reduced_cookies)
            removable = bool(result.get("success"))
            if removable:
                required_cookies = reduced_cookies
            item["elimination_steps"].append(
                {
                    "dimension": "cookie",
                    "name": cookie_name,
                    "result": "removed" if removable else "kept",
                    "status_code": result.get("status_code"),
                    "error": result.get("error"),
                }
            )

        item["minimal_required_headers"] = sorted(list(required_headers))
        item["minimal_required_cookies"] = sorted(list(required_cookies))
        item["replay_seed_request_id"] = event_request_id(seed_row)
        endpoint_sets.append(item)

    payload = dict(passive)
    payload["schema_version"] = 1
    payload["generated_at_utc"] = utc_now()
    payload["inference_mode"] = "active_http_replay"
    payload["active_replay_timeout_ms"] = timeout_ms
    payload["allow_unsafe_methods"] = bool(allow_unsafe_methods)
    payload["endpoint_minimal_sets"] = endpoint_sets
    payload["active_replay_endpoint_count"] = len(endpoint_sets)
    payload["active_replay_attempted_count"] = len([item for item in endpoint_sets if item.get("replay_attempted")])
    payload["active_replay_success_count"] = len(
        [item for item in endpoint_sets if str(item.get("validation_mode") or "") == "active_http_replay"]
    )
    return payload


def build_required_headers(rows: List[Dict[str, Any]], active_elimination: bool = False) -> Dict[str, Any]:
    network_requests = [r for r in rows if r.get("event_type") == "network_request_event"]
    counter: Dict[str, Counter[str]] = defaultdict(Counter)
    cookie_counter: Dict[str, Counter[str]] = defaultdict(Counter)

    for row in network_requests:
        url = event_url(row)
        if is_noise_asset(url=url, mime=event_mime(row)):
            continue
        host_class = event_host_class(row)
        if host_class in NON_SIGNAL_HOST_CLASSES:
            continue
        host = event_normalized_host(row) or urlparse(url).netloc or "unknown"
        headers = event_headers(row)
        for h in headers.keys():
            counter[host][h.lower()] += 1
        cookie_raw = str(headers.get("cookie") or headers.get("Cookie") or "")
        if cookie_raw:
            for chunk in cookie_raw.split(";"):
                key = str(chunk.split("=", 1)[0]).strip().lower()
                if key:
                    cookie_counter[host][key] += 1

    active_sets: List[Dict[str, Any]] = []
    if active_elimination:
        active_sets = derive_observed_replay_elimination(rows)

    out = {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "inference_mode": "active_replay_elimination" if active_elimination else "passive_frequency",
        "hosts": {
            host: {
                "top_headers": [{"header": k, "count": v} for k, v in headers.most_common(30)],
                "top_cookies": [{"cookie": k, "count": v} for k, v in cookie_counter.get(host, Counter()).most_common(30)],
            }
            for host, headers in sorted(counter.items())
        },
        "endpoint_minimal_sets": active_sets,
    }
    return out


def build_required_cookies_report(rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    requests = [row for row in rows if row.get("event_type") == "network_request_event"]
    per_host: Dict[str, Counter[str]] = defaultdict(Counter)
    for row in requests:
        if not is_signal_host_class(event_host_class(row)):
            continue
        host = event_normalized_host(row) or "unknown"
        headers = event_headers(row)
        cookie_header = str(headers.get("Cookie") or headers.get("cookie") or "")
        for cookie_name in parse_cookie_names(cookie_header):
            per_host[host][cookie_name] += 1
    active_sets = derive_observed_replay_elimination(rows)
    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "hosts": {
            host: [{"cookie": cookie_name, "count": count} for cookie_name, count in counter.most_common(50)]
            for host, counter in sorted(per_host.items())
        },
        "endpoint_minimal_sets": [
            {
                "operation": item.get("operation"),
                "phase_id": item.get("phase_id"),
                "method": item.get("method"),
                "host": item.get("host"),
                "path": item.get("path"),
                "minimal_required_cookies": item.get("minimal_required_cookies", []),
                "candidate_cookies": item.get("candidate_cookies", []),
                "elimination_mode": item.get("elimination_mode"),
                "validation_mode": item.get("validation_mode"),
            }
            for item in active_sets
        ],
    }


def build_endpoint_candidates(rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    request_rows_all = [r for r in rows if r.get("event_type") == "network_request_event"]
    response_rows_all = [r for r in rows if r.get("event_type") == "network_response_event"]

    request_rows: List[Dict[str, Any]] = []
    seen_request_ids: set = set()
    for row in request_rows_all:
        request_id = event_request_id(row)
        if request_id and request_id in seen_request_ids:
            continue
        if request_id:
            seen_request_ids.add(request_id)
        request_rows.append(row)

    response_by_request_id: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for response in response_rows_all:
        request_id = event_request_id(response)
        if request_id:
            response_by_request_id[request_id].append(response)

    grouped: Dict[Tuple[str, str, str, str, str], List[Dict[str, Any]]] = defaultdict(list)
    for row in request_rows:
        url = event_url(row)
        if not url:
            continue
        host_class = event_host_class(row)
        phase_id = event_phase_id(row)
        if host_class in NON_SIGNAL_HOST_CLASSES:
            continue
        if phase_id not in SCORABLE_PHASES:
            continue
        if is_noise_asset(url=url, mime=event_mime(row)):
            continue
        host = event_normalized_host(row)
        path = event_normalized_path(row)
        method = event_method(row) or "GET"
        operation = event_request_operation(row) or str(event_payload(row).get("graphql_operation_name") or "")
        grouped[(phase_id, method, host, path, operation)].append(row)

    def candidate_type_for(phase_id: str, path: str, operation: str) -> str:
        lower = f"{phase_id} {path} {operation}".lower()
        if phase_id == PHASE_HOME:
            return "home_candidate"
        if phase_id == PHASE_SEARCH:
            return "search_candidate"
        if phase_id == PHASE_DETAIL:
            return "detail_candidate"
        if phase_id == PHASE_PLAYBACK or any(token in lower for token in ("playback", "resolver", ".m3u8", ".mpd")):
            return "playback_candidate"
        if phase_id == PHASE_AUTH or any(token in lower for token in ("auth", "token", "refresh", "login")):
            return "auth_or_refresh_candidate"
        return "home_candidate"

    candidates = []
    for (phase_id, method, host, path, operation), items in grouped.items():
        count = len(items)
        request_ids = [event_request_id(item) for item in items if event_request_id(item)]
        response_events = []
        for request_id in request_ids:
            response_events.extend(response_by_request_id.get(request_id, []))
        successful = [resp for resp in response_events if event_status(resp).startswith("2")]
        body_present = [
            resp
            for resp in successful
            if bool(event_body_preview(resp))
            or bool(event_payload(resp).get("body_ref"))
            or bool(event_response_store_path(resp))
        ]
        full_body_present = [resp for resp in body_present if not event_capture_truncated(resp)]
        truncated_responses = [resp for resp in successful if event_capture_truncated(resp)]
        graphql_op = str(event_payload(items[0]).get("graphql_operation_name") or "")
        repeated_bonus = min(count, 5) / 5.0
        phase_bonus = 1.0 if phase_id in SCORABLE_PHASES else 0.0
        response_bonus = 1.0 if successful else 0.0
        body_bonus = 1.0 if full_body_present else 0.0
        graphql_bonus = 1.0 if graphql_op else 0.0
        truncation_penalty = 0.15 if truncated_responses else 0.0
        score = round(
            (0.30 * repeated_bonus)
            + (0.20 * phase_bonus)
            + (0.25 * response_bonus)
            + (0.15 * body_bonus)
            + (0.10 * graphql_bonus),
            4,
        )
        score = round(
            max(
                0.0,
                score - truncation_penalty,
            ),
            4,
        )

        template_headers = Counter()
        for item in items:
            for key in event_headers(item).keys():
                if key:
                    template_headers[str(key).lower()] += 1
        common_headers = [name for name, c in template_headers.items() if c == count]

        candidate = {
            "candidate_type": candidate_type_for(phase_id, path, operation),
            "phase_id": phase_id,
            "target_site_id": event_target_site_id(items[0]),
            "host_class": event_host_class(items[0]),
            "method": method,
            "host": host,
            "path": path,
            "request_operation": operation,
            "graphql_operation_name": graphql_op,
            "request_fingerprint": event_request_fingerprint(items[0]),
            "count": count,
            "score": score,
            "evidence": {
                "repeated_request_templates": count,
                "phase_alignment": phase_id in SCORABLE_PHASES,
                "response_success_count": len(successful),
                "response_body_presence": len(body_present),
                "response_full_body_presence": len(full_body_present),
                "response_truncated_count": len(truncated_responses),
                "graphql_operation_stability": bool(graphql_op),
                "playback_or_auth_linkage": phase_id in {PHASE_PLAYBACK, PHASE_AUTH},
            },
            "request_template": {
                "headers_required_hint": sorted(common_headers),
                "query_params": event_payload(items[0]).get("query_params") or {},
            },
        }
        candidates.append(candidate)

    candidates.sort(key=lambda item: (float(item.get("score") or 0.0), int(item.get("count") or 0)), reverse=True)
    by_type: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for candidate in candidates:
        by_type[str(candidate.get("candidate_type") or "home_candidate")].append(candidate)

    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "candidates": candidates[:500],
        "by_candidate_type": {key: value[:120] for key, value in sorted(by_type.items())},
    }


def read_response_store_payload(row: Dict[str, Any], runtime_dir: Optional[pathlib.Path]) -> str:
    if runtime_dir is None:
        return ""
    rel_path = event_response_store_path(row)
    if not rel_path:
        return ""
    candidate = runtime_dir / "response_store" / rel_path
    if not candidate.exists() or not candidate.is_file():
        return ""
    try:
        raw = candidate.read_bytes()
    except Exception:
        return ""
    if not raw:
        return ""
    try:
        return raw.decode("utf-8", errors="replace")
    except Exception:
        return ""


def iter_json_leaf_paths(value: Any, prefix: str = "") -> Iterable[Tuple[str, Any]]:
    if isinstance(value, dict):
        for key, child in value.items():
            next_prefix = f"{prefix}.{key}" if prefix else str(key)
            yield from iter_json_leaf_paths(child, next_prefix)
        return
    if isinstance(value, list):
        for idx, child in enumerate(value[:20]):
            next_prefix = f"{prefix}[{idx}]" if prefix else f"[{idx}]"
            yield from iter_json_leaf_paths(child, next_prefix)
        return
    yield (prefix or "$", value)


def field_status_from_hits(hits: List[Dict[str, Any]], derived: bool = False) -> str:
    if hits:
        return "derived" if derived else "directly_observed"
    return "missing"


def field_confidence(hit_count: int, derived: bool = False) -> float:
    if hit_count <= 0:
        return 0.0
    base = 0.55 if derived else 0.7
    confidence = base + (0.08 * min(hit_count, 3))
    return round(min(confidence, 0.99), 4)


def html_extract_title(body: str) -> str:
    match = re.search(r"<title[^>]*>(.*?)</title>", body, re.IGNORECASE | re.DOTALL)
    if not match:
        return ""
    text = re.sub(r"\s+", " ", match.group(1)).strip()
    return text[:200]


def html_extract_og_image(body: str) -> str:
    match = re.search(
        r'<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']+)["\']',
        body,
        re.IGNORECASE,
    )
    if not match:
        return ""
    return str(match.group(1)).strip()


def build_field_matrix(rows: List[Dict[str, Any]], runtime_dir: Optional[pathlib.Path] = None) -> Dict[str, Any]:
    response_events = [r for r in rows if r.get("event_type") == "network_response_event"]
    key_counter: Counter[str] = Counter()
    parsed_events = 0
    skipped_non_target = 0
    skipped_truncated = 0
    truncation_warnings: List[Dict[str, Any]] = []
    field_hits: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    search_mapping_samples: List[Dict[str, Any]] = []
    extraction_events: List[Dict[str, Any]] = []

    def confidence_summary_for_attempt(extracted_field_count: int) -> str:
        if extracted_field_count <= 0:
            return "none"
        if extracted_field_count <= 2:
            return "low"
        if extracted_field_count <= 5:
            return "medium"
        return "high"

    for row in response_events:
        host_class = event_host_class(row)
        explicit_host_class_present = any(
            isinstance(event_payload(row).get(key), str) and str(event_payload(row).get(key)).strip()
            for key in ("host_class", "hostClass")
        )
        if host_class == HOST_CLASS_BACKGROUND_NOISE and not explicit_host_class_present:
            url = event_url(row)
            _, host, path, _ = normalized_url_components(url)
            if host and not looks_like_google_noise(host, url) and not looks_like_analytics_noise(host, url):
                if looks_like_playback(
                    url=url,
                    path=path,
                    operation=event_request_operation(row),
                    classification=event_request_classification(row),
                    mime_type=event_mime(row),
                ):
                    host_class = HOST_CLASS_TARGET_PLAYBACK
                elif looks_like_asset(url=url, path=path, source=event_source(row), mime_type=event_mime(row)):
                    host_class = HOST_CLASS_TARGET_ASSET
                elif looks_like_target_api(
                    url=url,
                    path=path,
                    operation=event_request_operation(row),
                    classification=event_request_classification(row),
                    mime_type=event_mime(row),
                ):
                    host_class = HOST_CLASS_TARGET_API
                else:
                    host_class = HOST_CLASS_TARGET_DOCUMENT
        if host_class not in EXTRACTION_ELIGIBLE_HOST_CLASSES:
            skipped_non_target += 1
            continue
        event_id = str(row.get("event_id") or "")
        source_ref = str(event_payload(row).get("body_ref") or event_response_store_path(row) or event_id)
        phase_id = event_phase_id(row)
        body_capture_policy = event_body_capture_policy(row)
        candidate_relevance = event_candidate_relevance(row)
        capture_truncated = event_capture_truncated(row)
        truncation_reason = event_truncation_reason(row)
        capture_failure = str(event_payload(row).get("capture_failure") or "").strip()
        extraction_kind = "unknown"
        extracted_field_count = 0
        if capture_truncated:
            skipped_truncated += 1
            truncation_warnings.append(
                {
                    "event_id": event_id,
                    "request_id": event_request_id(row),
                    "response_id": event_response_id(row),
                    "reason": truncation_reason or TRUNCATION_REASON_BODY_SIZE_LIMIT,
                    "body_capture_policy": body_capture_policy,
                    "candidate_relevance": candidate_relevance,
                }
            )
            extraction_events.append(
                {
                    "source_ref": source_ref,
                    "phase_id": phase_id,
                    "host_class": host_class,
                    "extraction_kind": "truncated_body",
                    "success": False,
                    "extracted_field_count": 0,
                    "confidence_summary": "none",
                    "event_id": event_id,
                }
            )
            continue
        if capture_failure:
            truncation_warnings.append(
                {
                    "event_id": event_id,
                    "request_id": event_request_id(row),
                    "response_id": event_response_id(row),
                    "reason": capture_failure,
                    "body_capture_policy": body_capture_policy,
                    "candidate_relevance": candidate_relevance,
                }
            )
        body = read_response_store_payload(row, runtime_dir)
        if not body:
            body = event_body_preview(row)
        if not body:
            extraction_events.append(
                {
                    "source_ref": source_ref,
                    "phase_id": phase_id,
                    "host_class": host_class,
                    "extraction_kind": "missing_body",
                    "success": False,
                    "extracted_field_count": 0,
                    "confidence_summary": "none",
                    "event_id": event_id,
                }
            )
            continue
        try:
            extraction_kind = "json"
            loaded = json.loads(body)
            parsed_events += 1
            for key in flatten_keys(loaded):
                key_counter[key] += 1

            leaves = list(iter_json_leaf_paths(loaded))
            for key, value in leaves:
                lower_key = key.lower()
                str_value = value if isinstance(value, str) else ""
                str_value_lower = str(str_value).lower()
                if any(token in lower_key for token in ("title", "headline", "name")) and str_value:
                    field_hits["title"].append({"event_id": event_id, "path": key, "value": str_value[:200], "source": "json"})
                    extracted_field_count += 1
                if any(token in lower_key for token in ("subtitle", "sub_title", "teaser_title")) and str_value:
                    field_hits["subtitle"].append({"event_id": event_id, "path": key, "value": str_value[:200], "source": "json"})
                    extracted_field_count += 1
                if any(token in lower_key for token in ("description", "summary", "teasertext")) and str_value:
                    field_hits["description"].append({"event_id": event_id, "path": key, "value": str_value[:300], "source": "json"})
                    extracted_field_count += 1
                if any(token in lower_key for token in ("image", "poster", "thumbnail")) and str_value and str_value_lower.startswith("http"):
                    field_hits["image/poster"].append({"event_id": event_id, "path": key, "value": str_value[:300], "source": "json"})
                    extracted_field_count += 1
                if any(token in lower_key for token in ("canonicalid", "canonical_id", "contentid", "content_id")):
                    field_hits["canonical id"].append({"event_id": event_id, "path": key, "value": str(value)[:120], "source": "json"})
                    extracted_field_count += 1
                if any(token in lower_key for token in ("collectionid", "collection_id", "seriesid", "series_id", "railid", "rail_id")):
                    field_hits["collection id"].append({"event_id": event_id, "path": key, "value": str(value)[:120], "source": "json"})
                    extracted_field_count += 1
                if any(token in lower_key for token in ("type", "itemtype", "item_type", "teasertype", "teaser_type")) and str_value:
                    field_hits["teaser/item type"].append({"event_id": event_id, "path": key, "value": str_value[:120], "source": "json"})
                    extracted_field_count += 1
                if any(token in lower_key for token in ("playback", "manifest", "stream", "drm", "resolver")):
                    field_hits["playback hints"].append({"event_id": event_id, "path": key, "value": str(value)[:160], "source": "json"})
                    extracted_field_count += 1
                if any(token in lower_key for token in ("section", "rail", "category")) and str_value:
                    field_hits["section/rail names"].append({"event_id": event_id, "path": key, "value": str_value[:120], "source": "json"})
                    extracted_field_count += 1

            if isinstance(loaded, dict):
                for root_key in ("results", "items", "hits"):
                    value = loaded.get(root_key)
                    if isinstance(value, list) and value:
                        sample = value[0]
                        if isinstance(sample, dict):
                            search_mapping_samples.append(
                                {
                                    "event_id": event_id,
                                    "root": root_key,
                                    "sample_keys": sorted(list(sample.keys()))[:20],
                                }
                            )
                            extracted_field_count += 1
        except Exception:
            extraction_kind = "html"
            title = html_extract_title(body)
            if title:
                field_hits["title"].append(
                    {
                        "event_id": str(row.get("event_id") or ""),
                        "path": "html.title",
                        "value": title,
                        "source": "html",
                    }
                )
                extracted_field_count += 1
            og_image = html_extract_og_image(body)
            if og_image:
                field_hits["image/poster"].append(
                    {
                        "event_id": str(row.get("event_id") or ""),
                        "path": "html.meta.og:image",
                        "value": og_image,
                        "source": "html",
                    }
                )
                extracted_field_count += 1

        extraction_events.append(
            {
                "source_ref": source_ref,
                "phase_id": phase_id,
                "host_class": host_class,
                "extraction_kind": extraction_kind,
                "success": extracted_field_count > 0,
                "extracted_field_count": extracted_field_count,
                "confidence_summary": confidence_summary_for_attempt(extracted_field_count),
                "event_id": event_id,
            }
        )

    required_fields = [
        "title",
        "subtitle",
        "description",
        "image/poster",
        "canonical id",
        "collection id",
        "teaser/item type",
        "playback hints",
        "section/rail names",
        "search result mapping",
    ]

    field_matrix_rows = []
    for field_name in required_fields:
        hits = field_hits.get(field_name, [])
        if field_name == "search result mapping":
            mapped_hits = [{"source": "json", **item} for item in search_mapping_samples[:20]]
            status = field_status_from_hits(mapped_hits, derived=False)
            confidence = field_confidence(len(mapped_hits), derived=False)
            field_matrix_rows.append(
                {
                    "field": field_name,
                    "status": status,
                    "confidence": confidence,
                    "sources": mapped_hits[:20],
                    "sample_values": [str(item.get("sample_keys") or "") for item in mapped_hits[:5]],
                }
            )
            continue

        direct_hits = [item for item in hits if item.get("source") == "json"]
        derived_hits = [item for item in hits if item.get("source") != "json"]
        derived_only = not direct_hits and bool(derived_hits)
        status = field_status_from_hits(hits, derived=derived_only)
        confidence = field_confidence(len(hits), derived=derived_only)
        field_matrix_rows.append(
            {
                "field": field_name,
                "status": status,
                "confidence": confidence,
                "sources": hits[:20],
                "sample_values": [str(item.get("value") or "") for item in hits[:5]],
            }
        )

    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "parsed_response_events": parsed_events,
        "total_response_events": len(response_events),
        "skipped_non_target_events": skipped_non_target,
        "skipped_truncated_events": skipped_truncated,
        "truncation_warnings": truncation_warnings,
        "extraction_event_count": len(extraction_events),
        "extraction_success_count": len([item for item in extraction_events if bool(item.get("success"))]),
        "extraction_events": extraction_events,
        "fields": field_matrix_rows,
        "keys": [{"field": key, "count": count} for key, count in key_counter.most_common(500)],
    }


def build_profile_draft(rows: List[Dict[str, Any]], endpoint_candidates: Dict[str, Any], required_headers: Dict[str, Any]) -> Dict[str, Any]:
    run_id = "unknown"
    for row in rows:
        rid = row.get("run_id")
        if isinstance(rid, str) and rid:
            run_id = rid
            break

    primary_host = None
    candidates = endpoint_candidates.get("candidates", [])
    if candidates:
        primary_host = candidates[0].get("host")

    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "run_id": run_id,
        "profile_name": f"mapper-draft-{run_id}",
        "primary_host": primary_host,
        "capability_class": "HYBRID",
        "endpoint_candidates": candidates[:50],
        "required_headers_hint": required_headers.get("hosts", {}).get(primary_host or "", {}),
    }


def build_replay_seed(rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    requests = [r for r in rows if r.get("event_type") == "network_request_event"]
    selected = []
    for row in requests[:200]:
        payload = event_payload(row)
        selected.append(
            {
                "event_id": row.get("event_id"),
                "request_id": event_request_id(row),
                "url": event_url(row),
                "normalized_scheme": event_normalized_scheme(row),
                "normalized_host": event_normalized_host(row),
                "normalized_path": event_normalized_path(row),
                "target_site_id": event_target_site_id(row),
                "method": event_method(row),
                "headers": event_headers(row),
                "headers_reduced": payload.get("headers_reduced") or reduce_headers(event_headers(row)),
                "query_params": payload.get("query_params") or parse_query_params(event_url(row)),
                "body_preview": payload.get("body_preview"),
                "phase_id": event_phase_id(row),
                "host_class": event_host_class(row),
            }
        )
    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "steps": selected,
    }


def replay_operation_for_row(row: Dict[str, Any]) -> str:
    phase_id = event_phase_id(row)
    path = event_normalized_path(row).lower()
    if phase_id == PHASE_HOME:
        return "home"
    if phase_id == PHASE_SEARCH:
        return "search"
    if phase_id == PHASE_DETAIL:
        return "detail"
    if phase_id == PHASE_PLAYBACK or any(token in path for token in ("playback", "resolver", ".m3u8", ".mpd")):
        return "playback_resolver"
    if phase_id == PHASE_AUTH or any(token in path for token in ("auth", "token", "refresh", "login")):
        return "auth_or_refresh"
    return "home"


def build_replay_requirements_observed(rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    active_sets = derive_observed_replay_elimination(rows)
    operations: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for item in active_sets:
        operation = str(item.get("operation") or "home")
        operations[operation].append(
            {
                "phase_id": item.get("phase_id"),
                "method": item.get("method"),
                "host": item.get("host"),
                "path": item.get("path"),
                "observed_success_count": int(item.get("successful_chain_count") or 0),
                "observed_failure_count": int(item.get("failed_chain_count") or 0),
                "required_headers": list(item.get("minimal_required_headers") or []),
                "required_cookies": list(item.get("minimal_required_cookies") or []),
                "candidate_headers": list(item.get("candidate_headers") or []),
                "candidate_cookies": list(item.get("candidate_cookies") or []),
                "elimination_mode": item.get("elimination_mode"),
                "validation_mode": item.get("validation_mode"),
                "elimination_steps": list(item.get("elimination_steps") or []),
            }
        )

    if not operations:
        requests = [row for row in rows if row.get("event_type") == "network_request_event"]
        grouped: Dict[str, Dict[Tuple[str, str, str], List[Dict[str, set]]]] = defaultdict(lambda: defaultdict(list))
        for request in requests:
            if event_host_class(request) in NON_SIGNAL_HOST_CLASSES:
                continue
            operation = replay_operation_for_row(request)
            key = (
                event_method(request) or "GET",
                event_normalized_host(request) or "unknown",
                event_normalized_path(request) or "/",
            )
            headers = {str(header).lower() for header in event_headers(request).keys() if header}
            cookie_names = set()
            cookie_raw = str(event_headers(request).get("cookie") or event_headers(request).get("Cookie") or "")
            for cookie in parse_cookie_names(cookie_raw):
                cookie_names.add(cookie.lower())
            grouped[operation][key].append({"headers": headers, "cookies": cookie_names})

        for operation, endpoints in sorted(grouped.items()):
            endpoint_rows = []
            for (method, host, path), observations in sorted(endpoints.items()):
                header_sets = [item["headers"] for item in observations if item["headers"]]
                cookie_sets = [item["cookies"] for item in observations if item["cookies"]]
                minimal_headers = set.intersection(*header_sets) if header_sets else set()
                minimal_cookies = set.intersection(*cookie_sets) if cookie_sets else set()
                endpoint_rows.append(
                    {
                        "method": method,
                        "host": host,
                        "path": path,
                        "observed_success_count": len(observations),
                        "required_headers": sorted(list(minimal_headers)),
                        "required_cookies": sorted(list(minimal_cookies)),
                        "elimination_mode": "passive_intersection_fallback",
                        "validation_mode": "no_response_chain_data",
                    }
                )
            operations[operation] = endpoint_rows

    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "inference_mode": "observed_replay_elimination",
        "operations": {operation: endpoints for operation, endpoints in sorted(operations.items())},
    }


def build_replay_requirements(
    rows: List[Dict[str, Any]],
    prefer_active_replay: bool = True,
    timeout_ms: int = ACTIVE_REPLAY_ROLLUP_TIMEOUT_MS,
    allow_unsafe_methods: bool = False,
) -> Dict[str, Any]:
    if prefer_active_replay:
        active_payload = build_required_headers_active_replay(
            rows,
            timeout_ms=max(int(timeout_ms or 0), 500),
            allow_unsafe_methods=allow_unsafe_methods,
        )
        endpoint_sets = list(active_payload.get("endpoint_minimal_sets") or [])
        replay_payload = replay_requirements_from_endpoint_sets(
            endpoint_sets,
            inference_mode=str(active_payload.get("inference_mode") or "active_http_replay"),
        )
        replay_payload["active_replay_timeout_ms"] = max(int(timeout_ms or 0), 500)
        replay_payload["allow_unsafe_methods"] = bool(allow_unsafe_methods)
        replay_payload = annotate_replay_truncation_visibility(replay_payload, rows)
        if replay_payload.get("operations"):
            return replay_payload

    observed = build_replay_requirements_observed(rows)
    if prefer_active_replay:
        observed["inference_mode"] = "active_http_replay_fallback_observed"
        observed["active_replay_timeout_ms"] = max(int(timeout_ms or 0), 500)
        observed["allow_unsafe_methods"] = bool(allow_unsafe_methods)
    return annotate_replay_truncation_visibility(observed, rows)


def annotate_replay_truncation_visibility(payload: Dict[str, Any], rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    operations = payload.get("operations")
    if not isinstance(operations, dict):
        payload["truncation_summary"] = {"total_truncated_responses": 0, "required_body_failures": 0}
        return payload

    request_index = {
        event_request_id(row): row
        for row in rows
        if row.get("event_type") == "network_request_event" and event_request_id(row)
    }
    endpoint_truncation_counter: Counter[Tuple[str, str, str, str]] = Counter()
    endpoint_failure_counter: Counter[Tuple[str, str, str, str]] = Counter()
    total_truncated = 0
    required_failures = 0

    for response in [row for row in rows if row.get("event_type") == "network_response_event"]:
        request_id = event_request_id(response)
        request_row = request_index.get(request_id)
        operation = replay_operation_for_row(request_row) if request_row is not None else replay_operation_for_row(response)
        method = event_method(request_row) if request_row is not None else event_method(response)
        host = event_normalized_host(request_row) if request_row is not None else event_normalized_host(response)
        path = event_normalized_path(request_row) if request_row is not None else event_normalized_path(response)
        key = (operation, method or "GET", host or "unknown", path or "/")
        if event_capture_truncated(response):
            endpoint_truncation_counter[key] += 1
            total_truncated += 1
        if str(event_payload(response).get("capture_failure") or "").strip() == "required_body_truncated":
            endpoint_failure_counter[key] += 1
            required_failures += 1

    for operation, endpoints in operations.items():
        if not isinstance(endpoints, list):
            continue
        for endpoint in endpoints:
            if not isinstance(endpoint, dict):
                continue
            key = (
                str(operation),
                str(endpoint.get("method") or "GET"),
                str(endpoint.get("host") or "unknown"),
                str(endpoint.get("path") or "/"),
            )
            truncated_count = int(endpoint_truncation_counter.get(key, 0))
            failure_count = int(endpoint_failure_counter.get(key, 0))
            endpoint["truncated_response_count"] = truncated_count
            endpoint["required_body_truncation_count"] = failure_count
            endpoint["replay_readiness"] = "degraded_by_truncation" if (truncated_count > 0 or failure_count > 0) else "ready"

    payload["truncation_summary"] = {
        "total_truncated_responses": total_truncated,
        "required_body_failures": required_failures,
    }
    return payload


def build_response_store_index(
    rows: List[Dict[str, Any]],
    runtime_dir: Optional[pathlib.Path] = None,
    event_body_refs: Optional[Dict[str, str]] = None,
) -> Dict[str, Any]:
    responses = [r for r in rows if r.get("event_type") == "network_response_event"]
    refs = []
    for row in responses:
        event_id = str(row.get("event_id") or "")
        rel_path = event_response_store_path(row)
        resolved = str((runtime_dir / "response_store" / rel_path)) if (runtime_dir and rel_path) else ""
        exists = bool(runtime_dir and rel_path and (runtime_dir / "response_store" / rel_path).exists())
        refs.append(
            {
                "event_id": event_id,
                "request_id": event_request_id(row),
                "response_id": event_response_id(row),
                "url": event_url(row),
                "normalized_scheme": event_normalized_scheme(row),
                "normalized_host": event_normalized_host(row),
                "normalized_path": event_normalized_path(row),
                "target_site_id": event_target_site_id(row),
                "path": rel_path,
                "resolved_path": resolved,
                "exists": exists,
                "mime": event_mime(row),
                "mime_type": str(event_payload(row).get("mime_type") or event_mime(row)),
                "status": event_status(row),
                "status_code": safe_int(event_payload(row).get("status_code") or event_status(row), default=0),
                "content_length_header": event_content_length_header(row),
                "original_content_length": event_original_content_length(row),
                "stored_size_bytes": event_stored_size_bytes(row),
                "capture_truncated": event_capture_truncated(row),
                "capture_limit_bytes": event_capture_limit_bytes(row),
                "truncation_reason": event_truncation_reason(row),
                "body_capture_policy": event_body_capture_policy(row),
                "candidate_relevance": event_candidate_relevance(row),
                "capture_failure": str(event_payload(row).get("capture_failure") or ""),
                "source_channel": event_source_channel(row),
                "phase_id": event_phase_id(row),
                "host_class": event_host_class(row),
                "body_ref": str((event_body_refs or {}).get(event_id) or event_payload(row).get("body_ref") or event_response_store_path(row)),
            }
        )
    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "items": refs,
    }


def is_media_segment_url(url_lower: str) -> bool:
    return any(ext in url_lower for ext in (".m4s", ".ts", ".mp4", ".m4a", ".webm", ".aac", ".m4v"))


def is_manifest_or_playback_payload(url_lower: str, merged: str) -> bool:
    return any(token in merged for token in (".m3u8", ".mpd", "manifest", "playback", "resolver", "ptmd", "/tmd/"))


def body_capture_decision(row: Dict[str, Any]) -> Dict[str, Any]:
    payload = event_payload(row)
    url = event_url(row).lower()
    mime = event_mime(row).lower()
    operation = event_request_operation(row).lower()
    classification = event_request_classification(row).lower()
    host_class = event_host_class(row)
    phase = event_phase_id(row)
    source = event_source(row).lower()
    merged = f"{url} {mime} {operation} {classification} {source}"
    is_playback_related = parse_boolish(payload.get("is_playback_related"), default=False) or (
        phase == PHASE_PLAYBACK or any(token in merged for token in ("playback", ".m3u8", ".mpd", "resolver", "stream"))
    )
    extraction_relevant_html = parse_boolish(
        payload.get("candidate_document")
        if payload.get("candidate_document") is not None
        else payload.get("candidateDocument"),
        default=False,
    )
    is_candidate_response = host_class in EXTRACTION_ELIGIBLE_HOST_CLASSES and phase in SCORABLE_PHASES
    candidate_relevance = CANDIDATE_RELEVANCE_SIGNAL if is_candidate_response else CANDIDATE_RELEVANCE_NON_CANDIDATE

    if host_class in NON_SIGNAL_HOST_CLASSES:
        if is_media_segment_url(url):
            return {
                "action": BODY_ACTION_STORE_METADATA_ONLY,
                "body_capture_policy": BODY_CAPTURE_POLICY_SKIPPED_MEDIA_SEGMENT,
                "capture_reason": "noise_media_segment",
                "candidate_relevance": CANDIDATE_RELEVANCE_NON_CANDIDATE,
            }
        if host_class == HOST_CLASS_IGNORED:
            return {
                "action": BODY_ACTION_SKIP_BODY,
                "body_capture_policy": BODY_CAPTURE_POLICY_SKIP_BODY,
                "capture_reason": "ignored_host",
                "candidate_relevance": CANDIDATE_RELEVANCE_NON_CANDIDATE,
            }
        return {
            "action": BODY_ACTION_STORE_METADATA_ONLY,
            "body_capture_policy": BODY_CAPTURE_POLICY_METADATA_ONLY,
            "capture_reason": "non_signal_host_class",
            "candidate_relevance": CANDIDATE_RELEVANCE_NON_CANDIDATE,
        }

    if is_media_segment_url(url):
        debug_force_media = parse_boolish(payload.get("debug_capture_media_segment"), default=False)
        if debug_force_media:
            return {
                "action": BODY_ACTION_STORE_TRUNCATED,
                "body_capture_policy": BODY_CAPTURE_POLICY_TRUNCATED_CANDIDATE,
                "capture_reason": "debug_media_segment_capture",
                "candidate_relevance": CANDIDATE_RELEVANCE_NON_CANDIDATE,
            }
        return {
            "action": BODY_ACTION_STORE_METADATA_ONLY,
            "body_capture_policy": BODY_CAPTURE_POLICY_SKIPPED_MEDIA_SEGMENT,
            "capture_reason": "generic_media_segment",
            "candidate_relevance": CANDIDATE_RELEVANCE_NON_CANDIDATE,
        }

    if any(token in merged for token in ("analytics", "doubleclick", "googletagmanager", "adservice", "pixel")):
        return {
            "action": BODY_ACTION_STORE_METADATA_ONLY,
            "body_capture_policy": BODY_CAPTURE_POLICY_METADATA_ONLY,
            "capture_reason": "analytics_or_ad_noise",
            "candidate_relevance": CANDIDATE_RELEVANCE_NON_CANDIDATE,
        }

    if "graphql" in merged:
        return {
            "action": BODY_ACTION_STORE_FULL_REQUIRED,
            "body_capture_policy": BODY_CAPTURE_POLICY_FULL_CANDIDATE_REQUIRED,
            "capture_reason": "graphql_payload_required",
            "candidate_relevance": CANDIDATE_RELEVANCE_REQUIRED,
        }

    if is_manifest_or_playback_payload(url, merged):
        return {
            "action": BODY_ACTION_STORE_FULL_REQUIRED,
            "body_capture_policy": BODY_CAPTURE_POLICY_FULL_CANDIDATE_REQUIRED,
            "capture_reason": "playback_or_manifest_required",
            "candidate_relevance": CANDIDATE_RELEVANCE_REQUIRED,
        }

    if "json" in mime:
        if is_candidate_response or is_playback_related:
            return {
                "action": BODY_ACTION_STORE_FULL_REQUIRED,
                "body_capture_policy": BODY_CAPTURE_POLICY_FULL_CANDIDATE_REQUIRED,
                "capture_reason": "candidate_json_required",
                "candidate_relevance": CANDIDATE_RELEVANCE_REQUIRED,
            }
        return {
            "action": BODY_ACTION_STORE_FULL,
            "body_capture_policy": BODY_CAPTURE_POLICY_FULL_CANDIDATE,
            "capture_reason": "json_payload",
            "candidate_relevance": CANDIDATE_RELEVANCE_SIGNAL,
        }

    if "html" in mime:
        if extraction_relevant_html:
            return {
                "action": BODY_ACTION_STORE_FULL_REQUIRED,
                "body_capture_policy": BODY_CAPTURE_POLICY_FULL_CANDIDATE_REQUIRED,
                "capture_reason": "candidate_document_required",
                "candidate_relevance": CANDIDATE_RELEVANCE_REQUIRED,
            }
        if is_candidate_response:
            return {
                "action": BODY_ACTION_STORE_FULL,
                "body_capture_policy": BODY_CAPTURE_POLICY_FULL_CANDIDATE,
                "capture_reason": "candidate_html",
                "candidate_relevance": CANDIDATE_RELEVANCE_SIGNAL,
            }
        return {
            "action": BODY_ACTION_STORE_TRUNCATED,
            "body_capture_policy": BODY_CAPTURE_POLICY_TRUNCATED_CANDIDATE,
            "capture_reason": "non_candidate_html",
            "candidate_relevance": CANDIDATE_RELEVANCE_NON_CANDIDATE,
        }

    if "xml" in mime or any(token in merged for token in ("bootstrap", "config", "init")):
        if is_candidate_response:
            return {
                "action": BODY_ACTION_STORE_FULL_REQUIRED,
                "body_capture_policy": BODY_CAPTURE_POLICY_FULL_CANDIDATE_REQUIRED,
                "capture_reason": "bootstrap_or_config_required",
                "candidate_relevance": CANDIDATE_RELEVANCE_REQUIRED,
            }
        return {
            "action": BODY_ACTION_STORE_FULL,
            "body_capture_policy": BODY_CAPTURE_POLICY_FULL_CANDIDATE,
            "capture_reason": "xml_or_config",
            "candidate_relevance": CANDIDATE_RELEVANCE_SIGNAL,
        }

    if mime.startswith("application/octet-stream"):
        return {
            "action": BODY_ACTION_STORE_METADATA_ONLY,
            "body_capture_policy": BODY_CAPTURE_POLICY_METADATA_ONLY,
            "capture_reason": "binary_octet_stream",
            "candidate_relevance": CANDIDATE_RELEVANCE_NON_CANDIDATE,
        }

    return {
        "action": BODY_ACTION_SKIP_BODY,
        "body_capture_policy": BODY_CAPTURE_POLICY_SKIP_BODY,
        "capture_reason": "non_candidate_mime",
        "candidate_relevance": candidate_relevance,
    }


def should_capture_candidate_body(row: Dict[str, Any]) -> bool:
    decision = body_capture_decision(row)
    return decision.get("action") in {
        BODY_ACTION_STORE_FULL,
        BODY_ACTION_STORE_FULL_REQUIRED,
        BODY_ACTION_STORE_TRUNCATED,
    }


def body_extension_from_payload(text: str) -> str:
    trimmed = text.strip()
    if not trimmed:
        return "txt"
    if trimmed.startswith("{") or trimmed.startswith("["):
        return "json"
    if trimmed.startswith("<!doctype html") or trimmed.startswith("<html"):
        return "html"
    if trimmed.startswith("<?xml") or trimmed.startswith("<"):
        return "xml"
    return "txt"


def zstd_compress(payload: bytes) -> Optional[bytes]:
    try:
        import zstandard as zstd  # type: ignore
    except Exception:
        return None
    compressor = zstd.ZstdCompressor(level=3)
    return compressor.compress(payload)


def build_body_store(rows: List[Dict[str, Any]], runtime_dir: pathlib.Path) -> Dict[str, Any]:
    bodies_root = runtime_dir / "bodies"
    bodies_root.mkdir(parents=True, exist_ok=True)

    items = []
    event_body_refs: Dict[str, str] = {}
    seen_sha: set = set()

    response_rows = [row for row in rows if row.get("event_type") == "network_response_event"]
    for row in response_rows:
        decision = body_capture_decision(row)
        action = str(decision.get("action") or BODY_ACTION_SKIP_BODY)
        body_capture_policy = str(decision.get("body_capture_policy") or BODY_CAPTURE_POLICY_METADATA_ONLY)
        capture_reason = str(decision.get("capture_reason") or "policy_resolver")
        candidate_relevance = str(decision.get("candidate_relevance") or CANDIDATE_RELEVANCE_NON_CANDIDATE)
        if action in {BODY_ACTION_STORE_METADATA_ONLY, BODY_ACTION_SKIP_BODY}:
            continue
        event_id = str(row.get("event_id") or "")
        if not event_id:
            continue

        text = read_response_store_payload(row, runtime_dir)
        if not text:
            text = event_body_preview(row)
        if not text:
            if action == BODY_ACTION_STORE_FULL_REQUIRED:
                event_payload(row)["capture_failure"] = "required_body_missing"
                event_payload(row)["capture_truncated"] = True
                if safe_int(event_payload(row).get("capture_limit_bytes"), default=0) <= 0:
                    event_payload(row)["capture_limit_bytes"] = FOUR_MB_BYTES
                event_payload(row)["truncation_reason"] = str(event_payload(row).get("truncation_reason") or TRUNCATION_REASON_BODY_SIZE_LIMIT)
            continue

        capture_truncated = event_capture_truncated(row)
        capture_limit_bytes = event_capture_limit_bytes(row)
        truncation_reason = event_truncation_reason(row)
        stored_size_bytes = event_stored_size_bytes(row)
        content_length_header = event_content_length_header(row)
        original_content_length = event_original_content_length(row)
        if not capture_truncated and original_content_length > 0 and stored_size_bytes > 0 and stored_size_bytes < original_content_length:
            capture_truncated = True
            if capture_limit_bytes <= 0:
                capture_limit_bytes = stored_size_bytes
        payload_bytes = text.encode("utf-8", errors="replace")
        pre_extension = body_extension_from_payload(text)
        allow_truncation = True
        if action == BODY_ACTION_STORE_TRUNCATED and pre_extension == "html" and len(payload_bytes) > LARGE_HTML_DEDUPE_THRESHOLD_BYTES:
            full_digest = hashlib.sha256(payload_bytes).hexdigest()
            full_zst = bodies_root / f"{full_digest}.html.zst"
            full_plain = bodies_root / f"{full_digest}.html"
            if full_zst.exists() or full_plain.exists():
                allow_truncation = False
                capture_reason = "dedup_reused_large_html"
        if action == BODY_ACTION_STORE_TRUNCATED and allow_truncation and len(payload_bytes) > FOUR_MB_BYTES:
            payload_bytes = payload_bytes[:FOUR_MB_BYTES]
            text = payload_bytes.decode("utf-8", errors="replace")
            capture_truncated = True
            capture_limit_bytes = FOUR_MB_BYTES
            if not truncation_reason:
                truncation_reason = TRUNCATION_REASON_BODY_SIZE_LIMIT

        if capture_truncated and capture_limit_bytes <= 0 and len(payload_bytes) > 0:
            capture_limit_bytes = FOUR_MB_BYTES if len(payload_bytes) == FOUR_MB_BYTES else len(payload_bytes)
        if capture_truncated and not truncation_reason:
            truncation_reason = TRUNCATION_REASON_BODY_SIZE_LIMIT
        if (
            action == BODY_ACTION_STORE_FULL_REQUIRED
            and capture_truncated
            and not str(event_payload(row).get("capture_failure") or "").strip()
        ):
            event_payload(row)["capture_failure"] = "required_body_truncated"
        if (
            action == BODY_ACTION_STORE_FULL_REQUIRED
            and not payload_bytes
            and not str(event_payload(row).get("capture_failure") or "").strip()
        ):
            event_payload(row)["capture_failure"] = "required_body_missing"

        digest = hashlib.sha256(payload_bytes).hexdigest()
        extension = body_extension_from_payload(text)
        compressed = zstd_compress(payload_bytes)
        if compressed is not None:
            filename = f"{digest}.{extension}.zst"
            compression = "zstd"
            to_write = compressed
        else:
            filename = f"{digest}.{extension}"
            compression = "none"
            to_write = payload_bytes

        target = bodies_root / filename
        dedup_reused = digest in seen_sha or target.exists()
        if digest not in seen_sha and not target.exists():
            target.write_bytes(to_write)
        seen_sha.add(digest)
        blob_size_bytes = int(target.stat().st_size) if target.exists() else len(to_write)

        rel = str(target.relative_to(runtime_dir))
        event_body_refs[event_id] = rel
        items.append(
            {
                "event_id": event_id,
                "request_id": event_request_id(row),
                "response_id": event_response_id(row),
                "url": event_url(row),
                "mime": event_mime(row),
                "status": event_status(row),
                "sha256": digest,
                "size_bytes": len(payload_bytes),
                "captured_size_bytes": stored_size_bytes,
                "stored_size_bytes": blob_size_bytes,
                "content_length_header": content_length_header,
                "original_content_length": original_content_length,
                "compression": compression,
                "body_ref": rel,
                "phase_id": event_phase_id(row),
                "host_class": event_host_class(row),
                "target_site_id": event_target_site_id(row),
                "body_capture_policy": body_capture_policy,
                "capture_reason": capture_reason,
                "capture_truncated": capture_truncated,
                "capture_limit_bytes": capture_limit_bytes if capture_truncated else 0,
                "truncation_reason": truncation_reason if capture_truncated else "",
                "candidate_relevance": candidate_relevance,
                "dedup_reused": dedup_reused,
            }
        )

    index = {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "item_count": len(items),
        "unique_blob_count": len(seen_sha),
        "items": items,
    }
    write_json(runtime_dir / "response_index.json", index)
    return {
        "index": index,
        "event_body_refs": event_body_refs,
        "index_path": runtime_dir / "response_index.json",
    }


def hash_value_repr(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8", errors="replace")).hexdigest()


def safe_value_repr(value: str) -> str:
    if not value:
        return ""
    if len(value) <= 8:
        return "*" * len(value)
    return f"{value[:4]}...{value[-2:]}"


def parse_cookie_names(cookie_header: str) -> List[str]:
    return [name for name, _value in parse_cookie_pairs(cookie_header)]


def parse_cookie_pairs(cookie_header: str) -> List[Tuple[str, str]]:
    raw = str(cookie_header or "")
    if not raw:
        return []
    out: List[Tuple[str, str]] = []
    for chunk in raw.split(";"):
        part = str(chunk).strip()
        if not part or "=" not in part:
            continue
        name_raw, value_raw = part.split("=", 1)
        name = str(name_raw).strip().lower()
        value = str(value_raw).strip()
        if name:
            out.append((name, value))
    return out


def parse_set_cookie_pairs(set_cookie_raw: str) -> List[Tuple[str, str]]:
    raw = str(set_cookie_raw or "").strip()
    if not raw:
        return []
    out: List[Tuple[str, str]] = []
    candidates = re.split(r",\s*(?=[A-Za-z0-9_.-]+=)", raw)
    for candidate in candidates:
        first = str(candidate).split(";", 1)[0].strip()
        if not first or "=" not in first:
            continue
        name_raw, value_raw = first.split("=", 1)
        name = str(name_raw).strip().lower()
        value = str(value_raw).strip()
        if name:
            out.append((name, value))
    return out


def provenance_key_name(key: str) -> str:
    normalized = str(key or "").strip().lower()
    if normalized in {"zdf-app-id", "x-zdf-app-id"}:
        return "zdf-app-id"
    if normalized in {"api-auth", "x-api-auth", "authorization"}:
        return "api-auth"
    if normalized in {"usersegment", "x-usersegment"}:
        return "userSegment"
    if normalized in {"abgroup", "x-ab-group"}:
        return "abGroup"
    if normalized in {"referer"}:
        return "referer"
    if normalized in {"origin"}:
        return "origin"
    return ""


def extract_provenance_body_values(body: str) -> List[Tuple[str, str, str]]:
    hits: List[Tuple[str, str, str]] = []
    text = str(body or "")
    if not text:
        return hits

    try:
        loaded = json.loads(text)
        for path, value in iter_json_leaf_paths(loaded):
            if value is None:
                continue
            lower_path = str(path).lower()
            name = ""
            if any(token in lower_path for token in ("zdf-app-id", "x-zdf-app-id")):
                name = "zdf-app-id"
            elif any(token in lower_path for token in ("api-auth", "x-api-auth", "authorization")):
                name = "api-auth"
            elif any(token in lower_path for token in ("usersegment", "x-usersegment")):
                name = "userSegment"
            elif any(token in lower_path for token in ("abgroup", "x-ab-group")):
                name = "abGroup"
            elif "referer" in lower_path:
                name = "referer"
            elif "origin" in lower_path:
                name = "origin"
            if name:
                hits.append((name, str(value), f"body_json:{path}"))
    except Exception:
        pass

    for name, pattern in [
        ("zdf-app-id", r'["\']?(?:x-)?zdf-app-id["\']?\s*[:=]\s*["\']([^"\']+)["\']'),
        ("api-auth", r'["\']?(?:x-)?api-auth["\']?\s*[:=]\s*["\']([^"\']+)["\']'),
        ("userSegment", r'["\']?usersegment["\']?\s*[:=]\s*["\']([^"\']+)["\']'),
        ("abGroup", r'["\']?abgroup["\']?\s*[:=]\s*["\']([^"\']+)["\']'),
    ]:
        for match in re.finditer(pattern, text, flags=re.IGNORECASE):
            value = str(match.group(1) or "").strip()
            if value:
                hits.append((name, value, "body_text"))

    for meta_name, key_name in [("origin", "origin"), ("referer", "referer")]:
        meta_pattern = rf'<meta[^>]+(?:name|property)=["\']{meta_name}["\'][^>]+content=["\']([^"\']+)["\']'
        for match in re.finditer(meta_pattern, text, flags=re.IGNORECASE):
            value = str(match.group(1) or "").strip()
            if value:
                hits.append((key_name, value, "html_meta"))

    return hits


def build_provenance_registry(rows: List[Dict[str, Any]], runtime_dir: Optional[pathlib.Path] = None) -> Dict[str, Any]:
    registry: Dict[Tuple[str, str], Dict[str, Any]] = {}

    def upsert_entry(
        *,
        name: str,
        value: str,
        source_type: str,
        source_ref: str,
        ts_utc: str,
        phase_id: str,
        target_site_id: str,
    ) -> None:
        if not value:
            return
        value_hash = hash_value_repr(value)
        key = (name, value_hash)
        entry = registry.get(key)
        if entry is None:
            entry = {
                "name": name,
                "value_hash": value_hash,
                "safe_value_repr": safe_value_repr(value),
                "source_type": source_type,
                "source_types": [source_type] if source_type else [],
                "source_refs": [],
                "first_seen_ts": ts_utc,
                "last_seen_ts": ts_utc,
                "phase_id": phase_id,
                "target_site_id": target_site_id,
            }
            registry[key] = entry
        entry["last_seen_ts"] = ts_utc
        source_types = entry.get("source_types")
        if not isinstance(source_types, list):
            source_types = []
            entry["source_types"] = source_types
        if source_type and source_type not in source_types:
            source_types.append(source_type)
            source_types.sort()
        if len(source_types) == 1:
            entry["source_type"] = source_types[0]
        elif len(source_types) > 1:
            entry["source_type"] = "multi_source"
        if source_ref and source_ref not in entry["source_refs"]:
            entry["source_refs"].append(source_ref)

    for row in rows:
        payload = event_payload(row)
        ts_utc = str(row.get("ts_utc") or "")
        phase_id = event_phase_id(row)
        target_site_id = event_target_site_id(row)
        event_id = str(row.get("event_id") or "")

        headers = event_headers(row)
        lowered_headers = {str(k).lower(): str(v) for k, v in headers.items()}
        for provenance_name, header_keys in PROVENANCE_TARGET_NAMES.items():
            for header_key in header_keys:
                if header_key in lowered_headers:
                    upsert_entry(
                        name=provenance_name,
                        value=lowered_headers[header_key],
                        source_type="header",
                        source_ref=f"{event_id}:header:{header_key}",
                        ts_utc=ts_utc,
                        phase_id=phase_id,
                        target_site_id=target_site_id,
                    )

        cookie_header = lowered_headers.get("cookie", "")
        if cookie_header:
            for cookie_name, cookie_value in parse_cookie_pairs(cookie_header):
                upsert_entry(
                    name=f"cookies.{cookie_name}",
                    value=cookie_value or cookie_name,
                    source_type="cookie_header",
                    source_ref=f"{event_id}:cookie:{cookie_name}",
                    ts_utc=ts_utc,
                    phase_id=phase_id,
                    target_site_id=target_site_id,
                )
        set_cookie_header = lowered_headers.get("set-cookie", "")
        if set_cookie_header:
            for cookie_name, cookie_value in parse_set_cookie_pairs(set_cookie_header):
                upsert_entry(
                    name=f"cookies.{cookie_name}",
                    value=cookie_value or cookie_name,
                    source_type="set_cookie_header",
                    source_ref=f"{event_id}:set-cookie:{cookie_name}",
                    ts_utc=ts_utc,
                    phase_id=phase_id,
                    target_site_id=target_site_id,
                )

        query_params = payload.get("query_params")
        if isinstance(query_params, dict):
            for query_key, values in query_params.items():
                resolved_name = provenance_key_name(query_key)
                if not resolved_name:
                    continue
                if isinstance(values, list):
                    for idx, value in enumerate(values):
                        upsert_entry(
                            name=resolved_name,
                            value=str(value),
                            source_type="query_param",
                            source_ref=f"{event_id}:query:{query_key}[{idx}]",
                            ts_utc=ts_utc,
                            phase_id=phase_id,
                            target_site_id=target_site_id,
                        )

        if row.get("event_type") == "network_request_event":
            request_body = str(payload.get("body") or payload.get("body_preview") or "")
            for name, value, source_marker in extract_provenance_body_values(request_body):
                upsert_entry(
                    name=name,
                    value=value,
                    source_type=f"request_{source_marker.split(':', 1)[0]}",
                    source_ref=f"{event_id}:{source_marker}",
                    ts_utc=ts_utc,
                    phase_id=phase_id,
                    target_site_id=target_site_id,
                )

        if row.get("event_type") == "storage_event":
            storage_key = str(payload.get("key") or "")
            value_preview = str(payload.get("value_preview") or "")
            resolved_name = provenance_key_name(storage_key)
            if resolved_name and value_preview:
                upsert_entry(
                    name=resolved_name,
                    value=value_preview,
                    source_type="storage_event",
                    source_ref=f"{event_id}:storage:{storage_key}",
                    ts_utc=ts_utc,
                    phase_id=phase_id,
                    target_site_id=target_site_id,
                )

        if row.get("event_type") == "network_response_event":
            body = read_response_store_payload(row, runtime_dir)
            if not body:
                body = event_body_preview(row)
            body_ref = event_response_store_path(row)
            for name, value, source_marker in extract_provenance_body_values(body):
                upsert_entry(
                    name=name,
                    value=value,
                    source_type=source_marker.split(":", 1)[0],
                    source_ref=f"{event_id}:{source_marker}:{body_ref or 'body_preview'}",
                    ts_utc=ts_utc,
                    phase_id=phase_id,
                    target_site_id=target_site_id,
                )

        if row.get("event_type") == "provenance_event":
            entity_key = str(payload.get("entity_key") or "")
            entity_type = str(payload.get("entity_type") or "provenance")
            if entity_key:
                upsert_entry(
                    name=entity_type,
                    value=entity_key,
                    source_type="provenance_event",
                    source_ref=f"{event_id}:entity_key",
                    ts_utc=ts_utc,
                    phase_id=phase_id,
                    target_site_id=target_site_id,
                )

    entries = sorted(registry.values(), key=lambda item: (str(item.get("name") or ""), str(item.get("value_hash") or "")))
    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "entry_count": len(entries),
        "entries": entries,
    }


def build_provenance_graph(rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    provenance_rows = [row for row in rows if row.get("event_type") == "provenance_event"]
    nodes: Dict[str, Dict[str, Any]] = {}
    edges: List[Dict[str, Any]] = []

    for row in provenance_rows:
        payload = event_payload(row)
        entity_type = str(payload.get("entity_type") or "unknown")
        entity_key = str(payload.get("entity_key") or "unknown")
        entity_id = f"{entity_type}:{entity_key}"
        nodes.setdefault(
            entity_id,
            {
                "id": entity_id,
                "entity_type": entity_type,
                "entity_key": entity_key,
            },
        )

        produced_by = str(payload.get("produced_by") or "")
        consumed_by = str(payload.get("consumed_by") or "")
        derived_from = payload.get("derived_from")
        if not isinstance(derived_from, list):
            derived_from = []

        if produced_by:
            edges.append(
                {
                    "relation": "produced_by",
                    "from": produced_by,
                    "to": entity_id,
                    "event_id": str(row.get("event_id") or ""),
                    "phase_id": event_phase_id(row),
                    "target_site_id": event_target_site_id(row),
                }
            )
        if consumed_by:
            edges.append(
                {
                    "relation": "consumed_by",
                    "from": entity_id,
                    "to": consumed_by,
                    "event_id": str(row.get("event_id") or ""),
                    "phase_id": event_phase_id(row),
                    "target_site_id": event_target_site_id(row),
                }
            )
        for source in derived_from:
            source_key = str(source)
            if not source_key:
                continue
            source_id = f"{entity_type}:{source_key}"
            nodes.setdefault(
                source_id,
                {
                    "id": source_id,
                    "entity_type": entity_type,
                    "entity_key": source_key,
                },
            )
            edges.append(
                {
                    "relation": "derived_from",
                    "from": source_id,
                    "to": entity_id,
                    "event_id": str(row.get("event_id") or ""),
                    "phase_id": event_phase_id(row),
                    "target_site_id": event_target_site_id(row),
                }
            )

    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "node_count": len(nodes),
        "edge_count": len(edges),
        "nodes": sorted(nodes.values(), key=lambda item: item["id"]),
        "edges": edges,
    }


def within_time_range(row: Dict[str, Any], time_from: str, time_to: str) -> bool:
    ts = str(row.get("ts_utc") or "")
    if not ts:
        return True
    if time_from and ts < time_from:
        return False
    if time_to and ts > time_to:
        return False
    return True


def match_filters(rows: List[Dict[str, Any]], args: argparse.Namespace) -> List[Dict[str, Any]]:
    body_rx = re.compile(args.body_regex) if args.body_regex else None
    out: List[Dict[str, Any]] = []

    for row in rows:
        if getattr(args, "trace_id", "") and str(row.get("trace_id") or "") != args.trace_id:
            continue
        if getattr(args, "action_id", "") and str(row.get("action_id") or "") != args.action_id:
            continue

        payload = event_payload(row)
        screen_id = str(payload.get("screen_id") or payload.get("screenId") or "")
        if getattr(args, "screen_id", "") and screen_id != args.screen_id:
            continue
        phase_id = event_phase_id(row)
        if getattr(args, "phase_id", "") and phase_id != args.phase_id:
            continue
        host_class = event_host_class(row)
        if getattr(args, "host_class", "") and host_class != args.host_class:
            continue

        if not within_time_range(row, getattr(args, "time_from", ""), getattr(args, "time_to", "")):
            continue

        url = event_url(row)
        parsed = urlparse(url) if url else None

        if getattr(args, "domain", "") and (not parsed or args.domain not in parsed.netloc):
            continue
        if getattr(args, "path", "") and (not parsed or args.path not in parsed.path):
            continue
        if getattr(args, "method", "") and event_method(row) != args.method.upper():
            continue
        if getattr(args, "status", "") and event_status(row) != str(args.status):
            continue
        if getattr(args, "mime", "") and args.mime.lower() not in event_mime(row).lower():
            continue

        headers = event_headers(row)
        if getattr(args, "header_key", "") and args.header_key.lower() not in {h.lower() for h in headers.keys()}:
            continue

        cookie_text = " ".join([
            str(payload.get("cookie") or ""),
            str(payload.get("cookie_name") or ""),
            str(payload.get("set_cookie") or ""),
            str(headers.get("Cookie") or headers.get("cookie") or ""),
            str(headers.get("Set-Cookie") or headers.get("set-cookie") or ""),
        ])
        if getattr(args, "cookie_key", "") and args.cookie_key not in cookie_text:
            continue

        if body_rx and not body_rx.search(event_body_preview(row)):
            continue

        out.append(row)

    if getattr(args, "limit", 0) and args.limit > 0:
        out = out[: args.limit]
    return out


def compact_requests_canonical(rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    canonical_rows: List[Dict[str, Any]] = []
    by_canonical_id: Dict[str, Dict[str, Any]] = {}
    for row in rows:
        if row.get("event_type") != "network_request_event":
            continue
        compact = compact_request(row)
        canonical_id = str(compact.get("canonical_request_id") or compact.get("request_id") or compact.get("event_id") or "")
        if not canonical_id:
            canonical_rows.append(compact)
            continue
        existing = by_canonical_id.get(canonical_id)
        if existing is None:
            compact["duplicate_event_count"] = 0
            compact["duplicate_event_ids"] = []
            by_canonical_id[canonical_id] = compact
            canonical_rows.append(compact)
            continue
        existing["duplicate_event_count"] = int(existing.get("duplicate_event_count") or 0) + 1
        duplicate_ids = existing.get("duplicate_event_ids")
        if not isinstance(duplicate_ids, list):
            duplicate_ids = []
            existing["duplicate_event_ids"] = duplicate_ids
        event_id = str(compact.get("event_id") or "")
        if event_id and event_id != str(existing.get("event_id") or "") and event_id not in duplicate_ids:
            duplicate_ids.append(event_id)
    return canonical_rows


def build_extraction_event_rows(rows: List[Dict[str, Any]], field_matrix: Dict[str, Any]) -> List[Dict[str, Any]]:
    request_id_by_response_event: Dict[str, str] = {}
    for row in rows:
        if row.get("event_type") != "network_response_event":
            continue
        request_id_by_response_event[str(row.get("event_id") or "")] = event_request_id(row)

    response_by_event_id = {
        str(row.get("event_id") or ""): row
        for row in rows
        if row.get("event_type") == "network_response_event"
    }

    out: List[Dict[str, Any]] = []
    for idx, item in enumerate(list(field_matrix.get("extraction_events") or [])):
        if not isinstance(item, dict):
            continue
        source_event_id = str(item.get("event_id") or "")
        source_row = response_by_event_id.get(source_event_id, {})
        run_id = str(source_row.get("run_id") or "derived_run")
        trace_id = str(source_row.get("trace_id") or "")
        span_id = str(source_row.get("span_id") or "")
        action_id = str(source_row.get("action_id") or "")
        ts_utc = str(source_row.get("ts_utc") or utc_now())
        request_id = request_id_by_response_event.get(source_event_id, "")
        payload = {
            "operation": "field_matrix_extraction_attempt",
            "source_ref": str(item.get("source_ref") or source_event_id),
            "phase_id": str(item.get("phase_id") or event_phase_id(source_row)),
            "host_class": str(item.get("host_class") or event_host_class(source_row)),
            "extraction_kind": str(item.get("extraction_kind") or "unknown"),
            "success": bool(item.get("success")),
            "extracted_field_count": int(item.get("extracted_field_count") or 0),
            "confidence_summary": str(item.get("confidence_summary") or "none"),
            "source_event_id": source_event_id,
            "request_id": request_id,
            "target_site_id": event_target_site_id(source_row),
        }
        out.append(
            {
                "schema_version": 1,
                "run_id": run_id,
                "event_id": f"derived_extraction_{idx}_{stable_hash([source_event_id, payload])[:12]}",
                "event_type": "extraction_event",
                "ts_utc": ts_utc,
                "ts_mono_ns": int(source_row.get("ts_mono_ns") or 0),
                "trace_id": trace_id,
                "span_id": span_id,
                "action_id": action_id,
                "device": source_row.get("device") if isinstance(source_row.get("device"), dict) else {},
                "app": source_row.get("app") if isinstance(source_row.get("app"), dict) else {},
                "payload": payload,
            }
        )
    return out


def build_truncation_event_rows(rows: List[Dict[str, Any]], event_body_refs: Optional[Dict[str, str]] = None) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    for row in rows:
        if row.get("event_type") != "network_response_event":
            continue
        payload = event_payload(row)
        capture_truncated = event_capture_truncated(row)
        capture_failure = str(payload.get("capture_failure") or "").strip()
        if not capture_truncated and not capture_failure:
            continue
        event_id = str(row.get("event_id") or "")
        trunc_payload = {
            "operation": "response_body_truncation",
            "source_ref": event_id,
            "request_id": event_request_id(row),
            "response_id": event_response_id(row) or event_id,
            "body_ref": str((event_body_refs or {}).get(event_id) or payload.get("body_ref") or event_response_store_path(row)),
            "normalized_host": event_normalized_host(row),
            "normalized_path": event_normalized_path(row),
            "mime_type": str(payload.get("mime_type") or event_mime(row)),
            "phase_id": event_phase_id(row),
            "host_class": event_host_class(row),
            "capture_limit_bytes": event_capture_limit_bytes(row),
            "stored_size_bytes": event_stored_size_bytes(row),
            "original_content_length": event_original_content_length(row),
            "truncation_reason": event_truncation_reason(row) or (TRUNCATION_REASON_BODY_SIZE_LIMIT if capture_truncated else ""),
            "body_capture_policy": event_body_capture_policy(row),
            "candidate_relevance": event_candidate_relevance(row),
            "capture_truncated": capture_truncated,
            "required_body_failure": capture_failure if capture_failure else "",
            "target_site_id": event_target_site_id(row),
        }
        out.append(
            {
                "schema_version": 1,
                "run_id": str(row.get("run_id") or "derived_run"),
                "event_id": f"derived_truncation_{len(out)}_{stable_hash([event_id, trunc_payload])[:12]}",
                "event_type": "truncation_event",
                "ts_utc": str(row.get("ts_utc") or utc_now()),
                "ts_mono_ns": int(row.get("ts_mono_ns") or 0),
                "trace_id": str(row.get("trace_id") or ""),
                "span_id": str(row.get("span_id") or ""),
                "action_id": str(row.get("action_id") or ""),
                "device": row.get("device") if isinstance(row.get("device"), dict) else {},
                "app": row.get("app") if isinstance(row.get("app"), dict) else {},
                "payload": trunc_payload,
            }
        )
    return out


def ensure_derived(runtime_dir: pathlib.Path, rows: List[Dict[str, Any]]) -> Dict[str, pathlib.Path]:
    rows = normalize_runtime_rows(rows, runtime_dir=runtime_dir)
    correlation = build_correlation(rows)
    cookie_timeline = build_cookie_timeline(rows)
    required_headers = build_required_headers_active_replay(
        rows,
        timeout_ms=ACTIVE_REPLAY_ROLLUP_TIMEOUT_MS,
        allow_unsafe_methods=False,
    )
    endpoint_sets = list(required_headers.get("endpoint_minimal_sets") or [])
    required_cookies_payload = build_required_cookies_report_for_endpoint_sets(
        rows,
        endpoint_sets=endpoint_sets,
        inference_mode=str(required_headers.get("inference_mode") or "active_http_replay"),
    )
    replay_requirements = build_replay_requirements(
        rows,
        prefer_active_replay=True,
        timeout_ms=ACTIVE_REPLAY_ROLLUP_TIMEOUT_MS,
        allow_unsafe_methods=False,
    )
    endpoint_candidates = build_endpoint_candidates(rows)
    field_matrix = build_field_matrix(rows, runtime_dir=runtime_dir)
    profile_draft = build_profile_draft(rows, endpoint_candidates, required_headers)
    replay_seed = build_replay_seed(rows)
    body_store = build_body_store(rows, runtime_dir=runtime_dir)
    extraction_event_rows = build_extraction_event_rows(rows, field_matrix)
    truncation_event_rows = build_truncation_event_rows(rows, event_body_refs=body_store.get("event_body_refs", {}))
    response_store_index = build_response_store_index(
        rows,
        runtime_dir=runtime_dir,
        event_body_refs=body_store.get("event_body_refs", {}),
    )
    provenance_graph = build_provenance_graph(rows)
    provenance_registry = build_provenance_registry(rows, runtime_dir=runtime_dir)
    events_ssot = {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "event_count": len(rows) + len(extraction_event_rows) + len(truncation_event_rows),
        "raw_event_store_ssot": str(events_path(runtime_dir)),
    }
    profile_candidate = dict(profile_draft)
    profile_candidate["profile_type"] = "candidate"

    request_rows = compact_requests_canonical(rows)
    response_rows = [compact_response(row) for row in rows if row.get("event_type") == "network_response_event"]

    paths = {
        "events_ssot": runtime_dir / "events.jsonl",
        "extraction_events": runtime_dir / "extraction_events.jsonl",
        "truncation_events": runtime_dir / "truncation_events.jsonl",
        "events_ssot_meta": runtime_dir / "events.meta.json",
        "requests_normalized": runtime_dir / "requests.normalized.jsonl",
        "responses_normalized": runtime_dir / "responses.normalized.jsonl",
        "correlation": runtime_dir / "correlation_index.jsonl",
        "cookie_timeline": runtime_dir / "cookie_timeline.json",
        "required_headers": runtime_dir / "required_headers_report.json",
        "required_cookies": runtime_dir / "required_cookies_report.json",
        "replay_requirements": runtime_dir / "replay_requirements.json",
        "endpoint_candidates": runtime_dir / "endpoint_candidates.json",
        "field_matrix": runtime_dir / "field_matrix.json",
        "profile_draft": runtime_dir / "site_profile.draft.json",
        "profile_candidate": runtime_dir / "profile_candidate.json",
        "replay_seed": runtime_dir / "replay_seed.json",
        "response_store_index": runtime_dir / "response_store" / "index.json",
        "response_index": runtime_dir / "response_index.json",
        "provenance_graph": runtime_dir / "provenance_graph.json",
        "provenance_registry": runtime_dir / "provenance_registry.json",
    }

    write_jsonl(paths["events_ssot"], list(rows) + extraction_event_rows + truncation_event_rows)
    write_jsonl(paths["extraction_events"], extraction_event_rows)
    write_jsonl(paths["truncation_events"], truncation_event_rows)
    write_json(paths["events_ssot_meta"], events_ssot)
    write_jsonl(paths["requests_normalized"], request_rows)
    write_jsonl(paths["responses_normalized"], response_rows)
    write_jsonl(paths["correlation"], correlation)
    write_json(paths["cookie_timeline"], cookie_timeline)
    write_json(paths["required_headers"], required_headers)
    write_json(paths["required_cookies"], required_cookies_payload)
    write_json(paths["replay_requirements"], replay_requirements)
    write_json(paths["endpoint_candidates"], endpoint_candidates)
    write_json(paths["field_matrix"], field_matrix)
    write_json(paths["profile_draft"], profile_draft)
    write_json(paths["profile_candidate"], profile_candidate)
    write_json(paths["replay_seed"], replay_seed)
    write_json(paths["response_store_index"], response_store_index)
    write_json(paths["response_index"], body_store.get("index", {}))
    write_json(paths["provenance_graph"], provenance_graph)
    write_json(paths["provenance_registry"], provenance_registry)
    return paths


def discover_primary_host(rows: List[Dict[str, Any]]) -> str:
    counts: Counter[str] = Counter()
    for row in rows:
        if row.get("event_type") != "network_request_event":
            continue
        if event_host_class(row) in NON_SIGNAL_HOST_CLASSES:
            continue
        url = event_url(row)
        if not url:
            continue
        host = event_normalized_host(row) or urlparse(url).netloc
        if not host:
            continue
        if is_noise_asset(url=url, mime=event_mime(row)):
            continue
        counts[host] += 1
    if not counts:
        return ""
    return counts.most_common(1)[0][0]


def pipeline_quality_report(
    runtime_dir: pathlib.Path,
    rows: List[Dict[str, Any]],
    quality_host: str = "",
) -> Dict[str, Any]:
    run_id = ""
    for row in reversed(rows):
        rid = str(row.get("run_id") or "")
        if rid:
            run_id = rid
            break
    scoped_rows = rows
    if run_id:
        scoped_rows = [row for row in rows if str(row.get("run_id") or "") == run_id]

    rows = normalize_runtime_rows(scoped_rows, runtime_dir=runtime_dir)
    derived = ensure_derived(runtime_dir, rows)
    host = quality_host.strip() or discover_primary_host(rows)

    request_rows_all = [row for row in rows if row.get("event_type") == "network_request_event"]
    response_rows_all = [row for row in rows if row.get("event_type") == "network_response_event"]
    target_request_rows = [row for row in request_rows_all if event_host_class(row) not in NON_SIGNAL_HOST_CLASSES]
    target_response_rows = [row for row in response_rows_all if event_host_class(row) not in NON_SIGNAL_HOST_CLASSES]
    request_rows = target_request_rows if target_request_rows else request_rows_all
    response_rows = target_response_rows if target_response_rows else response_rows_all
    if host:
        request_rows = [row for row in request_rows if host in (urlparse(event_url(row)).netloc or "")]
        response_rows = [row for row in response_rows if host in (urlparse(event_url(row)).netloc or "")]

    response_by_request_id: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for response in response_rows:
        req_id = event_request_id(response)
        if req_id:
            response_by_request_id[req_id].append(response)

    total_requests_all = len(request_rows)
    observable_request_rows = [row for row in request_rows if request_is_response_observable(row)]
    request_rows_for_coverage = observable_request_rows if observable_request_rows else request_rows
    total_requests = len(request_rows_for_coverage)
    matched_requests = 0
    relevant_requests = 0
    correlated_requests = 0
    for request in request_rows_for_coverage:
        req_id = event_request_id(request)
        action_id = str(request.get("action_id") or "")
        if action_id and action_id != "session_start":
            correlated_requests += 1
        if action_id:
            relevant_requests += 1

        if req_id and any(event_status(resp) for resp in response_by_request_id.get(req_id, [])):
            matched_requests += 1
            continue

        fallback_hit = False
        request_url = event_url(request)
        request_method = event_method(request)
        request_trace = str(request.get("trace_id") or "")
        request_action = str(request.get("action_id") or "")
        request_ts = ts_to_epoch_seconds(str(request.get("ts_utc") or ""))
        for response in response_rows:
            if event_url(response) != request_url:
                continue
            if event_method(response) and request_method and event_method(response) != request_method:
                continue
            if request_trace and str(response.get("trace_id") or "") != request_trace:
                continue
            if request_action and str(response.get("action_id") or "") != request_action:
                continue
            delta = abs(ts_to_epoch_seconds(str(response.get("ts_utc") or "")) - request_ts)
            if delta <= 20 and event_status(response):
                fallback_hit = True
                break
        if fallback_hit:
            matched_requests += 1

    response_coverage = round((matched_requests / total_requests), 4) if total_requests > 0 else 0.0
    correlation_coverage = round((correlated_requests / relevant_requests), 4) if relevant_requests > 0 else 0.0

    dedup_events = [
        row
        for row in rows
        if row.get("event_type") == "correlation_event"
        and str(event_payload(row).get("operation") or "") == "request_dedup"
        and dedup_event_is_observable(row)
    ]
    dedup_refs = len(dedup_events)
    logical_request_total = len(observable_request_rows) + dedup_refs
    duplicate_request_ratio = round((dedup_refs / logical_request_total), 6) if logical_request_total > 0 else 0.0

    required_phases = [PHASE_HOME, PHASE_SEARCH, PHASE_DETAIL, PHASE_PLAYBACK]
    optional_phase = PHASE_AUTH
    phase_starts = {
        str(event_payload(row).get("phase_id") or "")
        for row in rows
        if row.get("event_type") == "probe_phase_event"
        and str(event_payload(row).get("transition") or "").lower() in {"start", "resume"}
    }
    if not phase_starts:
        phase_starts = {event_phase_id(row) for row in request_rows_all if event_phase_id(row)}
    phase_starts.discard("")
    phase_hits = len([phase for phase in required_phases if phase in phase_starts])
    phase_completeness_ratio = round((phase_hits / len(required_phases)), 4) if required_phases else 0.0

    latest_targets = [
        runtime_dir / "events.jsonl",
        runtime_dir / "extraction_events.jsonl",
        runtime_dir / "requests.normalized.jsonl",
        runtime_dir / "responses.normalized.jsonl",
        runtime_dir / "correlation_index.jsonl",
        runtime_dir / "cookie_timeline.json",
        runtime_dir / "required_headers_report.json",
        runtime_dir / "required_cookies_report.json",
        runtime_dir / "replay_requirements.json",
        runtime_dir / "endpoint_candidates.json",
        runtime_dir / "field_matrix.json",
        runtime_dir / "site_profile.draft.json",
        runtime_dir / "profile_candidate.json",
        runtime_dir / "provenance_graph.json",
        runtime_dir / "provenance_registry.json",
        runtime_dir / "response_index.json",
        runtime_dir / "replay_seed.json",
    ]
    latest_non_empty = True
    empty_targets = []
    for target in latest_targets:
        if not target.exists() or target.stat().st_size == 0:
            latest_non_empty = False
            empty_targets.append(str(target))

    response_index = load_contract_json(derived["response_store_index"])
    refs = response_index.get("items", [])
    ref_total = 0
    ref_valid = 0
    if isinstance(refs, list):
        for item in refs:
            if not isinstance(item, dict):
                continue
            if not str(item.get("path") or ""):
                continue
            ref_total += 1
            if bool(item.get("exists")):
                ref_valid += 1
    ref_ratio = round((ref_valid / ref_total), 4) if ref_total > 0 else 1.0

    gates = {
        "duplicate_request_ratio_gate": {
            "threshold": 0.01,
            "value": duplicate_request_ratio,
            "passed": duplicate_request_ratio <= 0.01,
        },
        "phase_completeness_gate": {
            "threshold": 1.0,
            "value": phase_completeness_ratio,
            "passed": phase_hits == len(required_phases),
            "required_phases": required_phases,
            "optional_phase": optional_phase,
            "optional_phase_seen": optional_phase in phase_starts,
            "seen_phases": sorted(list(phase_starts)),
            "phase_hits": phase_hits,
        },
        "response_coverage_gate": {
            "threshold": 0.9,
            "value": response_coverage,
            "passed": response_coverage >= 0.9 if total_requests > 0 else False,
        },
        "correlation_coverage_gate": {
            "threshold": 0.9,
            "value": correlation_coverage,
            "passed": correlation_coverage >= 0.9 if relevant_requests > 0 else False,
        },
        "latest_non_empty_gate": {
            "threshold": True,
            "value": latest_non_empty,
            "passed": latest_non_empty,
            "empty_targets": empty_targets,
        },
        "response_ref_integrity_gate": {
            "threshold": 1.0,
            "value": ref_ratio,
            "passed": ref_ratio >= 1.0,
            "total_refs": ref_total,
            "valid_refs": ref_valid,
        },
    }
    pipeline_ready = all(bool(gate.get("passed")) for gate in gates.values())
    return {
        "schema_version": 1,
        "run_id": run_id or "unknown",
        "generated_at_utc": utc_now(),
        "quality_host": host,
        "pipeline_ready": pipeline_ready,
        "metrics": {
            "request_count": total_requests,
            "request_count_total": total_requests_all,
            "request_count_observable": len(observable_request_rows),
            "request_count_all": len(request_rows_all),
            "matched_request_count": matched_requests,
            "response_coverage": response_coverage,
            "relevant_request_count": relevant_requests,
            "correlated_request_count": correlated_requests,
            "correlation_coverage": correlation_coverage,
            "duplicate_request_ratio": duplicate_request_ratio,
            "dedup_reference_count": dedup_refs,
            "phase_hits": phase_hits,
            "phase_completeness_ratio": phase_completeness_ratio,
            "response_ref_count": ref_total,
            "response_ref_valid_count": ref_valid,
            "response_ref_ratio": ref_ratio,
        },
        "gates": gates,
        "derived_paths": {k: str(v) for k, v in derived.items()},
    }


def rule_map() -> Dict[str, Dict[str, Any]]:
    rules = triage_rules_contract().get("rules", [])
    out: Dict[str, Dict[str, Any]] = {}
    if isinstance(rules, list):
        for rule in rules:
            if isinstance(rule, dict):
                rid = str(rule.get("id") or "")
                if rid:
                    out[rid] = rule
    return out


def new_alert(
    idx: int,
    run_id: str,
    trace_id: str,
    action_id: str,
    rule_id: str,
    title: str,
    severity: str,
    reason: str,
    repro_hint: str,
    event_ids: List[str],
) -> Dict[str, Any]:
    return {
        "schema_version": 1,
        "run_id": run_id or "unknown",
        "event_id": f"triage_alert_{dt.datetime.utcnow().strftime('%Y%m%dT%H%M%SZ')}_{idx}",
        "event_type": "triage_alert_event",
        "ts_utc": utc_now(),
        "ts_mono_ns": 0,
        "trace_id": trace_id,
        "span_id": "",
        "action_id": action_id,
        "severity": severity,
        "rule_id": rule_id,
        "title": title,
        "reason": reason,
        "repro_hint": repro_hint,
        "chain": {"event_ids": event_ids[:50]},
    }


def detect_triage_alerts(rows: List[Dict[str, Any]], only_rules: Optional[List[str]] = None) -> List[Dict[str, Any]]:
    rules = rule_map()
    wanted = set(only_rules or [])

    def enabled(rule_id: str) -> bool:
        if wanted and rule_id not in wanted:
            return False
        cfg = rules.get(rule_id, {})
        if "enabled_default" in cfg:
            return bool(cfg.get("enabled_default"))
        return True

    grouped: Dict[Tuple[str, str], List[Dict[str, Any]]] = defaultdict(list)
    for row in rows:
        trace_id = str(row.get("trace_id") or "")
        action_id = str(row.get("action_id") or "")
        grouped[(trace_id, action_id)].append(row)

    alerts: List[Dict[str, Any]] = []
    idx = 1

    if enabled("auth_loop"):
        for (trace_id, action_id), events in grouped.items():
            responses = [e for e in events if e.get("event_type") == "network_response_event"]
            statuses = [event_status(r) for r in responses]
            if any(s in {"401", "403"} for s in statuses) and "200" in statuses:
                first = statuses.index("200")
                if any(s in {"401", "403"} for s in statuses[:first]):
                    run_id = str((events[0].get("run_id") if events else "unknown") or "unknown")
                    alerts.append(
                        new_alert(
                            idx=idx,
                            run_id=run_id,
                            trace_id=trace_id,
                            action_id=action_id,
                            rule_id="auth_loop",
                            title="Auth loop / recovered chain",
                            severity=str(rules.get("auth_loop", {}).get("severity_default") or "high"),
                            reason="Observed 401/403 followed by 200 in same correlated chain.",
                            repro_hint="Replay this action_id and inspect auth refresh and cookie/token transition.",
                            event_ids=[str(e.get("event_id") or "") for e in events if e.get("event_id")],
                        )
                    )
                    idx += 1

    if enabled("retry_storm"):
        threshold = rule_threshold(rules.get("retry_storm", {}), "request_count", 4)
        window_s = rule_threshold(rules.get("retry_storm", {}), "window_seconds", 60)
        request_rows = [r for r in rows if r.get("event_type") == "network_request_event"]
        by_endpoint: Dict[Tuple[str, str], List[Dict[str, Any]]] = defaultdict(list)
        for row in request_rows:
            key = (event_method(row) or "GET", event_url(row))
            by_endpoint[key].append(row)
        for (method, url), items in by_endpoint.items():
            if not url:
                continue
            ordered = sorted(items, key=lambda r: str(r.get("ts_utc") or ""))
            ts_values = [ts_to_epoch_seconds(str(r.get("ts_utc") or "")) for r in ordered]
            for start in range(len(ordered)):
                end = start
                while end < len(ordered) and (ts_values[end] - ts_values[start]) <= window_s:
                    end += 1
                count = end - start
                if count >= threshold:
                    sample = ordered[start:end]
                    trace_id = str(sample[0].get("trace_id") or "")
                    action_id = str(sample[0].get("action_id") or "")
                    run_id = str(sample[0].get("run_id") or "unknown")
                    alerts.append(
                        new_alert(
                            idx=idx,
                            run_id=run_id,
                            trace_id=trace_id,
                            action_id=action_id,
                            rule_id="retry_storm",
                            title="Retry storm detected",
                            severity=str(rules.get("retry_storm", {}).get("severity_default") or "medium"),
                            reason=f"{count} requests to {method} {url} within {window_s}s.",
                            repro_hint="Inspect retries, backoff strategy, and transient auth/network failures.",
                            event_ids=[str(e.get("event_id") or "") for e in sample if e.get("event_id")],
                        )
                    )
                    idx += 1
                    break

    if enabled("redirect_loop"):
        threshold = rule_threshold(rules.get("redirect_loop", {}), "redirect_count", 3)
        redirect_codes = {"301", "302", "303", "307", "308"}
        for (trace_id, action_id), events in grouped.items():
            responses = [e for e in events if e.get("event_type") == "network_response_event"]
            redirects = [r for r in responses if event_status(r) in redirect_codes]
            if len(redirects) >= threshold:
                run_id = str((events[0].get("run_id") if events else "unknown") or "unknown")
                alerts.append(
                    new_alert(
                        idx=idx,
                        run_id=run_id,
                        trace_id=trace_id,
                        action_id=action_id,
                        rule_id="redirect_loop",
                        title="Redirect loop pattern",
                        severity=str(rules.get("redirect_loop", {}).get("severity_default") or "medium"),
                        reason=f"{len(redirects)} redirect responses in one chain.",
                        repro_hint="Inspect redirect target sequence and session/login redirect behavior.",
                        event_ids=[str(e.get("event_id") or "") for e in redirects if e.get("event_id")],
                    )
                )
                idx += 1

    if enabled("cookie_churn"):
        threshold = rule_threshold(rules.get("cookie_churn", {}), "event_count", 3)
        window_s = rule_threshold(rules.get("cookie_churn", {}), "window_seconds", 120)
        cookie_rows = [r for r in rows if r.get("event_type") == "cookie_event"]
        by_domain: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
        for row in cookie_rows:
            domain = str(event_payload(row).get("domain") or "unknown")
            by_domain[domain].append(row)
        for domain, items in by_domain.items():
            ordered = sorted(items, key=lambda r: str(r.get("ts_utc") or ""))
            ts_values = [ts_to_epoch_seconds(str(r.get("ts_utc") or "")) for r in ordered]
            for start in range(len(ordered)):
                end = start
                while end < len(ordered) and (ts_values[end] - ts_values[start]) <= window_s:
                    end += 1
                count = end - start
                if count >= threshold:
                    sample = ordered[start:end]
                    trace_id = str(sample[0].get("trace_id") or "")
                    action_id = str(sample[0].get("action_id") or "")
                    run_id = str(sample[0].get("run_id") or "unknown")
                    alerts.append(
                        new_alert(
                            idx=idx,
                            run_id=run_id,
                            trace_id=trace_id,
                            action_id=action_id,
                            rule_id="cookie_churn",
                            title="Cookie churn detected",
                            severity=str(rules.get("cookie_churn", {}).get("severity_default") or "medium"),
                            reason=f"{count} cookie events for domain '{domain}' in {window_s}s.",
                            repro_hint="Inspect Set-Cookie refresh/rotation and session invalidation behavior.",
                            event_ids=[str(e.get("event_id") or "") for e in sample if e.get("event_id")],
                        )
                    )
                    idx += 1
                    break

    if enabled("schema_drift"):
        endpoint_keys: Dict[str, Counter[str]] = defaultdict(Counter)
        endpoint_event_ids: Dict[str, List[str]] = defaultdict(list)
        response_rows = [r for r in rows if r.get("event_type") == "network_response_event"]
        for row in response_rows:
            url = event_url(row)
            path = urlparse(url).path if url else ""
            if not path:
                continue
            body = event_body_preview(row)
            if not body:
                continue
            try:
                loaded = json.loads(body)
            except Exception:
                continue
            signature = "|".join(sorted(set(flatten_keys(loaded))))
            if not signature:
                continue
            endpoint_keys[path][signature] += 1
            if row.get("event_id"):
                endpoint_event_ids[path].append(str(row.get("event_id")))
        variant_threshold = rule_threshold(rules.get("schema_drift", {}), "variant_count", 2)
        for path, signatures in endpoint_keys.items():
            if len(signatures) >= variant_threshold:
                alerts.append(
                    new_alert(
                        idx=idx,
                        run_id=str((rows[0].get("run_id") if rows else "unknown") or "unknown"),
                        trace_id="",
                        action_id="",
                        rule_id="schema_drift",
                        title="Response schema drift",
                        severity=str(rules.get("schema_drift", {}).get("severity_default") or "high"),
                        reason=f"Endpoint path '{path}' observed {len(signatures)} response key signatures.",
                        repro_hint="Compare field matrix variants and update parser mapping guards.",
                        event_ids=endpoint_event_ids[path][:50],
                    )
                )
                idx += 1

    alerts.sort(
        key=lambda a: (
            SEVERITY_RANK.get(str(a.get("severity") or "").lower(), 99),
            str(a.get("ts_utc") or ""),
        )
    )
    return alerts


def list_presets() -> Dict[str, Dict[str, Any]]:
    payload = usecase_presets_contract()
    presets = payload.get("presets", [])
    out: Dict[str, Dict[str, Any]] = {}
    if isinstance(presets, list):
        for preset in presets:
            if isinstance(preset, dict):
                pid = str(preset.get("id") or "")
                if pid:
                    out[pid] = preset
    return out


def apply_preset_filters(rows: List[Dict[str, Any]], preset_id: str) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    presets = list_presets()
    preset = presets.get(preset_id)
    if not preset:
        raise SystemExit(f"unknown preset: {preset_id}")

    filters = preset.get("filters", {})
    if not isinstance(filters, dict):
        filters = {}

    namespace = argparse.Namespace(
        domain=str(filters.get("domain") or ""),
        path=str(filters.get("path") or ""),
        method=str(filters.get("method") or ""),
        status=str(filters.get("status") or ""),
        mime=str(filters.get("mime") or ""),
        header_key=str(filters.get("header_key") or ""),
        cookie_key=str(filters.get("cookie_key") or ""),
        body_regex=str(filters.get("body_regex") or ""),
        action_id=str(filters.get("action_id") or ""),
        screen_id=str(filters.get("screen_id") or ""),
        phase_id=str(filters.get("phase_id") or ""),
        host_class=str(filters.get("host_class") or ""),
        time_from=str(filters.get("time_from") or ""),
        time_to=str(filters.get("time_to") or ""),
        limit=int(filters.get("limit") or 0),
    )
    filtered = match_filters(rows, namespace)
    return filtered, preset


def write_triage_alerts(runtime_dir: pathlib.Path, alerts: List[Dict[str, Any]]) -> pathlib.Path:
    paths = alert_paths(runtime_dir)
    write_jsonl(paths["triage_alerts"], alerts)
    return paths["triage_alerts"]


def read_triage_alerts(runtime_dir: pathlib.Path) -> List[Dict[str, Any]]:
    return read_jsonl(alert_paths(runtime_dir)["triage_alerts"])


def read_bookmarks(runtime_dir: pathlib.Path) -> List[Dict[str, Any]]:
    path = alert_paths(runtime_dir)["triage_bookmarks"]
    if not path.exists():
        return []
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return []
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    return []


def write_bookmarks(runtime_dir: pathlib.Path, rows: List[Dict[str, Any]]) -> pathlib.Path:
    path = alert_paths(runtime_dir)["triage_bookmarks"]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(rows, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    return path


def write_triage_state(runtime_dir: pathlib.Path, state: Dict[str, Any]) -> pathlib.Path:
    path = alert_paths(runtime_dir)["triage_state"]
    write_json(path, state)
    return path


def read_triage_state(runtime_dir: pathlib.Path) -> Dict[str, Any]:
    path = alert_paths(runtime_dir)["triage_state"]
    if not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}
    if isinstance(payload, dict):
        return payload
    return {}


def select_events_for_incident(rows: List[Dict[str, Any]], args: argparse.Namespace) -> List[Dict[str, Any]]:
    selected = rows
    if args.preset:
        selected, _ = apply_preset_filters(rows, args.preset)

    if args.trace_id:
        selected = [r for r in selected if str(r.get("trace_id") or "") == args.trace_id]
    if args.action_id:
        selected = [r for r in selected if str(r.get("action_id") or "") == args.action_id]
    if args.event_id:
        selected = [r for r in selected if str(r.get("event_id") or "") == args.event_id]
    if args.limit and args.limit > 0:
        selected = selected[: args.limit]
    return selected


def incident_bundle(runtime_dir: pathlib.Path, rows: List[Dict[str, Any]], args: argparse.Namespace) -> pathlib.Path:
    selected = select_events_for_incident(rows, args)
    if not selected:
        raise SystemExit("incident-pack produced no events; provide --trace-id/--action-id/--event-id or --preset")

    incident_id = args.incident_id or f"incident_{dt.datetime.utcnow().strftime('%Y%m%dT%H%M%SZ')}"
    root = alert_paths(runtime_dir)["incident_root"] / incident_id
    root.mkdir(parents=True, exist_ok=True)

    write_jsonl(root / "events.jsonl", selected)

    derived = ensure_derived(runtime_dir, rows)
    correlation_rows = read_jsonl(derived["correlation"])
    selected_trace_ids = {str(r.get("trace_id") or "") for r in selected}
    selected_action_ids = {str(r.get("action_id") or "") for r in selected}
    corr_slice = [
        row
        for row in correlation_rows
        if str(row.get("trace_id") or "") in selected_trace_ids
        or str(row.get("action_id") or "") in selected_action_ids
    ]
    write_jsonl(root / "correlation_slice.jsonl", corr_slice)

    for key in (
        "cookie_timeline",
        "required_headers",
        "field_matrix",
        "endpoint_candidates",
        "profile_draft",
        "profile_candidate",
        "replay_seed",
        "provenance_graph",
        "response_index",
    ):
        source = derived[key]
        if source.exists():
            shutil.copy2(source, root / source.name)

    triage_alerts = alert_paths(runtime_dir)["triage_alerts"]
    triage_bookmarks = alert_paths(runtime_dir)["triage_bookmarks"]
    triage_state = alert_paths(runtime_dir)["triage_state"]
    if triage_alerts.exists():
        shutil.copy2(triage_alerts, root / triage_alerts.name)
    if triage_bookmarks.exists():
        shutil.copy2(triage_bookmarks, root / triage_bookmarks.name)
    if triage_state.exists():
        shutil.copy2(triage_state, root / triage_state.name)

    response_index_path = derived["response_store_index"]
    response_index = load_contract_json(response_index_path)
    items = response_index.get("items", [])
    selected_event_ids = {str(r.get("event_id") or "") for r in selected}
    copied_response_refs = []
    if isinstance(items, list):
        store_dir = runtime_dir / "response_store"
        bundle_store_dir = root / "response_store"
        bundle_store_dir.mkdir(parents=True, exist_ok=True)
        for item in items:
            if not isinstance(item, dict):
                continue
            event_id = str(item.get("event_id") or "")
            path = str(item.get("path") or "")
            if event_id not in selected_event_ids or not path:
                continue
            source = store_dir / path
            if source.exists() and source.is_file():
                target = bundle_store_dir / source.name
                shutil.copy2(source, target)
                copied_response_refs.append({"event_id": event_id, "path": str(target.relative_to(root))})

    candidate_response_index = load_contract_json(derived["response_index"])
    body_items = candidate_response_index.get("items", [])
    copied_body_refs = []
    if isinstance(body_items, list):
        bundle_bodies_dir = root / "bodies"
        bundle_bodies_dir.mkdir(parents=True, exist_ok=True)
        for item in body_items:
            if not isinstance(item, dict):
                continue
            event_id = str(item.get("event_id") or "")
            body_ref = str(item.get("body_ref") or "")
            if event_id not in selected_event_ids or not body_ref:
                continue
            source = runtime_dir / body_ref
            if source.exists() and source.is_file():
                target = bundle_bodies_dir / source.name
                shutil.copy2(source, target)
                copied_body_refs.append({"event_id": event_id, "path": str(target.relative_to(root))})

    report = {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "incident_id": incident_id,
        "selected_event_count": len(selected),
        "selected_trace_ids": sorted(v for v in selected_trace_ids if v),
        "selected_action_ids": sorted(v for v in selected_action_ids if v),
        "response_refs": copied_response_refs,
        "body_refs": copied_body_refs,
    }
    write_json(root / "incident_report.json", report)
    return root


def select_rows_for_replay(rows: List[Dict[str, Any]], args: argparse.Namespace, runtime_dir: pathlib.Path) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    selected = rows
    source = {"incident_id": "", "preset": "", "trace_id": "", "action_id": "", "event_id": ""}

    if args.incident_id:
        incident_events = alert_paths(runtime_dir)["incident_root"] / args.incident_id / "events.jsonl"
        if not incident_events.exists():
            raise SystemExit(f"incident not found: {args.incident_id}")
        selected = read_jsonl(incident_events)
        source["incident_id"] = args.incident_id

    if args.preset:
        selected, _ = apply_preset_filters(selected, args.preset)
        source["preset"] = args.preset

    if args.trace_id:
        selected = [row for row in selected if str(row.get("trace_id") or "") == args.trace_id]
        source["trace_id"] = args.trace_id
    if args.action_id:
        selected = [row for row in selected if str(row.get("action_id") or "") == args.action_id]
        source["action_id"] = args.action_id
    if args.event_id:
        selected = [row for row in selected if str(row.get("event_id") or "") == args.event_id]
        source["event_id"] = args.event_id
    if args.limit and args.limit > 0:
        selected = selected[: args.limit]
    return selected, source


def replay_seed_payload(selected_rows: List[Dict[str, Any]], args: argparse.Namespace, source_meta: Dict[str, Any]) -> Dict[str, Any]:
    seed = build_replay_seed(selected_rows)
    if args.limit and args.limit > 0:
        seed["steps"] = seed.get("steps", [])[: args.limit]
    seed["source"] = source_meta
    seed["preset"] = args.preset or ""
    seed["created_at_utc"] = utc_now()
    seed["schema_version"] = 1
    return seed


def status_chain_summary(rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    grouped: Dict[Tuple[str, str], List[str]] = defaultdict(list)
    for row in rows:
        if row.get("event_type") != "network_response_event":
            continue
        trace_id = str(row.get("trace_id") or "")
        action_id = str(row.get("action_id") or "")
        grouped[(trace_id, action_id)].append(event_status(row))

    chains = []
    status_counts: Counter[str] = Counter()
    for (trace_id, action_id), statuses in grouped.items():
        cleaned = [status for status in statuses if status]
        for status in cleaned:
            status_counts[status] += 1
        chains.append(
            {
                "trace_id": trace_id,
                "action_id": action_id,
                "statuses": cleaned[:20],
            }
        )

    return {
        "chain_count": len(chains),
        "status_counts": dict(status_counts),
        "chains": chains[:250],
    }


def sampled_responses(rows: List[Dict[str, Any]], limit: int = 20) -> List[Dict[str, Any]]:
    response_rows = [row for row in rows if row.get("event_type") == "network_response_event"][: max(limit, 1)]
    sampled = []
    for row in response_rows:
        sampled.append(
            {
                "event_id": row.get("event_id"),
                "trace_id": row.get("trace_id"),
                "action_id": row.get("action_id"),
                "status": event_status(row),
                "mime": event_mime(row),
                "url": event_url(row),
                "body_preview": event_body_preview(row)[:240],
            }
        )
    return sampled


def response_form_signature(rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    signatures: Dict[str, Counter[str]] = defaultdict(Counter)
    for row in rows:
        if row.get("event_type") != "network_response_event":
            continue
        url = event_url(row)
        path = urlparse(url).path if url else ""
        if not path:
            continue
        body = event_body_preview(row)
        if not body:
            continue
        try:
            parsed = json.loads(body)
        except Exception:
            continue
        sig = stable_hash(sorted(set(flatten_keys(parsed))))
        signatures[path][sig] += 1

    out = []
    for path, counter in signatures.items():
        top = [{"signature": sig, "count": count} for sig, count in counter.most_common(3)]
        out.append({"path": path, "variants": top})
    return {
        "path_count": len(out),
        "paths": sorted(out, key=lambda item: item["path"])[:200],
    }


def field_matrix_signature(rows: List[Dict[str, Any]]) -> str:
    field_matrix = build_field_matrix(rows)
    keys = [item.get("field") for item in field_matrix.get("keys", []) if isinstance(item, dict)]
    return stable_hash(keys)


def build_replay_baseline(rows: List[Dict[str, Any]], baseline_name: str, preset: str, source_meta: Dict[str, Any]) -> Dict[str, Any]:
    required_headers = build_required_headers_active_replay(
        rows,
        timeout_ms=ACTIVE_REPLAY_ROLLUP_TIMEOUT_MS,
        allow_unsafe_methods=False,
    )
    endpoint_candidates = build_endpoint_candidates(rows)
    response_signature = response_form_signature(rows)

    return {
        "schema_version": 1,
        "baseline_name": baseline_name,
        "created_at_utc": utc_now(),
        "preset": preset,
        "source": source_meta,
        "required_headers": required_headers,
        "endpoint_candidates": endpoint_candidates,
        "field_matrix_signature": field_matrix_signature(rows),
        "status_chain_summary": status_chain_summary(rows),
        "sampled_responses": sampled_responses(rows, limit=20),
        "response_form_signature": response_signature,
    }


def read_baseline_manifest(runtime_dir: pathlib.Path) -> Dict[str, Any]:
    manifest_path = replay_paths(runtime_dir)["manifest"]
    if not manifest_path.exists():
        return {
            "schema_version": 1,
            "generated_at_utc": utc_now(),
            "policy": "named_baseline_set",
            "active_baseline": "",
            "baselines": [],
        }
    try:
        payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    except Exception:
        return {
            "schema_version": 1,
            "generated_at_utc": utc_now(),
            "policy": "named_baseline_set",
            "active_baseline": "",
            "baselines": [],
        }
    if isinstance(payload, dict):
        payload.setdefault("schema_version", 1)
        payload.setdefault("policy", "named_baseline_set")
        payload.setdefault("active_baseline", "")
        payload.setdefault("baselines", [])
        return payload
    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "policy": "named_baseline_set",
        "active_baseline": "",
        "baselines": [],
    }


def write_baseline_manifest(runtime_dir: pathlib.Path, manifest: Dict[str, Any]) -> pathlib.Path:
    manifest["generated_at_utc"] = utc_now()
    path = replay_paths(runtime_dir)["manifest"]
    write_json(path, manifest)
    return path


def create_named_baseline(runtime_dir: pathlib.Path, baseline: Dict[str, Any]) -> Dict[str, Any]:
    paths = replay_paths(runtime_dir)
    baseline_name = str(baseline.get("baseline_name") or "").strip()
    if not baseline_name:
        raise SystemExit("replay baseline-create requires --baseline-name")

    ts = dt.datetime.utcnow().strftime("%Y%m%dT%H%M%SZ")
    baseline_dir = paths["baseline_root"] / baseline_name
    baseline_dir.mkdir(parents=True, exist_ok=True)
    baseline_path = baseline_dir / f"baseline_{ts}.json"
    write_json(baseline_path, baseline)

    manifest = read_baseline_manifest(runtime_dir)
    rel = str(baseline_path.relative_to(runtime_dir))
    entry = {
        "baseline_name": baseline_name,
        "preset": baseline.get("preset") or "",
        "created_at_utc": baseline.get("created_at_utc") or utc_now(),
        "path": rel,
        "field_matrix_signature": baseline.get("field_matrix_signature") or "",
        "source": baseline.get("source") or {},
    }
    baselines = manifest.get("baselines")
    if not isinstance(baselines, list):
        baselines = []
    baselines.append(entry)
    manifest["baselines"] = baselines[-500:]
    manifest["active_baseline"] = rel
    write_baseline_manifest(runtime_dir, manifest)

    return {
        "manifest": manifest,
        "entry": entry,
        "baseline_path": baseline_path,
    }


def resolve_baseline(runtime_dir: pathlib.Path, baseline_name: str) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    manifest = read_baseline_manifest(runtime_dir)
    entries = manifest.get("baselines", [])
    if not isinstance(entries, list) or not entries:
        raise SystemExit("no baseline available; create one with replay baseline-create")

    selected: Optional[Dict[str, Any]] = None
    if baseline_name:
        matches = [
            item
            for item in entries
            if isinstance(item, dict) and str(item.get("baseline_name") or "") == baseline_name
        ]
        if not matches:
            raise SystemExit(f"baseline not found: {baseline_name}")
        matches.sort(key=lambda item: str(item.get("created_at_utc") or ""))
        selected = matches[-1]
    else:
        active_path = str(manifest.get("active_baseline") or "")
        if active_path:
            for item in entries:
                if isinstance(item, dict) and str(item.get("path") or "") == active_path:
                    selected = item
                    break
        if selected is None:
            ordered = [item for item in entries if isinstance(item, dict)]
            ordered.sort(key=lambda item: str(item.get("created_at_utc") or ""))
            if ordered:
                selected = ordered[-1]

    if not selected:
        raise SystemExit("unable to resolve baseline")

    path_rel = str(selected.get("path") or "")
    baseline_path = runtime_dir / path_rel
    if not baseline_path.exists():
        raise SystemExit(f"baseline file missing: {path_rel}")

    baseline = load_contract_json(baseline_path)
    if not baseline:
        raise SystemExit(f"invalid baseline payload: {path_rel}")
    return baseline, selected


def normalize_required_headers(payload: Dict[str, Any]) -> Dict[str, set]:
    out: Dict[str, set] = {}
    hosts = payload.get("hosts", {})
    if not isinstance(hosts, dict):
        return out
    for host, data in hosts.items():
        if not isinstance(data, dict):
            continue
        rows = data.get("top_headers", [])
        headers = set()
        if isinstance(rows, list):
            for row in rows:
                if isinstance(row, dict):
                    header = str(row.get("header") or "").lower()
                    if header:
                        headers.add(header)
        out[str(host)] = headers
    return out


def normalize_endpoint_paths(payload: Dict[str, Any]) -> set:
    candidates = payload.get("candidates", [])
    paths = set()
    if isinstance(candidates, list):
        for candidate in candidates[:80]:
            if not isinstance(candidate, dict):
                continue
            path = str(candidate.get("path") or "")
            if path:
                paths.add(path)
    return paths


def normalize_response_signature_paths(payload: Dict[str, Any]) -> set:
    out = set()
    paths = payload.get("paths", [])
    if not isinstance(paths, list):
        return out
    for item in paths:
        if not isinstance(item, dict):
            continue
        path = str(item.get("path") or "")
        variants = item.get("variants", [])
        if not path or not isinstance(variants, list) or not variants:
            continue
        top = variants[0]
        if isinstance(top, dict):
            sig = str(top.get("signature") or "")
            if sig:
                out.add(f"{path}:{sig}")
    return out


def assertion_config_map() -> Dict[str, Dict[str, Any]]:
    cfg = replay_assertions_contract()
    rows = cfg.get("assertions", [])
    out: Dict[str, Dict[str, Any]] = {}
    if isinstance(rows, list):
        for row in rows:
            if isinstance(row, dict):
                aid = str(row.get("id") or "")
                if aid:
                    out[aid] = row
    return out


def evaluate_replay_diff(baseline: Dict[str, Any], current: Dict[str, Any]) -> Dict[str, Any]:
    assertions = assertion_config_map()
    checks: List[Dict[str, Any]] = []

    base_headers = normalize_required_headers(baseline.get("required_headers", {}))
    cur_headers = normalize_required_headers(current.get("required_headers", {}))
    missing_by_host: Dict[str, List[str]] = {}
    for host, expected in base_headers.items():
        actual = cur_headers.get(host, set())
        missing = sorted(list(expected - actual))
        if missing:
            missing_by_host[host] = missing
    checks.append(
        {
            "id": "required_headers",
            "severity": assertions.get("required_headers", {}).get("severity", "high"),
            "passed": len(missing_by_host) == 0,
            "details": {"missing_by_host": missing_by_host},
            "message": "Required headers present on observed hosts." if not missing_by_host else "Some required headers are missing.",
        }
    )

    base_paths = normalize_endpoint_paths(baseline.get("endpoint_candidates", {}))
    cur_paths = normalize_endpoint_paths(current.get("endpoint_candidates", {}))
    overlap = len(base_paths & cur_paths)
    overlap_ratio = (overlap / len(base_paths)) if base_paths else 1.0
    min_ratio = float(assertions.get("endpoint_shape", {}).get("thresholds", {}).get("overlap_min", 0.6))
    checks.append(
        {
            "id": "endpoint_shape",
            "severity": assertions.get("endpoint_shape", {}).get("severity", "high"),
            "passed": overlap_ratio >= min_ratio,
            "details": {
                "baseline_path_count": len(base_paths),
                "current_path_count": len(cur_paths),
                "overlap": overlap,
                "overlap_ratio": round(overlap_ratio, 4),
                "threshold": min_ratio,
            },
            "message": "Endpoint shape overlap within threshold." if overlap_ratio >= min_ratio else "Endpoint path overlap dropped below threshold.",
        }
    )

    base_sig = str(baseline.get("field_matrix_signature") or "")
    cur_sig = str(current.get("field_matrix_signature") or "")
    checks.append(
        {
            "id": "field_matrix_signature",
            "severity": assertions.get("field_matrix_signature", {}).get("severity", "critical"),
            "passed": bool(base_sig) and base_sig == cur_sig,
            "details": {"baseline": base_sig, "current": cur_sig},
            "message": "Field matrix signature matches baseline." if base_sig and base_sig == cur_sig else "Field matrix signature mismatch.",
        }
    )

    base_status = baseline.get("status_chain_summary", {}).get("status_counts", {})
    cur_status = current.get("status_chain_summary", {}).get("status_counts", {})
    base_keys = set(str(key) for key in base_status.keys()) if isinstance(base_status, dict) else set()
    cur_keys = set(str(key) for key in cur_status.keys()) if isinstance(cur_status, dict) else set()
    key_ratio = (len(base_keys & cur_keys) / len(base_keys)) if base_keys else 1.0
    status_min = float(assertions.get("auth_status_chain", {}).get("thresholds", {}).get("key_overlap_min", 0.75))
    checks.append(
        {
            "id": "auth_status_chain",
            "severity": assertions.get("auth_status_chain", {}).get("severity", "critical"),
            "passed": key_ratio >= status_min,
            "details": {
                "baseline_statuses": sorted(list(base_keys)),
                "current_statuses": sorted(list(cur_keys)),
                "overlap_ratio": round(key_ratio, 4),
                "threshold": status_min,
            },
            "message": "Status chain remains compatible." if key_ratio >= status_min else "Status chain diverges from baseline.",
        }
    )

    base_form = normalize_response_signature_paths(baseline.get("response_form_signature", {}))
    cur_form = normalize_response_signature_paths(current.get("response_form_signature", {}))
    form_ratio = (len(base_form & cur_form) / len(base_form)) if base_form else 1.0
    form_min = float(assertions.get("response_form_signature", {}).get("thresholds", {}).get("overlap_min", 0.5))
    checks.append(
        {
            "id": "response_form_signature",
            "severity": assertions.get("response_form_signature", {}).get("severity", "medium"),
            "passed": form_ratio >= form_min,
            "details": {
                "baseline_signature_count": len(base_form),
                "current_signature_count": len(cur_form),
                "overlap_ratio": round(form_ratio, 4),
                "threshold": form_min,
            },
            "message": "Response form signatures compatible." if form_ratio >= form_min else "Response form signature drift detected.",
        }
    )

    failed = [check for check in checks if not check["passed"]]
    failed_by_severity = Counter(str(check.get("severity") or "unknown") for check in failed)

    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "checks": checks,
        "failed_count": len(failed),
        "failed_by_severity": dict(failed_by_severity),
    }


def run_playwright_seed(seed: Dict[str, Any], limit: int, timeout_ms: int) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    steps = seed.get("steps", [])
    if not isinstance(steps, list):
        steps = []
    steps = steps[: max(limit, 0)] if limit > 0 else steps

    meta = {
        "runner": "playwright",
        "started_at_utc": utc_now(),
        "step_count": len(steps),
        "classified_failures": 0,
    }

    try:
        from playwright.sync_api import sync_playwright
    except Exception as exc:
        results = []
        for index, step in enumerate(steps):
            results.append(
                {
                    "schema_version": 1,
                    "ts_utc": utc_now(),
                    "step_index": index,
                    "event_id": step.get("event_id"),
                    "url": step.get("url"),
                    "method": step.get("method"),
                    "ok": False,
                    "classification": "playwright_unavailable",
                    "error": str(exc),
                    "duration_ms": 0,
                }
            )
        meta["classified_failures"] = len(results)
        meta["finished_at_utc"] = utc_now()
        return results, meta

    results: List[Dict[str, Any]] = []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context()
        page = context.new_page()
        for index, step in enumerate(steps):
            started = dt.datetime.now(dt.timezone.utc)
            url = str(step.get("url") or "")
            method = str(step.get("method") or "GET").upper()
            result = {
                "schema_version": 1,
                "ts_utc": utc_now(),
                "step_index": index,
                "event_id": step.get("event_id"),
                "url": url,
                "method": method,
                "ok": False,
                "classification": "",
                "response_status": None,
                "final_url": "",
                "error": "",
                "duration_ms": 0,
            }

            if not url:
                result["classification"] = "invalid_step"
                result["error"] = "missing url"
            elif method != "GET":
                result["classification"] = "unsupported_method"
                result["error"] = f"method {method} not replayed by browser runner"
            else:
                try:
                    response = page.goto(url, wait_until="domcontentloaded", timeout=timeout_ms)
                    result["final_url"] = page.url
                    if response is None:
                        result["classification"] = "no_response"
                    else:
                        result["response_status"] = response.status
                        if response.status < 400:
                            result["ok"] = True
                            result["classification"] = "passed"
                        else:
                            result["classification"] = "http_error"
                except Exception as exc:
                    result["classification"] = "navigation_error"
                    result["error"] = str(exc)

            finished = dt.datetime.now(dt.timezone.utc)
            result["duration_ms"] = int((finished - started).total_seconds() * 1000)
            if not result["ok"]:
                meta["classified_failures"] = int(meta.get("classified_failures") or 0) + 1
            results.append(result)

        context.close()
        browser.close()

    meta["finished_at_utc"] = utc_now()
    return results, meta


def write_replay_results(runtime_dir: pathlib.Path, results: List[Dict[str, Any]]) -> pathlib.Path:
    path = replay_paths(runtime_dir)["results"]
    write_jsonl(path, results)
    return path


def build_replay_report_payload(
    baseline_entry: Dict[str, Any],
    diff_payload: Dict[str, Any],
    results_meta: Dict[str, Any],
    results: List[Dict[str, Any]],
) -> Dict[str, Any]:
    policy = replay_assertions_contract().get("policy", {})
    if not isinstance(policy, dict):
        policy = {}
    ci_block_on = policy.get("ci_block_on", ["critical"])
    if not isinstance(ci_block_on, list):
        ci_block_on = ["critical"]

    failed_by = diff_payload.get("failed_by_severity", {})
    if not isinstance(failed_by, dict):
        failed_by = {}

    ci_block = any(int(failed_by.get(str(level), 0)) > 0 for level in ci_block_on)
    warnings = [
        check.get("message")
        for check in diff_payload.get("checks", [])
        if isinstance(check, dict) and not check.get("passed")
    ]
    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "policy_mode": str(policy.get("mode") or "warn_then_block"),
        "ci_block_on": ci_block_on,
        "ci_block": ci_block,
        "baseline": baseline_entry,
        "diff": diff_payload,
        "run_meta": results_meta,
        "result_count": len(results),
        "warnings": warnings,
    }


def print_json(obj: Any) -> None:
    print(json.dumps(obj, ensure_ascii=True, indent=2))


def do_trace(action: str, rows: List[Dict[str, Any]], args: argparse.Namespace, runtime_dir: pathlib.Path) -> int:
    filtered = match_filters(rows, args)

    if action == "query":
        for row in filtered:
            print(json.dumps(row, ensure_ascii=True))
        return 0

    if action == "tail":
        tail_n = args.limit or 50
        for row in filtered[-tail_n:]:
            print(json.dumps(row, ensure_ascii=True))
        return 0

    if action == "export":
        export_dir = runtime_dir / "exports"
        export_dir.mkdir(parents=True, exist_ok=True)
        target = export_dir / f"trace_export_{dt.datetime.utcnow().strftime('%Y%m%dT%H%M%SZ')}.jsonl"
        write_jsonl(target, filtered)
        print(str(target))
        return 0

    if action == "correlate":
        paths = ensure_derived(runtime_dir, rows)
        print(str(paths["correlation"]))
        return 0

    if action == "diff":
        if not args.left or not args.right:
            raise SystemExit("trace diff requires --left and --right")
        left = read_jsonl(pathlib.Path(args.left))
        right = read_jsonl(pathlib.Path(args.right))
        left_counter = Counter(str(r.get("event_type") or "unknown") for r in left)
        right_counter = Counter(str(r.get("event_type") or "unknown") for r in right)
        out = {
            "left": str(args.left),
            "right": str(args.right),
            "left_count": len(left),
            "right_count": len(right),
            "left_by_type": left_counter,
            "right_by_type": right_counter,
        }
        print_json(out)
        return 0

    raise SystemExit(f"unsupported trace action: {action}")


def do_cookies(action: str, rows: List[Dict[str, Any]], args: argparse.Namespace, runtime_dir: pathlib.Path) -> int:
    paths = ensure_derived(runtime_dir, rows)
    timeline = json.loads(paths["cookie_timeline"].read_text(encoding="utf-8"))

    if action == "timeline":
        print_json(timeline)
        return 0
    if action == "set-events":
        events = [e for e in timeline.get("timeline", []) if "set" in json.dumps(event_payload(e)).lower()]
        print_json({"count": len(events), "events": events})
        return 0
    if action == "refresh-events":
        events = [
            e
            for e in timeline.get("timeline", [])
            if any(k in json.dumps(event_payload(e)).lower() for k in ("refresh", "rotat", "renew"))
        ]
        print_json({"count": len(events), "events": events})
        return 0
    if action == "domain-view":
        print_json(timeline.get("by_domain", {}))
        return 0

    raise SystemExit(f"unsupported cookies action: {action}")


def do_headers(action: str, rows: List[Dict[str, Any]], args: argparse.Namespace, runtime_dir: pathlib.Path) -> int:
    paths = ensure_derived(runtime_dir, rows)
    required_headers = json.loads(paths["required_headers"].read_text(encoding="utf-8"))

    if action == "infer-required":
        print_json(required_headers)
        return 0

    if action == "infer-required-active":
        selected_rows = match_filters(rows, args)
        if not selected_rows:
            selected_rows = rows
        payload = build_required_headers_active_replay(
            selected_rows,
            timeout_ms=max(int(args.timeout_ms or 0), 1000),
            allow_unsafe_methods=bool(getattr(args, "allow_unsafe_replay_methods", False)),
        )
        endpoint_sets = list(payload.get("endpoint_minimal_sets") or [])
        replay_payload = replay_requirements_from_endpoint_sets(
            endpoint_sets,
            inference_mode=str(payload.get("inference_mode") or "active_http_replay"),
        )
        replay_payload = annotate_replay_truncation_visibility(replay_payload, selected_rows)
        cookies_payload = build_required_cookies_report_for_endpoint_sets(
            selected_rows,
            endpoint_sets=endpoint_sets,
            inference_mode=str(payload.get("inference_mode") or "active_http_replay"),
        )
        write_json(runtime_dir / "required_headers_report.active.json", payload)
        write_json(runtime_dir / "required_cookies_report.active.json", cookies_payload)
        write_json(runtime_dir / "replay_requirements.active.json", replay_payload)
        print_json(
            {
                "required_headers": payload,
                "required_cookies": cookies_payload,
                "replay_requirements": replay_payload,
            }
        )
        return 0

    if action == "token-deps":
        tokenish = {}
        for host, data in required_headers.get("hosts", {}).items():
            hits = [h for h in data.get("top_headers", []) if any(k in h.get("header", "") for k in ("authorization", "cookie", "token", "x-"))]
            if hits:
                tokenish[host] = hits
        print_json({"schema_version": 1, "generated_at_utc": utc_now(), "hosts": tokenish})
        return 0

    if action == "auth-chain":
        auth_related = [
            r
            for r in rows
            if r.get("event_type") in {"auth_event", "network_request_event", "network_response_event"}
            and (
                "auth" in json.dumps(r).lower()
                or event_status(r) in {"401", "403"}
                or "authorization" in " ".join(event_headers(r).keys()).lower()
            )
        ]
        print_json({"schema_version": 1, "generated_at_utc": utc_now(), "events": auth_related[-400:]})
        return 0

    raise SystemExit(f"unsupported headers action: {action}")


def do_responses(action: str, rows: List[Dict[str, Any]], args: argparse.Namespace, runtime_dir: pathlib.Path) -> int:
    response_rows = [r for r in rows if r.get("event_type") == "network_response_event"]
    filtered = match_filters(response_rows, args)

    if action == "raw":
        if not args.event_id:
            raise SystemExit("responses raw requires --event-id")
        for row in response_rows:
            if str(row.get("event_id") or "") == args.event_id:
                print_json(row)
                return 0
        raise SystemExit(f"event not found: {args.event_id}")

    if action == "filter":
        for row in filtered:
            print(json.dumps(row, ensure_ascii=True))
        return 0

    if action == "grep":
        if not args.body_regex:
            raise SystemExit("responses grep requires --body-regex")
        for row in filtered:
            print(json.dumps(row, ensure_ascii=True))
        return 0

    if action == "sample":
        sample_n = args.limit or 20
        compact = [
            {
                "event_id": row.get("event_id"),
                "ts_utc": row.get("ts_utc"),
                "status": event_status(row),
                "mime": event_mime(row),
                "url": event_url(row),
                "body_preview": event_body_preview(row)[:300],
            }
            for row in filtered[:sample_n]
        ]
        print_json({"count": len(compact), "items": compact})
        ensure_derived(runtime_dir, rows)
        return 0

    raise SystemExit(f"unsupported responses action: {action}")


def do_mapping(action: str, rows: List[Dict[str, Any]], _args: argparse.Namespace, runtime_dir: pathlib.Path) -> int:
    paths = ensure_derived(runtime_dir, rows)

    mapping = {
        "candidate-endpoints": paths["endpoint_candidates"],
        "field-matrix": paths["field_matrix"],
        "profile-draft": paths["profile_draft"],
        "replay-seed": paths["replay_seed"],
    }
    target = mapping.get(action)
    if target is None:
        raise SystemExit(f"unsupported mapping action: {action}")

    print(target.read_text(encoding="utf-8"))
    return 0


def do_provenance(action: str, rows: List[Dict[str, Any]], args: argparse.Namespace, runtime_dir: pathlib.Path) -> int:
    provenance_rows = [row for row in rows if row.get("event_type") == "provenance_event"]
    filtered = match_filters(provenance_rows, args)

    if action == "query":
        for row in filtered:
            print(json.dumps(row, ensure_ascii=True))
        return 0

    if action == "graph":
        paths = ensure_derived(runtime_dir, rows)
        print(paths["provenance_graph"].read_text(encoding="utf-8"))
        return 0

    if action == "export":
        export_dir = runtime_dir / "exports"
        export_dir.mkdir(parents=True, exist_ok=True)
        target = export_dir / f"provenance_export_{dt.datetime.utcnow().strftime('%Y%m%dT%H%M%SZ')}.jsonl"
        write_jsonl(target, filtered)
        print(str(target))
        return 0

    raise SystemExit(f"unsupported provenance action: {action}")


def do_housekeeping(action: str, rows: List[Dict[str, Any]], args: argparse.Namespace, runtime_dir: pathlib.Path) -> int:
    if action == "reindex":
        paths = ensure_derived(runtime_dir, rows)
        print_json({"reindexed": {k: str(v) for k, v in paths.items()}})
        return 0

    if action == "validate":
        report = pipeline_quality_report(runtime_dir, rows, quality_host=args.quality_host)
        report_path = runtime_dir / "pipeline_ready_report.json"
        write_json(report_path, report)
        print_json(report)
        return 0 if bool(report.get("pipeline_ready")) else 2

    if action in {"pack", "compress"}:
        archive_dir = runtime_dir.parent / "archive"
        archive_dir.mkdir(parents=True, exist_ok=True)
        out = archive_dir / f"mapper_toolkit_pack_{dt.datetime.utcnow().strftime('%Y%m%dT%H%M%SZ')}.tar.gz"
        with tarfile.open(out, "w:gz") as tar:
            tar.add(runtime_dir, arcname=runtime_dir.name)
        print(str(out))
        return 0

    if action == "purge":
        if not args.yes:
            raise SystemExit("housekeeping purge requires --yes")
        for rel in [
            "events/runtime_events.jsonl",
            "events.jsonl",
            "extraction_events.jsonl",
            "truncation_events.jsonl",
            "events.meta.json",
            "requests.normalized.jsonl",
            "responses.normalized.jsonl",
            "events/indexer_state.json",
            "correlation_index.jsonl",
            "cookie_timeline.json",
            "required_headers_report.json",
            "required_cookies_report.json",
            "replay_requirements.json",
            "field_matrix.json",
            "endpoint_candidates.json",
            "site_profile.draft.json",
            "profile_candidate.json",
            "provenance_graph.json",
            "provenance_registry.json",
            "response_index.json",
            "replay_seed.json",
            "replay_results.jsonl",
            "replay_report.json",
            "baseline_diff.json",
            "baseline_manifest.json",
            "triage_alerts.jsonl",
            "triage_bookmarks.json",
            "triage_session_state.json",
            "pipeline_ready_report.json",
        ]:
            target = runtime_dir / rel
            if target.exists():
                target.unlink()
        response_store = runtime_dir / "response_store"
        if response_store.exists():
            shutil.rmtree(response_store)
        bodies = runtime_dir / "bodies"
        if bodies.exists():
            shutil.rmtree(bodies)
        incident_root = runtime_dir / "incident_bundle"
        if incident_root.exists():
            shutil.rmtree(incident_root)
        replay_root = runtime_dir / "replay"
        if replay_root.exists():
            shutil.rmtree(replay_root)
        print("purge_complete")
        return 0

    raise SystemExit(f"unsupported housekeeping action: {action}")


def do_triage(action: str, rows: List[Dict[str, Any]], args: argparse.Namespace, runtime_dir: pathlib.Path) -> int:
    paths = alert_paths(runtime_dir)

    if action == "start":
        selected_rows = rows
        selected_preset: Optional[Dict[str, Any]] = None
        if args.preset:
            selected_rows, selected_preset = apply_preset_filters(rows, args.preset)

        rule_ids: List[str] = []
        if selected_preset and isinstance(selected_preset.get("anomaly_rules"), list):
            rule_ids = [str(item) for item in selected_preset.get("anomaly_rules", [])]

        alerts = detect_triage_alerts(selected_rows, only_rules=rule_ids or None)
        write_triage_alerts(runtime_dir, alerts)
        ensure_derived(runtime_dir, rows)
        state = {
            "schema_version": 1,
            "status": "running",
            "started_at_utc": utc_now(),
            "preset": args.preset or "",
            "window_limit": args.window_limit or 400,
            "sampling_rate": args.sampling_rate or 1,
            "last_alert_count": len(alerts),
        }
        write_triage_state(runtime_dir, state)
        print_json({"status": "running", "alerts": len(alerts), "state_path": str(paths["triage_state"])})
        return 0

    if action == "tail":
        state = read_triage_state(runtime_dir)
        sample_rate = int(state.get("sampling_rate") or args.sampling_rate or 1)
        sample_rate = max(sample_rate, 1)
        selected = rows
        preset = str(state.get("preset") or "")
        if preset:
            try:
                selected, _ = apply_preset_filters(rows, preset)
            except SystemExit:
                selected = rows
        if sample_rate > 1:
            selected = [row for idx, row in enumerate(selected) if idx % sample_rate == 0]
        limit = args.limit or int(state.get("window_limit") or 80)
        for row in selected[-limit:]:
            print(json.dumps(row, ensure_ascii=True))
        for alert in read_triage_alerts(runtime_dir)[- min(limit, 50):]:
            print(json.dumps(alert, ensure_ascii=True))
        return 0

    if action == "focus":
        selected = rows
        preset_payload: Optional[Dict[str, Any]] = None
        if args.preset:
            selected, preset_payload = apply_preset_filters(rows, args.preset)
        selected = match_filters(selected, args)

        rule_ids: List[str] = []
        if preset_payload and isinstance(preset_payload.get("anomaly_rules"), list):
            rule_ids = [str(item) for item in preset_payload.get("anomaly_rules", [])]
        alerts = detect_triage_alerts(selected, only_rules=rule_ids or None)
        write_triage_alerts(runtime_dir, alerts)
        derived = ensure_derived(runtime_dir, rows)

        generated_outputs = []
        if preset_payload and isinstance(preset_payload.get("target_outputs"), list):
            mapping = {
                "field_matrix": derived["field_matrix"],
                "required_headers_report": derived["required_headers"],
                "endpoint_candidates": derived["endpoint_candidates"],
                "site_profile.draft": derived["profile_draft"],
                "replay_seed": derived["replay_seed"],
            }
            for target in preset_payload.get("target_outputs", []):
                if str(target) in mapping:
                    generated_outputs.append(str(mapping[str(target)]))

        state = read_triage_state(runtime_dir)
        state.update(
            {
                "schema_version": 1,
                "status": state.get("status") or "running",
                "focused_at_utc": utc_now(),
                "preset": args.preset or str(state.get("preset") or ""),
                "focus": {
                    "trace_id": args.trace_id,
                    "action_id": args.action_id,
                    "screen_id": args.screen_id,
                    "phase_id": args.phase_id,
                    "host_class": args.host_class,
                    "domain": args.domain,
                    "path": args.path,
                    "limit": args.limit,
                },
                "last_alert_count": len(alerts),
            }
        )
        write_triage_state(runtime_dir, state)

        top_paths = Counter(urlparse(event_url(r)).path for r in selected if event_url(r)).most_common(10)
        print_json(
            {
                "preset": args.preset or "",
                "matched_events": len(selected),
                "alerts": len(alerts),
                "top_paths": [{"path": path, "count": count} for path, count in top_paths if path],
                "generated_outputs": generated_outputs,
            }
        )
        return 0

    if action == "anomalies":
        alerts = read_triage_alerts(runtime_dir)
        if args.severity:
            alerts = [a for a in alerts if str(a.get("severity") or "").lower() == args.severity.lower()]
        alerts.sort(
            key=lambda a: (
                SEVERITY_RANK.get(str(a.get("severity") or "").lower(), 99),
                str(a.get("ts_utc") or ""),
            )
        )
        if args.limit and args.limit > 0:
            alerts = alerts[: args.limit]
        print_json(
            {
                "count": len(alerts),
                "items": alerts,
            }
        )
        return 0

    if action == "bookmark":
        if not any([args.event_id, args.trace_id, args.action_id]):
            raise SystemExit("triage bookmark requires --event-id or --trace-id or --action-id")
        current = read_bookmarks(runtime_dir)
        entry = {
            "schema_version": 1,
            "bookmark_id": f"bm_{dt.datetime.utcnow().strftime('%Y%m%dT%H%M%SZ')}_{len(current)+1}",
            "created_at_utc": utc_now(),
            "event_id": args.event_id,
            "trace_id": args.trace_id,
            "action_id": args.action_id,
            "note": args.note or "",
        }
        current.append(entry)
        write_bookmarks(runtime_dir, current[-1000:])
        print_json(entry)
        return 0

    if action == "incident-pack":
        target = incident_bundle(runtime_dir, rows, args)
        print(str(target))
        return 0

    if action == "stop":
        state = read_triage_state(runtime_dir)
        state.update(
            {
                "schema_version": 1,
                "status": "stopped",
                "stopped_at_utc": utc_now(),
            }
        )
        write_triage_state(runtime_dir, state)
        print_json({"status": "stopped", "state_path": str(paths["triage_state"])})
        return 0

    raise SystemExit(f"unsupported triage action: {action}")


def do_replay(action: str, rows: List[Dict[str, Any]], args: argparse.Namespace, runtime_dir: pathlib.Path) -> int:
    paths = replay_paths(runtime_dir)
    paths["root"].mkdir(parents=True, exist_ok=True)
    paths["baseline_root"].mkdir(parents=True, exist_ok=True)

    if action == "seed":
        selected, source_meta = select_rows_for_replay(rows, args, runtime_dir)
        if not selected:
            raise SystemExit("replay seed produced no events")
        seed = replay_seed_payload(selected, args, source_meta)
        seed_path = runtime_dir / "replay_seed.json"
        write_json(seed_path, seed)
        print_json({"seed_path": str(seed_path), "step_count": len(seed.get("steps", [])), "source": source_meta})
        return 0

    if action == "baseline-create":
        selected, source_meta = select_rows_for_replay(rows, args, runtime_dir)
        if not selected:
            raise SystemExit("baseline-create produced no events")
        baseline = build_replay_baseline(
            rows=selected,
            baseline_name=args.baseline_name,
            preset=args.preset or "",
            source_meta=source_meta,
        )
        created = create_named_baseline(runtime_dir, baseline)
        print_json(
            {
                "baseline_path": str(created["baseline_path"]),
                "entry": created["entry"],
                "active_baseline": created["manifest"].get("active_baseline"),
            }
        )
        return 0

    if action == "baseline-list":
        manifest = read_baseline_manifest(runtime_dir)
        print_json(manifest)
        return 0

    if action == "run":
        seed_path = pathlib.Path(args.seed_path) if args.seed_path else (runtime_dir / "replay_seed.json")
        if not seed_path.exists():
            selected, source_meta = select_rows_for_replay(rows, args, runtime_dir)
            seed = replay_seed_payload(selected, args, source_meta)
            write_json(seed_path, seed)
        seed = load_contract_json(seed_path)
        if not seed:
            raise SystemExit(f"invalid seed payload: {seed_path}")

        timeout_ms = args.timeout_ms if args.timeout_ms > 0 else 15000
        limit = args.limit if args.limit > 0 else len(seed.get("steps", []))
        results, meta = run_playwright_seed(seed, limit=limit, timeout_ms=timeout_ms)
        result_path = write_replay_results(runtime_dir, results)

        summary = {
            "result_path": str(result_path),
            "step_count": len(results),
            "classified_failures": meta.get("classified_failures", 0),
            "runner": meta.get("runner"),
        }
        print_json(summary)
        return 0

    if action == "diff":
        baseline, baseline_entry = resolve_baseline(runtime_dir, args.baseline_name)
        selected, source_meta = select_rows_for_replay(rows, args, runtime_dir)
        current = build_replay_baseline(
            rows=selected,
            baseline_name=str(baseline_entry.get("baseline_name") or ""),
            preset=args.preset or str(baseline_entry.get("preset") or ""),
            source_meta=source_meta,
        )
        diff_payload = evaluate_replay_diff(baseline, current)
        diff_payload["baseline"] = baseline_entry
        diff_payload["source"] = source_meta
        write_json(paths["diff"], diff_payload)
        print_json(diff_payload)

        critical_failures = int(diff_payload.get("failed_by_severity", {}).get("critical", 0))
        if args.ci_strict and critical_failures > 0:
            return 2
        return 0

    if action == "report":
        baseline, baseline_entry = resolve_baseline(runtime_dir, args.baseline_name)
        selected, source_meta = select_rows_for_replay(rows, args, runtime_dir)
        current = build_replay_baseline(
            rows=selected,
            baseline_name=str(baseline_entry.get("baseline_name") or ""),
            preset=args.preset or str(baseline_entry.get("preset") or ""),
            source_meta=source_meta,
        )
        diff_payload = evaluate_replay_diff(baseline, current)
        diff_payload["baseline"] = baseline_entry
        diff_payload["source"] = source_meta
        write_json(paths["diff"], diff_payload)

        results = read_jsonl(paths["results"])
        results_meta = {
            "runner": "playwright",
            "generated_at_utc": utc_now(),
            "result_path": str(paths["results"]),
            "result_count": len(results),
            "classified_failures": len([row for row in results if not bool(row.get("ok"))]),
        }
        report_payload = build_replay_report_payload(baseline_entry, diff_payload, results_meta, results)
        write_json(paths["report"], report_payload)
        print_json(report_payload)

        if args.ci_strict and bool(report_payload.get("ci_block")):
            return 2
        return 0

    raise SystemExit(f"unsupported replay action: {action}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Mapper-Toolkit dataset CLI")
    parser.add_argument(
        "group",
        choices=["trace", "cookies", "headers", "responses", "mapping", "provenance", "housekeeping", "triage", "replay"],
    )
    parser.add_argument("action")

    parser.add_argument("--runtime-dir", default=str(default_runtime_dir()))
    parser.add_argument("--domain", default="")
    parser.add_argument("--path", default="")
    parser.add_argument("--method", default="")
    parser.add_argument("--status", default="")
    parser.add_argument("--mime", default="")
    parser.add_argument("--header-key", default="")
    parser.add_argument("--cookie-key", default="")
    parser.add_argument("--body-regex", default="")
    parser.add_argument("--action-id", default="")
    parser.add_argument("--screen-id", default="")
    parser.add_argument("--phase-id", default="")
    parser.add_argument("--host-class", default="")
    parser.add_argument("--time-from", default="")
    parser.add_argument("--time-to", default="")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--event-id", default="")
    parser.add_argument("--trace-id", default="")
    parser.add_argument("--left", default="")
    parser.add_argument("--right", default="")
    parser.add_argument("--preset", default="")
    parser.add_argument("--severity", default="")
    parser.add_argument("--note", default="")
    parser.add_argument("--incident-id", default="")
    parser.add_argument("--baseline-name", default="")
    parser.add_argument("--seed-path", default="")
    parser.add_argument("--timeout-ms", type=int, default=15000)
    parser.add_argument("--allow-unsafe-replay-methods", action="store_true")
    parser.add_argument("--ci-strict", action="store_true")
    parser.add_argument("--window-limit", type=int, default=400)
    parser.add_argument("--sampling-rate", type=int, default=1)
    parser.add_argument("--quality-host", default="")
    parser.add_argument("--yes", action="store_true")

    return parser.parse_args()


def main() -> int:
    args = parse_args()
    runtime_dir = pathlib.Path(args.runtime_dir)
    runtime_dir.mkdir(parents=True, exist_ok=True)
    rows_raw = read_jsonl(events_path(runtime_dir))
    rows = normalize_runtime_rows(rows_raw, runtime_dir=runtime_dir)

    dispatch = {
        "trace": do_trace,
        "cookies": do_cookies,
        "headers": do_headers,
        "responses": do_responses,
        "mapping": do_mapping,
        "provenance": do_provenance,
        "housekeeping": do_housekeeping,
        "triage": do_triage,
        "replay": do_replay,
    }

    handler = dispatch[args.group]
    return handler(args.action, rows, args, runtime_dir)


if __name__ == "__main__":
    raise SystemExit(main())
