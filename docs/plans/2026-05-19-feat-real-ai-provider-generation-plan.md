---
title: "feat: Replace Mock Pattern Generation With Real AI Provider Calls"
type: feat
date: 2026-05-19
---

# feat: Replace Mock Pattern Generation With Real AI Provider Calls

## Overview

Replace the Node MVP's deterministic-only pattern generation path with a real provider-backed path while preserving the current local mock provider for demos and stable tests.

The implementation will keep the existing `/api/generation/run` API shape and domain workflow. Non-mock providers will call an OpenAI-compatible Responses API endpoint, parse structured JSON output, validate it, and then create the same draft pattern card, tags, problems, review task, audit log, and generation run records that the deterministic path creates today.

## Research Summary

Internal references:

- `src/platform.js`: current provider registration, deterministic `inferPattern`, and generation persistence.
- `src/security.js`: current credential reference/redaction helpers.
- `tests/platform.test.js`: current end-to-end platform tests.
- `docs/brainstorms/2026-05-17-spring-react-platform-split-brainstorm.md`: real OpenAI/Claude/Gemini adapters were intentionally left for later.
- `docs/plans/2026-05-17-feat-ai-code-learning-platform-plan.md`: requires structured AI output validation, provider failure handling, token metadata, and no credential leakage.

External reference:

- OpenAI Responses API supports structured JSON schema output through `text.format` with `type: "json_schema"` and strict schema support.

## Scope

In scope:

- Real HTTP provider call for OpenAI-compatible Responses API.
- Encrypted local credential material for provider calls.
- Output normalization and validation.
- Failure-state persistence for provider errors.
- Tests using a local fake provider server.
- README updates for provider setup.

Out of scope:

- OAuth refresh flows.
- Background queues and retry workers.
- Provider billing/cost estimation.
- Claude/Gemini-specific native adapters.
- UI provider-management polish.

## Implementation Phases

### Phase 1: Credential Storage Foundation

- [x] Add encrypted credential storage fields returned by `sealCredential`.
- [x] Add `openCredential` helper for server-side provider calls.
- [x] Preserve API redaction for all credential fields.
- [x] Add tests proving raw credentials are absent from API responses and raw DB JSON.

Verification:

- [x] `./scripts/test.sh`

### Phase 2: Provider Configuration Validation

- [x] Accept optional `baseUrl` during provider registration.
- [x] Normalize provider names and base URLs.
- [x] Reject unsafe base URLs by default.
- [x] Allow loopback HTTP only when `APP_ALLOW_INSECURE_PROVIDER_BASE_URL=1`.
- [x] Preserve existing personal-provider publication policy.

Verification:

- [x] Provider registration tests cover safe and unsafe base URLs.

### Phase 3: OpenAI-Compatible Generation Adapter

- [x] Add a provider adapter for OpenAI-compatible Responses API.
- [x] Build a structured pattern-generation prompt from evidence.
- [x] Send `model`, `input`, and `text.format.json_schema`.
- [x] Parse `output_text`, Responses `output[].content[].text`, or compatible chat response text.
- [x] Validate title, summary, tags, and problems before persistence.

Verification:

- [x] Unit/integration test uses a local fake AI server and asserts the HTTP request shape.

### Phase 4: Generation Failure Handling

- [x] Mark `generationRuns.status` as `failed` when provider calls fail or output is invalid.
- [x] Store only a redacted failure category/message.
- [x] Ensure no partial pattern cards, tags, problems, or review tasks are created on provider failure.
- [x] Add audit/metric entries for failed generation.

Verification:

- [x] Invalid provider output test proves no draft asset is created.

### Phase 5: E2E And Documentation

- [x] Update smoke/E2E coverage to exercise real provider HTTP generation through a fake local provider.
- [x] Keep the existing deterministic local mock smoke path passing.
- [x] Update README with real provider setup and test-only loopback guidance.
- [x] Run full test and smoke suite in a clean local environment.

Verification:

- [x] `./scripts/test.sh`
- [x] `./scripts/smoke.sh`

## Acceptance Criteria

- [x] `provider-local-mock` still uses deterministic local generation.
- [x] A registered non-mock provider is actually called over HTTP during generation.
- [x] Generated assets use the provider's returned structured pattern data.
- [x] Provider credentials are encrypted at rest and redacted from APIs.
- [x] Unsafe provider endpoint configuration is rejected unless explicit loopback dev mode is enabled.
- [x] Provider failures create failed generation runs without partial draft assets.
- [x] E2E test covers the first-use flow through a local fake AI provider.

## Risks And Mitigations

- Provider prompt injection from evidence: treat evidence as untrusted input, require JSON schema output, validate output, and keep human review.
- Credential leakage: encrypt credential material, redact by field name, avoid audit metadata with raw credential or prompt.
- SSRF through provider `baseUrl`: allow HTTPS by default and loopback HTTP only with explicit env opt-in.
- Flaky external tests: use a local fake provider server for E2E.

## Notes For Implementation

The implementation should stay dependency-free. Use Node's built-in `fetch`, `AbortController`, and `crypto` APIs.
