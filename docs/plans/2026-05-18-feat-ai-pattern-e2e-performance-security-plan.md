---
title: "feat: Add Hidden AI Pattern Prompt, Full E2E, Performance, Security Hardening"
type: feat
date: 2026-05-18
brainstorm: docs/brainstorms/2026-05-18-ai-pattern-e2e-performance-security-brainstorm.md
---

# feat: Add Hidden AI Pattern Prompt, Full E2E, Performance, Security Hardening

## Overview

Implement the next product-hardening slice for the installable AI Code Learning Platform. The work adds a backend-only pattern-recognition prompt contract, validates the complete first-user journey, starts measurable 30% performance improvements on controllable project metrics, and completes a Codex Security scan with fixes for validated issues.

## Decisions

- Prompt storage is backend-only application code for this phase.
- The prompt must not appear in API responses, frontend assets, audit metadata, browser state, or test snapshots.
- Keep deterministic local mock generation for stable tests, but introduce a prompt-building seam that future Codex/Gemini/Claude adapters can reuse.
- E2E tests cover installed app browser flow plus Spring API workflow.
- Performance targets are measured against practical project metrics: generation API latency, library detail/list query count or runtime, frontend bundle size, browser E2E time, and install/build script behavior.
- Open questions are closed by the defaults in the brainstorm; no user pause is required.

## Internal References

- Brainstorm: `docs/brainstorms/2026-05-18-ai-pattern-e2e-performance-security-brainstorm.md`
- Generation flow: `backend/src/main/kotlin/com/aicodelearning/learning/GenerationService.kt`
- Pattern read query path: `backend/src/main/kotlin/com/aicodelearning/learning/PatternReadService.kt`
- Existing Spring integration tests: `backend/src/test/kotlin/com/aicodelearning/auth/SessionAuthenticationIntegrationTest.kt`
- Existing Node/API smoke tests: `tests/platform.test.js`
- Installed app scripts: `scripts/install.sh`, `scripts/status.sh`, `scripts/package-release.sh`

## Acceptance Criteria

- [x] A backend-only prompt builder exists for AI pattern recognition.
- [x] Prompt output includes strict JSON instructions for patterns, tags, implementation guidance, failure modes, generated problems, and review risks.
- [x] No endpoint returns the internal prompt.
- [x] Audit logs do not contain raw prompt text.
- [x] Frontend build artifacts do not contain the internal prompt marker.
- [x] Full first-user E2E browser test covers signup, login, local AI setup, local key non-transmission, and workflow start.
- [x] Spring API E2E covers ingest, source link confirmation, generation, review approval, library read, learner submission, and progress.
- [x] Baseline and final performance measurements are captured in a repeatable script.
- [x] At least one representative hot path improves by 30% or more, and any non-improved metric is documented with evidence.
- [x] Codex Security scan produces a report and all validated findings are fixed.
- [x] `./scripts/check-split.sh` passes.
- [x] Installed app is healthy at `http://localhost:8080`.
- [x] Release bundle is regenerated.

## Implementation Phases

### Phase 0: Workflow Documentation and Baseline Setup

- [x] Create the brainstorm document with chosen defaults and closed open questions.
- [x] Create this implementation plan with measurable acceptance criteria.
- [x] Commit brainstorm and plan before code changes.
- [x] Capture current git commit and working-tree status.
- [x] Confirm installed app status with `./scripts/status.sh`.

### Phase 1: Current-State Performance Baseline

- [x] Measure current installed-app API workflow runtime.
- [x] Measure current library list response runtime after multiple published cards exist.
- [x] Measure current pattern detail response runtime.
- [x] Measure current frontend production JS/CSS asset size from `frontend/dist`.
- [x] Record baseline command and raw numbers in the work log.
- [x] Avoid changing production code before this baseline is captured.

### Phase 2: Hidden Prompt Contract Design

- [x] Define prompt schema version and internal prompt marker.
- [x] Define evidence input shape for prompt construction.
- [x] Define model output JSON contract:
  - [x] pattern title
  - [x] summary
  - [x] confidence
  - [x] tags
  - [x] evidence references
  - [x] language-agnostic explanation
  - [x] implementation guidance
  - [x] common failure modes
  - [x] generated problems
  - [x] review risks
- [x] Add prompt-injection defensive instructions that treat evidence as untrusted data.
- [x] Add privacy instructions to avoid secrets, proprietary identifiers, raw private code, and customer-specific details.
- [x] Decide what prompt metadata may be stored. Decision: schema/version/hash only if needed; never raw prompt text.

### Phase 3: Hidden Prompt Contract Implementation

- [x] Add backend prompt builder under the learning/generation module.
- [x] Bound evidence excerpt size before prompt construction.
- [x] Normalize evidence text enough to keep prompt size stable.
- [x] Keep prompt builder out of controllers and DTOs.
- [x] Inject prompt builder into `GenerationService`.
- [x] Build the prompt during generation without persisting or returning raw prompt text.
- [x] Preserve deterministic local mock generation output.
- [x] Add unit/integration tests for:
  - [x] prompt builder contains schema and JSON contract
  - [x] generation response does not contain internal prompt marker
  - [x] audit response does not contain internal prompt marker
  - [x] frontend built assets do not contain internal prompt marker

### Phase 4: First-User Browser E2E

- [x] Add a Playwright-based script using bundled Node runtime and installed Chrome fallback.
- [x] Open `http://localhost:8080` as an unauthenticated user.
- [x] Sign up a unique user.
- [x] Confirm first login lands on AI setup.
- [x] Select Claude API key mode.
- [x] Save a generated local API key.
- [x] Assert the key is present only in browser localStorage.
- [x] Assert the key is absent from every observed request body.
- [x] Log out and log back in as the same user.
- [x] Assert onboarding is skipped after local setup exists.
- [x] Re-open local AI settings.
- [x] Select Gemini OAuth mode.
- [x] Save a local OAuth profile label.
- [x] Assert OAuth label is absent from observed request bodies.
- [x] Run the visible workflow action and assert a draft card/review state appears.
- [x] Add a script entry point such as `scripts/e2e-installed.sh`.

### Phase 5: Full API E2E and Security Invariants

- [x] Extend Spring integration tests or Node API smoke tests for full role flow.
- [x] Contributor ingests manual code evidence.
- [x] Contributor ingests `codex-obsidian-sync` conversation evidence.
- [x] Contributor confirms suggested source link.
- [x] Contributor runs generation.
- [x] Reviewer approves generated review task.
- [x] Learner sees published card in library.
- [x] Learner cannot see reference answer before submission.
- [x] Learner submits answer.
- [x] Learner can see reference answer after submission.
- [x] Learner progress/proficiency updates.
- [x] Admin audit log remains free of prompt text and local AI secrets.
- [x] Existing negative tests for authz, secret scanning, provider ownership, and idempotency still pass.

### Phase 6: Performance Script and Backend Hot Path Optimization

- [x] Add a repeatable performance script under `scripts/`.
- [x] Script reports:
  - [x] app URL
  - [x] library card count
  - [x] generation/workflow runtime
  - [x] library median latency
  - [x] detail median latency
  - [x] frontend JS/CSS asset size
- [x] Add script to `package.json`.
- [x] Optimize `PatternReadService` list path to batch tag links and problems.
- [x] Add repository methods for multi-card tag/problem lookup.
- [x] Keep detail path behavior unchanged.
- [x] Add or update tests that prove card tags/problems remain correct after batching.
- [x] Run performance script after optimization.
- [x] Compare baseline/final and document percentage improvement.

### Phase 7: Frontend and E2E Runtime Optimization

- [x] Review bundle composition from production build output.
- [x] Remove unused frontend types/imports/state if any remain.
- [x] Ensure E2E waits use network/selector events, not fixed sleeps.
- [x] Keep the first screen consistent with the existing design prompt.
- [x] Verify mobile auth/onboarding still has no horizontal overflow.
- [x] Record final browser E2E runtime.

### Phase 8: Codex Security Scan and Fixes

- [x] Run Codex Security `security-scan` over the implemented diff.
- [x] Produce or update repository threat model if needed.
- [x] Run finding discovery.
- [x] Validate candidate findings.
- [x] Run attack-path analysis for surviving findings.
- [x] Fix every validated finding.
- [x] Re-run targeted tests after each fix.
- [x] Save scan report under `/tmp/codex-security-scans`.

### Phase 9: Final Verification, Packaging, and Commit

- [x] Run `./scripts/test.sh`.
- [x] Run `./scripts/backend-test.sh`.
- [x] Run `./scripts/frontend-typecheck.sh`.
- [x] Run `./scripts/frontend-build.sh`.
- [x] Run `./scripts/check-split.sh`.
- [x] Run installed app E2E.
- [x] Run performance measurement script.
- [x] Run `./scripts/install.sh`.
- [x] Confirm `./scripts/status.sh` reports all services healthy.
- [x] Run `./scripts/package-release.sh`.
- [x] Confirm release bundle checksum exists.
- [x] Update this plan checklist to reflect completed work.
- [x] Commit implementation with Korean conventional commit message.

## Risks and Mitigations

- Prompt exposure risk: use tests that search API responses, audit responses, and frontend dist for the internal prompt marker.
- Overclaiming performance: measure before/after within the same environment and report exact metrics.
- E2E brittleness: prefer role/text selectors and network response checks over fixed sleeps.
- Scope creep into real provider calls: keep adapters out of this phase and preserve deterministic tests.
- localStorage risk: document that local-only key storage is same-origin local state and must be revisited before rendering untrusted script.

## Success Metrics

- Generation API p50 or repeated workflow runtime improves by at least 30% on the optimized measured path.
- Frontend built JS size decreases where feasible without removing required UX.
- Full installed-app E2E completes consistently on local Docker Compose.
- Security scan has no unresolved validated findings.

## Notes

The 30% target applies to measured and controllable project metrics in this slice. Some metrics, such as Docker image rebuild time dominated by Gradle distribution downloads inside Docker, may require separate cache architecture work if they do not improve within this feature.
