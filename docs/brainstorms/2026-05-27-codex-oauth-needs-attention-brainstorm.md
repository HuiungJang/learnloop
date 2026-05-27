---
date: 2026-05-27
topic: codex-oauth-needs-attention
---

# Codex OAuth Needs Attention

## What We're Building

Find and prevent the failure where the AI Setup page shows `Needs attention` after pressing
`Connect Codex`. The current screen fails because the browser cannot reach the local AI companion on
`127.0.0.1:4317`, so the frontend maps the connection state to `unavailable`.

The prevention work should make the companion lifecycle visible and recoverable. A user should know
before pressing Connect whether the companion is offline, whether Codex CLI is missing, or whether
Codex is already logged in.

## Current Findings

- Frontend `startOAuthConnection` first calls `GET /auth/token` on `VITE_LOCAL_AI_COMPANION_URL`,
  defaulting to `http://127.0.0.1:4317`.
- If that request fails with a browser network error, the UI sets OAuth status to `unavailable`.
  `unavailable` is rendered as `Needs attention`.
- Local verification found no listener on TCP `4317`.
- `.local-ai-companion.pid` existed but pointed to a dead process, so the PID file was stale.
- `codex` itself is installed at `/opt/homebrew/bin/codex`.
- `codex login status` returns `Logged in using ChatGPT`, so the current failure is not caused by
  Codex CLI authentication.
- `scripts/install.sh` and `scripts/start.sh` attempt to start the companion, but they only print a
  warning if it does not start. The app can therefore look ready while OAuth is unavailable.

## Why This Approach

Recommendation: use a two-layer fix.

1. Add explicit companion health/preflight feedback in the AI Setup page.
2. Harden install/start/status scripts so stale PID files and failed companion startup are surfaced
   clearly.

This solves the observed failure without adding a heavyweight background service yet. A launchd or
native helper supervisor can be added later if one-click desktop behavior needs stronger guarantees.

## Approaches Considered

### Approach A: Better Message Only

Change `Needs attention` into a more specific message such as `Companion offline`.

Pros: smallest change, low risk.

Cons: does not prevent the failure; users still discover it only after clicking Connect.

### Approach B: UI Preflight + Script Health Hardening

AI Setup checks companion health, shows a clear offline state, disables or guides Connect until the
companion is healthy, and scripts repair stale PID state while reporting companion status as a first
class readiness item.

Pros: addresses the real failure mode, keeps security boundary intact, easy to test.

Cons: browser still cannot start a local process directly, so the user may need to run a command.

### Approach C: Host Supervisor

Install a launchd agent or native app helper that starts and restarts the companion automatically.

Pros: strongest one-click experience and best crash recovery.

Cons: more packaging, permissions, uninstall, and platform-specific behavior to maintain.

## Key Decisions

- Treat the local companion as required for OAuth and local collection features, even if the backend
  and frontend are healthy.
- Do not let the browser start arbitrary local processes. Recovery should go through existing local
  scripts or a future signed/native helper.
- Show exact cause categories: companion offline, companion unhealthy, Codex CLI missing, Codex login
  failed, already connected, and timeout.
- Keep the app usable when companion startup fails. Treat companion failure as a degraded local-AI
  state instead of failing the whole app startup.
- Keep all OAuth/token handling local; do not send Codex OAuth tokens to the LearnLoop server.
- Prefer Approach B now and leave Approach C as a later packaging enhancement.
- UX target state is Approach C, but it should be implemented as a separate follow-up after the
  immediate recurrence-prevention work is stable.

## Acceptance Criteria

- With companion stopped, AI Setup shows an offline companion state before or immediately after
  selecting OAuth.
- `Connect Codex` no longer leaves the user with only `Needs attention`; it shows the command to run
  and the checked URL.
- `./scripts/status.sh` and release `./status.sh` clearly report companion health.
- Stale `.local-ai-companion.pid` is ignored or repaired on start/status.
- When companion is running and `codex login status` is already logged in, Connect Codex resolves to
  connected without requiring a new login.
- When `codex` is missing from PATH, the UI reports Codex CLI missing instead of generic failure.
- Tests cover companion-down, stale PID, Codex already-connected, and Codex missing cases.

## Open Questions

- Resolved: `./scripts/start.sh` should keep the app running when companion startup fails, while AI
  Setup and status scripts show a degraded local-AI state with recovery guidance.
- Resolved: the current implementation should use script-guided recovery. The UX-preferred
  launchd/native helper auto-start and auto-restart model should be planned as a later phase.

## Next Steps

→ `/workflows:plan docs/brainstorms/2026-05-27-codex-oauth-needs-attention-brainstorm.md`
