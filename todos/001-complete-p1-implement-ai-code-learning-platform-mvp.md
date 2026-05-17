---
status: complete
priority: p1
issue_id: "001"
tags: [mvp, node, security, ai-learning-platform]
dependencies: []
---

# Implement AI Code Learning Platform MVP

## Problem Statement

The workspace contains a complete plan but no runnable application. Build a local MVP that demonstrates the core platform loop: workspace setup, provider registration, evidence ingestion, source linking, pattern generation, human review, publication, learner practice, progress tracking, and security validation.

## Findings

- The workspace had documentation only and was not a git repository.
- System Node is broken because of a Homebrew `llhttp` dynamic library mismatch.
- Bundled Codex runtime Node at `/Users/heeung/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node` works.
- To keep the MVP self-contained, implementation should avoid external packages and network-dependent dependency installation.

## Proposed Solutions

- Build a zero-dependency Node app using built-in `http`, `fs`, `crypto`, and `node:test`.
- Use JSON-file persistence plus object-reference files under local data directories.
- Use a deterministic local AI generator for MVP tests instead of calling external AI providers.
- Use scripts that explicitly invoke the bundled Node runtime.

## Recommended Action

Implement the MVP as a local web app and API with tests and security checks. Use `Codex Security` after implementation and fix any validated findings.

## Acceptance Criteria

- [x] Local app can start without external package installation.
- [x] Admin, contributor, reviewer, learner flows work through API and UI.
- [x] Provider credentials are not exposed through API responses or logs.
- [x] Evidence ingestion scans and blocks test secrets.
- [x] Draft generation creates reviewable pattern cards with problems.
- [x] Review publication makes assets visible to learners.
- [x] Submissions update proficiency and recommendations.
- [x] Automated tests cover core flows, permissions, and security cases.
- [x] Codex Security scan runs and findings are fixed or documented as false positives.
- [x] Smoke test verifies the app works in a local environment.

## Work Log

### 2026-05-17 - Start Implementation

**By:** Codex

**Actions:**
- Initialized local git repository and committed brainstorm/plan documentation.
- Created working branch `feat/ai-code-learning-platform`.
- Selected zero-dependency Node MVP using bundled Codex Node runtime.

**Learnings:**
- Avoid relying on system Node in this environment; use the bundled runtime path in scripts.

### 2026-05-17 - MVP Complete

**By:** Codex

**Actions:**
- Implemented a zero-dependency Node HTTP app with JSON persistence and static UI.
- Added session-token authentication, scoped authorization, provider registration, evidence ingestion, source linking, deterministic draft generation, review/publication, learner submission, proficiency, and recommendations.
- Added automated tests and smoke environment.
- Ran Codex Security scan and fixed discovered authentication/rate-limit/bootstrap issues.

**Learnings:**
- The initial `x-user-id` development shortcut was unsafe; even local MVPs need a real session boundary before security review.
