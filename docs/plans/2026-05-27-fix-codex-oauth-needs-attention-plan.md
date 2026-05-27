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

Each phase below should be small enough to implement, test, and commit independently. Do not combine
frontend, companion runtime, shell script, E2E, and documentation changes in one commit unless the
change is purely mechanical.

### Phase 1: Baseline Companion-Down Reproduction

- Stop the companion with `./scripts/local-ai-companion.sh stop`.
- Confirm no listener exists on `4317`.
- Open AI Setup and capture the current `Needs attention` behavior.
- Confirm `codex login status` separately.

Verify:

- `./scripts/local-ai-companion.sh status` reports not running.
- `lsof -nP -iTCP:4317 -sTCP:LISTEN || true` returns no listener.
- `codex login status` succeeds or produces a documented local-machine-specific result.

### Phase 2: Baseline Status Script Output

- Run `./scripts/status.sh` with companion stopped.
- Record whether the app is healthy and how companion health is displayed.
- Do not change code in this phase.

Verify:

- The baseline notes identify whether app health and companion health are distinguishable.

### Phase 3: Add Frontend Companion State Type

- Add `LocalCompanionStatus` in `frontend/src/App.tsx`.
- Include only fields needed by the UI: `state`, `message`, `checkedUrl`, `command`, and
  `checkedAt`.
- Keep the type local to `App.tsx`.

Verify:

- `./scripts/frontend-typecheck.sh`

### Phase 4: Add Companion URL and Command Helpers

- Add helper functions for the companion base URL and recovery command.
- Reuse `LOCAL_AI_COMPANION_URL`.
- Keep command text environment-aware enough for repo UI: `./scripts/local-ai-companion.sh start`.

Verify:

- `./scripts/frontend-typecheck.sh`

### Phase 5: Add Non-Mutating Companion Health Reader

- Add `readLocalCompanionHealth()`.
- Check `/health` or `/status`.
- Do not call `/auth/token` in this reader.
- Map `TypeError` to `offline`.
- Map non-OK responses to `blocked` or `unhealthy`.

Verify:

- `./scripts/frontend-typecheck.sh`

### Phase 6: Add Companion Health State Hook

- Add React state for companion health and loading.
- Add `refreshLocalCompanionHealth()` with stale-response protection if needed.
- Do not render new UI yet.

Verify:

- `./scripts/frontend-typecheck.sh`

### Phase 7: Trigger Health Check on OAuth Entry

- Run health check when `activePage === "aiSetup"` and auth method is `oauth`.
- Rerun when the selected OAuth provider changes.
- Do not add polling intervals.

Verify:

- `./scripts/frontend-typecheck.sh`
- Browser/network inspection or E2E route proves only one health check per entry/change.

### Phase 8: Render Companion Health Text

- Show a compact text row in the OAuth setup stack.
- Display `Checking`, `Companion online`, `Companion offline`, or `Companion blocked`.
- Keep existing buttons and OAuth behavior unchanged.

Verify:

- `./scripts/frontend-typecheck.sh`
- Manual UI check in AI Setup.

### Phase 9: Add Manual Refresh Action

- Add `Refresh companion status` button beside the health text.
- Disable it while a health check is running.
- Do not couple it to OAuth start.

Verify:

- `./scripts/frontend-typecheck.sh`
- E2E or manual click updates status text.

### Phase 10: Replace Offline OAuth Status Label

- Change OAuth label mapping so `unavailable` renders as `Companion offline`.
- Keep `failed` as `Connection failed`.
- Do not add new status enum values yet.

Verify:

- `./scripts/frontend-typecheck.sh`
- Companion-down UI no longer shows only `Needs attention`.

### Phase 11: Add Offline Recovery Callout

- When companion is offline, show:
  - checked URL
  - recovery command
  - refresh action or reference to the refresh button
- Do not include tokens, environment variables, or raw command output.

Verify:

- `./scripts/frontend-typecheck.sh`
- Manual UI check confirms text fits on desktop and mobile width.

### Phase 12: Guard OAuth Start While Offline

- If companion health is offline, `Connect Codex` should not call `/auth/token`.
- Set the OAuth connection message to the same recovery guidance.
- Leave healthy companion behavior unchanged.

Verify:

- E2E route or request tracking confirms no `/auth/token` request when offline.
- `./scripts/frontend-typecheck.sh`

### Phase 13: Guard OAuth Start While Blocked

- If companion health is blocked/unhealthy, avoid `/auth/token`.
- Show a cause-specific message.
- Keep a refresh action available.

Verify:

- E2E route can return a 403/500 health response and UI reports blocked/unhealthy.
- `./scripts/frontend-typecheck.sh`

### Phase 14: Add Companion Provider Status Route

- Add a non-mutating route in `scripts/local-ai-companion.mjs`, for example
  `GET /providers/status?provider=codex`.
- Return existing `stateFor(provider)` after running the safe status check where supported.
- Do not start OAuth from this route.

Verify:

- `node --check scripts/local-ai-companion.mjs`
- Focused Node test for the new route returns JSON.

### Phase 15: Extract Safe Provider Status Check

- Reuse `readExistingConnection(provider, config)` for Codex provider status.
- Preserve timeout behavior.
- Preserve missing-command handling.
- Do not change `/oauth/start` behavior.

Verify:

- Node test: fake status command returning logged-in output yields `connected`.
- `./scripts/test.sh --test-name-pattern` if available, otherwise `./scripts/test.sh`.

### Phase 16: Test Codex Missing Provider Status

- Add a Node test with `LEARNLOOP_CODEX_STATUS_COMMAND` pointing to a missing command.
- Assert response status is `missing`.
- Assert message is bounded and does not include secrets.

Verify:

- `./scripts/test.sh`

### Phase 17: Test Codex Already Connected Provider Status

- Add a Node test with a fake status command output matching `logged in`.
- Assert response status is `connected`.
- Assert `credentialLabel` is `Codex CLI OAuth`.

Verify:

- `./scripts/test.sh`

### Phase 18: Test Provider Status Origin Controls

- Assert unsafe origins still fail.
- Assert allowed local app origin succeeds.
- Keep token requirements unchanged for mutating endpoints.

Verify:

- `./scripts/test.sh`

### Phase 19: Frontend Provider Readiness Reader

- Add a frontend helper to call the provider status route after companion health is online.
- Map `connected`, `missing`, `failed`, and `idle` to UI state.
- Do not save local AI settings in this phase.

Verify:

- `./scripts/frontend-typecheck.sh`

### Phase 20: Trigger Provider Readiness on Online Companion

- When companion health becomes online and OAuth provider is Codex/Gemini, read provider readiness.
- Avoid repeated calls while a previous check is in flight.

Verify:

- `./scripts/frontend-typecheck.sh`
- E2E route confirms expected provider-status request count.

### Phase 21: Show Already Connected State

- If Codex readiness is `connected`, set OAuth label to `Codex CLI OAuth`.
- Show `Connected` without requiring a new login prompt.
- Keep Save local setup behavior unchanged.

Verify:

- E2E route returns `connected`; UI shows `Connected`.
- `./scripts/frontend-typecheck.sh`

### Phase 22: Show Codex CLI Missing State

- If Codex readiness is `missing`, show `Codex CLI missing`.
- Disable or guard Connect until the user fixes PATH and refreshes.
- Keep Gemini unaffected.

Verify:

- E2E route returns `missing`; UI shows missing state.
- `./scripts/frontend-typecheck.sh`

### Phase 23: Preserve Healthy OAuth Start Flow

- Re-run the existing mocked healthy OAuth path.
- Ensure `/auth/token` then `/oauth/start` still happens when companion is online.
- Ensure local OAuth label remains browser-local only.

Verify:

- `APP_URL=... ./scripts/e2e-installed.sh`

### Phase 24: Add Local Script Safe PID Reader

- Update `scripts/local-ai-companion.sh` with a small `pid_value()` helper.
- Treat missing, empty, and non-running PID as stale.
- Do not change start/status output yet.

Verify:

- `sh -n scripts/local-ai-companion.sh`

### Phase 25: Local Script Status Repairs Stale PID

- Make `status` remove stale `.local-ai-companion.pid`.
- Keep listener detection and healthcheck as source of truth.
- Print recovery guidance when not running.

Verify:

- Create a dead PID file and run `./scripts/local-ai-companion.sh status`.
- Confirm the stale PID file is removed.

### Phase 26: Local Script Start Repairs Stale PID

- Make `start` remove stale PID before launch.
- Preserve listener adoption when another healthy companion is already running.

Verify:

- Create a dead PID file.
- Run `./scripts/local-ai-companion.sh start`.
- Run `./scripts/local-ai-companion.sh status`.
- Stop companion after the check.

### Phase 27: Local Script Node Availability Check

- Add a Node availability check to `scripts/local-ai-companion.sh`, matching release behavior.
- If Node is missing, print a clear message and return non-zero.

Verify:

- `sh -n scripts/local-ai-companion.sh`
- Test with `NODE_BIN=/path/that/does/not/exist ./scripts/local-ai-companion.sh start`.

### Phase 28: Release Script Safe PID Reader

- Mirror the PID helper in `packaging/release-bundle/local-ai-companion.sh`.
- Keep release paths unchanged.

Verify:

- `sh -n packaging/release-bundle/local-ai-companion.sh`

### Phase 29: Release Script Status Repairs Stale PID

- Mirror stale PID status behavior in the release script.
- Print release command names without `./scripts/`.

Verify:

- `sh -n packaging/release-bundle/local-ai-companion.sh`
- Script-level test or manual release-staging check.

### Phase 30: Release Script Start Repairs Stale PID

- Mirror stale PID start behavior in the release script.
- Preserve existing Node availability check.

Verify:

- `sh -n packaging/release-bundle/local-ai-companion.sh`

### Phase 31: Local Status Script Companion Output

- Update `scripts/status.sh` to print companion status as:
  - `Local AI companion: running`
  - `Local AI companion: degraded - not running`
  - `Local AI companion: degraded - unhealthy`
- Keep `--wait` success based on app health.

Verify:

- `sh -n scripts/status.sh`
- Run with companion stopped and confirm degraded output.

### Phase 32: Release Status Script Companion Output

- Mirror companion status output in `packaging/release-bundle/status.sh`.
- Keep release command paths correct.

Verify:

- `sh -n packaging/release-bundle/status.sh`

### Phase 33: Local Start Warning Copy

- Update `scripts/start.sh` warning copy to match AI Setup recovery guidance.
- Keep companion failure non-fatal.

Verify:

- `sh -n scripts/start.sh`

### Phase 34: Local Install Warning Copy

- Update `scripts/install.sh` warning copy to match AI Setup recovery guidance.
- Keep companion failure non-fatal.

Verify:

- `sh -n scripts/install.sh`

### Phase 35: Release Start Warning Copy

- Update `packaging/release-bundle/start.sh` warning copy.
- Keep companion failure non-fatal.

Verify:

- `sh -n packaging/release-bundle/start.sh`

### Phase 36: Release Install Warning Copy

- Update `packaging/release-bundle/install.sh` warning copy.
- Keep companion failure non-fatal.

Verify:

- `sh -n packaging/release-bundle/install.sh`

### Phase 37: Add Focused Companion-Down E2E

- Extend `scripts/e2e-installed.mjs` or add a focused script path that simulates companion offline.
- Assert AI Setup shows companion offline guidance.
- Assert `/auth/token` is not called while offline.

Verify:

- Installed-app E2E passes in a local install test environment.

### Phase 38: Preserve Healthy Companion E2E

- Keep the existing mocked companion route for healthy OAuth.
- Assert `Connected` still appears and OAuth label is saved locally.
- Assert OAuth label is absent from observed server request bodies.

Verify:

- `APP_URL=... ./scripts/e2e-installed.sh`

### Phase 39: Add Documentation for Recovery

- Update `README.md`.
- Include:
  - companion is required for OAuth and local collection
  - status command
  - start/restart command
  - degraded state meaning

Verify:

- Read the README section and confirm it does not imply server-side OAuth token storage.

### Phase 40: Add Korean Documentation

- Mirror the recovery guidance in `README.ko.md`.
- Use the same operational meaning as English docs.

Verify:

- Korean docs match English behavior.

### Phase 41: Add Release Bundle Documentation

- Update `packaging/release-bundle/README.md`.
- Use release-local commands such as `./local-ai-companion.sh start`.
- Avoid repo-only `./scripts/...` paths.

Verify:

- Release docs use correct paths.

### Phase 42: Package Content Check

- Run release packaging or inspect staging logic if packaging is too expensive.
- Confirm updated release scripts and README are included.

Verify:

- `RUNNER_IMAGE_MODE=online OUTPUT_DIR=dist/e2e-release VERSION=0.1.0-e2e ./scripts/package-release.sh`
- Inspect tar contents for updated scripts/docs.

### Phase 43: Full Frontend Verification

- Run frontend typecheck after all frontend and E2E changes.

Verify:

- `./scripts/frontend-typecheck.sh`

### Phase 44: Full Node/Companion Verification

- Run the Node test suite after companion and script changes.

Verify:

- `./scripts/test.sh`

### Phase 45: Backend Regression Verification

- Run backend tests even though backend code should not change.

Verify:

- `./gradlew :backend:test`

### Phase 46: Shell Syntax Verification

- Check every changed shell script.

Verify:

- `sh -n scripts/local-ai-companion.sh`
- `sh -n scripts/status.sh`
- `sh -n scripts/start.sh`
- `sh -n scripts/install.sh`
- `sh -n packaging/release-bundle/local-ai-companion.sh`
- `sh -n packaging/release-bundle/status.sh`
- `sh -n packaging/release-bundle/start.sh`
- `sh -n packaging/release-bundle/install.sh`

### Phase 47: Installed-App E2E Verification

- Start a clean installed-app environment.
- Run the full installed-app E2E flow.
- Include companion-down and healthy companion paths.

Verify:

- `APP_URL=... ./scripts/e2e-installed.sh`
- E2E output reports no API key or OAuth label leakage to server requests.

### Phase 48: Security Review Pass

- Review the diff for:
  - no browser-started local process
  - no OAuth token sent to backend
  - bounded companion messages
  - origin and loopback restrictions preserved
  - no raw command output in UI

Verify:

- Security review notes list no remaining reportable findings, or fixes are committed before final.

### Phase 49: Launchd Helper Follow-Up Plan Stub

- Add a short follow-up section or separate TODO note for launchd/native helper.
- It must not change installer behavior in this fix.
- It must define that helper work needs its own security review.

Verify:

- Plan/docs mention helper as follow-up only.

### Phase 50: Launchd Helper Discovery Checklist

- For the follow-up, list the minimum decisions needed:
  - launchd vs native signed helper
  - install/uninstall ownership
  - log location and retention
  - token file access
  - disabled/manual mode

Verify:

- Checklist exists in this plan or a linked follow-up doc.

## Acceptance Criteria

### Functional Requirements

- [ ] AI Setup shows companion offline/degraded state before or immediately after selecting OAuth.
- [ ] `Connect Codex` no longer leaves the user with only `Needs attention`.
- [ ] Offline companion state shows the checked URL and the command to run.
- [ ] Companion online plus already-authenticated Codex CLI resolves to connected.
- [ ] Missing `codex` binary is reported as Codex CLI missing.
- [ ] The app remains usable when companion startup fails.
- [ ] Status scripts report companion degradation separately from app health.
- [ ] Stale `.local-ai-companion.pid` is ignored, removed, or repaired by start/status.

### Non-Functional Requirements

- [ ] Browser never starts arbitrary local processes.
- [ ] Companion endpoints remain loopback-only and origin restricted.
- [ ] OAuth tokens and local API tokens are not sent to the LearnLoop backend.
- [ ] Failure messages do not include secrets, raw token values, or full command output.
- [ ] Health/preflight checks do not poll aggressively.
- [ ] Local and release scripts remain behaviorally consistent.

### Quality Gates

- [ ] `./scripts/frontend-typecheck.sh`
- [ ] `./scripts/test.sh`
- [ ] `./gradlew :backend:test`
- [ ] `sh -n scripts/local-ai-companion.sh`
- [ ] `sh -n scripts/status.sh`
- [ ] `sh -n scripts/start.sh`
- [ ] `sh -n scripts/install.sh`
- [ ] `sh -n packaging/release-bundle/local-ai-companion.sh`
- [ ] `sh -n packaging/release-bundle/status.sh`
- [ ] `sh -n packaging/release-bundle/start.sh`
- [ ] `sh -n packaging/release-bundle/install.sh`
- [ ] Installed-app E2E covers both companion-down and healthy OAuth paths.

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
