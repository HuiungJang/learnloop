# LearnLoop MVP

LearnLoop turns AI-assisted coding evidence into reviewed practice assets. It collects source evidence, links related conversation/code artifacts, asks an AI provider to extract reusable implementation patterns, and sends generated cards/problems through human review before learners practice them.

Korean documentation: [docs/README.ko.md](docs/README.ko.md)

## Recommended Users

- Developers who want to learn from code generated with AI tools.
- Engineering teams that want to convert repeated implementation patterns into an internal practice library.
- Reviewers or platform admins who need a human approval step before organization-wide learning assets are published.

## Runtime

The scripts use the bundled Codex Node runtime automatically, so they keep working even if the system Node installation is broken:

```sh
./scripts/test.sh
./scripts/dev.sh
./scripts/smoke.sh
```

Default dev URL:

```text
http://localhost:4173
```

## Demo Users

Use the UI role selector or create a session with `POST /api/session`:

- `u-admin`
- `u-contributor`
- `u-reviewer`
- `u-learner`

Default local password: `demo-password`. Set `APP_DEMO_PASSWORD` for non-local environments.

## AI Provider Setup

The seeded `provider-local-mock` still supports deterministic local demos and stable tests. Non-mock providers now use an OpenAI-compatible Responses API call to `POST /v1/responses` with structured JSON schema output.

Register a provider with `POST /api/providers`:

```json
{
  "organizationId": "org-demo",
  "provider": "openai",
  "model": "gpt-4.1-mini",
  "scope": "organization",
  "credential": "YOUR_API_KEY",
  "orgApproved": true
}
```

For `provider: "openai"`, `baseUrl` defaults to `https://api.openai.com`. Custom `baseUrl` values must use HTTPS. Loopback HTTP is allowed only for local tests when `APP_ALLOW_INSECURE_PROVIDER_BASE_URL=1`.

Provider credentials are encrypted in the local JSON store and redacted from API responses. Set `APP_CREDENTIAL_ENCRYPTION_KEY` outside local development; production mode requires it.

## Verification

Run the full local test suite:

```sh
./scripts/test.sh
```

Run the E2E smoke flow:

```sh
./scripts/smoke.sh
```

The smoke flow covers session creation, ingestion, source linking, deterministic local generation, review, publication, learner submission, progress tracking, and real HTTP provider generation through a local fake OpenAI-compatible provider.
