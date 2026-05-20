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

### 2026-05-19 - Phase 5 Local Session Evidence Schema

**By:** Codex

**Actions:**
- Added `V11__local_session_evidence_schema.sql` for `local_ai_session`, local-session artifact item types, attribution columns, attribution events, and generated-asset lineage JSON fields.
- Extended evidence and generation entities/responses to expose attribution, artifact metadata, and source bundle/evidence item lineage.
- Added a local AI session request DTO contract with prompt, AI response, before/after file, diff, and tool event artifacts.
- Added a migration test that applies migrations through V10, inserts old evidence rows, migrates to V11, and verifies defaults plus new constraints.
- Added a DTO serialization test for the full local session payload.
- Checked off Phase 5 in the plan document.

**Learnings:**
- The Phase 4 full-delete test now forces new application tables to be added to `LocalDataService` delete order.
- Running Flyway to a target version is a practical way to prove compatibility with rows that existed before a new migration.

### 2026-05-19 - Phase 5 Review Fixes

**By:** Codex

**Actions:**
- Changed the `evidence_items.item_type` constraint to `NOT VALID` so V10-valid legacy rows with custom item types do not block migration, while new rows still follow the contract.
- Added a V10 `generation_runs` row to the migration test and verified new lineage columns default to `[]`.
- Removed `local_ai_session` from the manual ingest allowlist so Phase 7 remains responsible for structured local-session ingestion.
- Aligned artifact item type names with the plan contract: `file_before` and `file_after`.
- Added repo identity, display label, tool provider, event/timestamp bucket, idempotency key, and limit reason fields to the local-session DTO contract.
- Added bounded content response coverage for local-session evidence excerpts.

**Learnings:**
- Schema contract migrations should not retroactively reject historical rows that were valid under earlier migrations.
- Contract DTO tests need to mirror the plan terminology exactly because future collectors will use that shape as their integration target.

### 2026-05-19 - Phase 5 Final Review Fix

**By:** Codex

**Actions:**
- Removed the database-level `evidence_items.item_type` constraint so legacy custom item rows remain updateable for raw purge.
- Added migration-test coverage that updates a legacy custom item after V11 migration.
- Clarified the plan that item-type enforcement stays in the DTO/service contract layer.

**Learnings:**
- A `NOT VALID` PostgreSQL check still applies to updated legacy rows, so it can break retention workflows even if migration itself succeeds.

### 2026-05-19 - Phase 6 Local Session Artifact Preflight

**By:** Codex

**Actions:**
- Added `LocalSessionArtifactPreflight` to validate local-session artifact paths, approved repo-root containment, symlink escapes, ignore rules, size limits, and secret findings before durable storage.
- Normalized artifact paths to repo-relative NFC paths and rejected absolute, traversal, null-byte, home, Windows separator, and URL-encoded traversal paths.
- Added ignore handling for hidden paths, `.env*`, key/cert files, dependency/build directories, binary/media/archive artifacts.
- Enforced 200KB text artifact, 1MB diff, 100 artifact, and 5MB session limits in preflight.
- Added tests for valid paths, Unicode normalization, symlink escape, hidden/sensitive ignored paths, invalid path forms, oversized artifacts, oversized sessions, too many files, and secret quarantine.
- Checked off Phase 6 in the plan document.

**Learnings:**
- This phase should remain preflight-only; endpoint persistence and idempotency stay in Phase 7.
- The preflight result can preserve hash/path/metadata while dropping raw content for oversized or secret-bearing artifacts.

### 2026-05-19 - Phase 6 Review Fixes

**By:** Codex

**Actions:**
- Added secret scanning over normalized artifact paths and allowlisted metadata values.
- Added metadata allowlisting, absolute-path filtering, and a 16KB metadata byte budget.
- Enforced the 1MB diff limit across the whole session, not only per diff artifact.
- Moved symlink containment before ignore classification so ignored escaped paths are rejected.
- Clarified the plan wording to describe backend preflight before durable storage/generation, not before local file reads.

**Learnings:**
- Safe metadata still needs the same raw-evidence treatment as content because tool events can carry paths, stdout/stderr-like values, or credentials.

### 2026-05-19 - Phase 6 Final Review Fixes

**By:** Codex

**Actions:**
- Scanned ignored artifact paths before returning ignored artifact metadata.
- Required `contentHash` to be a SHA-256 hex digest before copying it into preflight results.
- Dropped untrusted `tool_event.content` from safe preflight artifacts.
- Normalized `limitReason` to server-owned values only.
- Strengthened metadata filtering against embedded absolute paths and UNC paths.
- Expanded embedded path filtering to cover colon-delimited paths such as `cwd:/Users/...`.
- Removed secret-bearing artifact paths, metadata values, and content from safe preflight results.
- Applied ignore rules to contained symlink targets, not only the submitted lexical path.
- Split the 100-file limit from the total artifact envelope so prompt/response/tool events do not consume file capacity.
- Changed metadata secret scanning to inspect full normalized values before truncating clean result metadata.
- Broadened metadata absolute-path filtering to catch comma/semicolon-delimited local paths.
- Validated `sizeBytes` range and avoided byte-counter overflow during session and diff limit checks.
- Replaced caller-supplied content hashes in preflight output with server-computed or server-derived safe digests.
- Removed free-form `limitReason` from preserved metadata; only the top-level normalized limit reason remains.
- Dropped content text that contains absolute local paths before generation eligibility.
- Expanded assigned-secret detection for prefixed variable names such as `AWS_SECRET_ACCESS_KEY`, `SLACK_BOT_TOKEN`, and `ANTHROPIC_API_KEY`.
- Normalized preserved metadata by key to tight slug, numeric, or boolean formats instead of copying allowlisted free-form strings.
- Applied the same trimmed metadata key normalization to secret finding correlation and safe value preservation.
- Counted all raw metadata keys and values toward per-artifact metadata and session byte limits before scanning or filtering.
- Rejected repo-relative paths on non-file artifacts so prompt/response/tool events cannot bypass the 100-file cap.

**Learnings:**
- Ignored artifacts still produce metadata, so ignored paths need the same path containment and secret-scan treatment as accepted artifacts.
- Even scalar fields like hash and reason need contract validation because they can otherwise become unexpected raw-data carriers.
- In-repo symlinks can hide ignored target names, so containment checks and ignore checks must use both submitted and resolved paths.
- Truncation is a storage/display boundary, not a security boundary; scanning must happen first.
- Byte counters must reject untrusted sizes before arithmetic, otherwise overflow can bypass hard caps.
- Client-provided hashes are still user input; preflight output should use server-owned digest values.
- Secret variable names often have provider prefixes, so scanner rules need to match tokenized key names rather than exact bare words only.
- Allowlisted metadata keys still need per-key value contracts; key allowlisting alone does not prevent raw output smuggling.
- Metadata key normalization must be shared by scanning and preservation; otherwise whitespace variants can split the decision.
- Size limits must apply to raw request fields, not only the fields selected for persistence.

### 2026-05-20 - Phase 7 Local Session Ingestion

**By:** Codex

**Actions:**
- Added `POST /api/ingest/local-ai-session` for local-owner structured session ingestion.
- Added `source_bundles.dedupe_key`, local-session status values, and a partial unique index for active bundle idempotency.
- Persisted one source bundle with multiple local-session evidence items after Phase 6 preflight.
- Computed server-owned artifact hashes and bundle dedupe keys; sequential and concurrent duplicates reuse one durable bundle.
- Stored only safe local repository references, safe provenance hashes, normalized metadata, and bounded item fields.
- Added integration tests for multi-artifact ingest, sequential duplicate reuse, concurrent duplicate reuse, and secret quarantine.
- Cleared local-session dedupe keys during raw purge so recollection creates a fresh readable bundle.
- Canonicalized artifact descriptors in the dedupe key so upload order does not affect duplicate detection.
- Included preflight security state, ignored artifacts, and secret finding fingerprints in the dedupe key so secret-tainted retries remain quarantined.
- Added duplicate conflict recovery so a database unique-index race can re-query and return the existing bundle.
- Scanned ignored artifact content and metadata before deciding generation eligibility.
- Removed artifact index from secret-finding dedupe identity and added stable response ordering for local-session items.
- Secret-scanned request metadata tokens before persisting tool provider, timestamp bucket, or attribution reasons.
- Checked off Phase 7 in the plan document.

**Learnings:**
- The service must not store the `file://` repository URL it uses for local root validation because that can expose an absolute local path.
- Transaction-level idempotency needs the lock to cover the commit boundary, so the local lock wraps a `TransactionTemplate` transaction.
- Local-session status values require a new constraint migration before the endpoint can persist quarantined or generation-eligible bundles.
- Local-session dedupe must follow raw purge semantics; otherwise recollection can keep pointing at a raw-purged bundle.
- Idempotency keys must include safety state, not only accepted artifacts, because ignored artifacts can still carry secret findings.
- Ignored artifacts do not persist raw content, but their submitted content still affects quarantine decisions.
- Request-level metadata can carry secrets too; token-shaped fields still need scanner checks before persistence.

### 2026-05-20 - Phase 8 Attribution Override

**By:** Codex

**Actions:**
- Added `PATCH /api/evidence/{bundleId}/attribution` for local-owner curation.
- Implemented user attribution values `use_for_generation`, `manual`, and `delete`.
- Preserved `autoAttribution` separately while writing each user override to `source_bundle_attribution_events`.
- Changed generation eligibility through user curation without mutating the collected evidence status.
- Blocked `use_for_generation` for quarantined or otherwise unsafe evidence.
- Added audit metadata for attribution overrides using only bundle id, attribution values, confidence, safe reason codes, and status.
- Added integration tests for eligibility changes, repeated override history, audit-safe metadata, and quarantined evidence gating.
- Checked off Phase 8 in the plan document.

**Learnings:**
- User-entered attribution reasons should be treated as reason codes, not free-form notes, because audit metadata must never become a raw evidence side channel.
- Automatic attribution and user curation need separate storage semantics: the user can override generation eligibility without mutating the original detection result.

### 2026-05-20 - Phase 8 Review Fixes

**By:** Codex

**Actions:**
- Restricted attribution override to `local_ai_session` bundles only.
- Kept automatic attribution confidence and reason fields immutable on the bundle; user override confidence and reason codes now live in attribution events and audit metadata.
- Added a shared local-session generation curation predicate.
- Blocked local-session source-link suggestion, source-link confirmation, and generation unless local evidence is marked `use_for_generation`.
- Made `delete` attribution delegate to the existing soft-delete semantics.
- Filtered local-session generation input to prompt/AI response/before/after/diff artifacts so `tool_event` metadata can remain raw-free.
- Serialized attribution override and evidence delete mutations with a row lock so stale curation updates cannot commit after a delete.
- Made generation participate in the same source-bundle row lock before checking local-session curation.
- Made source-link suggest and confirm participate in the same source-bundle row lock before checking curation.
- Made raw purge participate in the same source-bundle row lock before clearing evidence item content.
- Moved sorted source-bundle row locking to a shared repository helper.
- Renamed the lock helper to make its existing-row contract explicit.
- Applied stable local-session item ordering to evidence detail reads.
- Renamed the generation curation predicate to describe the full pass-through gate policy.
- Split required locked bundle loading from existing-row purge locking in generation and source-link flows.
- Moved stable local-session item ordering to a shared evidence helper.
- Centralized local-session source kind, generation status, curation values, generation item types, and item ordering in `LocalAiSessionPolicy`.
- Moved local-session item-type predicates such as diff/tool-event/content-required into `LocalAiSessionPolicy`.
- Sorted local-session generation artifacts by type, path, and id before building the generation prompt.
- Preserved requested source-link order when building multi-link generation input.
- Updated tests to verify non-local override rejection, source-link/generation gating, append-only event history without timestamp ordering assumptions, soft-delete behavior, tool-event filtering, and preserved automatic metadata.

**Learnings:**
- Changing a curation field is not enough unless every generation entrypoint uses the same effective eligibility predicate.
- `delete` needs to behave like the destructive evidence action; otherwise API naming would mislead the UI and user.
- Local-session `tool_event` artifacts are useful lineage metadata, but they must not be treated as required raw generation content.
- The preflight eligibility policy and generation input policy should share one definition so they do not drift.

### 2026-05-20 - Phase 9 Direct Local Evidence Generation

**By:** Codex

**Actions:**
- Added `sourceBundleIds` to generation requests so curated local AI session bundles can generate directly without source links.
- Kept source-link and direct source-bundle generation as mutually exclusive request shapes.
- Required direct generation bundles to be local sessions in the requested organization, non-deleted, marked `use_for_generation`, and backed by unpurged generation artifacts.
- Stored direct generation lineage in `sourceBundleIdsJson` and `evidenceItemIdsJson` while leaving `sourceLinkIdsJson` empty.
- Added integration tests for usable, duplicate, unknown, manual, deleted, raw-purged, quarantined, and non-local evidence.
- Added coverage that direct generation does not create source links and that raw purge after generation leaves the generated card readable.
- Limited direct generation to one distinct local-session bundle to avoid cross-scope evidence mixing.
- Split direct-generation rejection tests so failures identify the specific gate that regressed.
- Checked off Phase 9 in the plan document.

**Learnings:**
- Direct local generation should reject mixed `sourceLinkIds` and `sourceBundleIds`; otherwise lineage semantics become ambiguous.
- The generation service can share the persistence flow once source resolution is separated from run/card creation.

### 2026-05-20 - Phase 10 Collection And Curation UI

**By:** Codex

**Actions:**
- Added a paginated `GET /api/evidence` summary endpoint that excludes raw content and raw-ish metadata from broad list responses.
- Added frontend evidence API helpers for list/detail, attribution override, delete, raw purge, and direct generation from a curated bundle.
- Added the Collected Evidence panel with lazy-loaded bounded excerpts, curation actions, generation trigger, delete, raw purge, and pagination controls.
- Added Collection Status settings for approved, revoked, ignored, and missing local repositories with browser-local consent state only.
- Added integration coverage for raw-free list summaries and a 1,000-bundle list page-size cap.
- Verified backend tests, frontend typecheck/build, diff whitespace, and a mock browser flow for repository approval plus local-session evidence curation actions.
- Checked off Phase 10 in the plan document.

**Learnings:**
- Evidence lists should stay summary-only; raw excerpts belong behind explicit detail selection.
- The browser can keep local repository consent labels, but collected file content must stay out of broad responses and browser storage.

### 2026-05-20 - Phase 10 Review Fixes

**By:** Codex

**Actions:**
- Restricted evidence list pagination to bundles visible through the current user's team/project scope.
- Added stable evidence list ordering and database indexes for active bundle pagination.
- Added backend repository consent storage and required approved consent before local-session ingestion.
- Changed evidence detail responses to safe bundle summaries with bounded excerpts for all source kinds.
- Cleared evidence UI state on logout, ignored stale detail responses, and added confirmation for destructive UI actions.
- Removed raw repository labels from browser storage; repository approval state now round-trips through the backend consent API.

**Learnings:**
- A local personal app still needs API-side consent and scope checks because stale browser state and compatibility users can otherwise bypass UI-only controls.

### 2026-05-20 - Phase 11 Codex CLI Shim

**By:** Codex

**Actions:**
- Added `scripts/local-ai-shim.mjs` and `scripts/local-ai-shim.sh` with Codex shim install, uninstall, repair, status, and runtime forwarding commands.
- Kept shim files in a LearnLoop-managed directory, recorded original `codex` path/hash metadata, detected recursive shim candidates, and reported PATH precedence or original-path drift.
- Forwarded args, stdin, stdout, stderr, cwd, signals, and exit code to the original Codex binary while emitting bounded best-effort shim events.
- Omitted env vars and local shell/config/cache data from shim events; stdout and stderr content are suppressed by default.
- Added Node tests for fake PATH handling, fake Codex passthrough, large output, signal forwarding, companion-down behavior, event redaction, and latency budgets.
- Added release-bundle wrappers and status/docs references for the Codex shim manager.
- Replaced stale installed-app demo-role command output with local-owner credential guidance.
- Checked off Phase 11 in the plan document.

**Learnings:**
- The shim should use stored original-binary metadata for runtime behavior, while status/repair owns warnings about PATH drift.
- End events need a very short flush window; otherwise fast Codex invocations can exit before the companion receives the final bounded event.

### 2026-05-20 - Phase 11 Review Fixes

**By:** Codex

**Actions:**
- Restricted shim event delivery to loopback companion URLs only.
- Added the real `/shim/events` companion receiver and status endpoint with sanitized metadata-only storage.
- Prevented shim install, repair, and uninstall from following symlinks or replacing non-LearnLoop files.
- Preserved interactive TTY behavior by inheriting provider stdout/stderr for TTY sessions and capturing only non-interactive output byte counts.
- Suppressed stdout and stderr content in shim events instead of attempting partial redaction.
- Generalized signal exit-code preservation and added tests for unmanaged directories, symlink targets, non-marker uninstall, real HTTP receiver, loopback-only delivery, and TTY detection.

**Learnings:**
- CLI shims should not attempt to classify provider output in-process; byte counts and timing are safer until backend preflight owns content inspection.
- A local-only endpoint still needs loopback URL validation because inherited environment variables can otherwise redirect event delivery.

### 2026-05-20 - Phase 12 Companion Security Boundary

**By:** Codex

**Actions:**
- Added loopback-only companion `/status`, `/consent/status`, `/consent/revoke`, and `/consent/purge-raw` endpoints.
- Added per-install local API token creation under `~/.learnloop/local-api-token` by default with owner-only permissions.
- Required the local token for mutating companion endpoints, including shim events and consent controls.
- Added Host, Origin, loopback remote-address, body-size, and rate-limit checks.
- Updated source and release companion wrappers to reject non-loopback bind hosts, print structured status, and wait briefly after stop.
- Verified source and release-bundle companion start/status/stop with a dedicated token file.
- Checked off completed Phase 12 plan items except installed-app E2E, which is blocked until Docker access is available.

**Learnings:**
- Test token files should live under a dedicated temp directory; using `/private/tmp` directly would make the companion try to tighten permissions on a system directory.
- The release wrapper works with `NODE_BIN` override, which is necessary on machines where the default `node` binary is broken or unavailable.

### 2026-05-20 - Phase 12 Review Fixes

**By:** Codex

**Actions:**
- Required the local API token on `POST /oauth/start` and updated the frontend OAuth flow to read the local companion token before starting OAuth.
- Added a loopback-origin-only `/auth/token` helper so the installed web app can pass the token without asking the user to copy it.
- Restricted extra companion CORS origins to loopback app origins only.
- Rejected token-file overrides inside the application directory and required override parent directories to be LearnLoop-managed before writing a token.
- Fixed IPv6 loopback URL formatting for `::1`.
- Removed `screen` dependency from source and release companion wrappers so PID files point at the managed process directly.

**Learnings:**
- A token-protected local endpoint still needs a browser-readable token bootstrap path when the UI runs in a separate loopback origin.
- Environment overrides are part of the local attack surface; token path and CORS overrides need the same validation level as HTTP inputs.

### 2026-05-20 - Phase 12 Review Fixes Round 2

**By:** Codex

**Actions:**
- Changed browser token bootstrap to return a one-time, short-lived OAuth-start token instead of the full local API token.
- Removed dev server origins from the default companion allowlist; Vite dev origins now require explicit opt-in.
- Restricted token file overrides to the LearnLoop config directory and rejected config directories under git repositories.
- Updated shim event delivery to honor `LEARNLOOP_LOCAL_AI_HOST=::1` by building a bracketed IPv6 default companion URL.
- Added tests for one-time OAuth-start tokens, repo-contained config rejection, token override containment, and IPv6 shim event delivery.

**Learnings:**
- Browser-facing companion tokens should be scoped to the single browser action they enable, not reused as the companion's general local API token.
- Checking only the app install directory is insufficient for local tools; token storage also has to reject any git ancestor because the user's work repos are separate from the installed app.

### 2026-05-20 - Phase 13 Regression Gate

**By:** Codex

**Actions:**
- Ran backend integration tests, full script tests, frontend typecheck/build, smoke tests, Node companion/shim tests, and installed-app E2E.
- Updated the practice repository integration fixture to use the local owner seed user after removing admin/user role selection from the local profile.
- Fixed the installed-app E2E status wait to target the first matching run-flow state when repeated status text is present.
- Verified sentinel leakage coverage across audit/API/browser surfaces, duplicate ingestion race coverage, and the 1,000-bundle evidence list performance case.
- Completed Phase 13 plan checks and closed the Phase 12 installed-app E2E verification item.

**Learnings:**
- Installed-app E2E should run against a clean Compose project when the default local project has stale migration volumes; the successful Phase 13 run used `learnloop-phase13` on port 18080.
- Direct installed runner checks require the runner Docker overlay, otherwise backend health can pass while local execution endpoints fail.

### 2026-05-20 - Post-MVP Phase 14 Adapter Contract

**By:** Codex

**Actions:**
- Added a common local AI tool adapter event contract module with schema version, tool identity, invocation, cwd, repo root, timestamps, command classification, output excerpt metadata, capability flags, and attribution confidence output.
- Defined the supported capability flags: `process_signal`, `window_signal`, `cli_transcript`, `patch_output`, and `json_stream`.
- Added confidence contribution rules for each capability and a small corroboration bonus when multiple independent capabilities are present.
- Added contract tests that validate one fake event per capability flag and prove parser-owned raw payload fields are excluded from the common contract.
- Checked off Post-MVP Phase 14 in the plan document.

**Learnings:**
- Keeping the common contract metadata-only makes future provider parsers responsible for interpreting patch/json/transcript content without leaking raw provider output into shared adapter status.
- Confidence should be derived from declared capabilities rather than provider names, so future Codex, Claude, and Gemini adapters can share one attribution path.

### 2026-05-20 - Post-MVP Phase 15 Adapter Fixture Harness

**By:** Codex

**Actions:**
- Added deterministic adapter fixtures for process, CLI transcript, patch output, and JSON stream signals.
- Added fixture replay into a local session bundle payload with tool-event metadata and a safe synthetic diff artifact.
- Kept fixture paths under a synthetic fixture root, omitted `repositoryUrl`, and added tests that reject real `/Users` paths and secret-like content in serialized fixture bundles.
- Verified the harness can generate one local session bundle from fake adapter events without Codex, Claude, Gemini, or macOS process APIs.
- Checked off Post-MVP Phase 15 in the plan document.

**Learnings:**
- Fixture replay should produce the same local session ingest shape as later live adapters, while keeping external process and OS discovery dependencies out of the test path.
- Synthetic diffs give the bundle generation-relevant evidence without storing real user code in fixtures.

### 2026-05-20 - Post-MVP Phase 16 Host Process Snapshot Reader

**By:** Codex

**Actions:**
- Added a host process snapshot module that reads bounded macOS `ps` metadata without command-line arguments, environment variables, file contents, or provider cache data.
- Added fake snapshot normalization tests for pids, parent pids, process names, executable paths, frontmost/app-active flags, timeout degradation, max process limits, and home-path redaction.
- Added a token-protected companion `/host/processes` endpoint and covered missing-token rejection in the companion HTTP tests.
- Updated release packaging to include the process snapshot module with the companion runtime.
- Ran a bounded macOS metadata check with `maxProcesses: 5`; it returned process metadata only.
- Checked off Post-MVP Phase 16 in the plan document.

**Learnings:**
- `ps` must run outside the local sandbox for the manual host check, but the module itself degrades safely when the command is denied or times out.
- Using `ps -axo pid=,ppid=,comm=` avoids shell arguments and environment variables, which keeps snapshot status safe for local companion responses.

### 2026-05-20 - Post-MVP Phase 17 Codex App Presence Adapter

**By:** Codex

**Actions:**
- Added a Codex App adapter that reads sanitized process snapshots and reports `running`, `frontmost`, `recently_active`, or `unavailable`.
- Matched Codex App processes by `Codex.app` executable paths or Codex App process names while ignoring lowercase `codex` CLI processes.
- Added a token-protected companion `/adapters/codex-app/status` endpoint and included the adapter module in release packaging.
- Added fake process tests for running, frontmost, recently active, unavailable, injected snapshot, and process-name mismatch cases.
- Checked off Post-MVP Phase 17 in the plan document.

**Learnings:**
- The Codex App adapter should use tool/app identity from sanitized process metadata only; CLI process names are intentionally not enough to identify the GUI app.
- `recently_active` is best modeled as a separate hint layered on top of snapshots, so the process reader does not need to collect window text or app cache data.

### 2026-05-20 - Post-MVP Phase 18 GUI Activity Correlation

**By:** Codex

**Actions:**
- Added GUI activity window correlation for frontmost and recently-active tool signals.
- Matched repo changes only when their repo identity is approved and the change timestamp falls inside the activity window.
- Lowered confidence when multiple AI tools overlap the same repo change.
- Kept correlation output limited to tool/provider ids, repo identity, repo-relative paths, timestamps, confidence, and reason codes.
- Added tests for single tool, multiple tools, no approved repo, stale windows, unsafe paths, and non-activity running status.
- Checked off Post-MVP Phase 18 in the plan document.

**Learnings:**
- `running` is a presence signal, not a GUI activity signal; using it for correlation would over-attribute ordinary repo changes.
- Correlation output should never include repo roots, absolute paths, window text, or raw changed content because Phase 19 can build evidence from safe metadata only.

### 2026-05-20 - Post-MVP Phase 19 GUI Correlation Evidence

**By:** Codex

**Actions:**
- Added a GUI correlation evidence builder that converts matched activity windows into metadata-only `local_ai_session` ingest payloads.
- Added `gui_correlated` automatic attribution and `user_confirmation_required` bundle status with Flyway constraints.
- Allowed safe GUI correlation reason codes and metadata keys while continuing to strip raw snippets, full absolute paths, and unsupported attribution reasons.
- Added backend integration coverage that creates one GUI-correlated evidence bundle, confirms it stores only tool-event metadata, rejects generation override, and keeps direct generation blocked without direct AI output or patch evidence.
- Ran `./scripts/test.sh`, targeted backend evidence/migration tests, and full `./gradlew :backend:test`.
- Checked off Post-MVP Phase 19 in the plan document.

**Learnings:**
- GUI-only evidence should be confirmable as a correlation, but it must not become generation-eligible until a later phase attaches direct AI output or patch data.
- Database check constraints need to evolve alongside Kotlin allowlists; otherwise new safe attribution/status values fail after service validation succeeds.

### 2026-05-20 - Post-MVP Phase 20 GUI Correlation UI States

**By:** Codex

**Actions:**
- Added GUI-correlated labels for collected evidence rows and detail summaries.
- Added lower-confidence state copy for GUI activity correlation, including the competing-tools case.
- Added a confirm action that records human review while keeping generation blocked for metadata-only GUI evidence.
- Kept the existing manual, delete, purge, lazy detail loading, and disabled generation flows visible in the evidence panel.
- Checked off Post-MVP Phase 20 in the plan document.

**Verification:**
- Ran `./scripts/frontend-typecheck.sh`.
- Manual checklist: a GUI-correlated bundle shows `Needs confirmation`, lower-confidence copy, `Confirm`, `Manual`, disabled `Generate`, `Purge raw`, and `Delete`; after confirm it shows `Confirmed` while generation remains blocked.

**Learnings:**
- GUI correlation confirmation should update curation state only; it should not imply generation eligibility while the bundle lacks direct AI output, patch, or diff content.

### 2026-05-20 - Post-MVP Phase 21 Watcher Registry

**By:** Codex

**Actions:**
- Added a local AI watcher registry module that starts one watcher for approved repository roots.
- Stopped active watchers immediately when the repository state changes to revoked, missing, or always ignored.
- Surfaced watcher status through companion endpoints with active, stopped, degraded, and unavailable counts.
- Kept absolute repository roots out of public watcher status responses.
- Included the watcher registry module in release packaging.
- Checked off Post-MVP Phase 21 in the plan document.

**Verification:**
- Ran bundled-Node `tests/local-ai-watcher-registry.test.js`.
- Ran bundled-Node `tests/codex-shim.test.js`.

**Learnings:**
- Watcher registration can be stateful in the companion while repository consent remains the backend source of truth; after companion restart the app can safely re-register approved repos.
- Phase 21 should count file events only as a minimal signal because debounce, backpressure, and Git reconciliation are separate later phases.

### 2026-05-20 - Post-MVP Phase 22 Watcher Event Debounce

**By:** Codex

**Actions:**
- Added per-repository debounce state to the local watcher registry.
- Coalesced repeated file events by normalized repo-relative path.
- Preserved only the latest event timestamp and event type per path before settling a change set.
- Added configurable debounce timing with a default 750ms quiet period and 250ms to 5s clamping.
- Surfaced pending and settled change counts in watcher status without exposing absolute repo roots.
- Checked off Post-MVP Phase 22 in the plan document.

**Verification:**
- Ran bundled-Node `tests/local-ai-watcher-registry.test.js`.
- Ran bundled-Node `tests/codex-shim.test.js`.

**Learnings:**
- Debounce belongs before Git reconciliation so rapid editor saves collapse into one bounded change set before any expensive Git commands run.

### 2026-05-20 - Post-MVP Phase 23 Watcher Backpressure

**By:** Codex

**Actions:**
- Added a bounded pending-change queue per watched repository.
- Marked repositories as degraded and requiring full Git reconciliation when pending file paths exceed the queue limit.
- Added dropped event counters and full-reconciliation recovery semantics.
- Added a cross-repository reconciliation concurrency guard for later snapshot/diff work.
- Added companion environment knobs for max pending changes and reconciliation concurrency.
- Checked off Post-MVP Phase 23 in the plan document.

**Verification:**
- Ran bundled-Node `tests/local-ai-watcher-registry.test.js`.
- Ran bundled-Node `tests/codex-shim.test.js`.

**Learnings:**
- Backpressure should degrade toward one full Git reconciliation instead of attempting to preserve every OS watcher event under bursty saves.

### 2026-05-20 - Post-MVP Phase 24 Bounded Git Reconciliation

**By:** Codex

**Actions:**
- Added a bounded Git reconciliation module for repo metadata, branch, status, changed files, and diff candidates.
- Used `git status --porcelain=v1 -z` for changed-path discovery.
- Added short-lived metadata caching for resolved repo root, branch, and sanitized remote URL.
- Applied timeout and output-size caps to every Git command.
- Wired watcher debounce settlement into one reconciliation call per settled change batch.
- Included the Git reconciliation module in release packaging.
- Checked off Post-MVP Phase 24 in the plan document.

**Verification:**
- Ran bundled-Node `tests/local-ai-git-reconcile.test.js`.
- Ran bundled-Node `tests/local-ai-watcher-registry.test.js`.
- Ran bundled-Node `tests/codex-shim.test.js`.
- Temp repo test verifies 100 file events produce one `git status --porcelain=v1 -z` pass and one final `git diff` pass in the debounce window.

**Learnings:**
- Git reconciliation should run after debounce and backpressure, so watcher bursts collapse before any status or diff command executes.

### 2026-05-20 - Post-MVP Phase 25 Watcher Ignore And Path Safety

**By:** Codex

**Actions:**
- Added watcher-side path safety filtering before diff capture.
- Denied `.env`, key/cert-style files, hidden paths, ignored directories, and binary/archive extensions before `git diff` runs.
- Rejected path traversal and symlink escapes from diff candidates.
- Preserved sanitized remote URL handling in Git reconciliation output.
- Checked off Post-MVP Phase 25 in the plan document.

**Verification:**
- Ran bundled-Node `tests/local-ai-git-reconcile.test.js`.
- Tests cover `.env`, key files, ignored tracked files, binary files, symlink escape, path traversal, and sanitized remote URLs.

**Learnings:**
- Watcher ignore rules need to run at candidate selection time, not only during later file reads, because `git diff` can reveal sensitive tracked file content.

### 2026-05-20 - Post-MVP Phase 26 Before Snapshot Cache

**By:** Codex

**Actions:**
- Added a bounded per-repo before snapshot cache.
- Captured clean-to-dirty and deleted file before snapshots from `HEAD:path`.
- Marked files already dirty during watcher startup as unavailable instead of pretending their current dirty content is a clean before state.
- Added LRU eviction by entry count and byte budget.
- Stored oversized before snapshots as metadata-only entries with hash and size.
- Wired the cache into companion Git reconciliation and release packaging.
- Checked off Post-MVP Phase 26 in the plan document.

**Verification:**
- Ran bundled-Node `tests/local-ai-before-snapshot-cache.test.js`.
- Ran bundled-Node `tests/local-ai-git-reconcile.test.js`, `tests/local-ai-watcher-registry.test.js`, and `tests/codex-shim.test.js`.
- Tests cover clean-to-dirty, pre-existing dirty, cache eviction, oversized file, and deleted file.

**Learnings:**
- Before snapshots should come from Git's clean baseline, not the filesystem at reconciliation time, otherwise pre-existing dirty files can create false before/after pairs.

### 2026-05-20 - Post-MVP Phase 27 After Snapshot And Diff Capture

**By:** Codex

**Actions:**
- Captured after snapshots from the filesystem after the watcher debounce quiet period.
- Limited path-scoped diffs to files that passed ignore and after-snapshot size checks.
- Added diff truncation metadata when output is capped.
- Kept ignored, binary/archive, and oversized files as metadata-only entries.
- Checked off Post-MVP Phase 27 in the plan document.

**Verification:**
- Ran bundled-Node `tests/local-ai-git-reconcile.test.js`, `tests/local-ai-before-snapshot-cache.test.js`, and `tests/local-ai-watcher-registry.test.js`.
- Temp repo rapid-save test proves final after content and diff match the settled file state after 100 rapid edits.
- Tests cover oversized after snapshots and capped diff metadata.

**Learnings:**
- After snapshots should be captured before diff generation so size filtering can prevent large changed files from entering `git diff`.

### 2026-05-20 - Post-MVP Phase 28 Watcher Session Bundler

**By:** Codex

**Actions:**
- Added a watcher session bundler that turns settled reconciliation output into one `local_ai_session` payload.
- Grouped before snapshots, after snapshots, and per-file diffs into one local session window.
- Attached metadata-only tool activity artifacts when Codex, Claude, Gemini, or GUI signals are available.
- Added safe attribution reason codes for `repo_changed`, `gui_activity_window`, `cli_shim`, `patch_match`, `single_ai_tool`, and `competing_ai_tools`.
- Dropped unsafe, ignored, oversized, and secret-like code artifacts before payload creation.
- Updated backend reason-code allowlists so watcher payloads survive local-session preflight.
- Checked off Post-MVP Phase 28 in the plan document.

**Verification:**
- Ran bundled-Node `tests/local-ai-watcher-session-bundler.test.js`.
- Ran `./gradlew :backend:test --tests "com.aicodelearning.evidence.LocalSessionArtifactPreflightTest"`.
- Ran `./scripts/test.sh`.
- Tests cover single-file session, multi-file session, no tool signal, competing tool signals, unsafe/oversized/secret artifact dropping, and no-safe-code rejection.

**Learnings:**
- The watcher bundler should require at least one safe code artifact so tool-only activity does not become a code-learning session.

### 2026-05-20 - Post-MVP Phase 29 Watcher Upload Queue And Retry

**By:** Codex

**Actions:**
- Added a persistent local AI session upload queue with bounded capacity.
- Added backend-down retry scheduling with exponential backoff and a max-attempt discard policy.
- Added repository cancellation so queued sessions can be removed when approval is revoked.
- Added metadata-only queue status output that avoids exposing raw code content.
- Added an uploader helper for posting queued sessions to `/api/ingest/local-ai-session`.
- Checked off Post-MVP Phase 29 in the plan document.

**Verification:**
- Ran bundled-Node `tests/local-ai-session-upload-queue.test.js`.
- Tests cover backend-down queueing, retry after restart, revoke-before-upload, queue-size limit, retry-limit discard, and backend ingest endpoint posting.

**Learnings:**
- The queue needs to keep raw payloads only inside the private retry store, while public status should expose hashes and counts only.

### 2026-05-20 - Post-MVP Phase 30 Watcher Settings And Status UI

**By:** Codex

**Actions:**
- Added companion watcher status fields for collection enabled state and upload queue metadata.
- Added companion watcher settings to disable and re-enable collection without removing CLI shims.
- Cancelled queued uploads when watcher repository approval is revoked.
- Added frontend watcher status cards with active, degraded, disabled, queued, last reconciliation, and upload queue counts.
- Synced repository approval changes to the local companion when available.
- Kept status responses and UI limited to labels, counts, hashes, and timestamps, without raw code or absolute repo roots.
- Checked off Post-MVP Phase 30 in the plan document.

**Verification:**
- Ran bundled-Node `tests/local-ai-watcher-registry.test.js` and `tests/codex-shim.test.js`.
- Ran `./scripts/frontend-typecheck.sh`.
- Ran `./scripts/frontend-build.sh`.
- Manual checklist coverage: active watcher row, degraded count, disabled watcher state, queued upload count, and revoked repository state are represented in the status UI.

**Learnings:**
- Watcher disablement belongs in the registry, not only the UI, because approved repositories should stay approved while filesystem watchers are stopped.

### 2026-05-20 - Post-MVP Phase 31 Watcher Performance Gate

**By:** Codex

**Actions:**
- Added a watcher performance gate script for local and CI execution.
- Simulated 10 approved repositories, 1,000 changed files, and 3 rapid save events per file.
- Asserted pending changes drain to zero after reconciliation.
- Asserted one reconciliation/session per repo settled window.
- Asserted the simulated Git command count stays within the repo-count-based budget.
- Checked off Post-MVP Phase 31 in the plan document.

**Verification:**
- Ran bundled-Node `tests/local-ai-watcher-performance.test.js`.
- Ran bundled-Node `scripts/local-ai-watcher-performance.mjs`.
- The performance summary reported 3,000 events, 10 reconciliations, 10 sessions, 0 pending changes, 0 dropped events, and 20 estimated Git commands.

**Learnings:**
- The watcher performance contract should be expressed as counts and budgets, not elapsed time, so it stays stable on local machines and CI.

### 2026-05-20 - Post-MVP Phase 32 Generic Shim Installer

**By:** Codex

**Actions:**
- Extracted provider-agnostic shim install, uninstall, repair, and status functions.
- Kept the existing Codex wrapper API for current callers.
- Added Gemini CLI and Claude Code provider configs.
- Preserved LearnLoop-managed shim directories and safe overwrite checks.
- Recorded original binary path and SHA-256 hash in provider metadata.
- Added recursive shim-chain detection for generic provider installs.
- Checked off Post-MVP Phase 32 in the plan document.

**Verification:**
- Ran bundled-Node `tests/codex-shim.test.js`.
- Tests cover Codex install, uninstall, repair, status, missing original, changed original, and recursive shim rejection.
- Tests cover generic Gemini and Claude install/status/repair/uninstall plus recursive shim rejection.

**Learnings:**
- Generic shim extraction is safest when the Codex-specific public functions remain wrappers, because existing scripts and tests keep their stable API.

### 2026-05-20 - Post-MVP Phase 33 Generic Shim Runtime

**By:** Codex

**Actions:**
- Extracted provider-agnostic runtime forwarding into `forwardProviderRuntime`.
- Kept `runProviderShim` responsible for original binary resolution and delegated runtime execution.
- Preserved stdout, stderr, stdin, args, cwd, signal forwarding, and exit-code behavior.
- Preserved asynchronous start/end event emission with short timeout fail-open behavior.
- Added generic Gemini runtime coverage for provider-specific event names.
- Checked off Post-MVP Phase 33 in the plan document.

**Verification:**
- Ran bundled-Node `tests/codex-shim.test.js`.
- Existing fake binary tests cover success, failure, signal, stdin, large output, and companion-down behavior.
- New generic runtime test covers args, stdout, stderr, exit code, and `gemini_cli` event provider mapping.

**Learnings:**
- Runtime forwarding should take an already-resolved original path so install/status resolution stays separate from process behavior.

### 2026-05-20 - Post-MVP Phase 34 Claude Code Shim Installer

**By:** Codex

**Actions:**
- Added and verified the `claude` provider config for the generic shim installer.
- Resolved the original `claude` binary before installing the shim.
- Preserved PATH guidance when the shim directory is not first.
- Verified Claude uninstall and repair behavior.
- Verified recursive existing-shim detection for Claude installs.
- Checked off Post-MVP Phase 34 in the plan document.

**Verification:**
- Ran bundled-Node `tests/codex-shim.test.js`.
- Fake PATH tests cover healthy Claude install, missing binary, existing shim recursion, PATH precedence failure, repair, and uninstall.

**Learnings:**
- Provider-specific installer phases can stay small when generic installer behavior is already shared and tested through the provider config.

### 2026-05-20 - Post-MVP Phase 35 Claude Code Runtime Capture

**By:** Codex

**Actions:**
- Added Claude `--print` output capture policy for high-confidence non-interactive sessions.
- Kept interactive Claude sessions on passthrough behavior without stdout/stderr interception.
- Added bounded stdout excerpt storage after redaction only.
- Kept stderr sensitive by default by recording bytes while suppressing stderr excerpts.
- Suppressed low-confidence non-`--print` Claude stdout content.
- Checked off Post-MVP Phase 35 in the plan document.

**Verification:**
- Ran bundled-Node `tests/codex-shim.test.js`.
- Fake Claude tests cover interactive passthrough, `--print`, large output truncation, stderr suppression, secret redaction, and companion-down behavior.

**Learnings:**
- Runtime capture should be provider-mode specific; shared forwarding can stay generic while excerpt policy stays narrow.

### 2026-05-20 - Post-MVP Phase 36 Claude Output Parser

**By:** Codex

**Actions:**
- Added a Claude-specific output parser module.
- Parsed exact git-diff output into diff artifacts with `patch_output` capability.
- Parsed edited `apply_patch` blocks without depending on surrounding assistant prose.
- Accepted stream-json output only when `stream-json` format is explicitly marked.
- Returned metadata-only adapter events when parsing fails or no patch/stream match exists.
- Checked off Post-MVP Phase 36 in the plan document.

**Verification:**
- Ran bundled-Node `tests/local-ai-claude-output-parser.test.js`.
- Parser tests cover exact patch, edited patch, stable json stream, malformed stream, and no-match fallback cases.

**Learnings:**
- Provider output parsing should be fail-open: attribution can degrade, but the original CLI command and event flow must not fail.

### 2026-05-20 - Post-MVP Phase 37 Gemini CLI Shim Installer

**By:** Codex

**Actions:**
- Added Gemini-specific installer verification on top of the generic provider shim implementation.
- Verified original `gemini` path resolution before shim installation.
- Verified PATH guidance, PATH precedence status, repair, and uninstall behavior.
- Verified broken Gemini metadata can be repaired from the current original binary path.
- Verified uninstalling Gemini does not remove Codex or Claude shims in the same managed directory.
- Checked off Post-MVP Phase 37 in the plan document.

**Verification:**
- Ran bundled-Node `tests/codex-shim.test.js`.
- Fake PATH tests cover healthy Gemini install, missing binary, broken metadata, PATH precedence failure, repair, uninstall, and provider independence.

**Learnings:**
- Provider-specific installer phases should test isolation explicitly when multiple shims share one managed directory.

### 2026-05-20 - Post-MVP Phase 38 Gemini Runtime Capture

**By:** Codex

**Actions:**
- Added Gemini stdout/stderr excerpt capture with bounded redaction.
- Preserved Gemini CLI stdout, stderr, exit-code, and companion-down behavior.
- Added Gemini runtime health events for missing originals and broken spawn paths.
- Exposed safe runtime status fields through companion shim event status.
- Verified Gemini runtime failures do not affect Codex shim execution in the same managed directory.
- Checked off Post-MVP Phase 38 in the plan document.

**Verification:**
- Ran bundled-Node `tests/codex-shim.test.js`.
- Fake Gemini tests cover healthy output, missing runtime, broken runtime, large output, stderr redaction, companion-down behavior, and provider isolation.

**Learnings:**
- Runtime health events should carry only coarse status/problem codes so status surfaces stay useful without exposing local paths or output.

### 2026-05-20 - Post-MVP Phase 39 Gemini Output Parser

**By:** Codex

**Actions:**
- Added a Gemini-specific output parser module.
- Parsed exact git-diff output into diff artifacts with `patch_output` capability.
- Parsed edited `apply_patch` blocks from surrounding Gemini prose.
- Accepted structured Gemini JSON only when `json` output format is explicitly marked.
- Returned metadata-only adapter events for malformed, unsupported, no-match, and parser-exception cases.
- Checked off Post-MVP Phase 39 in the plan document.

**Verification:**
- Ran bundled-Node `tests/local-ai-gemini-output-parser.test.js`.
- Parser tests cover exact match, edited match, stable JSON, malformed output, no match, and parser exception.

**Learnings:**
- Gemini parser failures should degrade to adapter metadata so CLI execution and collection health remain independent of parser quality.

### 2026-05-20 - Post-MVP Phase 40 Multi-Adapter Isolation And Status

**By:** Codex

**Actions:**
- Added an adapter status registry with isolated per-provider states.
- Added companion `/adapters/status` for Codex CLI, Codex App, Claude Code, and Gemini CLI.
- Recorded shim health and adapter error events without retaining raw prompt or code excerpts.
- Displayed adapter status inside the local Collection Status panel.
- Kept adapter status refresh separate from watcher status so adapter failures do not block watcher visibility.
- Checked off Post-MVP Phase 40 in the plan document.

**Verification:**
- Ran bundled-Node `tests/local-ai-adapter-status.test.js` and `tests/codex-shim.test.js`.
- Ran `./scripts/frontend-typecheck.sh`.
- Tests prove Codex CLI status remains OK when Claude or Gemini adapter events fail.

**Learnings:**
- Adapter status should be an operational signal layer, not a content layer; it must never retain raw prompts, code, stdout, or stderr.

### 2026-05-20 - Post-MVP Phase 41 Retention Policy Settings Model

**By:** Codex

**Actions:**
- Added a local evidence retention settings table scoped by organization and local owner.
- Added owner-gated GET/PATCH APIs for raw evidence retention settings.
- Supported `default`, `disabled`, and `immediate` retention modes.
- Normalized default retention days, disabled cleanup, and immediate purge semantics.
- Kept existing manual raw purge APIs unchanged.
- Checked off Post-MVP Phase 41 in the plan document.

**Verification:**
- Ran `./gradlew :backend:test --tests "com.aicodelearning.auth.LocalOwnerEvidenceRetentionIntegrationTest"`.
- Backend tests cover default value, update, disabled cleanup, immediate mode, invalid mode, invalid days, and existing manual raw purge behavior.

**Learnings:**
- Retention settings should model policy only; actual cleanup and purge execution stay in later bounded job phases.

### 2026-05-20 - Post-MVP Phase 42 Retention Settings UI

**By:** Codex

**Actions:**
- Added frontend API bindings for retention settings read/update and scoped raw purge.
- Displayed the current raw evidence retention policy in Collection Status.
- Added controls for default cleanup days, disabled cleanup, and immediate purge policy.
- Added a purge-now action for all scoped raw evidence.
- Kept user-facing copy explicit that metadata, generated cards, and practice progress remain after raw purge.
- Checked off Post-MVP Phase 42 in the plan document.

**Verification:**
- Ran `./scripts/frontend-typecheck.sh`.
- Ran `./scripts/frontend-build.sh`.
- Ran `./scripts/test.sh`.
- Ran `git diff --check`.
- Manual checklist: current policy loads in Collection Status; default days can be updated; disabled cleanup hides the days input and saves; purge-now confirmation says metadata, generated cards, and practice progress remain; purge-now calls the scoped raw purge-all endpoint.

**Learnings:**
- Retention controls belong beside collection status because the policy affects raw evidence handling, while generated learning assets and progress remain part of the main learning surface.

### 2026-05-20 - Post-MVP Phase 43 Retention Dry Run

**By:** Codex

**Actions:**
- Added a retention dry-run service that reuses local-owner retention settings.
- Added `GET /api/evidence/retention-dry-run` for automatic cleanup preview.
- Reported eligible bundle/item counts, raw metadata bundle count, artifact categories, and estimated reclaimed bytes.
- Reported quarantined evidence counts and behavior without returning secret details.
- Kept raw content, prompt text, diff text, absolute paths, provenance, and secret values out of the response model.
- Checked off Post-MVP Phase 43 in the plan document.

**Verification:**
- Ran `./gradlew :backend:test --tests "com.aicodelearning.auth.LocalOwnerEvidenceRetentionIntegrationTest"`.
- Backend tests cover default cleanup dry-run counts, category bytes, metadata-only aggregation, quarantined behavior, disabled cleanup, and response body non-disclosure for raw content, diff content, absolute paths, provenance, and secret values.

**Learnings:**
- The dry-run should be a reporting surface only; purge selection and mutation stay separate so Phase 44 can add bounded execution without changing the preview contract.
