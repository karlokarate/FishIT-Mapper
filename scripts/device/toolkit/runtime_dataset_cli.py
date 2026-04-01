#!/usr/bin/env python3
"""Mapper-Toolkit dataset operations for trace/cookies/headers/responses/mapping/replay."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import tarfile
from collections import Counter, defaultdict
from typing import Any, Dict, Iterable, List, Optional, Tuple
from urllib.parse import urlparse


SEVERITY_RANK = {"critical": 0, "high": 1, "medium": 2, "low": 3}


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
            return val
    return ""


def event_host_class(row: Dict[str, Any]) -> str:
    payload = event_payload(row)
    for key in ("host_class", "hostClass"):
        val = payload.get(key)
        if isinstance(val, str) and val:
            return val
    return ""


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
    return {
        "event_id": str(row.get("event_id") or ""),
        "request_id": event_request_id(row),
        "request_fingerprint": event_request_fingerprint(row),
        "ts_utc": str(row.get("ts_utc") or ""),
        "url": event_url(row),
        "normalized_url": str(payload.get("normalized_url") or ""),
        "method": event_method(row) or "GET",
        "phase_id": event_phase_id(row),
        "host_class": event_host_class(row),
        "dedup_of": event_dedup_of(row),
        "screen_id": str(payload.get("screen_id") or payload.get("screenId") or ""),
        "tab_id": str(payload.get("tab_id") or payload.get("tabId") or ""),
    }


def compact_response(row: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "event_id": str(row.get("event_id") or ""),
        "response_id": event_response_id(row),
        "request_id": event_request_id(row),
        "request_fingerprint": event_request_fingerprint(row),
        "ts_utc": str(row.get("ts_utc") or ""),
        "url": event_url(row),
        "normalized_url": str(event_payload(row).get("normalized_url") or ""),
        "method": event_method(row) or "GET",
        "status": event_status(row),
        "mime": event_mime(row),
        "phase_id": event_phase_id(row),
        "host_class": event_host_class(row),
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
        ui = [e for e in ordered if e.get("event_type") == "ui_action_event"]
        request_events = [e for e in ordered if e.get("event_type") == "network_request_event"]
        response_events = [e for e in ordered if e.get("event_type") == "network_response_event"]
        cookies = [e for e in ordered if e.get("event_type") == "cookie_event"]
        auth_events = [e for e in ordered if e.get("event_type") == "auth_event"]
        provenance_events = [e for e in ordered if e.get("event_type") == "provenance_event"]
        dedup_events = [
            e for e in ordered
            if e.get("event_type") == "correlation_event"
            and str(event_payload(e).get("operation") or "") == "request_dedup"
        ]

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
        phase_id = phase_counter.most_common(1)[0][0] if phase_counter else "unscoped"
        host_class_counter = Counter(
            host_class
            for host_class in (event_host_class(event) for event in ordered)
            if host_class
        )
        host_scope = {
            "target": int(host_class_counter.get("target", 0)),
            "external_noise": int(host_class_counter.get("external_noise", 0)),
            "ignored": int(host_class_counter.get("ignored", 0)),
            "unknown": int(host_class_counter.get("unknown", 0)),
        }
        dedup_ref_count = len(dedup_events) + len(
            [request for request in request_events if event_dedup_of(request)]
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


def build_required_headers(rows: List[Dict[str, Any]], active_elimination: bool = False) -> Dict[str, Any]:
    network_requests = [r for r in rows if r.get("event_type") == "network_request_event"]
    counter: Dict[str, Counter[str]] = defaultdict(Counter)
    request_by_id: Dict[str, Dict[str, Any]] = {}
    response_by_request_id: Dict[str, List[Dict[str, Any]]] = defaultdict(list)

    for request in network_requests:
        req_id = event_request_id(request)
        if req_id:
            request_by_id[req_id] = request
    for response in [r for r in rows if r.get("event_type") == "network_response_event"]:
        req_id = event_request_id(response)
        if req_id:
            response_by_request_id[req_id].append(response)

    for row in network_requests:
        url = event_url(row)
        if is_noise_asset(url=url, mime=event_mime(row)):
            continue
        host_class = event_host_class(row)
        if host_class and host_class != "target":
            continue
        host = urlparse(url).netloc or "unknown"
        for h in event_headers(row).keys():
            counter[host][h.lower()] += 1

    active_sets: List[Dict[str, Any]] = []
    if active_elimination:
        per_endpoint: Dict[Tuple[str, str, str], List[set]] = defaultdict(list)
        for request_id, request in request_by_id.items():
            responses = response_by_request_id.get(request_id, [])
            if not responses:
                continue
            if not any(event_status(resp).startswith("2") for resp in responses):
                continue
            url = event_url(request)
            if not url:
                continue
            if event_host_class(request) and event_host_class(request) != "target":
                continue
            parsed = urlparse(url)
            method = event_method(request) or "GET"
            key = (method, parsed.netloc or "unknown", parsed.path or "/")
            headers = {header.lower() for header in event_headers(request).keys() if header}
            if headers:
                per_endpoint[key].append(headers)

        for (method, host, path), header_sets in sorted(per_endpoint.items()):
            if not header_sets:
                continue
            minimal_set = set.intersection(*header_sets)
            passive_candidates = sorted(list(set.union(*header_sets)))
            active_sets.append(
                {
                    "method": method,
                    "host": host,
                    "path": path,
                    "successful_chain_count": len(header_sets),
                    "candidate_headers": passive_candidates,
                    "minimal_required_headers": sorted(list(minimal_set)),
                    "elimination_mode": "replay_elimination_observed",
                }
            )

    out = {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "inference_mode": "active_replay_elimination" if active_elimination else "passive_frequency",
        "hosts": {
            host: {
                "top_headers": [{"header": k, "count": v} for k, v in headers.most_common(30)]
            }
            for host, headers in sorted(counter.items())
        },
        "endpoint_minimal_sets": active_sets,
    }
    return out


def build_endpoint_candidates(rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    network_requests = [r for r in rows if r.get("event_type") == "network_request_event"]
    counts: Counter[Tuple[str, str, str, str]] = Counter()

    for row in network_requests:
        url = event_url(row)
        if not url:
            continue
        if is_noise_asset(url=url, mime=event_mime(row)):
            continue
        host_class = event_host_class(row)
        if host_class and host_class != "target":
            continue
        parsed = urlparse(url)
        method = event_method(row) or "GET"
        fingerprint = event_request_fingerprint(row)
        counts[(method, parsed.netloc, parsed.path, fingerprint)] += 1

    candidates = [
        {
            "method": method,
            "host": host,
            "path": path,
            "request_fingerprint": fingerprint,
            "count": count,
        }
        for (method, host, path, fingerprint), count in counts.most_common(300)
    ]

    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "candidates": candidates,
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


def build_field_matrix(rows: List[Dict[str, Any]], runtime_dir: Optional[pathlib.Path] = None) -> Dict[str, Any]:
    response_events = [r for r in rows if r.get("event_type") == "network_response_event"]
    key_counter: Counter[str] = Counter()
    parsed_events = 0
    skipped_non_target = 0

    for row in response_events:
        host_class = event_host_class(row)
        if host_class and host_class != "target":
            skipped_non_target += 1
            continue
        body = event_body_preview(row)
        if not body:
            body = read_response_store_payload(row, runtime_dir)
        if not body:
            continue
        try:
            loaded = json.loads(body)
        except Exception:
            continue
        parsed_events += 1
        for key in flatten_keys(loaded):
            key_counter[key] += 1

    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "parsed_response_events": parsed_events,
        "total_response_events": len(response_events),
        "skipped_non_target_events": skipped_non_target,
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
                "url": event_url(row),
                "method": event_method(row),
                "headers": event_headers(row),
                "body_preview": payload.get("body_preview"),
            }
        )
    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "steps": selected,
    }


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
        if rel_path:
            resolved = str((runtime_dir / "response_store" / rel_path)) if runtime_dir else ""
            exists = bool(runtime_dir and (runtime_dir / "response_store" / rel_path).exists())
            refs.append(
                {
                    "event_id": event_id,
                    "request_id": event_request_id(row),
                    "response_id": event_response_id(row),
                    "url": event_url(row),
                    "path": rel_path,
                    "resolved_path": resolved,
                    "exists": exists,
                    "mime": event_mime(row),
                    "status": event_status(row),
                    "phase_id": event_phase_id(row),
                    "host_class": event_host_class(row),
                    "body_ref": (event_body_refs or {}).get(event_id, ""),
                }
            )
    return {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "items": refs,
    }


def should_capture_candidate_body(row: Dict[str, Any]) -> bool:
    host_class = event_host_class(row)
    if host_class and host_class != "target":
        return False
    url = event_url(row).lower()
    mime = event_mime(row).lower()
    if any(ext in url for ext in (".m4s", ".ts", ".mp4", ".m4a", ".webm")):
        return False
    if any(token in url for token in ("graphql", "manifest", "playback", "resolver")):
        return True
    if any(ext in url for ext in (".m3u8", ".mpd")):
        return True
    if mime.startswith("application/json") or "json" in mime:
        return True
    if "html" in mime or "xml" in mime:
        return True
    return False


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
        if not should_capture_candidate_body(row):
            continue
        event_id = str(row.get("event_id") or "")
        if not event_id:
            continue

        text = event_body_preview(row)
        if not text:
            text = read_response_store_payload(row, runtime_dir)
        if not text:
            continue

        payload_bytes = text.encode("utf-8", errors="replace")
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
        if digest not in seen_sha and not target.exists():
            target.write_bytes(to_write)
        seen_sha.add(digest)

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
                "compression": compression,
                "body_ref": rel,
                "phase_id": event_phase_id(row),
                "host_class": event_host_class(row),
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


def ensure_derived(runtime_dir: pathlib.Path, rows: List[Dict[str, Any]]) -> Dict[str, pathlib.Path]:
    correlation = build_correlation(rows)
    cookie_timeline = build_cookie_timeline(rows)
    required_headers = build_required_headers(rows, active_elimination=False)
    endpoint_candidates = build_endpoint_candidates(rows)
    field_matrix = build_field_matrix(rows, runtime_dir=runtime_dir)
    profile_draft = build_profile_draft(rows, endpoint_candidates, required_headers)
    replay_seed = build_replay_seed(rows)
    body_store = build_body_store(rows, runtime_dir=runtime_dir)
    response_store_index = build_response_store_index(
        rows,
        runtime_dir=runtime_dir,
        event_body_refs=body_store.get("event_body_refs", {}),
    )
    provenance_graph = build_provenance_graph(rows)
    events_ssot = {
        "schema_version": 1,
        "generated_at_utc": utc_now(),
        "event_count": len(rows),
    }
    profile_candidate = dict(profile_draft)
    profile_candidate["profile_type"] = "candidate"

    paths = {
        "events_ssot": runtime_dir / "events.jsonl",
        "events_ssot_meta": runtime_dir / "events.meta.json",
        "correlation": runtime_dir / "correlation_index.jsonl",
        "cookie_timeline": runtime_dir / "cookie_timeline.json",
        "required_headers": runtime_dir / "required_headers_report.json",
        "endpoint_candidates": runtime_dir / "endpoint_candidates.json",
        "field_matrix": runtime_dir / "field_matrix.json",
        "profile_draft": runtime_dir / "site_profile.draft.json",
        "profile_candidate": runtime_dir / "profile_candidate.json",
        "replay_seed": runtime_dir / "replay_seed.json",
        "response_store_index": runtime_dir / "response_store" / "index.json",
        "response_index": runtime_dir / "response_index.json",
        "provenance_graph": runtime_dir / "provenance_graph.json",
    }

    write_jsonl(paths["events_ssot"], rows)
    write_json(paths["events_ssot_meta"], events_ssot)
    write_jsonl(paths["correlation"], correlation)
    write_json(paths["cookie_timeline"], cookie_timeline)
    write_json(paths["required_headers"], required_headers)
    write_json(paths["endpoint_candidates"], endpoint_candidates)
    write_json(paths["field_matrix"], field_matrix)
    write_json(paths["profile_draft"], profile_draft)
    write_json(paths["profile_candidate"], profile_candidate)
    write_json(paths["replay_seed"], replay_seed)
    write_json(paths["response_store_index"], response_store_index)
    write_json(paths["response_index"], body_store.get("index", {}))
    write_json(paths["provenance_graph"], provenance_graph)
    return paths


def discover_primary_host(rows: List[Dict[str, Any]]) -> str:
    counts: Counter[str] = Counter()
    for row in rows:
        if row.get("event_type") != "network_request_event":
            continue
        url = event_url(row)
        if not url:
            continue
        host = urlparse(url).netloc
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

    rows = scoped_rows
    derived = ensure_derived(runtime_dir, rows)
    host = quality_host.strip() or discover_primary_host(rows)

    request_rows_all = [row for row in rows if row.get("event_type") == "network_request_event"]
    response_rows_all = [row for row in rows if row.get("event_type") == "network_response_event"]
    target_request_rows = [row for row in request_rows_all if event_host_class(row) == "target"]
    target_response_rows = [row for row in response_rows_all if event_host_class(row) == "target"]
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

    total_requests = len(request_rows)
    matched_requests = 0
    relevant_requests = 0
    correlated_requests = 0
    for request in request_rows:
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
    ]
    dedup_refs = len(dedup_events)
    logical_request_total = len(request_rows_all) + dedup_refs
    duplicate_request_ratio = round((dedup_refs / logical_request_total), 6) if logical_request_total > 0 else 0.0

    required_phases = ["home_probe", "search_probe", "detail_probe", "playback_probe"]
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
        runtime_dir / "correlation_index.jsonl",
        runtime_dir / "cookie_timeline.json",
        runtime_dir / "required_headers_report.json",
        runtime_dir / "endpoint_candidates.json",
        runtime_dir / "field_matrix.json",
        runtime_dir / "site_profile.draft.json",
        runtime_dir / "profile_candidate.json",
        runtime_dir / "provenance_graph.json",
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
    required_headers = build_required_headers(rows, active_elimination=True)
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
        payload = build_required_headers(selected_rows, active_elimination=True)
        write_json(runtime_dir / "required_headers_report.active.json", payload)
        print_json(payload)
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
            "events.meta.json",
            "events/indexer_state.json",
            "correlation_index.jsonl",
            "cookie_timeline.json",
            "required_headers_report.json",
            "field_matrix.json",
            "endpoint_candidates.json",
            "site_profile.draft.json",
            "profile_candidate.json",
            "provenance_graph.json",
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
    rows = read_jsonl(events_path(runtime_dir))

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
