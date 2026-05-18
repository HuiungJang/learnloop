---
date: 2026-05-18
topic: frontend-practice-library-code-workbench
source: 2026-05-17-spring-react-platform-split-brainstorm.md
---

# Frontend Practice Library Code Workbench

## What We're Building

Evolve LearnLoop's frontend from a simple pattern-card viewer into a practice-centered code workbench. The main learner experience should help developers move from collected AI-generated code, PR/commit/diff evidence, and recognized patterns into reusable practice problems.

The practice screen should feel familiar to developers who use Visual Studio Code: multi-file tabs, a code editor surface, find, quick-open, command palette, formatting, save, diff toggle, theme toggle, and submit shortcuts. This should remain a learning workbench, not a full IDE. The surrounding LearnLoop app shell keeps the warm cream visual direction, while the editor area can use a selectable VS Code Light or Dark style.

## Why This Approach

LearnLoop's core value is not only collecting AI-assisted coding output, but turning it into reusable learning assets. A practice-first UI makes that value visible: users solve realistic code modification tasks, compare their answer against an expected diff, and receive pattern-aware feedback.

The recommended direction balances familiarity and scope. A full IDE would add unnecessary complexity, but a VS Code-like workbench gives developers the code-reading and editing affordances they already know. Pattern-centered browsing keeps the library language-agnostic and reusable across TypeScript, Kotlin, Java, libraries, APIs, algorithms, and design patterns.

## Key Decisions

- Primary experience: make the frontend practice-centered rather than only review- or reading-centered.
- Default exercise type: use code modification tasks first, with Q&A and reading exercises still supported by the broader platform.
- Library navigation: organize by recognized patterns, with filters for language, library/API, difficulty, and review/publication state.
- Code presentation: support multiple file tabs so generated exercises can include realistic project context.
- Editor fidelity: provide a VS Code-like learning workbench, not a full IDE clone.
- Editor themes: keep the LearnLoop shell cream-toned, but let users choose VS Code Light or Dark inside the code editor.
- Shortcuts: support the learning-workbench set: save, find, quick file open, command palette, format, submit, diff toggle, and theme toggle.
- Hint model: use progressive disclosure. Start with the problem and requirements, then reveal hints, then reveal the recognized pattern after enough learner action.
- Feedback model: show execution results, test-level pass/fail, stdout/stderr, error stack, failed diff, AI/rule-based explanation, pattern feedback, and next learning recommendations.
- Execution model: support sandbox execution for TypeScript, Kotlin, and Java first.
- Sandbox location: default to local execution in the installed LearnLoop environment, while allowing organization administrators to configure a server-side runner later.
- Editor library: use Monaco Editor for the VS Code-like editing surface, shortcuts, language support, and diff views.
- Local sandbox isolation: use Docker-based local runners for TypeScript, Kotlin, and Java. If Docker is unavailable, keep reading, editing, saving, and review flows available while disabling code execution.
- Review flow: generated problems start as personal drafts, then become organization library assets only after human review. Leave an extension point for administrator-controlled AI review.
- Progress storage: keep the original exercise asset unchanged. Store attempts and progress as per-user records locally first, then sync those user-specific records to the server. Avoid merging user answers back into the canonical organization asset.
- Evidence visibility: provide a collapsible provenance panel that links the exercise back to source snippets, PR/commit/diff evidence, and recognized patterns without overwhelming the main practice view.
- Conversion workbench: use a traceable collection-to-library screen with source evidence on the left, recognized pattern summaries in the center, and generated exercises plus review state on the right.

## Resolved Questions

- Editor implementation: use Monaco Editor instead of CodeMirror because the product specifically needs a familiar VS Code-like editing experience.
- Sandbox isolation: use Docker-based local runners by default, with a future extension point for organization-managed server runners.
- Progress sync conflicts: avoid canonical-asset conflicts by storing user submissions separately from the original exercise. Server sync should append or update per-user attempt records, while the organization library asset remains stable unless it goes through the review/edit workflow.

## Next Steps

-> `/workflows:plan docs/brainstorms/2026-05-18-frontend-practice-library-code-workbench-brainstorm.md`
