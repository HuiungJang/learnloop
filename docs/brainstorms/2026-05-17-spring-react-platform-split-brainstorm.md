---
date: 2026-05-17
topic: spring-react-platform-split
source: 2026-05-17-ai-generated-code-learning-platform-brainstorm.md
---

# Spring React Platform Split

## What We're Building

Rebuild the AI Generated Code Learning Platform MVP as a split application: a Kotlin/Spring Boot backend and a TypeScript/React frontend. The existing dependency-free Node MVP remains in the repository as a reference implementation until the new stack reaches feature parity.

The product scope does not shrink during the migration. The Spring/React version should preserve the current end-to-end loop: source ingestion, `codex-obsidian-sync` import shape, PR/commit/diff evidence entry, source linking, pattern generation, human review, organization publication, learner practice, submissions, proficiency tracking, provider registration, audit, and security controls.

## Why This Approach

Keeping the Node MVP as an executable reference reduces migration risk. It lets the team compare behavior while building the new backend and frontend in parallel, then decide later whether to remove the Node implementation.

The recommended stack uses conservative, widely supported defaults: Spring Boot for API and domain logic, PostgreSQL for relational state and auditability, Flyway for schema evolution, Vite React for the UI, TanStack Query for server state, React Router for navigation, and OpenAPI-generated TypeScript types to keep frontend DTOs aligned with backend contracts.

## Key Decisions

- Migration strategy: keep the existing Node MVP as a reference implementation and add `backend/` and `frontend/` in the same repository.
- API contract: use REST JSON with OpenAPI.
- Data store: use PostgreSQL with Flyway migrations.
- Authentication: use first-party session or JWT authentication with role-based authorization; keep the boundary open for future OIDC/SSO.
- Frontend stack: use Vite, React, TypeScript, TanStack Query, and React Router.
- Parity target: implement the full current Node MVP feature set, not a reduced slice.
- AI generation: define provider interfaces and implement a deterministic local mock generator first; leave real OpenAI/Claude/Gemini adapters for later.
- Frontend types: generate TypeScript API types from the OpenAPI contract.
- Deployment shape: treat the Spring API server and React app as separate deployable units.
- Evidence storage: store evidence metadata and body in PostgreSQL for the first version, behind an `EvidenceStorage` abstraction for later object storage.
- GitHub/PR source input: support manual PR/commit/diff text entry plus GitHub URL metadata; leave a `GitSourceProvider` extension point for future API/App integration.
- Backend architecture: use a modular monolith with domain modules such as `auth`, `organization`, `evidence`, `generation`, `review`, `library`, `learning`, and `audit`.
- Generation execution: model generation as asynchronous `GenerationRun` state even though the local mock can complete immediately.
- Local environment: use Docker Compose for PostgreSQL and run backend/frontend dev servers locally.
- Test strategy: combine backend integration tests, frontend API/UI smoke tests, and parity smoke checks against the existing Node MVP behavior.
- Backend runtime: use Gradle Kotlin DSL, Kotlin 2.x, Spring Boot 3.x, and Java 21.
- OpenAPI management: use Spring code-first controllers/DTOs with `springdoc-openapi` generation.
- Documentation: keep this architecture transition in a separate brainstorm document from the original product brainstorm.
- Frontend visual direction: use the Lovable-inspired warm neutral design system as the React UI foundation, adapted for a dense internal learning/workbench product rather than a marketing landing page.

## Frontend Design Direction

The React frontend should use a warm, restrained visual system inspired by Lovable. The base surface is cream `#f7f4ed`, primary text is charcoal `#1c1c1c`, subtle dividers use `#eceae4`, and secondary text derives from charcoal opacity or muted gray `#5f5f5d`. Dark primary actions use charcoal backgrounds with off-white `#fcfbf8` text and the specified inset shadow treatment.

The design should feel editorial and approachable, but the product remains an operational developer tool. The first screen should be the usable workspace: navigation, evidence intake, review queue, library, practice, and activity surfaces. Do not introduce a marketing-style hero as the default application entry point.

Typography should prefer `Camera Plain Variable` when available, with `ui-sans-serif, system-ui` fallbacks. Large display text may use tight letter spacing, but compact app panels, buttons, tables, cards, and form controls should preserve readable normal tracking. Font weights stay narrow: 400 for body/UI and 600 for headings.

Component styling follows the provided rules:

- Buttons: charcoal primary, ghost outline, cream tertiary, and pill/icon variants.
- Cards and panels: cream background, `1px solid #eceae4`, 8px-12px radius, no heavy shadows.
- Inputs: cream background, warm border, 6px radius, blue focus ring.
- Layout: generous outer spacing where appropriate, but dense organized work surfaces for repeated developer workflows.
- Navigation: app shell navigation with clear role-aware destinations rather than landing-page links.
- Interaction states: soft focus shadow, opacity-based active states, no saturated accent-heavy palette.

The frontend should include design tokens in code so the Lovable-inspired system is repeatable across pages. The palette should not become decorative-only; use status labels, review states, tags, and permissions carefully so the app stays scannable.

## MVP Parity Requirements

- Organization, team, project, membership, and role model.
- Organization and personal AI provider registration with credential redaction/reference storage.
- Manual source upload for code snippets, diffs, and supporting context.
- `codex-obsidian-sync`-style conversation import.
- PR/commit/diff evidence entry with GitHub URL and commit metadata fields.
- Secret scanning before evidence persistence.
- Evidence storage with content hash, sensitivity status, retention metadata, and audit events.
- Source link suggestion and contributor confirmation.
- Pattern card and problem-set generation using the local mock provider.
- Human review queue with approve, request changes, reject, and duplicate decisions.
- Self-review prevention and organization publication policy checks.
- Pattern library browse and learner-facing practice UI.
- Text/code submission storage and pattern-level proficiency updates.
- Recommendation endpoint based on tags and proficiency.
- Security controls already added to the Node MVP: authenticated API identity, credential redaction, raw evidence access control, and rate limiting.

## Proposed Repository Shape

```text
backend/
  build.gradle.kts
  src/main/kotlin/...
  src/main/resources/db/migration/...
  src/test/kotlin/...
frontend/
  package.json
  src/
  src/generated/
  vite.config.ts
docker-compose.yml
docs/brainstorms/
docs/plans/
src/ public/ tests/
```

The existing `src/`, `public/`, and `tests/` Node MVP directories stay temporarily as reference implementation assets.

## Open Questions

- Should the Spring session format be opaque server-side sessions or signed JWTs for the first implementation plan?
- Should the React rewrite introduce the full LeetCode-like problem navigation in the first parity phase, or start with the current MVP workspace and evolve the navigation after backend parity is stable?
- Should Testcontainers be required for backend integration tests, or should tests target the Docker Compose PostgreSQL service?
- Should generated OpenAPI TypeScript types be committed, or generated during frontend setup/CI?

## Next Steps

-> `/workflows:plan docs/brainstorms/2026-05-17-spring-react-platform-split-brainstorm.md`
