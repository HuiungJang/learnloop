# LearnLoop Release Bundle

This bundle contains the runtime Compose file, install scripts, and Docker image archives needed to run LearnLoop without building from source.

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

The installer creates `.env` with generated local credentials and prints the generated demo password. Enter that password in the UI to use the seeded demo roles.

## Commands

```sh
./start.sh
./status.sh
./stop.sh
```

`./stop.sh` stops containers without deleting the PostgreSQL Docker volume.

The application stores learner attempts in PostgreSQL. Stopping and starting the bundle keeps that volume intact, so saved drafts and submitted attempts remain available after restart.

## Practice Workbench

The bundled app supports the LearnLoop practice workbench for TypeScript, Java, and Kotlin exercises. Learners can browse practices, edit files, save drafts, submit answers, and inspect answer diffs without any extra setup.

Sandbox execution is optional. The Run action is available only when the backend runtime can use a Docker CLI, reach a Docker daemon, and find the local language runner images. The default bundle does not mount host Docker access into the backend container, so runner health may report `missing` or `runner_unavailable`. In that state, reading, editing, local save, draft sync, submission, and review still work.

Runner limits in this version:

- Supported languages: TypeScript, Java, Kotlin
- No network access during code execution
- No package installation during a run
- Fixed backend-selected harness commands
- Bounded timeout, CPU, memory, process count, and stdout/stderr excerpts

## Configuration

Edit `.env` before running `./start.sh` to change the browser port or project name:

```env
AI_CODE_PROJECT_NAME=learnloop
AI_CODE_WEB_PORT=8080
APP_RUNNER_ENABLED=true
```

Use a different `AI_CODE_PROJECT_NAME` when running multiple isolated installations on the same machine.
Set `APP_RUNNER_ENABLED=false` to hide runner readiness warnings in environments that do not provide Docker-backed sandbox execution.

## License

LearnLoop is licensed under the GNU Affero General Public License, Version 3. See `LICENSE` and `NOTICE`.
