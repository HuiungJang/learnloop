---
title: "Hidden Pattern Prompt, Installed E2E, and Performance Validation"
date: 2026-05-18
type: solution
tags:
  - ai-pattern-recognition
  - e2e
  - performance
  - security
related_plan: docs/plans/2026-05-18-feat-ai-pattern-e2e-performance-security-plan.md
---

# Hidden Pattern Prompt, Installed E2E, and Performance Validation

## User Flow Tested

1. New user opens the installed app at `http://localhost:8080`.
2. User signs up with a unique email.
3. First login opens local AI setup.
4. User selects Claude API key mode and saves a local key.
5. The key is verified in browser `localStorage` and is absent from all observed server request bodies.
6. User logs out and logs back in.
7. Onboarding is skipped because local AI setup already exists.
8. User re-opens local AI setup, switches to Gemini OAuth mode, and saves a local OAuth profile label.
9. OAuth label is verified absent from observed server request bodies.
10. User runs the visible workflow and sees generated/reviewed card state.

Command:

```sh
./scripts/e2e-installed.sh
```

Result:

```json
{
  "postRequestCount": 7,
  "localStorageProvider": "gemini",
  "localStorageAuthMethod": "oauth",
  "apiKeyLeakedToServer": false,
  "oauthLabelLeakedToServer": false
}
```

## Prompt Privacy

- The pattern-recognition prompt is built by the backend-only `PatternRecognitionPromptBuilder`.
- Raw prompt text is not stored in generation responses, audit responses, frontend code, or frontend build output.
- Evidence included in the prompt is bounded and likely secrets are redacted before prompt construction.

Verification:

```sh
./scripts/backend-test.sh
./scripts/frontend-build.sh
rg "ACL_INTERNAL_PATTERN_PROMPT_V1_DO_NOT_EXPOSE|You are a senior software engineering educator|Evidence excerpt" frontend/dist
```

The final `rg` command must return no matches.

## Performance Measurements

Baseline captured before implementation:

- Library median: `16.09ms`
- Detail median: `6.54ms`
- Frontend JS: `244672 bytes`
- Frontend CSS: `10400 bytes`
- Frontend total: `255072 bytes`

Final installed-app measurement:

```sh
PERF_SAMPLES=100 ./scripts/perf-measure.sh
```

```json
{
  "sampleCount": 100,
  "libraryCardCount": 12,
  "libraryMedianMs": 6.07,
  "detailMedianMs": 5.01,
  "frontendJsBytes": 211586,
  "frontendCssBytes": 10400,
  "frontendTotalBytes": 221986
}
```

Improvements:

- Library median: `62.3%` faster.
- Detail median: `23.4%` faster against the original baseline; `30%+` faster against the first post-install measurement before detail-specific optimizations.
- Frontend JS: `13.5%` smaller by removing React Query from the single health check path.
- Frontend total assets: `13.0%` smaller.

The library hot path exceeded the 30% target. Frontend total asset size did not reach 30% without changing the React runtime or icon strategy, so this remains a future optimization area.
