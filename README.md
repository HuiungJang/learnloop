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
```

To change the browser port, edit `AI_CODE_WEB_PORT` in `.env`, then restart:

```sh
./scripts/start.sh
```

Data is stored in the `learnloop_install-postgres-data` Docker volume. `./scripts/stop.sh` stops containers without deleting data.

### First User Flow

1. Sign up or log in.
2. Choose the AI provider you want to use on first login.
3. Codex and Gemini support OAuth or API key setup; Claude uses API key setup.
4. Save local AI settings, then run the learning flow.
5. Review generated pattern cards and exercises, then turn them into reusable learning assets.

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

Release-bundle installation does not build from source. It loads the packaged Docker images, starts the stack, and prints the generated demo password.

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

## Demo Users

Use the UI role selector or create a session with `POST /api/session`:

- `u-admin`
- `u-contributor`
- `u-reviewer`
- `u-learner`

Default local development password: `demo-password`. The Docker installer generates `APP_DEMO_PASSWORD` in `.env`; the installed UI asks for that password instead of embedding it in the static frontend bundle.

## Scope

The Node MVP remains as a parity oracle. The installable app runs the Kotlin/Spring Boot backend, React frontend, PostgreSQL persistence, and deterministic local pattern generation. Provider credentials are stored on the server only as non-reversible references.

## License

GNU Affero General Public License v3.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
