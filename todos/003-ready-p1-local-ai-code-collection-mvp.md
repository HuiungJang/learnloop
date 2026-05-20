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
