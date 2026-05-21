---
date: 2026-05-21
topic: spring-real-ai-provider-generation
status: captured
---

# Spring Real AI Provider Generation

## What We're Building

LearnLoop's installed Spring/React app must generate pattern cards and practice drafts from actual AI provider responses, not from the current local heuristic in `GenerationService.inferPattern()`.

The deterministic local generator should remain only as an explicit `provider-local-mock` path for demos, parity checks, and stable tests. Every non-local provider must either make a real provider HTTP call or fail the generation run with a visible, redacted failure category. A wrong API key must therefore fail, not silently produce a successful heuristic card.

## Current Reality

- Spring generation currently builds `PatternRecognitionPrompt`, then ignores `promptText` and calls `inferPattern(recognitionPrompt.evidenceExcerpt)`.
- `ai_providers` stores `credentialRef`/fingerprint only. There is no decryptable credential material and no `baseUrl`.
- Provider setup UI stores API key/OAuth settings in browser local storage for local tool setup, but backend generation chooses the first active backend provider, which is usually `provider-local-mock`.
- The older `2026-05-19-real-ai-provider-generation` brainstorm/plan documents describe the Node MVP provider path. They do not represent the current Spring installed-app implementation.

## Recommended Approach

Use a small provider-client layer inside the Spring backend:

- `local` provider: deterministic mock, unchanged but clearly isolated.
- `openai` / `codex`: OpenAI-compatible Responses API client.
- `gemini`: Gemini REST client with structured JSON response settings.
- `claude`: Anthropic Messages/tool-use style JSON client, or explicit unsupported failure until implemented.

Provider registration should persist encrypted credential material and optional safe `baseUrl` server-side. In this local personal app, server-side encrypted storage is acceptable because the backend is the user's own local backend. API responses, audit logs, E2E output, and UI state must never expose raw credentials.

## Approaches Considered

### A. Provider Clients In Spring Backend

The backend owns provider credentials, sends the prompt, parses structured JSON, and persists draft assets only after validation.

Pros:
- Matches the current generation API and transaction model.
- Easy to test with local fake provider servers.
- Makes wrong credentials fail deterministically.

Cons:
- Requires credential encryption fields and migrations.
- Provider-specific response formats still need normalization.

### B. Browser/Companion Mediated Calls

Keep credentials only in browser/local companion and ask that process to call the provider.

Pros:
- Keeps backend away from API keys.
- Aligns with the current local AI setup UI.

Cons:
- Harder to make generation reliable and auditable.
- Browser/local companion failure modes complicate E2E and retries.
- The backend still needs trusted structured output before persistence.

### C. SDK-First Provider Integrations

Use official SDKs for each provider from the start.

Pros:
- Provider-native ergonomics.
- Fewer hand-built request details.

Cons:
- More dependencies and version churn.
- Overkill for the first working slice.

Recommendation: Approach A. Use plain HTTP clients first, with provider-specific request/response modules behind a shared `PatternGenerationClient` contract.

## Key Decisions

- Non-local providers must never fall back to `inferPattern`.
- `provider-local-mock` remains available for demos and stable tests.
- Provider credentials move from hash-only to encrypted, decryptable local storage.
- Provider API responses normalize into the existing card/tag/problem shape before persistence.
- Failed provider calls create a failed generation run and no pattern card, tags, problems, or review task.
- Failure metadata is limited to safe categories such as `provider_http_error`, `provider_timeout`, `provider_output_invalid_json`, and `provider_output_invalid_schema`.
- CI/E2E should use local fake provider servers, not paid external providers.

## Success Criteria

- Creating a non-local provider with a bad key and running generation fails.
- A fake provider server receives the actual generation request and returns JSON used verbatim in the created pattern card.
- Invalid JSON or schema-invalid JSON fails without partial assets.
- API responses and audit logs do not contain raw provider credentials, raw prompt text, raw provider response text, or secret-bearing evidence.
- Existing local mock generation still passes when `provider-local-mock` is selected.
- Conversion Trace records both success and failed generation runs clearly enough for debugging.

## Default Decisions

- Implement first-slice clients for OpenAI-compatible/Codex, Gemini, and Claude so every visible provider family has a real call path.
- Make AI setup API-key mode register a backend provider. Browser local storage may keep only the selected provider and redacted label, not the raw key.
- Keep OAuth as local tool authentication only for now. It should not be silently used for backend generation until a separate token bridge exists.
- Keep generation synchronous and persist only `completed` or `failed` runs in the first slice. Ensure failure persistence is not rolled back by provider exceptions.
- If a provider is configured but missing required call settings, fail with `provider_configuration_invalid` instead of falling back to local mock.

## Next Steps

→ `/workflows:plan docs/brainstorms/2026-05-21-spring-real-ai-provider-generation-brainstorm.md`
