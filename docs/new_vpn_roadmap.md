Hier ist ein **kompletter Issue-Text** (copy/paste ready), basierend auf einer Tiefenanalyse deines ZIP-Repos. Fokus: **Traffic lesen wie HttpCanary** (Requests+Responses inkl. Redirect-Ketten) **innerhalb des integrierten Browsers**, plus **Click→Flow Korrelation** und **maschinenlesbare Map-Exports**. UI + CA-Zertifikat-Erstellung/Speicherung bleiben **unangetastet**.

---

# Issue: Full "HttpCanary-grade" Traffic Capture + Click→Flow Website Mapping (FishIT-Mapper)

## Summary

FishIT-Mapper records WebView navigation and best-effort resource requests, but it does **not yet capture full HTTP/HTTPS request+response flows** like HttpCanary, and it does **not correlate user clicks to the resulting request/redirect chains** in a machine-readable way.
Goal: For any website navigated inside the project Browser tab, generate an exportable **Website Map** that explains **Directs/Redirects** and **which requests/responses are required** per interaction (e.g., "download all course materials").

## Current Gaps (as observed in repo)

### A) MITM proxy exists but is not wired into recording

* `MitmProxyServer` is implemented (`androidApp/.../proxy/MitmProxyServer.kt`) and already emits `ResourceRequestEvent` + `ResourceResponseEvent`, but it is **not started anywhere** and no events are added to `ProjectViewModel` sessions.

### B) WebView traffic is not routed through the proxy

* `BrowserScreen` records `NavigationEvent` and WebView `shouldInterceptRequest` (request-only).
* There is **no WebView proxy configuration** (even though `androidx.webkit` is already included) to route browser traffic via `127.0.0.1:8888`.

### C) Proxy implementation is MVP-level and will break real sites

`androidApp/.../proxy/MitmProxyServer.kt` needs to be production-capable for "any website":

* Reads only **one request per connection** (no keep-alive loop).
* Does **not forward request bodies** (POST/PUT), uses `OkHttp Request.method(method, null)`.
* Uses character streams (`BufferedReader/Writer`) → corrupts binary.
* CONNECT/TLS path also handles only one request then closes.

### D) Click events are not link-aware / not machine-readable

* `TrackingScript.kt` click capture has selector/text/x/y, but **no href/target URL**, no page URL context.
* `JavaScriptBridge.kt` stores "target" as a compact string; hard to machine-parse reliably.

### E) Request/Response correlation is weaker than available data

* `ResourceResponseEvent` contains `requestId`, but `TimelineBuilder` correlates mainly by URL/time window, not by `requestId`.

---

## Goal / Definition of Done

When recording in **Project → Browser tab**:

1. WebView traffic is routed through the local MITM proxy.
2. The app captures **HTTP + HTTPS** flows (request+response) including **redirect responses**.
3. Each user interaction (click/form submit) produces a **machine-readable** action record that correlates to:

   * navigation outcome (direct + redirect chain)
   * the set of required HTTP requests/responses (method, url, status, key headers, optional bodies)
4. On stop recording, the app generates and persists a **WebsiteMap** file per session and includes it in export bundles.

---

## Implementation Plan (strict, no optional branches)

### Task 1 — Wire proxy capture into recording sessions

**Files to change**

* `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/BrowserScreen.kt`
* `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/ProjectViewModel.kt`
* **NEW:** `androidApp/src/main/java/dev/fishit/mapper/android/proxy/ProxyCaptureManager.kt` (or similar)

**Requirements (English, precise)**

* Start `MitmProxyServer` when recording starts and stop it when recording stops.
* Proxy events must be routed into the current session via `ProjectViewModel.onRecorderEvent(...)`.
* Ensure proxy is started once and survives recompositions (Compose lifecycle safe).
* Ensure proxy events are recorded only when `ProjectViewModel.state.isRecording == true`.

### Task 2 — Route WebView traffic through the local proxy (no VPN required for Browser tab)

**Files to change**

* `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/BrowserScreen.kt`
* **NEW:** `androidApp/src/main/java/dev/fishit/mapper/android/webview/WebViewProxyController.kt`

**Requirements**

* Use `androidx.webkit.ProxyController` to set a proxy override for the app WebView process:

  * Proxy target: `127.0.0.1:8888`
* Enable proxy override automatically when recording starts.
* Disable proxy override when recording stops.
* If proxy override is not supported on the running WebView, fall back to current WebView-only request capture and show a log warning (no UI change required).

### Task 3 — Make MitmProxyServer HTTP/HTTPS proxy robust enough for real websites

**Files to change**

* `androidApp/src/main/java/dev/fishit/mapper/android/proxy/MitmProxyServer.kt`

**Requirements**

* Replace character stream forwarding with byte-safe handling:

  * Parse HTTP headers as ASCII lines from `InputStream`.
  * Forward response as raw bytes to `OutputStream`.
* Implement keep-alive loops:

  * For HTTP (non-CONNECT): keep reading requests until EOF or Connection: close.
  * For CONNECT (TLS): after handshake, keep reading decrypted HTTP requests until EOF.
* Implement request bodies:

  * Read body via `Content-Length` (and handle `Transfer-Encoding: chunked` at minimum for decoding).
  * Forward the same body to upstream via OkHttp `RequestBody`.
* Preserve correct behavior for redirects:

  * Keep `OkHttpClient.followRedirects(false)` so 30x responses are observable and recorded.
* Correct body capture rules:

  * Capture response body only for text-like content types (HTML/JSON/XML/CSS/JS) and up to configured max.
  * Do not attempt to string-decode binary; if captured, store as Base64 and set a clear metadata flag.
* Emit events:

  * Emit a `ResourceRequestEvent` and `ResourceResponseEvent` per exchange.
  * Populate `initiatorUrl` using `Referer` header when available.

### Task 4 — Upgrade event models to support machine-readable mapping (contract + generator)

**Files to change**

* `tools/codegen-contract/src/main/kotlin/dev/fishit/mapper/codegen/ContractGenerator.kt`
* `schema/contract.schema.json` (bump `contractVersion` patch)
* Update all call sites constructing these events:

  * `androidApp/.../webview/JavaScriptBridge.kt`
  * `androidApp/.../ui/project/BrowserScreen.kt`
  * `androidApp/.../proxy/MitmProxyServer.kt`

**Requirements**

* Extend `UserActionEvent` to include a structured payload:

  * Add `payload: Attributes = emptyMap()`
  * Keep existing fields `action` and `target` for backwards readability.
* Extend `ResourceRequestEvent` to optionally include request details:

  * Add `headers: Map<String,String> = emptyMap()`
  * Add `contentType: String? = null`
  * Add `contentLength: Long? = null`
  * Add `body: String? = null`
  * Add `bodyTruncated: Boolean = false`
* Keep changes additive and backward compatible via defaults.

### Task 5 — Capture link-aware, context-rich user actions in WebView

**Files to change**

* `androidApp/src/main/java/dev/fishit/mapper/android/webview/TrackingScript.kt`
* `androidApp/src/main/java/dev/fishit/mapper/android/webview/JavaScriptBridge.kt`

**Requirements**

* On click:

  * Detect nearest anchor (`target.closest('a')`) and capture resolved `href`.
  * Include `pageUrl` (`location.href`) and a stable element identifier (selector).
  * Send payload fields: `selector`, `text`, `href`, `pageUrl`, `x`, `y`, `tagName`.
* On form submit:

  * Capture `action`, `method`, field names list (no values) in payload.
* Emit `UserActionEvent(payload=...)` so downstream mapping can correlate.

### Task 6 — Generate a WebsiteMap per session (click → direct/redirect chain → required requests/responses)

**Files to change**

* **NEW:** `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/WebsiteMapBuilder.kt`
* Potentially adjust correlation logic:

  * `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/TimelineBuilder.kt` (use `requestId` first)
* Storage + export:

  * `androidApp/src/main/java/dev/fishit/mapper/android/data/AndroidProjectStore.kt`
  * `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/ExportBundleBuilder.kt`
  * `androidApp/src/main/java/dev/fishit/mapper/android/import/ImportManager.kt`
  * (Optional but recommended) `tools/codegen-contract/.../ContractGenerator.kt` to generate `WebsiteMap` contract models

**Requirements**

* Build a deterministic map structure per session:

  * For each `UserActionEvent` (click/formSubmit), define an "action window":

    * Start = action timestamp
    * End = next action timestamp OR (action + fixed max window, e.g. 10s) whichever comes first
  * Collect within window:

    * All `ResourceRequestEvent` + matching `ResourceResponseEvent` via `requestId`
    * All `NavigationEvent` and redirect chain (derive from `ResourceResponseEvent.isRedirect` + `Location`, plus subsequent NavigationEvents)
  * Produce a `WebsiteMap` JSON file containing:

    * `sessionId`
    * `actions[]` with payload + resulting `navigationOutcome` + `httpExchanges[]`
* Persist to disk:

  * Store under: `projects/<projectId>/maps/<sessionId>.json`
* Include in export bundle:

  * Add `maps/<sessionId>.json` files to ZIP export.
* Import must restore maps as well.

### Task 7 — Add test coverage for correctness + regression safety

**Files to change**

* **NEW tests**:

  * `shared/engine/src/commonTest/...` for `WebsiteMapBuilder`
  * `androidApp/src/test/...` for proxy parsing and map generation integration

**Requirements**

* Add unit tests that validate:

  * request/response correlation uses `requestId`
  * redirect chains are captured
  * click → exchange grouping is deterministic
* Use `OkHttp MockWebServer` for HTTP scenarios (add dependency).

---

## Files that must be edited (complete list)

**androidApp**

* `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/BrowserScreen.kt`
* `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/ProjectViewModel.kt`
* `androidApp/src/main/java/dev/fishit/mapper/android/proxy/MitmProxyServer.kt`
* `androidApp/src/main/java/dev/fishit/mapper/android/webview/TrackingScript.kt`
* `androidApp/src/main/java/dev/fishit/mapper/android/webview/JavaScriptBridge.kt`
* `androidApp/src/main/java/dev/fishit/mapper/android/data/AndroidProjectStore.kt`
* `androidApp/src/main/java/dev/fishit/mapper/android/import/ImportManager.kt`
* **NEW:** `androidApp/src/main/java/dev/fishit/mapper/android/proxy/ProxyCaptureManager.kt`
* **NEW:** `androidApp/src/main/java/dev/fishit/mapper/android/webview/WebViewProxyController.kt`
* `androidApp/build.gradle.kts` (add MockWebServer test dependency)

**shared/engine**

* `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/TimelineBuilder.kt`
* **NEW:** `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/WebsiteMapBuilder.kt`
* `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/ExportBundleBuilder.kt`
* Tests under `shared/engine/src/commonTest/...`

**codegen + schema**

* `tools/codegen-contract/src/main/kotlin/dev/fishit/mapper/codegen/ContractGenerator.kt`
* `schema/contract.schema.json` (bump contractVersion patch)

---

## Acceptance Criteria

* Recording a real website (multi-page, redirects, XHR) results in a session that includes:

  * `NavigationEvent`s
  * `UserActionEvent`s with structured payload (`href`, `pageUrl`, etc.)
  * `ResourceRequestEvent` + `ResourceResponseEvent` pairs (requestId linked)
* `MitmProxyServer` supports keep-alive and forwards POST bodies correctly (no broken logins/forms).
* After stop recording:

  * `maps/<sessionId>.json` exists on disk
  * Export ZIP contains map files + sessions + graph + chains
* Import of exported ZIP restores maps as well.
* Tests for WebsiteMapBuilder pass and cover redirect + requestId correlation.

---

## Tooling / Quality Gates (recommended)

* Add **Detekt + ktlint** to CI for Kotlin quality gates.
* Use **MockWebServer** + unit tests for deterministic network scenarios.
* For debugging, validate captured flows with **Wireshark/PCAP** (optional) and WebView DevTools.
* Keep an eye on dependency freshness using `./gradlew -q dependencyUpdates` and later add the Gradle Versions Plugin.

---

## Dependency freshness note

Your current pinned versions (e.g. `androidx.webkit`, Kotlin, AGP, BouncyCastle) are very likely not the newest anymore. Before/after implementing this issue, run `./gradlew -q dependencyUpdates` and update strategically (especially `androidx.webkit` because ProxyOverride behavior improves over time).
