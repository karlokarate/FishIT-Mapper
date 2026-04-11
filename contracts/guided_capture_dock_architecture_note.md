# Guided Capture Dock Architecture Note

## Purpose
The wizard step overlay and the former live panel are merged into one right-docked Guided Capture Dock so capture guidance and endpoint decisions happen in one continuous flow.

## Runtime Structure
- `GuidedCaptureDockController` is the only UI orchestrator between `BrowserActivity`, telemetry data, and dock rendering.
- `DockShellStateMachine` controls shell visibility and mode transitions.
- `StepGuidanceStateMachine` projects mission/step feedback into deterministic UI state.
- `CandidateDecisionStateMachine` mirrors endpoint select/exclude/test state from overrides and in-run actions.

## Dock Modes
- `Collapsed`: only handle visible, polling off.
- `Peek`: compact summary visible, polling 4s.
- `Expanded`: full guidance, candidate cards, optional feed, polling 2s.

## Interaction Rules
- Handle tap cycles `Collapsed -> Peek -> Expanded -> Peek`.
- Handle swipe expands from collapsed without precision tapping.
- Step actions (`Start`, `Ready`, `Check`, `Pause/Hold`, `Next/Undo`) stay in the same panel.
- Secondary actions (`Retry`, `Skip optional`, `Finish`, export/anchor actions) remain in overflow.

## Candidate Cards
- Cards are rendered with `RecyclerView + ListAdapter + DiffUtil` and stable endpoint IDs.
- Each card keeps independent expand/collapse state.
- Card header exposes role/template/score/confidence/evidence plus actions: `Select`, `Exclude`, `Test`, `Copy`.
- Card details expose structured fields: observed examples, rank reasons, field hits, runtime viability, warnings, missing proof links, export readiness.

## Correlation Feed
- Feed is secondary and collapsed by default.
- Feed can be expanded on demand in expanded mode only.
- Candidate decision area remains the primary focus.

## Clipboard Behavior
- Candidate copy is one tap and uses `GuidedCaptureDockFormatter.formatCandidate(...)`.
- Summary copy is one tap from header and uses `GuidedCaptureDockFormatter.formatSummary(...)`.
- Both copy paths return structured plain text suitable for chat/Codex handoff.

## Fail-Closed and Scope
- Existing fail-closed export and replay behavior remains unchanged.
- No analyzer/builder policy redesign is introduced by this UI refactor.
