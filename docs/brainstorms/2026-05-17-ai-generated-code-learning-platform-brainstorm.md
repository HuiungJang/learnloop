---
date: 2026-05-17
topic: ai-generated-code-learning-platform
---

# AI Generated Code Learning Platform

## What We're Building

A learning platform for team and organization developers that turns AI-assisted coding activity into reusable learning assets. The platform collects AI conversation logs from tools such as Codex, Gemini, and Claude, and combines them with PR, commit, and diff-based code changes. From those sources it identifies repeated code patterns, libraries, APIs, algorithms, configuration approaches, and design patterns.

The primary learning asset is a pattern card with a problem set. A pattern card explains a reusable technical pattern and links it to generated learning material such as Q&A, short implementation exercises, and code-reading questions. The platform should be programming-language agnostic and use a LeetCode-like problem-solving experience where appropriate, while still supporting real-world library, API, and architecture patterns.

## Why This Approach

The initial target is organization-internal use, because the source material may include proprietary code, prompts, architecture details, or sensitive business context. The product should still be designed so that individual users and public learning assets can be supported later.

The platform uses two source streams: AI conversation logs and actual code changes. Conversation logs provide intent, explanation, alternatives, and reasoning. PR, commit, and diff data provide the final code that was actually accepted. The code-change source should act as the primary grounding source, with conversation logs used to enrich interpretation.

## Core Product Loop

1. Collect AI usage logs and PR, commit, or diff data.
2. Link related conversations and code changes through automatic suggestions plus user confirmation.
3. Extract candidate patterns from code and technical usage.
4. Generate draft pattern cards and problem sets using the configured AI provider.
5. Apply the configured review policy.
6. Publish approved assets to the organization learning library.
7. Developers solve problems and review pattern cards.
8. Learning history updates pattern-level proficiency and recommendations.

## Key Decisions

- Target users: Start with team and organization developers.
- Collection sources: Support both AI conversation logs and PR, commit, or diff-based code changes.
- Related project: `codex-obsidian-sync` can provide conversation-log ingestion.
- Source linking: Use a hybrid model where the platform suggests matches and the user confirms or edits them.
- Storage model: Use a two-layer model. Keep original logs, code snippets, and source links in an internal evidence store. Store reusable learning assets separately as anonymized and reviewed content.
- Learning asset model: Use pattern cards with attached problem sets.
- Problem transformation: Preserve technical structure while changing or anonymizing the business domain.
- Initial problem types: Implement Q&A, short implementation exercises, and code-reading questions first.
- Second-stage problem type: Add LeetCode-style automatically graded problems after the first problem types are working.
- Todo problem type: Add setup and debugging labs later.
- Sharing model: Start with organization-internal sharing plus team and project-level permissions.
- Future sharing model: Include `private`, `organization`, and `public` visibility in the model, but keep public publishing disabled in the first version.
- UI direction: Start with pattern exploration plus problem solving. Add an admin or leader dashboard later.
- AI provider model: Support both organization-level default AI providers and user-level personal AI providers.
- AI routing: Support both single-AI mode and task-specific model routing.
- Initial pattern extraction scope: Start with static code patterns and technical usage patterns.
- Later pattern extraction scope: Add development decision patterns from conversation logs.
- Review model: Default to human review, but design review as a policy that can later include AI-assisted review or AI first-pass review controlled by platform administrators.
- Asset approval checks: Validate security, deduplication, answer correctness, difficulty, tags, source similarity, and sharing scope.
- Learning tracking: Track problem-solving history and pattern-level proficiency.
- First ingestion path: Start with manual upload plus `codex-obsidian-sync` ingestion. Add Git provider integration after the MVP path is validated.
- Review-entry requirements: A pattern card can enter review only when it has a pattern name, summary, technical tags, at least one evidence source reference, at least one generated problem, and an answer or reference explanation.
- Source similarity policy: Apply different anonymization strictness by visibility. Private assets may stay close to the original source. Organization assets must transform business domain, identifiers, and data structures. Public assets must strongly transform domain, identifiers, structure, constants, and exception messages.
- Organization AI enforcement: Admins can require organization-approved AI providers for organization-published assets. Public assets require organization-approved AI plus stronger review.
- Personal AI provider policy: Personal AI uses BYOK or personal OAuth. The platform does not cover personal AI cost in the MVP, but records usage and audit events.
- Learner submissions: Store both code and text, depending on problem type. Later automatic grading should also store test results.

## MVP Scope

The first version should focus on:

- Organization workspace setup.
- AI provider registration with organization and user-level configuration.
- Ingestion from `codex-obsidian-sync`-style conversation logs.
- Manual upload for code snippets, diffs, and supporting context.
- Hybrid source linking.
- Pattern extraction for static code and technical usage.
- Pattern card and problem-set draft generation.
- Human review workflow with policy-based extension points.
- Organization learning library with team and project permissions.
- Learner UI for pattern discovery and problem solving.
- Progress tracking by problem and pattern.
- Submission storage for text answers and code answers.

## Post-MVP

- LeetCode-style automatic grading.
- Git provider integration for PR, commit, and diff ingestion.
- Admin and leader dashboard.
- Development decision pattern extraction.
- AI-assisted review policy.
- Public asset publishing.
- Individual-user mode outside organizations.
- Setup and debugging labs.

## Resolved Questions

- First source implementation: Manual upload plus `codex-obsidian-sync` ingestion first; Git provider integration second.
- Minimum review data: Pattern name, summary, technical tags, evidence source reference, at least one generated problem, and answer or reference explanation.
- Similarity checks: Use visibility-based strictness for private, organization, and public assets.
- Approved AI enforcement: Organization admins can require organization-approved AI for organization assets; public assets require approved AI and stronger review.
- Personal AI usage: BYOK or personal OAuth, usage/audit logging, no MVP billing or credit system.
- Submission storage: Store both text and code answers; automatic grading later adds test result storage.

## Next Steps

-> `/workflows:plan docs/brainstorms/2026-05-17-ai-generated-code-learning-platform-brainstorm.md`
