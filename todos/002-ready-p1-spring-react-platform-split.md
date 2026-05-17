---
status: complete
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
- [x] Organization, user, membership, and session schema is migrated.
- [x] Demo organization and users are seeded in the local profile.
- [x] Session authentication stores token hashes only and rejects invalid tokens.
- [x] Role authorization foundation covers hierarchy and cross-organization denial.
- [x] Provider registration stores credential references only and emits audit.
- [x] Audit logs use redacted metadata and hash chaining.
- [x] Manual evidence ingestion supports source metadata, secret scanning, dedupe, and learner raw-evidence denial.
- [x] codex-obsidian-sync import creates conversation evidence.
- [x] Source link suggestion and confirmation gate generation.
- [x] Local mock generation creates draft pattern cards, tags, problems, and review tasks.
- [x] Review approval publishes cards to the learner library.
- [x] Learner submissions reveal answers and update proficiency/recommendations.
- [x] OpenAPI endpoint is available for the Spring backend contract.
- [x] React frontend is connected to session, provider, ingestion, generation, review, library, submission, and progress APIs.
- [x] Each implementation phase has verification evidence before commit.
- [x] Codex Security scan runs after implementation and validated findings are fixed.

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

### 2026-05-17 - Phase 5-7 Auth Foundation Complete

**By:** Codex

**Actions:**
- Added Flyway migration for organizations, teams, projects, users, memberships, and session tokens.
- Added local demo data seeding for admin, contributor, reviewer, and learner users.
- Implemented `POST /api/session`, bearer-token authentication, token hashing, expiry/revocation handling, `last_used_at`, and login throttling.
- Added role hierarchy authorization service and canonical JSON errors for auth, validation, not-found, and rate-limit cases.
- Added Testcontainers integration tests for migration, login, token rejection, spoofing prevention, throttling, CORS, security headers, and role checks.

**Verification:**
- `./scripts/backend-test.sh`
- `./scripts/backend-dev.sh` with local Docker PostgreSQL migration from V1 to V2.
- `POST /api/session` and authenticated `GET /api/me` via curl.
- `./scripts/check-split.sh`
- `./scripts/npm.sh --prefix frontend audit`

### 2026-05-17 - Phase 8-10 Provider Audit Evidence Complete

**By:** Codex

**Actions:**
- Added provider, audit, source bundle, and evidence item migrations.
- Implemented provider create/list APIs with credential sealing and personal/organization scope authorization.
- Seeded an organization-approved local mock provider in the local profile.
- Implemented append-only audit writes with redacted metadata and hash chaining.
- Implemented `POST /api/ingest/manual` and `GET /api/evidence/{bundleId}` with source metadata, secret scanning, blocked-sensitive handling, dedupe, and raw evidence authorization.

**Verification:**
- `./scripts/backend-test.sh`
- `./scripts/backend-dev.sh` with local Docker PostgreSQL migration from V2 to V3.
- Authenticated curl checks for `GET /api/providers` and `POST /api/ingest/manual`.
- `./scripts/check-split.sh`
- `./scripts/npm.sh --prefix frontend audit`

### 2026-05-17 - Phase 11-19 Learning Flow Complete

**By:** Codex

**Actions:**
- Added schema for source links, generation runs, pattern cards, tags, problems, review tasks/decisions, submissions, and proficiency scores.
- Added codex-obsidian import, source-link suggestion/confirm/reject, deterministic local generation, review queue/decision, library/detail, submission, progress, and recommendations APIs.
- Added integration coverage for the full Spring workflow from evidence ingestion through learner submission.

**Verification:**
- `./scripts/backend-test.sh`
- `./scripts/backend-dev.sh` with local Docker PostgreSQL migration from V3 to V4.
- `./scripts/check-split.sh`
- `./scripts/npm.sh --prefix frontend audit`

### 2026-05-17 - Phase 20-27 Frontend Contract Flow Complete

**By:** Codex

**Actions:**
- Verified Spring OpenAPI at `/v3/api-docs`.
- Replaced the static React shell with a connected workbench that can create sessions, list providers, run the full evidence-to-learning demo flow, and show card/progress state.
- Kept the Lovable-inspired visual direction while shifting the first screen to an operational workspace.

**Verification:**
- `./scripts/frontend-typecheck.sh`
- `./scripts/frontend-build.sh`
- Backend + Vite dev servers with `curl http://127.0.0.1:5173/`
- Vite proxy check with `curl http://127.0.0.1:5173/api/health`
- OpenAPI check with `curl http://localhost:8080/v3/api-docs`
- `./scripts/check-split.sh`
- `./scripts/npm.sh --prefix frontend audit`

### 2026-05-17 - Phase 28-32 Security And Final Verification Complete

**By:** Codex

**Actions:**
- Ran Codex Security with threat-model, discovery, validation, attack-path, and final report artifacts.
- Reproduced validated findings with red Spring integration tests for scoped source-link/generation access, review queue/decision access, library list filtering, personal provider ownership, and idempotency replay.
- Fixed the authorization model by separating organization membership checks from object-scope checks.
- Enforced bundle scope in source-link and generation paths, card scope in review/library paths, provider ownership in generation, and author/current-scope checks for idempotency replay.

**Verification:**
- `./scripts/backend-test.sh`
- `./scripts/test.sh`
- `./scripts/frontend-typecheck.sh`
- `./scripts/frontend-build.sh`
- `./scripts/npm.sh --prefix frontend audit`
- `./scripts/check-split.sh`
- `git diff --check`
- Codex Security report: `/tmp/codex-security-scans/spring-react-platform-split/64d1a31_20260517T030413Z/report.md`
