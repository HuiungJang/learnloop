---
title: "fix: Clarify Codex OAuth Companion Recovery"
type: fix
date: 2026-05-27
source_brainstorm: docs/brainstorms/2026-05-27-codex-oauth-needs-attention-brainstorm.md
---

# fix: Clarify Codex OAuth Companion Recovery

## Overview

Fix the AI Setup failure where pressing `Connect Codex` shows only `Needs attention` when the local
AI companion is not running. The current root cause is that the frontend calls the companion at
`http://127.0.0.1:4317/auth/token`, receives a browser network error, and maps the state to
`unavailable`, which renders as `Needs attention`.

The immediate fix is script-guided recovery: keep the app running, but make the companion state
visible, diagnosable, and recoverable from AI Setup and status scripts. The UX-preferred
launchd/native helper auto-start model is intentionally a later phase.

## Problem Statement

The local app can appear healthy while Codex OAuth is unavailable. `scripts/install.sh` and
`scripts/start.sh` attempt to start `local-ai-companion`, but they continue after failure with only a
terminal warning. In the UI, the user sees a generic `Needs attention` label rather than the actual
cause.

Observed local state:

- No listener on TCP `4317`.
- `.local-ai-companion.pid` existed but pointed to a dead process.
- `codex` exists at `/opt/homebrew/bin/codex`.
- `codex login status` returns `Logged in using ChatGPT`.

Therefore the immediate failure is companion lifecycle/visibility, not Codex account authentication.

## Scope

### In Scope

- AI Setup companion health preflight.
- Cause-specific OAuth status labels and recovery guidance.
- Local and release companion scripts handling stale PID files clearly.
- Local and release status scripts reporting companion degradation as a first-class status item.
- Tests for companion-down, stale PID, Codex already-connected, and Codex missing paths.
- Documentation updates for the local OAuth recovery command.

### Out of Scope

- Launchd/native helper auto-start and auto-restart for the immediate recurrence-prevention fix.
  This remains tracked as the follow-up UX improvement phase in this plan.
- Sending OAuth tokens or Codex credentials to the LearnLoop backend.
- Changing backend provider generation to use Codex OAuth.
- Replacing the local companion security model.

## Research Summary

### Brainstorm Source

- `docs/brainstorms/2026-05-27-codex-oauth-needs-attention-brainstorm.md`: selected Approach B,
  script-guided recovery with clear degraded state. Approach C remains the UX target for later.

### Local Context

- `frontend/src/App.tsx:687`: `failed` and `unavailable` both render as `Needs attention`.
- `frontend/src/App.tsx:706`: frontend reads a local companion token from `/auth/token`.
- `frontend/src/App.tsx:3111`: `startOAuthConnection` catches `TypeError` as companion unavailable.
- `scripts/local-ai-companion.mjs:97`: `/auth/token` exists and requires an allowed origin.
- `scripts/local-ai-companion.mjs:109`: `/oauth/start` starts Codex/Gemini OAuth through the local
  companion.
- `scripts/local-ai-companion.mjs:459`: `readExistingConnection` already detects a logged-in Codex
  CLI using `codex login status`.
- `scripts/local-ai-companion.sh`: manages local companion PID, health, start, stop, and status.
- `packaging/release-bundle/local-ai-companion.sh`: release-bundle copy of the companion script.
- `scripts/status.sh` and `packaging/release-bundle/status.sh`: report app status and should surface
  companion degradation.
- `tests/codex-shim.test.js`: existing Node companion/shim tests cover launcher, token, OAuth
  security, and companion lifecycle behavior.
- `scripts/e2e-installed.mjs`: installed-app E2E already exercises local AI setup and OAuth storage
  privacy through a mocked companion.
- `docs/solutions/2026-05-18-ai-pattern-e2e-performance-security.md`: prior E2E pattern verifies
  local AI secrets and OAuth labels are not sent to the server.

### External Research

Skipped. The problem is an existing local lifecycle and UX issue, not a new OAuth protocol or vendor
integration decision.

## Proposed Solution

Add a local companion status model to AI Setup:

- `checking`: companion status is being read.
- `online`: `/health` or `/status` is reachable.
- `offline`: no listener, network error, or refused connection.
- `blocked`: companion responds but origin/token policy rejects the browser.
- `provider_missing`: companion is online but the selected local provider command is missing.
- `connected`: selected provider is already authenticated locally.

Use this model to render a clear callout and adjust the OAuth button:

- Companion offline: show checked URL, command, and `Refresh` action; do not show only
  `Needs attention`.
- Companion online and Codex already logged in: `Connect Codex` resolves to connected without a new
  login prompt.
- Codex CLI missing: show missing CLI message and do not imply OAuth failed remotely.
- OAuth still running: keep `Connecting` and polling behavior.

Harden scripts:

- `status` should ignore or remove stale PID files.
- `start` should remove stale PID files before launch.
- local and release scripts should have matching behavior.
- `scripts/status.sh --wait` should still exit success when the app is healthy, but print companion
  as degraded if it is not running.

## SpecFlow Analysis

### User Flow Overview

1. User opens AI Setup and selects Codex OAuth while companion is offline.
   - UI checks companion health.
   - UI shows `Local companion offline`, the URL checked, and `./scripts/local-ai-companion.sh start`.
   - Connect is disabled or immediately returns the same recovery message without generic wording.

2. User starts companion and clicks Refresh.
   - UI rechecks health.
   - Status becomes online.
   - Connect becomes available.

3. User clicks Connect Codex while Codex is already logged in.
   - Companion runs bounded Codex status check.
   - UI shows connected and fills `Codex CLI OAuth`.

4. User clicks Connect Codex while Codex CLI is missing.
   - Companion reports provider missing.
   - UI shows `Codex CLI is not installed or not available in PATH`.

5. User starts the installed app and companion fails.
   - App still starts.
   - `status.sh` reports the app URL as ready and local AI companion as degraded.

6. User has a stale `.local-ai-companion.pid`.
   - status/start removes or repairs the stale PID state.
   - health result is based on the actual listener, not the stale PID file.

### Flow Permutations Matrix

| Context | Companion | Codex CLI | Expected UX |
| --- | --- | --- | --- |
| First setup | offline | installed | AI Setup shows recovery command and checked URL |
| First setup | online | logged in | Connect resolves to connected |
| First setup | online | missing | UI reports Codex CLI missing |
| Returning user | offline | logged in | Existing saved setup remains, AI Setup shows degraded companion |
| Installed start | failed startup | any | App remains usable; status reports degraded local AI |
| Release bundle | stale PID | any | PID repaired; status reflects actual listener |

### Gaps Addressed

- Generic status label hides root cause.
- Companion health is not checked before the user presses Connect.
- Status scripts do not clearly separate app health from local-AI companion health.
- Stale PID files can confuse operational diagnosis.
- E2E currently mocks a healthy companion but does not assert the down path.

## Implementation Phases

Execution rule: each phase must change one small boundary, run its listed verification immediately,
and leave the repo in a state that can be committed. If a phase fails verification, fix that phase
before starting the next one.

### Phase 0: Confirm Work Base

Change:

- Confirm the branch starts from latest `main`.
- Record current dirty files before edits.

Verify:

- `git branch --show-current`
- `git status --short`

### Phase 1: Reproduce Stopped Companion

Change:

- Stop the local companion.

Verify:

- `./scripts/local-ai-companion.sh stop`
- `./scripts/local-ai-companion.sh status` exits non-zero and reports not running.

### Phase 2: Verify Port Is Closed

Change:

- Confirm no companion listener is active.

Verify:

- `lsof -nP -iTCP:4317 -sTCP:LISTEN || true` prints no listener.

### Phase 3: Separate Codex Account State

Change:

- Check Codex CLI authentication independently from LearnLoop.

Verify:

- `codex login status`
- Result is recorded as logged in, logged out, or command missing.

### Phase 4: Capture Current UI Failure

Change:

- Open AI Setup with companion stopped.
- Click Codex OAuth connect once.

Verify:

- UI currently shows the generic failure state.
- Browser console/network confirms the failed request is local companion access, not backend OAuth.

### Phase 5: Capture Current Status Output

Change:

- Run app status with companion stopped.

Verify:

- `./scripts/status.sh || true`
- Notes identify whether app readiness and companion readiness are distinguishable.

### Phase 6: Add Frontend Companion State Shape

Change:

- Add the smallest `LocalCompanionStatus` type in `frontend/src/App.tsx`.
- Include only `state`, `message`, `checkedUrl`, `command`, and `checkedAt`.

Verify:

- `./scripts/frontend-typecheck.sh`

### Phase 7: Add Companion URL Helper

Change:

- Add a helper that returns the local companion health URL.
- Reuse the existing companion base URL constant.

Verify:

- `./scripts/frontend-typecheck.sh`

### Phase 8: Add Recovery Command Helper

Change:

- Add a helper that returns `./scripts/local-ai-companion.sh start` for repo builds.
- Do not execute the command from the browser.

Verify:

- `./scripts/frontend-typecheck.sh`

### Phase 9: Add Health Reader Offline Mapping

Change:

- Add `readLocalCompanionHealth()`.
- Map browser network errors to `offline`.
- Do not call `/auth/token`.

Verify:

- `./scripts/frontend-typecheck.sh`

### Phase 10: Add Health Reader HTTP Mapping

Change:

- Map HTTP `403` to `blocked`.
- Map other non-OK responses to `unhealthy`.

Verify:

- `./scripts/frontend-typecheck.sh`

### Phase 11: Add Health State Without UI

Change:

- Add React state for companion health.
- Add a refresh function with stale-response protection.
- Do not render new UI yet.

Verify:

- `./scripts/frontend-typecheck.sh`

### Phase 12: Trigger Health Check On OAuth Page Entry

Change:

- Run one health check when AI Setup enters OAuth mode.
- Do not add polling.

Verify:

- `./scripts/frontend-typecheck.sh`
- Browser route/request log shows one health request on entry.

### Phase 13: Trigger Health Check On Provider Change

Change:

- Re-run the health check when the selected OAuth provider changes.
- Keep this separate from the page-entry trigger.

Verify:

- `./scripts/frontend-typecheck.sh`
- Browser route/request log shows one health request per provider change.

### Phase 14: Render Read-Only Health Row

Change:

- Render a compact companion health row.
- Show only checking, online, offline, blocked, or unhealthy.
- Leave connect behavior unchanged.

Verify:

- `./scripts/frontend-typecheck.sh`
- Manual AI Setup check confirms the row appears.

### Phase 15: Add Refresh Button

Change:

- Add `Refresh companion status`.
- Disable it while a check is running.

Verify:

- `./scripts/frontend-typecheck.sh`
- Manual click updates the health row.

### Phase 16: Replace Generic Offline Label

Change:

- Change OAuth status text so companion network failure shows `Companion offline`.
- Keep generic `Connection failed` for non-companion failures.

Verify:

- `./scripts/frontend-typecheck.sh`
- Companion-down UI no longer shows only `Needs attention`.

### Phase 17: Add Offline Recovery Callout

Change:

- Show checked URL and recovery command when state is `offline`.
- Do not show tokens, environment variables, or raw command output.

Verify:

- `./scripts/frontend-typecheck.sh`
- Manual UI check confirms the callout text fits.

### Phase 18: Add Blocked/Unhealthy Recovery Callout

Change:

- Show cause-specific callout text for `blocked` and `unhealthy`.
- Keep the same refresh action.

Verify:

- `./scripts/frontend-typecheck.sh`
- Mocked `403` and `500` health responses render distinct messages.

### Phase 19: Guard Connect While Offline

Change:

- Prevent `Connect Codex` from calling `/auth/token` while health is `offline`.
- Set the visible message to the recovery guidance.

Verify:

- E2E route/request counter shows `/auth/token` was not requested.
- `./scripts/frontend-typecheck.sh`

### Phase 20: Guard Connect While Blocked Or Unhealthy

Change:

- Prevent `/auth/token` when health is `blocked` or `unhealthy`.
- Keep healthy behavior untouched.

Verify:

- Mocked `403` and `500` health responses do not call `/auth/token`.
- `./scripts/frontend-typecheck.sh`

### Phase 21: Add Provider Status Response Type

Change:

- Add the smallest frontend response type for companion provider readiness.
- Do not fetch it yet.

Verify:

- `./scripts/frontend-typecheck.sh`

### Phase 22: Add Provider Status Reader

Change:

- Add a frontend helper for `/providers/status?provider=...`.
- Map `connected`, `missing`, `failed`, and `idle`.
- Do not save local AI setup.

Verify:

- `./scripts/frontend-typecheck.sh`

### Phase 23: Trigger Provider Status When Health Is Online

Change:

- Call provider status only after companion health is `online`.
- Avoid duplicate in-flight requests.

Verify:

- `./scripts/frontend-typecheck.sh`
- E2E route/request counter shows expected provider-status calls.

### Phase 24: Show Already-Connected Provider

Change:

- If provider readiness is `connected`, show `Connected`.
- Use the provider credential label from the companion.

Verify:

- Mocked provider-status response shows connected UI.
- `./scripts/frontend-typecheck.sh`

### Phase 25: Show Missing Provider

Change:

- If provider readiness is `missing`, show `Codex CLI missing` or provider-specific missing text.
- Disable or guard Connect until refresh reports a non-missing state.

Verify:

- Mocked missing response renders missing state.
- `./scripts/frontend-typecheck.sh`

### Phase 26: Preserve Healthy OAuth Start

Change:

- Ensure healthy OAuth still calls `/auth/token` then `/oauth/start`.
- Do not change the local-only save flow.

Verify:

- Existing healthy OAuth E2E path still reaches `Connected`.
- OAuth label is not sent to backend request bodies.

### Phase 27: Add Companion Provider Route Skeleton

Change:

- Add `GET /providers/status` in `scripts/local-ai-companion.mjs`.
- Validate that `provider` is present and supported.
- Return JSON only.

Verify:

- `node --check scripts/local-ai-companion.mjs`
- Focused Node test returns a JSON response for a supported provider.

### Phase 28: Enforce Provider Route Origin Rules

Change:

- Reuse existing allowed-origin checks for the new route.
- Keep mutating token requirements unchanged.

Verify:

- Node test: unsafe origin gets `403`.
- Node test: allowed local app origin succeeds.

### Phase 29: Wire Codex Existing-Login Check

Change:

- Reuse `readExistingConnection(provider, config)` for Codex.
- Do not start OAuth from the status route.

Verify:

- Node test with fake logged-in command returns `connected`.

### Phase 30: Wire Codex Missing-Command Check

Change:

- Preserve missing-command handling for Codex status.
- Bound the user-visible message.

Verify:

- Node test with missing command returns `missing`.
- Response does not include secrets or full shell output.

### Phase 31: Preserve OAuth Start Route Behavior

Change:

- Confirm the new status route did not change `/oauth/start`.

Verify:

- Existing OAuth start tests still pass through `./scripts/test.sh`.

### Phase 32: Add Local PID Reader

Change:

- Add `pid_value()` to `scripts/local-ai-companion.sh`.
- Treat missing and empty PID files as no PID.

Verify:

- `sh -n scripts/local-ai-companion.sh`

### Phase 33: Local Status Removes Stale PID

Change:

- Make `status` remove stale `.local-ai-companion.pid`.
- Keep actual healthcheck/listener as source of truth.

Verify:

- Create a dead PID file.
- `./scripts/local-ai-companion.sh status || true`
- PID file is removed.

### Phase 34: Local Start Removes Stale PID

Change:

- Make `start` clear stale PID before launch.
- Preserve adoption of an already healthy listener.

Verify:

- Create a dead PID file.
- `./scripts/local-ai-companion.sh start`
- `./scripts/local-ai-companion.sh status`
- `./scripts/local-ai-companion.sh stop`

### Phase 35: Local Stop Uses Safe PID

Change:

- Make `stop` use `pid_value()` rather than reading the file multiple times.

Verify:

- `./scripts/local-ai-companion.sh start`
- `./scripts/local-ai-companion.sh stop`
- `./scripts/local-ai-companion.sh status || true`

### Phase 36: Local Start Checks Node

Change:

- Add a Node availability check to local start.
- Print a clear non-zero failure if Node is unavailable.

Verify:

- `sh -n scripts/local-ai-companion.sh`
- `NODE_BIN=/path/that/does/not/exist ./scripts/local-ai-companion.sh start || true`

### Phase 37: Release PID Reader

Change:

- Mirror `pid_value()` in `packaging/release-bundle/local-ai-companion.sh`.
- Keep release paths unchanged.

Verify:

- `sh -n packaging/release-bundle/local-ai-companion.sh`

### Phase 38: Release Status Removes Stale PID

Change:

- Mirror local stale-PID status behavior in the release script.
- Use release command text, not `./scripts/...`.

Verify:

- `sh -n packaging/release-bundle/local-ai-companion.sh`
- Manual release-staging stale PID check.

### Phase 39: Release Start Removes Stale PID

Change:

- Mirror local stale-PID start behavior.
- Preserve release Node availability behavior.

Verify:

- `sh -n packaging/release-bundle/local-ai-companion.sh`

### Phase 40: Release Stop Uses Safe PID

Change:

- Mirror safe PID handling in release `stop`.

Verify:

- `sh -n packaging/release-bundle/local-ai-companion.sh`

### Phase 41: Local Status Reports Companion Running

Change:

- Update `scripts/status.sh` to print `Local AI companion: running` when healthy.

Verify:

- `sh -n scripts/status.sh`
- Start companion, then run `./scripts/status.sh`.

### Phase 42: Local Status Reports Companion Degraded

Change:

- Update `scripts/status.sh` to print `Local AI companion: degraded - not running` when stopped.
- Keep app health as the `--wait` success condition.

Verify:

- Stop companion, then run `./scripts/status.sh || true`.

### Phase 43: Release Status Reports Companion Running

Change:

- Mirror running companion status in `packaging/release-bundle/status.sh`.

Verify:

- `sh -n packaging/release-bundle/status.sh`

### Phase 44: Release Status Reports Companion Degraded

Change:

- Mirror degraded companion status in release status script.

Verify:

- `sh -n packaging/release-bundle/status.sh`

### Phase 45: Local Start Warning Copy

Change:

- Update `scripts/start.sh` companion failure warning to show the exact recovery command.
- Keep the failure non-fatal.

Verify:

- `sh -n scripts/start.sh`

### Phase 46: Local Install Warning Copy

Change:

- Update `scripts/install.sh` companion failure warning to show the exact recovery command.
- Keep the failure non-fatal.

Verify:

- `sh -n scripts/install.sh`

### Phase 47: Release Start Warning Copy

Change:

- Update `packaging/release-bundle/start.sh` companion failure warning.

Verify:

- `sh -n packaging/release-bundle/start.sh`

### Phase 48: Release Install Warning Copy

Change:

- Update `packaging/release-bundle/install.sh` companion failure warning.

Verify:

- `sh -n packaging/release-bundle/install.sh`

### Phase 49: Add E2E Companion Offline Mock

Change:

- Extend `scripts/e2e-installed.mjs` with a mode where companion health requests fail.
- Keep this separate from healthy OAuth mock behavior.

Verify:

- E2E route log shows health request failure is simulated locally.

### Phase 50: E2E Offline UI Assertion

Change:

- Assert AI Setup shows companion offline guidance and recovery command.

Verify:

- `APP_URL=... ./scripts/e2e-installed.sh`

### Phase 51: E2E Offline No-Token Assertion

Change:

- Track `/auth/token` calls while companion is offline.
- Assert the count stays zero.

Verify:

- `APP_URL=... ./scripts/e2e-installed.sh`

### Phase 52: E2E Healthy Provider Status Mock

Change:

- Add healthy `/health` and `/providers/status` mock responses.

Verify:

- E2E route log shows provider-status call after online health.

### Phase 53: E2E Healthy OAuth Assertion

Change:

- Assert healthy OAuth still reaches `Connected`.

Verify:

- `APP_URL=... ./scripts/e2e-installed.sh`

### Phase 54: E2E Local-Only Credential Assertion

Change:

- Assert OAuth label and API key are absent from observed backend request bodies.

Verify:

- E2E output reports no credential leakage.

### Phase 55: English README Recovery Docs

Change:

- Update `README.md` with companion requirement, status command, start command, and degraded meaning.

Verify:

- README text does not imply server-side OAuth token storage.

### Phase 56: Korean README Recovery Docs

Change:

- Mirror the same recovery guidance in `README.ko.md`.

Verify:

- Korean wording matches English behavior and uses local-only credential language.

### Phase 57: Release Bundle README Recovery Docs

Change:

- Update `packaging/release-bundle/README.md`.
- Use `./local-ai-companion.sh start`, not repo-only script paths.

Verify:

- Release README contains only release-bundle command paths.

### Phase 58: Release Package Content Check

Change:

- Build or stage the release package.

Verify:

- `RUNNER_IMAGE_MODE=online OUTPUT_DIR=dist/e2e-release VERSION=0.1.0-e2e ./scripts/package-release.sh`
- Tar/package contents include updated companion scripts and README.

### Phase 59: Full Frontend Gate

Change:

- No code change; run after all frontend/E2E edits.

Verify:

- `./scripts/frontend-typecheck.sh`

### Phase 60: Full Node Companion Gate

Change:

- No code change; run after companion runtime/tests are complete.

Verify:

- `./scripts/test.sh`

### Phase 61: Backend Regression Gate

Change:

- No backend behavior should change; run regression tests anyway.

Verify:

- `./gradlew :backend:test`

### Phase 62: Shell Syntax Gate

Change:

- No code change; check every changed shell script.

Verify:

- `sh -n scripts/local-ai-companion.sh`
- `sh -n scripts/status.sh`
- `sh -n scripts/start.sh`
- `sh -n scripts/install.sh`
- `sh -n packaging/release-bundle/local-ai-companion.sh`
- `sh -n packaging/release-bundle/status.sh`
- `sh -n packaging/release-bundle/start.sh`
- `sh -n packaging/release-bundle/install.sh`

### Phase 63: Clean Installed-App E2E Gate

Change:

- Start a clean installed-app environment.
- Run the full installed-app E2E flow.

Verify:

- `APP_URL=... ./scripts/e2e-installed.sh`
- E2E covers both companion-down and healthy OAuth paths.

### Phase 64: Security Review Gate

Change:

- Review the final diff for local OAuth security regressions.

Verify:

- Browser never starts a local process.
- OAuth tokens and local API tokens are not sent to backend APIs.
- Companion remains loopback-only and origin restricted.
- UI does not render raw command output or token values.

### Phase 65: Plan Checklist Update

Change:

- Check off acceptance criteria and quality gates that actually passed.
- Leave failed or skipped gates unchecked with a short note.

Verify:

- Acceptance criteria match real verification results.

### Phase 66: Launchd Helper Follow-Up Stub

Change:

- Add or keep a follow-up note for launchd/native helper.
- Do not change installer behavior in this fix.

Verify:

- Docs clearly mark helper work as future UX improvement only.

### Phase 67: Launchd Helper Discovery Checklist

Change:

- Keep a checklist of decisions required before helper implementation:
  - launchd vs native signed helper
  - install/uninstall ownership
  - log location and retention
  - token file access
  - disabled/manual mode

Verify:

- The checklist exists in this plan or a linked follow-up doc.

## Acceptance Criteria

### Functional Requirements

- [x] AI Setup shows companion offline/degraded state before or immediately after selecting OAuth.
- [x] `Connect Codex` no longer leaves the user with only `Needs attention`.
- [x] Offline companion state shows the checked URL and the command to run.
- [x] Companion online plus already-authenticated Codex CLI resolves to connected.
- [x] Missing `codex` binary is reported as Codex CLI missing.
- [x] The app remains usable when companion startup fails.
- [x] Status scripts report companion degradation separately from app health.
- [x] Stale `.local-ai-companion.pid` is ignored, removed, or repaired by start/status.

### Non-Functional Requirements

- [x] Browser never starts arbitrary local processes.
- [x] Companion endpoints remain loopback-only and origin restricted.
- [x] OAuth tokens and local API tokens are not sent to the LearnLoop backend.
- [x] Failure messages do not include secrets, raw token values, or full command output.
- [x] Health/preflight checks do not poll aggressively.
- [x] Local and release scripts remain behaviorally consistent.

### Quality Gates

- [x] `./scripts/frontend-typecheck.sh`
- [x] `./scripts/test.sh`
- [x] `./gradlew :backend:test`
- [x] `sh -n scripts/local-ai-companion.sh`
- [x] `sh -n scripts/status.sh`
- [x] `sh -n scripts/start.sh`
- [x] `sh -n scripts/install.sh`
- [x] `sh -n packaging/release-bundle/local-ai-companion.sh`
- [x] `sh -n packaging/release-bundle/status.sh`
- [x] `sh -n packaging/release-bundle/start.sh`
- [x] `sh -n packaging/release-bundle/install.sh`
- [x] Installed-app E2E covers both companion-down and healthy OAuth paths.

## Success Metrics

- A user can identify companion-down state from the AI Setup page without reading browser devtools.
- A user can recover by running the displayed companion command and clicking refresh.
- Existing healthy OAuth flow remains unchanged for Codex and Gemini.
- Status script output distinguishes app readiness from local AI companion readiness.
- No new server-side credential exposure is introduced.

## Dependencies & Prerequisites

- Node.js must remain available for the host-side local companion.
- Codex CLI availability is still a local machine prerequisite for Codex OAuth.
- Installed-app E2E needs a local browser automation runtime and loopback app URL.
- The implementation should start from latest `main`.

## Risk Analysis & Mitigation

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Browser preflight leaks local environment details | Privacy concern | Keep messages bounded; do not return raw command output |
| Companion health check spams local port | UX/performance issue | Run on page/provider changes and manual refresh only |
| Disabling Connect blocks recovery after companion starts | User friction | Add visible Refresh action and recheck after provider changes |
| Release and repo scripts diverge | Installed app bugs | Update and test both script copies together |
| Treating companion failure as non-fatal hides setup problem | Confusing app state | Status scripts and AI Setup must label local AI as degraded |
| Provider preflight accidentally starts OAuth | Unexpected prompt | Keep preflight non-mutating; login only from Connect |

## Documentation Plan

- `README.md`: local OAuth troubleshooting and companion recovery.
- `README.ko.md`: Korean mirror of recovery guidance.
- `packaging/release-bundle/README.md`: release-bundle command names without `./scripts/`.
- Optional follow-up note: launchd/native helper as future UX improvement.

## Future Considerations

- Implement launchd/native helper for auto-start and auto-restart.
- Add a menu-bar or desktop-level local companion status indicator.
- Add one-click restart from a signed/native helper, not from the browser.
- Separate local companion lifecycle telemetry from OAuth-specific UI state.

## References

### Internal References

- `docs/brainstorms/2026-05-27-codex-oauth-needs-attention-brainstorm.md`
- `frontend/src/App.tsx`
- `scripts/local-ai-companion.mjs`
- `scripts/local-ai-companion.sh`
- `scripts/status.sh`
- `scripts/start.sh`
- `scripts/install.sh`
- `packaging/release-bundle/local-ai-companion.sh`
- `packaging/release-bundle/status.sh`
- `packaging/release-bundle/start.sh`
- `packaging/release-bundle/install.sh`
- `tests/codex-shim.test.js`
- `scripts/e2e-installed.mjs`
- `docs/solutions/2026-05-18-ai-pattern-e2e-performance-security.md`

### Related Work

- `docs/plans/2026-05-19-feat-local-ai-code-auto-collection-plan.md`
- `docs/plans/2026-05-21-feat-spring-real-ai-provider-generation-plan.md`
