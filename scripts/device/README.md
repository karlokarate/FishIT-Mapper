# Device Runtime Validation (Join Flow + Performance Artifacts)

This folder adds a reproducible debug workflow for:
- Join-link detail flow validation (variant A/B)
- Live logcat capture + structured parser summary
- Runtime snapshots (`gfxinfo`, `framestats`, `meminfo`)
- Optional APK bloat metrics via `apkanalyzer`

It is designed for Codespace + remote ADB (`127.0.0.1:5037`) and uses official tooling:
- Maestro CLI: <https://maestro.mobile.dev/getting-started/installing-maestro>
- Android adb / dumpsys / apkanalyzer
- Existing in-repo macrobenchmark scripts (`scripts/perf/*`)

## 1) Prerequisites

1. Ensure device is visible:
```bash
adb -H 127.0.0.1 -P 5037 devices -l
```
2. Install Maestro (if not already present):
```bash
curl -Ls "https://get.maestro.mobile.dev" | bash
export PATH="$PATH:$HOME/.maestro/bin"
maestro --version
```
3. Build/installable debug APK:
```bash
./gradlew :app-v2:assembleDebug --no-daemon --stacktrace
```

## 2) Run Join-Flow Session

Variant A (already member):
```bash
scripts/device/run-join-observability.sh \
  --mode maestro \
  --variant A \
  --series-title "YOUR_SERIES_TITLE"
```

Variant B (not member, uses Join Chat CTA):
```bash
scripts/device/run-join-observability.sh \
  --mode maestro \
  --variant B \
  --series-title "YOUR_SERIES_TITLE"
```

Manual mode (no Maestro automation, for live device interaction):
```bash
scripts/device/run-join-observability.sh \
  --mode manual \
  --manual-seconds 240
```

Artifacts are written to:
```text
logs/device/runs/<run_id>/
```

Important files:
- `summary.json` (machine-readable checks + event counts + transitions)
- `timeline.md` (quick human timeline)
- `logcat_threadtime.txt` (raw source)
- `gfxinfo*.txt`, `meminfo.txt` (runtime/jank snapshots)
- `apk_*.txt` (`apkanalyzer` output when apk + tool available)

## 3) What The Parser Validates

`parse_join_logcat.py` extracts and validates:
- `tg_join_*` join runtime events
- `tg_scope_binding_*` scope binding lookup/upsert/cache events
- sync transitions (`TO_ENQUEUED -> TO_RUNNING -> TO_STABILIZING -> terminal`)
- check that post-sync refresh is not started at `TO_RUNNING`
- join persist/schedule events
- UI jank frames (`ui_jank_frame`)

## 4) Performance/Jank/Bloat Extensions (already in repo)

Macrobenchmark + baseline profile + budget gates:
```bash
bash scripts/perf/run-connected-benchmarks.sh
```

Evaluate latest benchmark result explicitly:
```bash
bash scripts/perf/evaluate-benchmark-results.sh
```

Validate performance budget file:
```bash
bash scripts/perf/verify-performance-budgets.sh
```

## 5) Notes About Maestro Templates

Flow files under `scripts/device/maestro/` are templates:
- `join_series_variant_a.yaml`
- `join_series_variant_b.yaml`

Update selectors/texts (`Suche`, `Join Chat`, `Episoden`, series title matching) to your current UI locale/layout before first strict run.

## 6) Fast UI Anchor Navigation (ADB-only)

For fast runtime jumps without interactive driving, use:
```bash
scripts/device/ui-anchor-nav.sh state
scripts/device/ui-anchor-nav.sh anchors --screen now
scripts/device/ui-anchor-nav.sh goto home
scripts/device/ui-anchor-nav.sh goto library
scripts/device/ui-anchor-nav.sh goto movies
scripts/device/ui-anchor-nav.sh goto series
scripts/device/ui-anchor-nav.sh goto refresh
```

Generic tap by runtime label/regex:
```bash
scripts/device/ui-anchor-nav.sh tap --match "^Search$"
scripts/device/ui-anchor-nav.sh tap --match "Join Chat"
```

Notes:
- The script resolves labels from `text` and `content-desc` in the current `uiautomator` hierarchy.
- For non-clickable label nodes, it automatically uses the nearest clickable parent bounds.
- Hierarchy snapshots are persisted under `logs/device/live/ui_anchors/`.
