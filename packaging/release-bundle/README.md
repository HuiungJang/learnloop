# LearnLoop Release Bundle

This bundle contains the runtime Compose file, install scripts, and Docker image archives needed to run LearnLoop as a single-user local learning app without building from source.

## Requirements

- Docker Desktop or Docker Engine with Compose v2

## Install

On macOS, double-click:

```text
LearnLoop.app
```

The app opens Terminal, installs the bundled Docker images, starts the services, and opens the browser.

You can also install from a shell:

```sh
./install.sh
```

Open the printed URL after installation. The default is:

```text
http://localhost:8080
```

The installer creates `.env` with generated local credentials. The installed product path is a single local owner workspace rather than a role-switching demo.

## Commands

```sh
./start.sh
./status.sh
./stop.sh
./local-ai-companion.sh
```

`./stop.sh` stops containers without deleting the PostgreSQL Docker volume.

The application stores learner attempts in PostgreSQL. Stopping and starting the bundle keeps that volume intact, so saved drafts and submitted attempts remain available after restart.

## Local Product Boundary

The MVP is a personal local app:

- one local owner
- approved local repositories
- local AI provider setup
- collected evidence
- generated learning cards
- practice exercises

Non-goals for this MVP:

- hosted multi-user deployment
- admin dashboards
- reviewer queues
- organization membership
- team permissions
- remote collector pairing or sync

## Practice Workbench

The bundled app supports the LearnLoop practice workbench for TypeScript, Java, Kotlin, Swift, and Rust exercises. Learners can browse practices, edit files, save drafts, submit answers, and inspect answer diffs without installing every language runner first.

Sandbox execution is enabled by default when local Docker is available. The standard bundle is online-first for language runners: open the Runners page or use the install action from practice feedback to pull only the runners you want. Offline/full bundles may include `runner-images.manifest` and `images/runner-*.tar`; `./install.sh` imports any included runner images automatically.

Runner execution mounts the host Docker socket into the backend container and uses `.local-runner-workspaces/` as the shared workspace for nested runner containers. This gives the backend container access to the host Docker daemon; set `APP_RUNNER_ENABLED=false` before starting the app if you want to disable code execution.

Runner limits in this version:

- Supported languages: TypeScript, Java, Kotlin, Swift, Rust
- No network access during code execution
- No package installation during a run
- Fixed backend-selected harness commands
- Bounded timeout, CPU, memory, process count, and stdout/stderr excerpts

Swift is a large optional download, roughly 1.1GB compressed. Rust is smaller, roughly 290MB compressed. Both can be removed from the Runners page when they are no longer needed.

## Configuration

Edit `.env` before running `./start.sh` to change the browser port or project name:

```env
AI_CODE_PROJECT_NAME=learnloop
AI_CODE_WEB_PORT=8080
APP_RUNNER_ENABLED=true
APP_RUNNER_IMAGE_REGISTRY=ghcr.io/huiungjang/learnloop
APP_RUNNER_IMAGE_VERSION=0.1.0
```

Use a different `AI_CODE_PROJECT_NAME` when running multiple isolated installations on the same machine.
Set `APP_RUNNER_ENABLED=false` to hide runner readiness warnings in environments that do not provide Docker-backed sandbox execution.

## Local AI OAuth

The local AI setup screen can start Codex or Gemini OAuth through `./local-ai-companion.sh`. The companion listens only on loopback, runs the selected local login command, and never sends OAuth tokens to the LearnLoop server. Mutating companion endpoints require a random local API token stored outside repository directories with owner-only permissions; browser OAuth uses a short-lived OAuth-start token scoped to the installed app origin. If Node.js is not available on the host, install Node.js or set `NODE_BIN` before starting the companion.

If AI Setup shows `Companion offline` or `Local AI companion is not running`, check the bundle status and restart the companion from the bundle directory:

```sh
./status.sh
./local-ai-companion.sh start
```

`./status.sh` reports app readiness separately from local AI companion readiness. The app can remain usable while local AI is degraded; OAuth and automatic local collection need the companion to be running.

## Codex CLI Shim

Install the first automatic collection path with:

```sh
./local-ai-shim.sh codex install
```

The shim is written only to the LearnLoop-managed shim directory. It records the original `codex` path and hash, preserves stdin/stdout/stderr/exit behavior, and emits bounded best-effort collection events to the local companion. Run `./local-ai-shim.sh codex status`, `repair`, or `uninstall` to inspect or change the shim without overwriting the real Codex binary.

## License

LearnLoop is licensed under the GNU Affero General Public License, Version 3. See `LICENSE` and `NOTICE`.
