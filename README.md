<p align="center">
  <img src="assets/learnloop-logo.svg" alt="LearnLoop" width="180">
</p>

# LearnLoop

[한국어](README.ko.md)

LearnLoop turns code snippets, conversation logs, pull requests, commits, and diffs created through AI coding tools such as Codex, Gemini, and Claude into reusable developer learning assets.

## Key Screens and Features

<p align="center">
  <img src="assets/learnloop-demo.gif" alt="LearnLoop demo showing signup, local AI setup, workflow run, and generated learning card" width="900">
</p>

The demo shows the core flow: sign up, configure local AI, run the evidence-to-practice workflow, and review the generated learning card.

## Purpose

AI-generated code can improve short-term delivery speed, but teams often lose the chance to learn which patterns, libraries, APIs, and implementation choices keep repeating. LearnLoop turns that activity into a learning loop.

- Collect code snippets, conversation logs, pull requests, commits, and diffs generated with AI assistance.
- Analyze design patterns, libraries, algorithms, API usage, and configuration practices.
- Convert the analysis into implementation exercises, Q&A, and practice cards.
- Require human review before generated cards become reusable organization learning assets.
- Keep user AI API keys and OAuth settings in the local browser only, without sending them to the server.

## Recommended Users

- Developers who use AI coding tools often and want to turn generated code into durable learning material
- Tech leads who want to convert recurring libraries, APIs, and patterns into training content
- Platform administrators who manage onboarding, code review education, or internal developer learning
- Organizations that want to create learning problems from real pull request and commit history

## Usage

### Installable App

The easiest way to run LearnLoop is the Docker Compose installer. It builds the Spring Boot API, builds the React app, starts PostgreSQL, stores data in a Docker volume, and exposes one browser URL.

Requirements:

- Docker Desktop or Docker Engine with Compose v2

Install and start:

```sh
./scripts/install.sh
```

Open:

```text
http://localhost:8080
```

The installer creates `.env` with generated local credentials. It also prints the generated demo password after startup; enter that password in the UI to use the seeded demo roles.

Common installed-app commands:

```sh
./scripts/start.sh
./scripts/status.sh
./scripts/stop.sh
./scripts/local-ai-companion.sh
```

To change the browser port, edit `AI_CODE_WEB_PORT` in `.env`, then restart:

```sh
./scripts/start.sh
```

Data is stored in the `learnloop_install-postgres-data` Docker volume. `./scripts/stop.sh` stops containers without deleting data.

### Practice Workbench and Sandbox Runs

The practice workbench supports a VS Code-style editor experience for TypeScript, Java, and Kotlin exercises. Learners can browse a practice, edit files, save drafts, submit answers, inspect feedback, and compare answer diffs even when local code execution is unavailable.

Sandbox execution is optional and fail-closed. The Run action requires the backend runtime to have access to a Docker CLI, a reachable Docker daemon, and local language runner images. If those prerequisites are missing, the app reports `runner_unavailable` while preserving read, edit, local save, draft sync, and submit flows.

Runner limitations for the current version:

- Supported languages: TypeScript, Java, Kotlin
- No network access during code execution
- No package installation during a run; dependencies must be baked into runner images
- Fixed harness commands selected by the backend, not by the browser
- Bounded wall-clock timeout, CPU, memory, process count, and stdout/stderr excerpts

Useful runner checks from source:

```sh
./scripts/build-runner-images.sh
./scripts/runner-typescript-smoke.sh
./scripts/runner-java-smoke.sh
./scripts/runner-kotlin-smoke.sh
./scripts/status.sh
```

The installed app now enables local sandbox execution by default. It installs Docker CLI support in the backend image, mounts the host Docker socket, builds the TypeScript/Java/Kotlin runner images, and uses `.local-runner-workspaces/` as the shared host/container workspace. This is powerful local-only functionality: users who do not want the backend container to access the host Docker daemon should set `APP_RUNNER_ENABLED=false` before starting the app.

### Attempts and Sync

Editor state is local-first. The browser keeps unsent edits locally, then syncs drafts and submitted answers to the server as per-user attempt records. Canonical organization assets such as pattern cards, practice files, hints, and answer references are not mutated when a learner submits an attempt.

Server sync is idempotent by `(user, problem, clientAttemptId)`, so retries update the learner's own draft/submission instead of creating conflicting organization-level records. Reviewers and administrators can inspect submitted attempts according to their role, but local AI provider credentials remain in the user's browser and are not sent to the server.

### First User Flow

1. Sign up or log in.
2. Choose the AI provider you want to use on first login.
3. Codex and Gemini support OAuth or API key setup; Claude uses API key setup.
4. For OAuth, click `Connect Codex` or `Connect Gemini` in the local AI setup screen. The local companion starts the provider's login command on this machine.
5. Save local AI settings, then run the learning flow.
6. Review generated pattern cards and exercises, then turn them into reusable learning assets.

## Release Bundle

Build a distributable package for the current machine architecture:

```sh
./scripts/package-release.sh
```

The archive is written to `dist/release/` and contains:

- runtime `docker-compose.yml`
- `install.sh`, `start.sh`, `status.sh`, `stop.sh`
- release metadata
- backend, web, and PostgreSQL Docker image archives
- macOS `LearnLoop.app`

Install from the release bundle:

```sh
tar -xzf dist/release/learnloop-0.1.0-*.tar.gz
cd learnloop-0.1.0-*
./install.sh
```

Release-bundle installation does not build from source. It loads the packaged Docker images, including the language runner images, starts the stack, and prints the generated demo password.

The release bundle includes the application, database, and TypeScript/Java/Kotlin runner images. Runner execution still requires local Docker daemon access through the mounted Docker socket. When runner prerequisites are not available or `APP_RUNNER_ENABLED=false`, the release app still supports browsing, editing, saving, submitting, and reviewing practice attempts.

## CI/CD

LearnLoop uses GitHub Actions for the main quality and release gates.

- `CI` runs on pull requests and pushes to `main`: changed-file validation, tests, builds, dependency checks, secret scanning, filesystem scanning, and container image scanning.
- `CodeQL` runs on pull requests, pushes to `main`, and a weekly schedule for Kotlin and TypeScript static analysis.
- `Release` runs on version tags such as `v0.1.0` or manual dispatch: tests, build, security scan, release bundle packaging, and GitHub Release publishing.

## Development

The scripts in this repository use the bundled Codex Node runtime automatically.

```sh
./scripts/test.sh
./scripts/dev.sh
./scripts/smoke.sh
```

Default development URL:

```text
http://localhost:4173
```

To run the Spring Boot and React services separately:

```sh
./scripts/db-up.sh
./scripts/backend-dev.sh
./scripts/frontend-dev.sh
```

Default split-stack URLs:

```text
Backend API: http://localhost:8080
Frontend: http://127.0.0.1:5173
Health: http://localhost:8080/api/health
```

Run the current split-stack verification:

```sh
./scripts/check-split.sh
./scripts/npm.sh --prefix frontend audit
```

Build and verify sandbox runner images:

```sh
./scripts/runner-typescript-smoke.sh
./scripts/runner-java-smoke.sh
./scripts/runner-kotlin-smoke.sh
```

To add another runner language later, add a runner image under `runner/`, register a fixed harness in `backend/src/main/kotlin/com/aicodelearning/runner/RunnerRegistry.kt`, extend the practice contract tests, and add a smoke script that proves both passing and failing exercises without network access.

## Demo Users

Use the UI role selector or create a session with `POST /api/session`:

- `u-admin`
- `u-contributor`
- `u-reviewer`
- `u-learner`

Default local development password: `demo-password`. The Docker installer generates `APP_DEMO_PASSWORD` in `.env`; the installed UI asks for that password instead of embedding it in the static frontend bundle.

## AI Provider Setup

The installable app runs the Kotlin/Spring Boot backend, React frontend, PostgreSQL persistence, and local-first AI setup surfaces. The Node MVP remains as a parity oracle and now supports both deterministic local generation and real OpenAI-compatible provider calls.

Node MVP provider modes:

- `provider-local-mock` keeps deterministic generation for demos, stable tests, and parity checks.
- Non-mock providers call an OpenAI-compatible Responses API endpoint at `POST /v1/responses` with structured JSON schema output.

Register an OpenAI-compatible provider with `POST /api/providers`:

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

For `provider: "openai"`, `baseUrl` defaults to `https://api.openai.com`. Custom `baseUrl` values must use HTTPS. Loopback HTTP is allowed only for local fake-provider tests when `APP_ALLOW_INSECURE_PROVIDER_BASE_URL=1`.

Node MVP provider credentials are encrypted in the local JSON store and redacted from API responses. Set `APP_CREDENTIAL_ENCRYPTION_KEY` outside local development; production mode requires it.

## License

GNU Affero General Public License v3.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
