# Hosted/Team Re-Evaluation Decision

Date: 2026-05-20

## Decision

Hosted/team mode is not a product goal for the current local learning loop.

The product remains a personal local installed app where the same person is the local owner and learner. The app may keep organization/team/project identifiers as internal compatibility scaffolding, but the user-facing product should not add team administration, reviewer roles, hosted collection, or cross-user collaboration in this cycle.

## Scope

In scope:

- Preserve the local owner model and local-only data flow.
- Keep generated cards, practice progress, evidence metadata, and hashes in the local database.
- Keep raw evidence retention, purge, and cleanup controls local.
- Allow future backup/export research only as an explicit local-owner action.

Out of scope:

- Hosted collection from a remote web-only user.
- Multi-user team workspaces.
- Admin/reviewer role switching in the primary UI.
- Cloud sync, remote import, or shared review queues.
- Automatic upload of raw evidence outside the local machine.

## Local Data Decision

Local data is local-only by default.

Export/import is not approved for this phase. If it is revisited later, export must be explicit, user-triggered, and metadata-first. Raw evidence export must be opt-in, and quarantined raw payloads must be omitted or rejected unless a separate security review approves a precise format.

## Migration Assumptions

- Existing organization/team/project tables may remain to avoid broad schema churn.
- Local mode continues to seed one owner identity and one local organization.
- No new team tables, hosted import tables, or role surfaces should be added until a new decision document approves hosted/team mode.
- If hosted/team is reopened, the first implementation phase should define a local export manifest and migration boundary before any hosted import code is written.

## Non-Goals

- Do not reintroduce admin screens for local mode.
- Do not add team role management.
- Do not make raw evidence portable by default.
- Do not build hosted import prototypes.
- Do not weaken local owner authorization to accommodate future team scenarios.

## Follow-Up

Post-MVP Phases 49, 50, and 51 are deferred by this decision. They should remain unimplemented until hosted/team mode is explicitly approved in a new plan.
