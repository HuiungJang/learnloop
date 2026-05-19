---
title: "feat: Convert LearnLoop To Single-User Local Learning Tool"
type: feat
date: 2026-05-19
brainstorm: docs/brainstorms/2026-05-19-local-ai-code-auto-collection-brainstorm.md
---

# feat: Convert LearnLoop To Single-User Local Learning Tool

## Overview

Convert LearnLoop's MVP direction from an organization/admin/reviewer platform into a single-user local installed learning tool. In this model, the app runs on the developer's own machine and the user is simultaneously the owner, curator, and learner. The app can collect from approved local repositories without remote collector pairing, device tokens, organization permissions, or admin dashboards.

The MVP should prove one coherent local loop:

```text
single local owner
  -> approved repo
  -> local AI session evidence
  -> secret-safe curation
  -> generated pattern
  -> practice item
```

The first automated collection path should be narrow and deterministic: structured local-session ingestion plus a Codex CLI shim. Codex App GUI correlation, long-running background watchers, Claude Code shims, Gemini CLI shims, and retention schedulers move to Post-MVP after the core loop is proven.

## Problem Statement

The current product shape still carries team-platform concepts: demo users, roles, organization permissions, reviewer/admin queues, and evidence approval flows. Those concepts make sense for a future hosted/team product, but they add unnecessary friction for a local personal learning tool.

For a single local user, the useful loop is simpler:

1. Approve a repository.
2. Use an AI coding tool normally.
3. LearnLoop collects bounded prompt/output and before/after code context when available.
4. LearnLoop extracts reusable patterns.
5. The user curates generated learning assets and practices them.

## Review Synthesis

This plan was deepened with architecture, security, data-integrity, performance, product-flow, and simplicity review.

Key synthesis:

- Keep the MVP small: one personal local loop, one automated collection path, one curation surface.
- Do not build a full local collection platform before the learning loop works.
- Make the thin MVP path strict about raw evidence handling, audit/log redaction, deletion/purge semantics, idempotency, and lineage.
- Keep hosted/team concepts internally only where removal would create unnecessary churn.
- Move background file watching, Codex App process correlation, Claude/Gemini adapters, and automatic retention cleanup to Post-MVP.

## Research Summary

### Brainstorm Source

- Brainstorm: `docs/brainstorms/2026-05-19-local-ai-code-auto-collection-brainstorm.md`
- Key decisions: local installed app, single user, no admin/reviewer MVP, automatic approved-repo collection, no remote Local Collector, no mandatory raw evidence encryption, secret scan and deletion required.

### Local Context

- `scripts/local-ai-companion.mjs`: existing host-side Node companion already runs on `127.0.0.1` and starts Codex/Gemini OAuth commands. It can host local status, consent, and shim events.
- `scripts/git-collector.mjs`: current Git collector collects bounded commit and working-tree diffs. It can remain as a fallback/backstop, but it is not enough to infer full AI-generated patterns by itself.
- `backend/src/main/kotlin/com/aicodelearning/evidence/EvidenceController.kt`: manual ingestion currently accepts one `content` field capped at 20,000 characters, too small for structured session artifacts.
- `backend/src/main/kotlin/com/aicodelearning/evidence/EvidenceService.kt`: evidence already runs secret scanning, but audit metadata currently includes raw content and must be fixed before larger evidence collection.
- `backend/src/main/resources/db/migration/V3__provider_audit_evidence_schema.sql`: `source_bundles` and `evidence_items` can be extended for local AI session bundles.
- `backend/src/main/kotlin/com/aicodelearning/learning/GenerationService.kt`: generation currently requires confirmed source links and role checks; local mode should accept curated local session evidence directly.
- `frontend/src/App.tsx`: current UI has local AI setup, evidence review, role-driven flows, and workflow dashboards that can be reduced for the personal app.
- `docs/solutions/2026-05-18-ai-pattern-e2e-performance-security.md`: prior learning emphasizes prompt privacy, bounded evidence, secret redaction, and E2E validation.

### External Notes

- Node's `fs.watch` has platform caveats; Post-MVP watchers should use events only as triggers and reconcile with Git state.
- Node `child_process.spawn` is appropriate for CLI shims because it streams output and avoids blocking; shims must avoid shell interpolation.
- Since evidence remains local in this MVP, raw evidence encryption is optional rather than required. Secret scan, ignore rules, bounded storage, retention, and deletion are still required because local repos may contain `.env`, tokens, certificates, private keys, and proprietary code.

## Scope

### MVP Scope

- Single-user local installed app mode.
- One local owner profile instead of multi-role demo users.
- Hide admin/reviewer/organization workflow from the primary UI.
- Repository approval, revocation, and ignore controls.
- Structured local-session evidence ingestion.
- Codex CLI shim as the first user-facing automatic collection path.
- Local evidence curation: use for generation, mark manual, edit attribution, delete, or purge raw evidence.
- Secret scanning, quarantine, ignored paths, path validation, size limits, and audit/log redaction.
- Local pattern generation and practice from curated evidence.
- Installed-app E2E proving owner -> ingest/collect -> curate -> generate -> practice.

### Post-MVP Scope

- Codex App process/window correlation.
- Long-running background repository watcher and before-snapshot cache.
- Claude Code shim.
- Gemini CLI shim.
- Automatic retention scheduler and configurable retention.
- Rich attribution taxonomy beyond the MVP values.
- Local evidence encryption option.
- Hosted/team/admin/reviewer mode.

### Out Of Scope

- Browser-only hosted collection.
- Remote collector pairing, device tokens, remote sync, or fleet management.
- Public or organization sharing.
- Admin dashboards, reviewer queues, organization membership, team/project permissions in the primary MVP UI.
- Perfect AI attribution for GUI tools without captured AI output.

## Proposed Solution

### Product Shape

```text
LearnLoop Local App
  - localhost web UI
  - local backend API
  - local database / Docker volume
  - host-side companion process
  - local AI provider setup
  - personal evidence store
  - personal learning library
```

The app can keep a simple internal owner identity for data ownership and future migration, but the user should not experience admin/reviewer/member switching. Seeded demo roles should disappear from the primary product path.

### Runtime Boundary Contract

- Frontend owns user interaction only: setup, consent choices, curation, generation actions, and settings.
- Backend owns durable data, validation, secret scanning, generation eligibility, deletion, purge, audit-safe metadata, and generated asset lineage.
- Host-side companion owns local machine observation only: shim events, repository consent state, local status, and optional future file watching.
- The companion never mutates cards, practice items, or database rows directly. It talks to the backend through structured HTTP payloads.
- The backend must not shell out to inspect local repositories. It accepts structured local-session evidence from the companion or tests.
- The frontend must not read local files directly.

### Local Deployment Topology

```text
Host machine
  - approved repositories
  - Codex CLI shim
  - local companion on loopback
  - consent and shim metadata files

Local app runtime
  - backend API
  - frontend static app
  - PostgreSQL / local volume
```

The companion should run as a host-side process so it can observe local tools and repositories. The backend can remain containerized. Status scripts must distinguish backend health, frontend health, companion health, and shim installation state.

### Local Threat Assumptions

- Other local processes, browser tabs, extensions, or malware may attempt to call localhost endpoints.
- Approved repositories may contain secrets, generated credentials, private keys, proprietary code, and ignored build artifacts.
- CLI shims must not weaken the behavior or trust boundary of the original CLI.
- Raw evidence is sensitive even when stored locally and must be minimized, redacted where needed, retained briefly, and purgeable.

### User Experience

Primary navigation should focus on:

- Setup: local AI provider and repository approval.
- Collection: captured evidence and attribution.
- Patterns: generated learning cards.
- Practice: exercises and progress.
- Settings: data deletion, ignored repos, and tool adapters.

Replace reviewer/admin language:

- `Evidence Review` -> `Collected Evidence`
- `Review Tasks` -> `Curation Queue`
- `Approve/Reject` -> `Use/Edit/Delete`
- `Organization Library` -> `Learning Library`

### MVP Collection Architecture

```text
Codex CLI shim
  - resolves original codex binary
  - forwards args/stdin/stdout/stderr/exit code
  - emits bounded start/end events
  - collects cwd, repo, branch, timestamps, and bounded output where safe

Local companion
  - stores consent state
  - exposes loopback status and consent endpoints
  - sends structured local-session payloads to backend

Backend evidence API
  - validates repo/path/size/idempotency
  - scans secrets before durable raw storage or generation eligibility
  - stores bundle, evidence items, attribution, and lineage
```

Collection must be fail-open for developer work. If collection fails, Codex should still run normally with the original behavior, output, and exit code.

### Post-MVP Automatic Collection Architecture

Background repository watching should be added only after the MVP loop works.

Post-MVP watcher responsibilities:

- Watch approved Git repo roots only.
- Use filesystem events as triggers and Git state as reconciliation.
- Keep bounded before snapshots when a clean file first becomes dirty.
- Capture after snapshots and final diff after debounce.
- Group related file changes into one activity window.
- Correlate Codex App, Claude Desktop, Claude Code, or Gemini activity when safe signals exist.

### Repository Consent

MVP consent states:

- `approved`
- `always_ignored`
- `revoked`
- `missing`

Rules:

- Do not store raw prompt/code content until the repository is approved.
- Approval identity is based on normalized repo root, Git common-dir/worktree identity, and sanitized remote fingerprint when available.
- If a repo is moved or renamed, show it as missing and require re-approval instead of silently trusting the new path.
- If approval is revoked, stop collection immediately, cancel pending uploads for that repo, and offer keep metadata, delete repo evidence, or purge repo raw evidence.
- Consent file corruption is deny-by-default.
- Store consent files outside repositories with owner-only permissions where supported.

### Local Session Evidence Contract

Use one MVP source kind:

- `local_ai_session`

MVP evidence item types:

- `prompt`
- `ai_response`
- `file_before`
- `file_after`
- `diff`
- `tool_event`

Evidence states:

- `received`
- `rejected_by_path_or_size`
- `scanned_clean`
- `quarantined_secret`
- `redacted_stored`
- `generation_eligible`
- `purged_raw`
- `deleted`

The request contract must include:

- repo identity hash and display label
- source kind and tool provider
- tool event id or timestamp bucket
- repo-relative artifact paths
- artifact content hash
- artifact size
- optional bounded content
- truncation flag and limit reason
- attribution signal reasons
- idempotency key

List endpoints must not return full raw artifacts. Detail endpoints may return bounded excerpts only.

### Attribution Model

MVP automatic values:

- `ai_assisted`
- `manual_or_unknown`

MVP user curation values:

- `use_for_generation`
- `manual`
- `delete`

Optional metadata:

- `confidence`
- `reasons_json`

Post-MVP may add richer values such as `verified_ai_generated`, `ai_assisted_edited`, `likely_ai_assisted`, and provider-specific reason codes. The MVP should not block on perfect attribution taxonomy.

### Secret Handling And Retention

Encryption is not required for the local personal MVP, but privacy controls still matter.

- Raw local evidence is allowed only after repo approval, ignore filtering, path checks, size checks, secret scanning/redaction, and retention policy assignment.
- Secret scan must run before durable raw evidence persistence or generation eligibility.
- Ingestion may hold raw content only in memory or a short-lived staging transaction.
- If likely secrets are found, persist only redacted excerpts, artifact metadata, secret type, fingerprint, and quarantine reason.
- Never persist raw secret values in evidence, audit metadata, logs, traces, errors, frontend state, console logs, or test snapshots.
- Secret scanning must cover prompts, AI responses, before files, after files, diffs, tool stdout/stderr, and paths or filenames that may contain credentials.
- Quarantined evidence cannot generate practice in the MVP. The user can delete or purge it. False-positive release is Post-MVP.
- Manual delete and raw purge are MVP. Automatic retention cleanup is Post-MVP.
- Documentation must state that raw evidence is not encrypted in MVP and may be readable by local users/processes with filesystem access.

### Data Model Direction

Short-term pragmatic approach:

- Keep existing organization/team/project columns internally where removing them would create churn.
- Seed a single local workspace and single owner user automatically.
- Hide role switching and admin/reviewer surfaces from the UI.
- Treat review status as local curation status.
- Keep only existing IDs that are expensive to remove. Do not add new organization, membership, invite, tenant, device, or remote-sync tables for the local MVP.

Raw evidence and lineage:

- Separate raw artifact storage from durable lineage metadata.
- Deletion and retention may purge raw `content_text`, but must preserve bundle tombstones, hashes, attribution decisions, repo-relative paths, generated-card references, and audit-safe metadata unless the user chooses full app-data deletion.
- Generated cards and practice items must store the source bundle ids and evidence item ids used at generation time.
- Raw purge must not break generated assets.
- Full evidence delete should either block when generated assets reference it or convert the bundle to a tombstone.

Migration constraints:

- Do not edit old Flyway migrations.
- Update existing source-kind/status check constraints through new migrations.
- Namespace local stable ids and dedupe keys by workspace/organization to avoid future import or hosted/team migration collisions.

## Resolved MVP Decisions

- First run can complete without AI provider setup. Collection/settings remain usable, while generation actions stay disabled until a provider is configured.
- Repo approval is scoped to normalized repo root, Git common-dir/worktree identity, and sanitized remote fingerprint when available.
- Revocation stops collection and queued uploads immediately.
- Purging raw evidence preserves safe metadata, hashes, lineage, generated cards, and progress by default while clearing raw content and raw-ish user-supplied metadata.
- Deleting all app data in the current backend phase removes app database rows; existing browser storage and future companion consent state, shim metadata, filesystem artifacts, and retained local logs need explicit installed-app cleanup outside this backend endpoint.
- Attribution overrides are reversible and immediate, with append-only history.
- Shim install is user-triggered from Settings or install scripts. The MVP does not silently modify PATH.
- Practice generation is user-triggered in MVP.

## User Flows

### First Run

1. User installs LearnLoop.
2. App starts on localhost.
3. A local owner profile and local workspace are created automatically.
4. User can skip AI provider setup and configure it later.
5. User approves a Git repository or starts with an empty collection state.
6. App shows collection status without role selection.

Failure and recovery:

- If backend or companion is down, show degraded status and retry guidance.
- If repo approval fails, leave the repo pending or ignored without blocking the rest of setup.
- If local state is stale or corrupted, deny collection until the user repairs or re-approves.

### Codex CLI Collection

1. User installs the Codex shim manually from Settings or install scripts.
2. User runs `codex` normally.
3. Shim invokes the original Codex binary.
4. Shim emits bounded events to the companion without blocking the CLI.
5. Companion uploads one structured local session for an approved repo.
6. Evidence appears in Collected Evidence as AI-assisted or manual/unknown.

### Personal Curation

1. User opens Collected Evidence.
2. User sees attribution, confidence, reason codes, files, and bounded excerpts.
3. User chooses use for generation, mark manual, edit attribution, delete, or purge raw evidence.
4. User generates a pattern card.
5. User opens the practice item.

### Delete And Purge

Delete actions:

- Delete evidence: soft-delete bundle from normal UI and generation queries.
- Purge raw evidence: remove raw prompt/code/file content and raw-ish user-supplied metadata while preserving generated learning assets.
- Purge repository evidence: remove evidence and raw artifacts for one approved or revoked repo.
- Delete all app data: destructive local backend wipe of app database rows. Installed-app cleanup for existing browser storage plus companion consent state, shim metadata, filesystem artifacts, and retained local logs is a later UI/companion phase.

All delete/purge audit records must contain no raw content.

## Performance Targets

MVP targets:

- Local session upload payload: 5MB hard cap, 2MB soft warning, 200KB per text artifact.
- Max files per session: 100.
- Max diff bytes per session: 1MB.
- CLI shim startup overhead: under 100ms p95 when companion is healthy, under 50ms p95 when companion is down.
- Evidence list responses exclude raw content and remain responsive with 1,000 bundles through pagination or virtualization.

Post-MVP watcher targets:

- Watcher steady-state idle CPU: under 1% per 10 approved repos.
- Watcher memory: under 150MB for 10 approved repos and 1,000 changed files.
- File event debounce: 750ms default quiet period, configurable between 250ms and 5s.
- Git reconciliation: no more than one status/diff cycle per repo per debounce window.
- Cleanup job: batch deletes in chunks of 100-500 rows/artifacts and yields between batches.

## Implementation Phases

### Phase 1: Lock The Local Product Boundary

- [x] Update `README.md`, `README.ko.md`, and `packaging/release-bundle/README.md` to describe LearnLoop as a single-user local installed app.
- [x] Move hosted/team/admin language into a clearly labeled future section or remove it from the MVP path.
- [x] Add explicit non-goals: browser-only hosted collection, remote collector pairing, admin dashboards, reviewer queues, and team permissions.
- [x] Avoid a broad `ProductMode` framework unless existing tests require runtime branching. If branching is required, keep it backend-only and minimal.

Verification:

- [x] `rg -n "admin|reviewer|organization|hosted|team" README.md README.ko.md packaging/release-bundle/README.md` only finds future-mode or internal-compatibility references.
- [x] Fresh local install docs describe owner -> collection -> curation -> practice.

### Phase 2: Convert Primary App Path To Local Owner Mode

- [x] Seed one local owner and one local workspace.
- [x] Stop seeding role-demo users in the installed/local path.
- [x] Disable open self-registration in the default local owner path.
- [x] Remove or hide role selector UI in the primary app path.
- [x] Rename review labels to local curation labels.
- [x] Map local auth to one seeded owner session while preserving an internal admin membership for compatibility.
- [x] Keep existing organization/team/project fields internally if removing them would create migration churn.

Verification:

- [x] Backend test proves fresh local mode creates exactly one owner profile and no admin/reviewer/learner demo set.
- [x] Integration test proves a fresh install reaches an authenticated local owner session without choosing a role.
- [x] Backend test proves default local owner mode rejects self-registration.
- [x] UI smoke path has no visible `admin@example.com`, `reviewer`, or `learner` chooser.

### Phase 3: Remove Raw Evidence From Audit And Log Surfaces

- [x] Update `EvidenceService.ingestManual` so audit metadata never includes `rawContent`.
- [x] Add a central audit metadata sanitizer used by manual ingest, local session ingest, attribution overrides, deletion, purge, shim events, and generation.
- [x] Use allowlisted audit metadata fields only.
- [x] Check logs, API audit responses, frontend console logs, and test snapshots for raw evidence leakage.

Verification:

- [x] Backend test ingests unique sentinel text and proves it is absent from audit rows and `/api/audit` responses.
- [x] Test with a fake secret across prompt, response, diff, path, stdout, and stderr; assert it is absent from audit/log surfaces.

### Phase 4: Add Evidence Delete, Raw Purge, And Tombstones

- [x] Add `DELETE /api/evidence/{bundleId}` for local owner evidence deletion.
- [x] Add local-only raw purge endpoint for one bundle, one repo, or all collected evidence.
- [x] Add full local app-data delete as a separate destructive path.
- [x] Add `source_bundles.deleted_at`, `deleted_by_user_id`, and `deletion_reason`.
- [x] Add `evidence_items.raw_purged_at` and `raw_purge_reason`.
- [x] Soft-delete bundles from normal UI/generation queries; hard-delete only for full app-data deletion or unreferenced temporary artifacts.
- [x] Ensure purge removes raw content from current DB fields, audit metadata, and API-visible excerpts; no durable filesystem artifact, staging, collector cache, or quarantine raw payload store exists yet in this phase.

Verification:

- [x] Backend tests cover delete evidence, single-bundle raw purge, and repo raw purge semantics.
- [x] Backend tests cover full app-data delete semantics.
- [x] Generated cards remain readable after raw purge.
- [x] Deleted bundles are excluded from generation queries.
- [x] Purged sentinel content cannot be found in current DB tables, audit metadata, or API responses; artifact directories and collector cache are not yet durable stores in this phase.

### Phase 5: Add Minimal Local Session Evidence Schema And API Contract

- [x] Add source kind `local_ai_session` through a new Flyway migration.
- [x] Drop/recreate affected check constraints in the new migration; do not edit old migrations.
- [x] Add item types for prompt, AI response, before file, after file, diff, and tool event.
- [x] Keep item-type enforcement at the DTO/service contract layer so legacy free-form `evidence_items.item_type` rows remain purgeable.
- [x] Add artifact metadata fields: repo-relative path, size bytes, metadata JSON, content hash, content truncated, and raw purged timestamp.
- [x] Add source-bundle attribution columns: `auto_attribution`, `user_attribution`, `attribution_confidence`, and `attribution_reasons_json`.
- [x] Add `source_bundle_attribution_events` for append-only attribution history.
- [x] Add generated-asset lineage references to source bundle ids and evidence item ids where missing.

Verification:

- [x] Migration test proves old evidence rows remain readable with default values.
- [x] DTO serialization test covers a full local session payload.
- [x] Constraint migration is tested against existing rows.
- [x] Migration test proves legacy free-form item types remain updateable for raw purge.

### Phase 6: Validate Paths, Limits, Ignore Rules, And Secret Scan Staging

- [x] Add path normalization helper for artifact paths.
- [x] Resolve artifacts against the approved repo root and reject symlink escapes.
- [x] Reject absolute paths, `..`, null bytes, home expansion, URL-encoded traversal, and platform separator escapes.
- [x] Store repo-relative paths by default; avoid absolute home paths in backend evidence and audit metadata.
- [x] Normalize Unicode paths before comparison.
- [x] Treat hidden files as deny-by-default unless explicitly allowed and not matched by sensitive rules.
- [x] Enforce 200KB per text artifact, 1MB diff, 100 files, and 5MB per session.
- [x] Ignore `.env*`, key/cert files, binary/media/archive files, dependency folders, and build output in backend preflight before durable storage and generation.
- [x] Run secret scanning before durable raw persistence or generation eligibility; use memory or short-lived staging only.

Verification:

- [x] Backend tests cover valid relative paths, symlink escape, Unicode normalization, hidden files, Windows separators, URL-encoded traversal, oversized files, too many files, and oversized sessions.
- [x] Secret findings quarantine evidence and block generation.
- [x] Oversized artifacts preserve metadata, hash, truncation flag, and limit reason without raw content.

### Phase 7: Implement Local Session Ingestion With Idempotency

- [x] Add `POST /api/ingest/local-ai-session`.
- [x] Persist one source bundle with multiple evidence items.
- [x] Compute content hashes per artifact and a bundle-level dedupe key.
- [x] Add `source_bundles.dedupe_key`.
- [x] Add a unique index on `(organization_id, source_kind, dedupe_key)` where `dedupe_key IS NOT NULL AND deleted_at IS NULL`.
- [x] Define dedupe key from repo identity hash, normalized relative paths, artifact hashes, tool event id or timestamp bucket, and source kind.
- [x] Reject or quarantine the whole bundle if any artifact contains likely secrets unless safe redaction can preserve useful context.
- [x] Do not return raw full content from broad list/detail APIs.

Verification:

- [x] Integration test ingests prompt, AI response, before, after, and diff artifacts in one request.
- [x] Sequential duplicate upload returns or reuses the existing bundle.
- [x] Concurrent duplicate uploads produce one durable bundle.
- [x] Secret-containing bundle is quarantined or redacted before generation eligibility.

### Phase 8: Add Simple Attribution And User Override

- [ ] Implement MVP automatic attribution: `ai_assisted` or `manual_or_unknown`.
- [ ] Implement user curation values: `use_for_generation`, `manual`, and `delete`.
- [ ] Add `PATCH /api/evidence/{bundleId}/attribution`.
- [ ] Each override writes an attribution event row and an audit log entry with no raw code, prompt, diff, stdout/stderr, or full path list.
- [ ] Preserve automatic attribution separately from user override.

Verification:

- [ ] Backend test proves override changes generation eligibility while preserving automatic attribution.
- [ ] Repeated overrides preserve full history.
- [ ] Audit metadata contains only attribution values, confidence/reason codes, bundle id, and safe metadata.

### Phase 9: Generate From Curated Local Evidence

- [ ] Update generation service to accept a curated local AI session bundle directly, without requiring a conversation/code source link when the bundle already contains both.
- [ ] Require secret scan pass, non-deleted bundle, non-purged required content, and user curation value `use_for_generation`.
- [ ] Exclude manual, deleted, quarantined, and unknown evidence from generation by default.
- [ ] Keep existing manual/source-link generation path untouched.
- [ ] Store generated-card lineage to source bundle ids and evidence item ids.

Verification:

- [ ] Backend tests cover usable, manual, deleted, raw-purged, quarantined, duplicate, and unknown evidence.
- [ ] Existing manual/source-link smoke path still generates.
- [ ] Local-session path generates without creating a source link.
- [ ] Raw purge after generation does not break generated cards or practice.

### Phase 10: Add Minimal Collection And Curation UI

- [ ] Add collection status to Settings.
- [ ] Show approved, revoked, ignored, and missing repositories.
- [ ] Let the user approve, revoke, always ignore, delete evidence, and purge raw evidence.
- [ ] Rename UI labels to Collected Evidence and curation language.
- [ ] Show attribution, confidence, reason codes, files, and bounded excerpts.
- [ ] Add actions: use for generation, mark manual, edit attribution, delete, purge raw.
- [ ] Use pagination or virtualization for evidence lists.
- [ ] Lazy-load bounded detail excerpts only when opened.
- [ ] Do not put raw evidence in localStorage, sessionStorage, URL params, telemetry, console logs, or broad list responses.

Verification:

- [ ] Frontend typecheck/build passes.
- [ ] Mock/manual flow shows curation actions for a local session bundle.
- [ ] Evidence list renders 1,000 bundles without long main-thread stalls.
- [ ] Raw full file content is absent from list responses and browser storage.

### Phase 11: Add Codex CLI Shim As First Automatic Collection Path

- [ ] Add install/uninstall/repair commands for the Codex shim.
- [ ] Install shims only in a LearnLoop-managed directory; do not overwrite the real provider binary.
- [ ] Resolve original `codex` path before installing and detect recursive shim chains.
- [ ] Record original binary path and hash where practical.
- [ ] Warn if original binary path changes unexpectedly.
- [ ] Preserve args, stdin, stdout, stderr, cwd, signal behavior, and exit code.
- [ ] Start the original CLI immediately after lightweight start-event enqueue.
- [ ] Send collection events asynchronously with short timeouts.
- [ ] Never block stdout/stderr passthrough on upload, diffing, attribution, or companion availability.
- [ ] Do not capture environment variables, shell history, terminal scrollback, config files, auth files, or provider cache directories.
- [ ] Redact stdout/stderr before durable storage and treat stderr as sensitive by default.
- [ ] Fail open to original Codex if companion or backend is down.

Verification:

- [ ] Fake PATH tests prove install, uninstall, repair, no recursion, and PATH precedence failure handling.
- [ ] Fake `codex` binary tests cover success, failure, large output, stdin passthrough, signal handling, companion-down behavior, and unchanged exit code.
- [ ] Latency tests prove shim startup overhead is under 100ms p95 when companion is healthy and under 50ms p95 when companion is down.
- [ ] Tests prove env vars are not captured and fake secrets in stdout/stderr are quarantined or redacted.

### Phase 12: Package Companion And Prove Installed-App E2E

- [ ] Keep existing OAuth helper behavior in `scripts/local-ai-companion.mjs`.
- [ ] Add only the companion modules needed by MVP; extract modules only when imported by both runtime and tests or when the file becomes hard to follow.
- [ ] Add loopback-only status and consent endpoints.
- [ ] Bind only to `127.0.0.1` and `::1`; never `0.0.0.0`.
- [ ] Require a random per-install local API token for mutating companion endpoints.
- [ ] Store the token outside repo directories with owner-only permissions.
- [ ] Validate `Origin` and `Host`; reject DNS rebinding-style hosts.
- [ ] Use strict CORS allowlist for the app origin only.
- [ ] Disable caching for evidence/status responses.
- [ ] Add request body limits and rate limits for ingestion and control endpoints.
- [ ] Update `scripts/install.sh`, `scripts/start.sh`, `scripts/status.sh`, and `scripts/stop.sh`.
- [ ] Ensure stop/uninstall does not leave orphan companion processes.

Verification:

- [ ] Node HTTP tests reject bad Host, bad Origin, missing token, oversized request, unauthenticated purge/revoke, and non-loopback access.
- [ ] Release bundle includes companion scripts and can start/stop cleanly.
- [ ] `./scripts/e2e-installed.sh` proves no role selector, local collection/ingestion, curation, generation, and practice.

### Phase 13: Run Regression, Security, Data, And Performance Gates

- [ ] Run backend tests.
- [ ] Run frontend typecheck/build.
- [ ] Run Node companion/shim tests.
- [ ] Run smoke test.
- [ ] Run installed-app E2E.
- [ ] Run sentinel leakage tests across audit, logs, API responses, and frontend state.
- [ ] Run duplicate-ingestion race test.
- [ ] Run evidence list performance check with 1,000 bundles.

Verification:

- [ ] `./scripts/test.sh`
- [ ] `./scripts/frontend-typecheck.sh`
- [ ] `./scripts/smoke.sh`
- [ ] `./scripts/e2e-installed.sh`
- [ ] No raw prompt/code appears in audit logs, broad list views, browser storage, or generated diagnostics.

## Post-MVP Implementation Phases

Post-MVP phases are intentionally smaller than the MVP phases. Each phase should produce one implementation slice with a direct verification path. Do not start Post-MVP work until the MVP acceptance criteria pass.

### Post-MVP Phase 14: Define Tool Adapter Event Contract

- [ ] Define the common adapter event shape: tool id, provider, invocation id, cwd, repo root, timestamps, command classification, output excerpt metadata, and capability flags.
- [ ] Define supported capability flags: `process_signal`, `window_signal`, `cli_transcript`, `patch_output`, and `json_stream`.
- [ ] Define how each capability contributes to attribution confidence.
- [ ] Keep provider-specific parsing outside the common contract.

Verification:

- [ ] Contract test validates one fake event for each capability flag.

### Post-MVP Phase 15: Add Adapter Fixture Harness

- [ ] Add fake adapter fixtures that produce deterministic process, CLI, patch, and stream events.
- [ ] Add fixture replay into the local session bundler without requiring Codex, Claude, Gemini, or macOS process APIs.
- [ ] Store fixtures without raw secrets or real local paths.

Verification:

- [ ] Fixture test can generate one local session bundle from fake adapter events.

### Post-MVP Phase 16: Add Host Process Snapshot Reader

- [ ] Add a host-side process snapshot interface behind the companion boundary.
- [ ] Implement macOS process listing with bounded command timeouts.
- [ ] Normalize process names, pids, executable paths, and frontmost/app-active indicators where available.
- [ ] Redact absolute home paths from status responses.

Verification:

- [ ] Unit tests use fake process snapshots.
- [ ] Manual macOS check shows process metadata without raw file contents or environment variables.

### Post-MVP Phase 17: Detect Codex App Presence

- [ ] Add a Codex App adapter that reads process snapshots.
- [ ] Report `running`, `frontmost`, `recently_active`, or `unavailable`.
- [ ] Keep the adapter optional and degraded when OS process data is unavailable.
- [ ] Do not collect window text, terminal scrollback, environment variables, or app cache files.

Verification:

- [ ] Fake process tests cover running, frontmost, unavailable, and process-name mismatch cases.

### Post-MVP Phase 18: Correlate GUI Activity Windows

- [ ] Define activity window start/end rules for GUI-only signals.
- [ ] Match approved repo changes that occur during the activity window.
- [ ] Lower confidence when multiple AI tools are active in the same window.
- [ ] Keep correlation metadata as reason codes, timestamps, and repo-relative paths only.

Verification:

- [ ] Tests cover single tool, multiple tools, no approved repo, and stale activity windows.

### Post-MVP Phase 19: Create GUI Correlation Evidence

- [ ] Convert GUI activity windows into `local_ai_session` payloads with correlation evidence only.
- [ ] Mark GUI-only sessions as user-confirmation-required.
- [ ] Do not auto-generate practice from GUI-only evidence unless direct AI output or patch data is later attached.
- [ ] Ensure reason codes do not include raw snippets or full absolute paths.

Verification:

- [ ] Integration test creates one GUI-correlated evidence bundle and blocks generation until user confirmation.

### Post-MVP Phase 20: Add GUI Correlation UI States

- [ ] Add UI labels for GUI-correlated evidence.
- [ ] Show why the evidence is lower confidence.
- [ ] Let the user confirm, mark manual, or delete the correlated evidence.
- [ ] Keep raw detail views bounded and lazy-loaded.

Verification:

- [ ] Frontend test or manual checklist covers confirm, manual, delete, and blocked generation states.

### Post-MVP Phase 21: Add Watcher Registry

- [ ] Add one watcher registration per approved repo root.
- [ ] Start watchers only for approved repos.
- [ ] Stop watchers immediately when a repo is revoked, missing, or ignored.
- [ ] Surface watcher state as active, stopped, degraded, or unavailable.

Verification:

- [ ] Node tests cover approve, revoke, missing repo, ignored repo, and companion restart.

### Post-MVP Phase 22: Add Watcher Event Debounce

- [ ] Debounce file events per repo with a default 750ms quiet period.
- [ ] Coalesce repeated events by normalized repo-relative path.
- [ ] Keep only the latest event timestamp per path.
- [ ] Make debounce configurable between 250ms and 5s.

Verification:

- [ ] Rapid save test proves repeated events produce one settled change set.

### Post-MVP Phase 23: Add Watcher Backpressure

- [ ] Add bounded per-repo event queues.
- [ ] If a queue is full, drop individual events and mark the repo as needing full Git reconciliation.
- [ ] Limit concurrent snapshot/diff work across repos.
- [ ] Surface degraded state when OS watcher limits are hit.

Verification:

- [ ] Stress test proves event queues do not grow without bound.

### Post-MVP Phase 24: Add Bounded Git Reconciliation

- [ ] Implement one Git reconciliation function for branch, status, changed files, and diff candidates.
- [ ] Prefer `git status --porcelain=v1 -z` for changed paths.
- [ ] Cache repo root, branch, and sanitized remote metadata with short TTLs.
- [ ] Add timeout and output-size caps for every Git command.
- [ ] Track Git command count in tests.

Verification:

- [ ] Temp repo test proves 100 file events trigger at most one status pass and one final diff pass per debounce window.

### Post-MVP Phase 25: Add Watcher Ignore And Path Safety

- [ ] Run ignore rules before file reads, snapshotting, diff upload, and UI preview.
- [ ] Deny sensitive extensions and filenames even when Git tracks the file.
- [ ] Reject symlink escapes and path traversal.
- [ ] Sanitize remote URLs before storage or logging.

Verification:

- [ ] Tests cover `.env`, key files, symlink escape, ignored tracked file, binary file, and sanitized remote URL.

### Post-MVP Phase 26: Add Before Snapshot Cache

- [ ] Capture before snapshots when a clean approved file first becomes dirty.
- [ ] Mark before snapshot unavailable for files already dirty when watcher starts.
- [ ] Bound before snapshot cache by count and bytes per repo.
- [ ] Use LRU eviction.
- [ ] Store metadata-only entries for oversized files.

Verification:

- [ ] Tests cover clean-to-dirty, pre-existing dirty, cache eviction, oversized file, and deleted file.

### Post-MVP Phase 27: Add After Snapshot And Diff Capture

- [ ] Capture after snapshots after the quiet period.
- [ ] Use path-scoped diffs only for files that pass ignore and size checks.
- [ ] Add diff truncation metadata when capped.
- [ ] Keep binary, ignored, and oversized files metadata-only.

Verification:

- [ ] Temp repo tests prove final after content and diff match the settled file state after rapid edits.

### Post-MVP Phase 28: Add Watcher Session Bundler

- [ ] Group settled file changes into one local session window.
- [ ] Attach tool activity when available.
- [ ] Add safe reason codes such as `repo_changed`, `gui_activity_window`, `cli_shim`, and `patch_match`.
- [ ] Drop artifacts that failed path, ignore, size, or secret checks.

Verification:

- [ ] Tests cover single-file session, multi-file session, no tool signal, and competing tool signals.

### Post-MVP Phase 29: Add Watcher Upload Queue And Retry

- [ ] Add bounded local queue for sessions when backend is down.
- [ ] Define retry count, retry delay, and discard policy.
- [ ] Cancel queued uploads when repo approval is revoked.
- [ ] Store queue metadata without raw code when possible.

Verification:

- [ ] Tests cover backend-down queueing, retry after restart, revoke-before-upload, and queue-size limit.

### Post-MVP Phase 30: Add Watcher Settings And Status UI

- [ ] Show watcher state per approved repo.
- [ ] Show queue size, last reconciliation time, and degraded states.
- [ ] Let the user disable watcher collection without uninstalling CLI shims.
- [ ] Do not expose raw prompt/code or absolute local paths in status views.

Verification:

- [ ] Frontend test or manual checklist covers active, degraded, disabled, queued, and revoked states.

### Post-MVP Phase 31: Add Watcher Performance Gate

- [ ] Simulate 10 approved repos.
- [ ] Simulate 1,000 changed files.
- [ ] Simulate rapid save bursts.
- [ ] Assert bounded memory, bounded Git command count, and one final session per settled window.

Verification:

- [ ] Performance test runs in CI or a documented local performance script.

### Post-MVP Phase 32: Extract Generic Shim Installer

- [ ] Extract provider-agnostic shim install, uninstall, repair, and status logic from the Codex shim.
- [ ] Keep shims in a LearnLoop-managed directory.
- [ ] Record original binary path and hash where practical.
- [ ] Detect recursive shim chains for every provider.

Verification:

- [ ] Fake PATH tests cover install, uninstall, repair, missing original binary, and recursion.

### Post-MVP Phase 33: Extract Generic Shim Runtime

- [ ] Extract provider-agnostic runtime forwarding around `spawn`.
- [ ] Preserve args, stdin, stdout, stderr, cwd, signal behavior, and exit code.
- [ ] Send collection events asynchronously with short timeouts.
- [ ] Fail open when companion or backend is down.

Verification:

- [ ] Fake binary tests prove behavior matches the original binary for success, failure, signal, stdin, large output, and companion-down cases.

### Post-MVP Phase 34: Add Claude Code Shim Installer

- [ ] Add provider config for the `claude` binary.
- [ ] Resolve original `claude` path before installing.
- [ ] Add PATH guidance for shells where the shim directory is not first.
- [ ] Add uninstall and repair behavior.

Verification:

- [ ] Fake PATH tests cover healthy install, missing binary, existing shim, PATH precedence failure, repair, and uninstall.

### Post-MVP Phase 35: Add Claude Code Runtime Capture

- [ ] Forward interactive Claude Code sessions without changing behavior.
- [ ] Capture bounded stdout/stderr only after redaction.
- [ ] Treat stderr as sensitive by default.
- [ ] Capture higher-confidence output only for safe non-interactive modes such as `--print`.

Verification:

- [ ] Fake `claude` tests cover interactive passthrough, `--print`, large output, stderr redaction, and companion-down behavior.

### Post-MVP Phase 36: Add Claude Output Parser

- [ ] Parse patch-like output in a Claude-specific module.
- [ ] Parse structured stream/json output only when documented and stable.
- [ ] Fall back to low-confidence metadata when parsing fails.
- [ ] Keep parsing failures isolated from the original CLI command.

Verification:

- [ ] Parser tests cover exact patch, edited patch, json stream, malformed stream, and no-match cases.

### Post-MVP Phase 37: Add Gemini CLI Shim Installer

- [ ] Add provider config for the `gemini` binary.
- [ ] Resolve original `gemini` path before installing.
- [ ] Add PATH guidance, uninstall, and repair behavior.
- [ ] Keep Gemini setup independent from Codex and Claude shims.

Verification:

- [ ] Fake PATH tests cover healthy, missing, broken metadata, PATH precedence failure, repair, and uninstall.

### Post-MVP Phase 38: Add Gemini Runtime Capture

- [ ] Forward Gemini CLI sessions without changing behavior.
- [ ] Capture bounded stdout/stderr only after redaction.
- [ ] Add health reporting for missing or broken Gemini runtime.
- [ ] Keep Gemini failures isolated from other providers.

Verification:

- [ ] Fake `gemini` tests cover healthy, missing, broken, large output, stderr redaction, and companion-down behavior.

### Post-MVP Phase 39: Add Gemini Output Parser

- [ ] Parse Gemini output in a Gemini-specific module.
- [ ] Support patch-like and structured output only when stable.
- [ ] Fall back to metadata-only attribution when parsing fails.
- [ ] Do not let parser errors affect CLI exit behavior.

Verification:

- [ ] Parser tests cover exact match, edited match, malformed output, no match, and parser exception.

### Post-MVP Phase 40: Add Multi-Adapter Isolation And Status

- [ ] Show adapter status per provider.
- [ ] Keep provider failures isolated.
- [ ] Prevent one provider's queue, parser, or shim state from blocking another provider.
- [ ] Add adapter-level error events without raw prompt/code.

Verification:

- [ ] Tests prove Codex still collects when Claude or Gemini adapter fails.

### Post-MVP Phase 41: Add Retention Policy Settings Model

- [ ] Add settings for raw evidence retention period.
- [ ] Support disabled automatic cleanup, default cleanup, and immediate purge.
- [ ] Store settings under local owner/workspace.
- [ ] Keep manual raw purge behavior unchanged.

Verification:

- [ ] Backend tests cover default value, update, disabled cleanup, and invalid values.

### Post-MVP Phase 42: Add Retention Settings UI

- [ ] Show current raw evidence retention policy.
- [ ] Let the user change retention period.
- [ ] Let the user trigger purge-now.
- [ ] Explain whether metadata, generated cards, and practice progress remain.

Verification:

- [ ] Frontend test or manual checklist covers update, disabled cleanup, purge-now, and confirmation copy.

### Post-MVP Phase 43: Add Retention Dry Run

- [ ] Add dry-run endpoint for automatic cleanup.
- [ ] Return counts, artifact categories, and estimated reclaimed bytes.
- [ ] Do not return raw content, prompts, diffs, absolute paths, or secret fingerprints.
- [ ] Include quarantined evidence behavior in the report.

Verification:

- [ ] Backend tests prove dry-run reports counts and bytes without raw content.

### Post-MVP Phase 44: Add Bounded Retention Purge Job

- [ ] Select expired raw artifacts in bounded batches.
- [ ] Null raw content while preserving hashes, metadata, lineage, generated cards, and progress.
- [ ] Delete filesystem artifacts separately from database metadata.
- [ ] Avoid running during active ingestion unless explicitly purging.

Verification:

- [ ] Backend tests cover expired raw content, metadata preservation, generated-card survival, and active-ingestion skip.

### Post-MVP Phase 45: Add Retention Resume And Progress

- [ ] Store cleanup progress metadata.
- [ ] Make cleanup idempotent and resumable.
- [ ] Report last cleanup time, deleted artifact count, and reclaimed bytes.
- [ ] Treat already-purged artifacts as no-op.

Verification:

- [ ] Tests cover interrupted cleanup, rerun safety, and progress reporting.

### Post-MVP Phase 46: Add Retention Schedule

- [ ] Run cleanup on a documented schedule only when the app is running.
- [ ] Add jitter so cleanup does not always run at startup.
- [ ] Skip scheduled cleanup when companion/backend health is degraded.
- [ ] Keep purge-now available independently of the schedule.

Verification:

- [ ] Scheduler tests cover due, not due, degraded health, disabled cleanup, and purge-now.

### Post-MVP Phase 47: Add Retention Performance Gate

- [ ] Seed 10,000 expired artifacts.
- [ ] Run cleanup in bounded batches.
- [ ] Assert normal evidence reads continue during cleanup.
- [ ] Assert raw content is removed from DB, filesystem artifacts, staging directories, and quarantine payloads.

Verification:

- [ ] Performance test documents runtime, batch size, and reclaimed bytes.

### Post-MVP Phase 48: Write Hosted/Team Re-Evaluation Decision

- [ ] Decide whether hosted/team mode is still a product goal after the personal local loop is validated.
- [ ] Decide whether local data should be exportable, importable, or local-only.
- [ ] Define non-goals before adding any team tables or role surfaces.
- [ ] Keep this phase as a decision checkpoint, not an implementation commitment.

Verification:

- [ ] Decision document states go/no-go, scope, migration assumptions, and explicit non-goals.

### Post-MVP Phase 49: Add Local Export Manifest If Hosted/Team Is Approved

- [ ] Define export manifest format for metadata, hashes, generated assets, and optional raw evidence.
- [ ] Make raw evidence export opt-in.
- [ ] Redact or omit quarantined raw payloads.
- [ ] Include schema version and workspace namespace.

Verification:

- [ ] Export test creates a manifest without raw content by default.

### Post-MVP Phase 50: Add Hosted Import Prototype If Approved

- [ ] Validate exported metadata into a separate hosted/team import path.
- [ ] Detect id collisions using workspace namespace and dedupe keys.
- [ ] Import generated cards and practice progress without requiring raw evidence.
- [ ] Reject imports containing unresolved quarantined raw payloads.

Verification:

- [ ] Prototype import test covers metadata-only import, duplicate import, and rejected quarantined payload.

### Post-MVP Phase 51: Reintroduce Team Roles Only Behind Separate Mode

- [ ] Add team roles only if the hosted/team decision approves it.
- [ ] Keep team surfaces outside the local personal primary UI.
- [ ] Do not alter local owner authorization semantics.
- [ ] Add migration and regression tests before exposing team UI.

Verification:

- [ ] Local personal E2E still has no admin/reviewer role switching.
- [ ] Team-mode tests are isolated from local-mode tests.

## Acceptance Criteria

### Functional Requirements

- [ ] The MVP runs as a single-user local installed app.
- [ ] The primary UI does not expose admin/reviewer role switching.
- [ ] First run can complete with no repos and empty collection state.
- [ ] User can approve, revoke, ignore, and see missing repositories.
- [ ] User can ingest or collect one local AI session from an approved repo.
- [ ] Codex CLI shim can create AI-assisted evidence without changing CLI behavior.
- [ ] User can correct attribution, generate a pattern, delete evidence, purge raw evidence, and practice locally.
- [ ] Existing manual/source-link generation remains available as a fallback.

### Security And Privacy Requirements

- [ ] Localhost endpoints reject bad Origin, bad Host, missing token, oversized requests, unauthenticated control actions, and non-loopback access.
- [ ] Raw local evidence is not written to audit logs, application logs, frontend state, browser storage, test snapshots, or broad list responses.
- [ ] Secret scanning covers prompts, AI responses, before/after files, diffs, stdout, stderr, and sensitive paths/filenames.
- [ ] `.env`, keys, certificates, binary files, dependency folders, and build output are ignored by default.
- [ ] Raw evidence is never durably persisted before repo approval, path checks, ignore checks, size checks, and secret checks pass, except short-lived staging deleted on failure.
- [ ] Secret findings quarantine evidence and block generation.
- [ ] Purge removes raw content from DB, filesystem artifacts, staging directories, collector cache, quarantine payloads, and API-visible excerpts.
- [ ] CLI shims never capture environment variables, shell history, credential files, provider auth caches, or terminal scrollback.
- [ ] Git remote URLs and paths are sanitized before storage or logging.
- [ ] Raw evidence encryption is documented as optional/future, not an MVP requirement.

### Data Integrity Requirements

- [ ] Deleting evidence excludes it from generation queries.
- [ ] Raw purge preserves metadata, hashes, lineage, generated cards, and progress.
- [ ] Full app-data delete has distinct destructive behavior and confirmation.
- [ ] Attribution overrides are append-only and reversible.
- [ ] Concurrent duplicate uploads create only one durable bundle.
- [ ] Flyway constraint changes are made only through new migrations.

### Performance Requirements

- [ ] CLI shim latency budget is measured for healthy, slow, and companion-down paths.
- [ ] Evidence list remains responsive with 1,000 local session bundles.
- [ ] Local session payload size, file count, and diff limits are enforced.
- [ ] Post-MVP watcher work includes explicit Git command-count and queue-growth tests before release.
- [ ] Post-MVP retention cleanup deletes expired raw artifacts in bounded batches.

### Quality Gates

- [ ] Backend tests cover local owner access, evidence ingestion, secret quarantine, attribution override, deletion, purge, idempotency, and generation gating.
- [ ] Node tests cover companion status/consent endpoints and Codex shim behavior.
- [ ] Frontend build/typecheck passes.
- [ ] Installed-app E2E covers the personal learning loop.

## Success Metrics

- First-run path reaches local learning library without admin/reviewer setup.
- One approved repo can produce one curated local AI session.
- Codex CLI collection is proven in automated tests.
- No raw prompt/code appears in audit logs, broad list views, browser storage, or diagnostics.
- User can generate a learning card and practice item from curated local evidence.
- User can delete evidence and purge raw evidence with verified outcomes.

## Risks And Mitigations

- Overcollecting private files: approve repos only, ignore sensitive paths, enforce size limits, scan before storage/generation, and provide delete/purge controls.
- Localhost attack surface: loopback bind, local token, Host/Origin validation, strict CORS, no raw data in status endpoints, and rate/body limits.
- Misattribution: start with simple attribution, show confidence/reasons, and allow user correction.
- Broken shim: fail open to original CLI, preserve behavior, and provide uninstall/repair.
- Secret capture: scan before durable storage or generation, quarantine findings, and keep only safe fingerprints.
- Duplicate bundles: use database-backed idempotency, not only app-level hash lookup.
- Flyway constraint drift: change check constraints only through new migrations and test existing rows.
- Future team migration: keep cheap internal workspace IDs, but do not expose team concepts or add new team tables in the MVP.

## References

- Brainstorm: `docs/brainstorms/2026-05-19-local-ai-code-auto-collection-brainstorm.md`
- Existing companion: `scripts/local-ai-companion.mjs`
- Existing Git collector: `scripts/git-collector.mjs`
- Evidence API: `backend/src/main/kotlin/com/aicodelearning/evidence/EvidenceController.kt`
- Evidence service: `backend/src/main/kotlin/com/aicodelearning/evidence/EvidenceService.kt`
- Evidence schema: `backend/src/main/resources/db/migration/V3__provider_audit_evidence_schema.sql`
- Generation service: `backend/src/main/kotlin/com/aicodelearning/learning/GenerationService.kt`
- Privacy learning: `docs/solutions/2026-05-18-ai-pattern-e2e-performance-security.md`
