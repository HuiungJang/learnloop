---
status: ready
priority: p1
issue_id: "002"
tags: [spring, react, kotlin, frontend, backend]
dependencies: ["001"]
---

# Implement Spring Boot And React Platform Split

## Problem Statement

The Node MVP validates the product loop, but the long-term platform needs a Kotlin/Spring Boot backend, a TypeScript/React frontend, PostgreSQL persistence, security gates, and phased verification.

## Acceptance Criteria

- [x] Phase 1-4 split-stack foundation is runnable locally.
- [x] Existing Node MVP tests continue to pass as a parity oracle.
- [x] Spring Boot backend exposes a health endpoint and has automated tests.
- [x] React frontend builds and typechecks with the Lovable-inspired workspace shell.
- [x] Local PostgreSQL starts through Docker Compose and backend can run against it.
- [ ] Each implementation phase has verification evidence before commit.
- [ ] Codex Security scan runs after implementation and validated findings are fixed.

## Work Log

### 2026-05-17 - Start Split Stack Implementation

**By:** Codex

**Actions:**
- Created the working todo from the Spring/React split plan.
- Kept the Node MVP in place as the reference implementation.

**Learnings:**
- The environment has Java 21 and Docker, but no system Gradle.
- The bundled Codex Node runtime works; npm must be launched through the bundled Node binary.

### 2026-05-17 - Phase 1-4 Foundation Complete

**By:** Codex

**Actions:**
- Added root Gradle wrapper, backend/frontend scripts, Docker Compose PostgreSQL, and split verification script.
- Added a Kotlin/Spring Boot backend with security configuration, CORS settings, Flyway baseline, health endpoint, and MockMvc test.
- Added a Vite/React/TypeScript frontend shell using the Lovable-inspired warm workspace direction.
- Fixed frontend npm execution to force lifecycle scripts through the bundled Codex Node runtime.
- Removed an unused `react-router` dependency and upgraded Vite to satisfy npm audit.

**Verification:**
- `./scripts/test.sh`
- `./scripts/backend-test.sh`
- `./scripts/frontend-typecheck.sh`
- `./scripts/frontend-build.sh`
- `./scripts/npm.sh --prefix frontend audit`
- `./scripts/db-up.sh`
- `./scripts/backend-dev.sh` with `curl -fsS http://localhost:8080/api/health`
- `./scripts/check-split.sh`
