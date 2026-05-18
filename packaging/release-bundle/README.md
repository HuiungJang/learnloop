# AI Code Learning Platform Release Bundle

This bundle contains the runtime Compose file, install scripts, and Docker image archives needed to run AI Code Learning Platform without building from source.

## Requirements

- Docker Desktop or Docker Engine with Compose v2

## Install

On macOS, double-click:

```text
AI Code Learning Platform.app
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

## Configuration

Edit `.env` before running `./start.sh` to change the browser port or project name:

```env
AI_CODE_PROJECT_NAME=ai-code-learning-platform
AI_CODE_WEB_PORT=8080
```

Use a different `AI_CODE_PROJECT_NAME` when running multiple isolated installations on the same machine.
