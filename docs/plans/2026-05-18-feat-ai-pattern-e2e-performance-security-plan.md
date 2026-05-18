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

- [ ] A backend-only prompt builder exists for AI pattern recognition.
- [ ] Prompt output includes strict JSON instructions for patterns, tags, implementation guidance, failure modes, generated problems, and review risks.
- [ ] No endpoint returns the internal prompt.
- [ ] Audit logs do not contain raw prompt text.
- [ ] Frontend build artifacts do not contain the internal prompt marker.
- [ ] Full first-user E2E browser test covers signup, login, local AI setup, local key non-transmission, and workflow start.
- [ ] Spring API E2E covers ingest, source link confirmation, generation, review approval, library read, learner submission, and progress.
- [ ] Baseline and final performance measurements are captured in a repeatable script.
- [ ] At least one representative hot path improves by 30% or more, and any non-improved metric is documented with evidence.
- [ ] Codex Security scan produces a report and all validated findings are fixed.
- [ ] `./scripts/check-split.sh` passes.
- [ ] Installed app is healthy at `http://localhost:8080`.
- [ ] Release bundle is regenerated.

## Implementation Phases

### Phase 0: Workflow Documentation and Baseline Setup

- [ ] Create the brainstorm document with chosen defaults and closed open questions.
- [ ] Create this implementation plan with measurable acceptance criteria.
- [ ] Commit brainstorm and plan before code changes.
- [ ] Capture current git commit and working-tree status.
- [ ] Confirm installed app status with `./scripts/status.sh`.

### Phase 1: Current-State Performance Baseline

- [ ] Measure current installed-app API workflow runtime.
- [ ] Measure current library list response runtime after multiple published cards exist.
- [ ] Measure current pattern detail response runtime.
- [ ] Measure current frontend production JS/CSS asset size from `frontend/dist`.
- [ ] Record baseline command and raw numbers in the work log.
- [ ] Avoid changing production code before this baseline is captured.

### Phase 2: Hidden Prompt Contract Design

- [ ] Define prompt schema version and internal prompt marker.
- [ ] Define evidence input shape for prompt construction.
- [ ] Define model output JSON contract:
  - [ ] pattern title
  - [ ] summary
  - [ ] confidence
  - [ ] tags
  - [ ] evidence references
  - [ ] language-agnostic explanation
  - [ ] implementation guidance
  - [ ] common failure modes
  - [ ] generated problems
  - [ ] review risks
- [ ] Add prompt-injection defensive instructions that treat evidence as untrusted data.
- [ ] Add privacy instructions to avoid secrets, proprietary identifiers, raw private code, and customer-specific details.
- [ ] Decide what prompt metadata may be stored. Decision: schema/version/hash only if needed; never raw prompt text.

### Phase 3: Hidden Prompt Contract Implementation

- [ ] Add backend prompt builder under the learning/generation module.
- [ ] Bound evidence excerpt size before prompt construction.
- [ ] Normalize evidence text enough to keep prompt size stable.
- [ ] Keep prompt builder out of controllers and DTOs.
- [ ] Inject prompt builder into `GenerationService`.
- [ ] Build the prompt during generation without persisting or returning raw prompt text.
- [ ] Preserve deterministic local mock generation output.
- [ ] Add unit/integration tests for:
  - [ ] prompt builder contains schema and JSON contract
  - [ ] generation response does not contain internal prompt marker
  - [ ] audit response does not contain internal prompt marker
  - [ ] frontend built assets do not contain internal prompt marker

### Phase 4: First-User Browser E2E

- [ ] Add a Playwright-based script using bundled Node runtime and installed Chrome fallback.
- [ ] Open `http://localhost:8080` as an unauthenticated user.
- [ ] Sign up a unique user.
- [ ] Confirm first login lands on AI setup.
- [ ] Select Claude API key mode.
- [ ] Save a generated local API key.
- [ ] Assert the key is present only in browser localStorage.
- [ ] Assert the key is absent from every observed request body.
- [ ] Log out and log back in as the same user.
- [ ] Assert onboarding is skipped after local setup exists.
- [ ] Re-open local AI settings.
- [ ] Select Gemini OAuth mode.
- [ ] Save a local OAuth profile label.
- [ ] Assert OAuth label is absent from observed request bodies.
- [ ] Run the visible workflow action and assert a draft card/review state appears.
- [ ] Add a script entry point such as `scripts/e2e-installed.sh`.

### Phase 5: Full API E2E and Security Invariants

- [ ] Extend Spring integration tests or Node API smoke tests for full role flow.
- [ ] Contributor ingests manual code evidence.
- [ ] Contributor ingests `codex-obsidian-sync` conversation evidence.
- [ ] Contributor confirms suggested source link.
- [ ] Contributor runs generation.
- [ ] Reviewer approves generated review task.
- [ ] Learner sees published card in library.
- [ ] Learner cannot see reference answer before submission.
- [ ] Learner submits answer.
- [ ] Learner can see reference answer after submission.
- [ ] Learner progress/proficiency updates.
- [ ] Admin audit log remains free of prompt text and local AI secrets.
- [ ] Existing negative tests for authz, secret scanning, provider ownership, and idempotency still pass.

### Phase 6: Performance Script and Backend Hot Path Optimization

- [ ] Add a repeatable performance script under `scripts/`.
- [ ] Script reports:
  - [ ] app URL
  - [ ] library card count
  - [ ] generation/workflow runtime
  - [ ] library median latency
  - [ ] detail median latency
  - [ ] frontend JS/CSS asset size
- [ ] Add script to `package.json`.
- [ ] Optimize `PatternReadService` list path to batch tag links and problems.
- [ ] Add repository methods for multi-card tag/problem lookup.
- [ ] Keep detail path behavior unchanged.
- [ ] Add or update tests that prove card tags/problems remain correct after batching.
- [ ] Run performance script after optimization.
- [ ] Compare baseline/final and document percentage improvement.

### Phase 7: Frontend and E2E Runtime Optimization

- [ ] Review bundle composition from production build output.
- [ ] Remove unused frontend types/imports/state if any remain.
- [ ] Ensure E2E waits use network/selector events, not fixed sleeps.
- [ ] Keep the first screen consistent with the existing design prompt.
- [ ] Verify mobile auth/onboarding still has no horizontal overflow.
- [ ] Record final browser E2E runtime.

### Phase 8: Codex Security Scan and Fixes

- [ ] Run Codex Security `security-scan` over the implemented diff.
- [ ] Produce or update repository threat model if needed.
- [ ] Run finding discovery.
- [ ] Validate candidate findings.
- [ ] Run attack-path analysis for surviving findings.
- [ ] Fix every validated finding.
- [ ] Re-run targeted tests after each fix.
- [ ] Save scan report under `/tmp/codex-security-scans`.

### Phase 9: Final Verification, Packaging, and Commit

- [ ] Run `./scripts/test.sh`.
- [ ] Run `./scripts/backend-test.sh`.
- [ ] Run `./scripts/frontend-typecheck.sh`.
- [ ] Run `./scripts/frontend-build.sh`.
- [ ] Run `./scripts/check-split.sh`.
- [ ] Run installed app E2E.
- [ ] Run performance measurement script.
- [ ] Run `./scripts/install.sh`.
- [ ] Confirm `./scripts/status.sh` reports all services healthy.
- [ ] Run `./scripts/package-release.sh`.
- [ ] Confirm release bundle checksum exists.
- [ ] Update this plan checklist to reflect completed work.
- [ ] Commit implementation with Korean conventional commit message.

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
