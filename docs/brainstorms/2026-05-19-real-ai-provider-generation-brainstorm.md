---
title: "Replace Mock Pattern Generation With Real AI Provider Calls"
date: 2026-05-19
status: captured
---

# Replace Mock Pattern Generation With Real AI Provider Calls

## What We're Building

The current Node MVP has one major mock: `provider-local-mock` routes generation through `inferPattern`, a deterministic local heuristic. That made the MVP testable, but it means registered providers are not actually used for pattern recognition.

We will turn this into a real provider-backed generation path while keeping the deterministic mock as a local fallback and test fixture. The first real provider target is an OpenAI-compatible Responses API adapter because it supports structured JSON output and can be tested end-to-end with a local fake provider server.

## Why This Approach

Recommendation: implement a small provider adapter behind the existing `/api/generation/run` flow.

This preserves the current domain model, review flow, publication rules, audit behavior, and tests. It also avoids a broad migration to queues or SDK dependencies. The adapter can call a configured provider endpoint, validate structured output, and persist only validated pattern cards, tags, and problems.

Alternatives considered:

- Replace the whole generation flow with async jobs: better long term, too large for this step.
- Implement provider-specific SDKs now: more complete, but adds dependency and version churn.
- Keep only deterministic generation: safe but leaves the mock in place.

## Key Decisions

- Keep `provider-local-mock` for deterministic local demos and tests.
- Add real provider execution for non-mock providers, starting with OpenAI-compatible Responses API.
- Store provider credentials encrypted locally instead of only hashing them, while keeping API responses redacted.
- Allow custom provider `baseUrl` only for HTTPS, or loopback HTTP when explicitly enabled for tests/dev.
- Validate AI output before creating reviewable assets.
- On provider failure, mark the generation run failed and avoid creating partial cards/problems.

## Open Questions Resolved

- **Which mock first?** Real provider generation, because it is the primary product-value mock and already called out in prior plans.
- **Which provider first?** OpenAI-compatible Responses API, because it has structured outputs and simple HTTP semantics.
- **How to E2E without real paid credentials?** Use a local fake Responses API server and a real HTTP call from the app.
- **Should deterministic generation disappear?** No. It remains as `local-mock` for local demos and stable tests.

## Success Criteria

- Registered non-mock provider credentials can be decrypted server-side for provider calls but never appear in API responses, audit metadata, or test output.
- Generation with a fake OpenAI-compatible provider creates a reviewable card using the provider-returned title, summary, tags, and problems.
- Invalid provider output fails the generation run without partial asset creation.
- Existing deterministic mock workflow still passes.
