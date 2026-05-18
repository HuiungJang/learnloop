---
date: 2026-05-18
topic: ai-pattern-e2e-performance-security
source: user-goal
---

# AI Pattern Prompt, E2E, Performance, Security

## What We're Building

Advance the installable AI Code Learning Platform from demo parity toward a safer product loop. The work has four connected outcomes: add a real server-side prompt contract for AI pattern recognition, define and automate first-user end-to-end coverage, start measurable performance improvements, and run a Codex Security scan over the current project with fixes for validated issues.

The first-user path starts from an installed local app with no prior user session: create an account, log in, configure local AI credentials without sending them to the backend, ingest AI/code evidence, link sources, generate a draft pattern card, review/publish the card, view it as a learner, submit an answer, and confirm proficiency/progress surfaces update.

## Why This Approach

The prompt should be treated as backend-owned product logic, not frontend content. It can exist in source code and tests, but must not be returned by API responses, rendered in the UI, included in audit metadata, or logged. This keeps the user-visible learning assets separate from the internal model instruction contract.

E2E should cover both browser behavior and API invariants. Browser tests prove the installable app works for a new user, while API tests can cover role transitions and review/publish/submission behavior that is currently broader than the single frontend screen.

The 30% performance target needs measurable baselines. The first version should focus on low-risk improvements that apply broadly: backend query count and lookup reduction, frontend bundle size and render/request reduction, release/build caching where practical, and E2E runtime stability.

## Key Decisions

- Prompt visibility: keep the pattern-recognition prompt in backend-only code and expose only generated pattern cards/problems.
- Prompt contract: require strict JSON with pattern title, summary, confidence, tags, evidence references, implementation guidance, failure modes, problems, and review risks.
- Provider strategy: preserve deterministic local mock behavior, but introduce an internal prompt-building seam for future Codex/Gemini/Claude adapters.
- E2E scope: automate a first-user journey that begins at `http://localhost:8080` after `./scripts/install.sh`.
- Local AI credential invariant: Playwright must assert API keys and OAuth labels are stored locally and never appear in observed request bodies.
- API parity E2E: keep Node-based API smoke coverage for ingest/link/generate/review/library/submit/progress and extend it where gaps exist.
- Performance metric choice: measure startup/build/test/E2E/bundle/API workflow runtime and target 30% improvement on at least the controllable hot paths changed in this work.
- Security process: run Codex Security after implementation, fix validated findings, and keep the report under `/tmp/codex-security-scans`.
- Open questions resolved by default: choose conservative internal-only prompt storage, installable local app test target, same-origin localStorage for local AI credentials, and no public publishing in this phase.

## User Flow To Validate

1. Fresh user opens the installed app.
2. User signs up and receives a contributor session.
3. First login requires local AI setup.
4. User selects Claude API key or Codex/Gemini OAuth profile.
5. Local credential material remains browser-local and is not sent to the backend.
6. User ingests code and `codex-obsidian-sync` conversation evidence.
7. User confirms a suggested source link.
8. User runs pattern generation.
9. Reviewer approves the generated draft.
10. Learner finds the published card.
11. Learner submits an answer and sees answer/proficiency effects.
12. Audit/security invariants remain intact.

## Open Questions

- Should the prompt be configurable by admins in this phase? Decision: no; backend-owned static prompt first, admin configuration later after audit and versioning exist.
- Should real provider calls be implemented now? Decision: no; create the internal prompt contract and keep mock generation deterministic until provider adapters are planned separately.
- Should 30% apply to every possible metric? Decision: define representative project metrics and improve the ones touched by this work; record any metric that cannot be improved without larger architecture changes.

## Next Steps

-> `/workflows:plan docs/brainstorms/2026-05-18-ai-pattern-e2e-performance-security-brainstorm.md`
