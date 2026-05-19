---
status: ready
priority: p1
issue_id: "003"
tags: [local-app, evidence, ai-code-collection, workflows-work]
dependencies: []
---

# Local AI Code Collection MVP

## Problem Statement

LearnLoop is being redirected from a team/admin/reviewer product shape into a single-user local installed learning tool. The MVP must prove the loop from local owner setup through approved local AI session evidence, curation, generation, and practice.

## Findings

- The work plan is defined in `docs/plans/2026-05-19-feat-local-ai-code-auto-collection-plan.md`.
- Existing code already contains local companion and Git collector concepts, but the product language and workflow still expose organization/admin/reviewer concepts.
- The implementation must preserve user changes in the main worktree and proceed in this isolated branch.

## Proposed Solutions

### Option 1: Execute MVP Phases In Order

**Approach:** Implement Phases 1-13 from the plan sequentially, committing and reviewing each phase.

**Pros:**
- Matches the approved work plan.
- Keeps changes reviewable.
- Reduces risk from the larger Post-MVP scope.

**Cons:**
- Requires multiple commits and repeated verification.

**Effort:** Large

**Risk:** Medium

## Recommended Action

Execute the MVP phases in order. Start with Phase 1 documentation boundary, then proceed to local owner mode only after the documentation commit, verification, and review are complete.

## Technical Details

Primary plan:
- `docs/plans/2026-05-19-feat-local-ai-code-auto-collection-plan.md`

Initial Phase 1 files:
- `README.md`
- `README.ko.md`
- `packaging/release-bundle/README.md`

## Resources

- Brainstorm: `docs/brainstorms/2026-05-19-local-ai-code-auto-collection-brainstorm.md`
- Plan: `docs/plans/2026-05-19-feat-local-ai-code-auto-collection-plan.md`

## Acceptance Criteria

- [x] Phase 1 documentation boundary is implemented and checked off in the plan.
- [x] Phase 1 verification command passes or deviations are documented.
- [x] Phase 1 changes are committed.
- [x] Phase 1 review is complete with no unresolved findings.
- [ ] Remaining MVP phases stay tracked in the plan.

## Work Log

### 2026-05-19 - Work Start

**By:** Codex

**Actions:**
- Created isolated branch/worktree `codex/local-ai-code-collection`.
- Created this ready todo to track execution of the local AI code collection MVP plan.

**Learnings:**
- The main worktree contains unrelated dirty changes, so implementation must stay isolated in this worktree.

### 2026-05-19 - Phase 1 Documentation Boundary

**By:** Codex

**Actions:**
- Updated `README.md`, `README.ko.md`, and `packaging/release-bundle/README.md` to describe the local personal app path.
- Added MVP non-goals for hosted deployment, admin dashboards, reviewer queues, team permissions, and remote collector sync.
- Checked off Phase 1 in the plan document.
- Verified the required `rg` command leaves only future/non-goal references.

**Learnings:**
- The plan's Phase 1 can be completed as documentation-only work without introducing a product-mode abstraction.

### 2026-05-19 - Phase 1 Review Fixes

**By:** Codex

**Actions:**
- Updated README image alt text to remove stale signup language.
- Clarified the MVP collection path as Codex CLI first, with Gemini and Claude adapters deferred.
- Marked Phase 1 commit and review checklist items complete.

**Learnings:**
- Documentation must avoid implying multi-adapter MVP parity while the plan intentionally keeps the first automatic path narrow.

### 2026-05-19 - Phase 2 Local Owner Mode

**By:** Codex

**Actions:**
- Changed local/install seed defaults to create `u-local-owner` and not seed demo role users unless `app.seed-demo-roles=true`.
- Preserved legacy role-heavy integration tests by enabling demo role seeding only in that test context.
- Added `LocalOwnerModeIntegrationTest` for the default local owner seed and login path.
- Updated frontend visible copy from organization/review language to local owner/curation language.
- Checked off Phase 2 in the plan document.
- Ran targeted backend and frontend verification.
- Addressed Phase 2 review findings by disabling default local self-registration, separating local-owner seed from demo-role mode, binding local profile to loopback by default, and updating installed E2E to log in as the seeded owner.

**Learnings:**
- Current authorization still depends on existing membership roles, so the local owner keeps an internal admin membership until a later authorization cleanup collapses local owner checks.
- Existing demo-role integration coverage can stay intact as an explicit compatibility mode while installed/local defaults move to one owner.

### 2026-05-19 - Phase 3 Audit Metadata Boundary

**By:** Codex

**Actions:**
- Changed audit metadata handling to allowlisted keys with centralized key/value sanitization in `AuditService`.
- Removed `rawContent` and free-form titles from manual evidence audit metadata.
- Added an integration test that ingests sentinel text and a fake secret across prompt, response, diff, path, stdout, and stderr surfaces, then verifies `/api/audit` does not expose them.
- Checked off Phase 3 in the plan document.
- Ran targeted backend audit verification.

**Learnings:**
- Redacting sensitive values is not enough for local evidence: audit metadata should avoid storing sensitive field names like `rawContent` at all.

### 2026-05-19 - Phase 4 Evidence Delete And Raw Purge

**By:** Codex

**Actions:**
- Added tombstone columns for `source_bundles` and raw purge columns for `evidence_items`.
- Added `DELETE /api/evidence/{bundleId}` plus raw purge endpoints for one bundle, one repository, or all active evidence in an organization.
- Excluded deleted bundles from evidence reads, source-link decisions, generation, and conversion trace bundle lookups.
- Added integration tests for soft delete, generation exclusion, single-bundle raw purge, generated-card readability after purge, and repository-scoped raw purge.
- Added full app-data delete endpoint with owner-only access, explicit confirmation, FK-ordered database deletion, and post-delete session invalidation.
- Added sentinel purge verification across current database text columns, audit metadata, and evidence API responses.
- Checked off Phase 4 in the plan document, noting that durable filesystem artifact, staging, collector cache, and quarantine stores are not implemented yet in this phase.

**Learnings:**
- Raw purge can preserve generated learning assets because generated cards read their own persisted pattern/problem data, not evidence item `contentText`.
- Hash dedupe must ignore raw-purged bundles so recollecting the same evidence creates a fresh readable bundle.

### 2026-05-19 - Phase 4 Review Fixes

**By:** Codex

**Actions:**
- Added a shared local-owner access check for destructive evidence and app-data delete actions.
- Made raw purge idempotent by updating and counting only rows that still contain raw content or lack purge metadata.
- Stopped persisting caller-supplied raw purge reason text; purge reason fields now use fixed local-owner reason codes.
- Moved destructive evidence success coverage into default local-owner mode and kept demo-role users/admins forbidden.
- Clarified the plan so current full app-data delete claims only the backend database wipe implemented in this phase.

**Learnings:**
- In demo compatibility mode, an internal admin role must not imply the configured local owner identity.
- Repeated purge calls should be operationally harmless and should not refresh timestamps or replace the original purge reason.

### 2026-05-19 - Phase 4 Second Review Fixes

**By:** Codex

**Actions:**
- Included tombstoned bundles in repository and all-evidence raw purge scopes so delete-then-purge still removes raw content.
- Removed the unused caller-supplied raw purge reason from the API surface.
- Cleared user-supplied file path and provenance metadata during raw purge.
- Changed full app-data delete verification to compare the delete response against current public application tables instead of duplicating the service table list in the test.
- Clarified that current backend delete-all does not clear existing browser storage.

**Learnings:**
- Soft delete and raw purge are separate user actions, so bulk purge must include already tombstoned evidence.
- Full-delete tests should fail when a future migration adds an app table that the service does not delete.
