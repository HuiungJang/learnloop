---
date: 2026-05-19
topic: local-ai-code-auto-collection
---

# Local AI Code Auto Collection

## What We're Building

LearnLoop should become a single-user local learning tool for developers who use AI coding tools. The app is installed and run on the user's own machine, so the same local environment can host the web UI, backend, database, and code collection service. The user is the owner, curator, and learner; admin/reviewer/organization workflows are not part of the MVP.

The app should automatically collect code generated or assisted by AI without explicit `start` or `stop` commands. It should watch repositories approved by the user, capture file changes with enough surrounding context to understand the implementation pattern, and link those changes to local AI tool activity.

## Why This Approach

The hosted/team model adds complexity that is not needed for the first useful product. If the app runs locally for one developer, LearnLoop can collect local repository changes directly and avoid device pairing, remote collector sync, organization permissions, admin dashboards, and reviewer queues.

Git commit and diff evidence remain useful as an index, but they are not enough to understand how code was generated or what full pattern was introduced. The better source unit is an AI activity window connected to file snapshots: prompt and response when available, before and after file content, final diff, repository metadata, and tool provenance.

## Key Decisions

- Product mode: single-user local installed app.
- User model: the local owner is also the learner and curator; admin/reviewer roles are not needed in the MVP.
- Collection mode: automatic background detection is required; explicit session start/stop is not acceptable as the primary workflow.
- Approved scope: only repositories approved once by the user are watched automatically.
- Raw storage: prompts, AI responses, before snapshots, after snapshots, and diffs may be stored locally in raw form.
- Encryption: raw local evidence encryption is not an MVP requirement because data stays on the user's machine. Secret scan, ignored paths, retention, and deletion remain required.
- Retention default: local raw evidence should use a practical default such as 90 days, with user deletion and secret-based quarantine.
- Tool priority: support Codex App first, then Codex CLI, Claude Desktop/Claude Code, and Gemini CLI.
- CLI collection: PATH shim/wrapper is allowed for Codex CLI, Claude Code, and Gemini CLI.
- GUI collection: Codex App and Claude Desktop start with process/window correlation plus repository file watching; direct app-server or event-stream integration can improve confidence later.
- Pattern context: collect changed file full content after modification, before content when available, final diff, file paths, branch, commit SHA when present, and relevant imports or neighboring code context.
- Provenance model: classify evidence by confidence rather than using a binary AI-generated flag.

## AI Attribution Model

- `verified_ai_generated`: LearnLoop directly captured the AI response or patch and it matches the final file content by hash or high similarity.
- `ai_assisted_edited`: LearnLoop captured AI output and the final code is similar, but user edits are material.
- `likely_ai_assisted`: AI tool activity, repository, file paths, timestamps, and conversation keywords strongly correlate, but direct output capture is missing.
- `unknown`: file changes were observed without enough AI provenance.

The product should present attribution as evidence-backed confidence. Process detection and timestamp matching alone should not be treated as verified AI generation.

## Resolved Questions

- First-run repository consent: when a GUI tool detects a new repository, show a lightweight one-time prompt with approve, ignore once, and always ignore options. Do not store raw prompt or code content until the repository is approved.
- Codex App metadata source: MVP should not depend on experimental app-server or private event streams. Use process/window correlation plus repository file watching as the stable baseline, and treat app-server/event metadata as optional confidence enrichment.
- Collector defaults: use conservative ignore rules for dependency folders, build output, caches, IDE metadata, lockfiles, binary/media/archive files, certificate/key files, and `.env`-style files.
- Content limits: store raw text only up to practical defaults such as 200KB per file and 5MB per session. For oversized files, keep path, size, content hash, and bounded diff/context excerpts.
- Raw evidence protection: do not require encryption in the local personal MVP. Keep secret scanning, quarantine, retention limits, ignored path defaults, and delete controls.
- Attribution correction: allow the user to override evidence attribution as AI-generated or assisted, partially AI-assisted, manual, or undecided. Preserve the automatic classification separately, and use the user correction as the stronger signal for learning asset generation.
- Deployment model: LearnLoop runs locally and owns both the app workflow and the collection workflow. Hosted/team sync can be a later product mode, not part of this MVP.

## Next Steps

-> `/workflows:plan docs/brainstorms/2026-05-19-local-ai-code-auto-collection-brainstorm.md`
