# LearnLoop MVP

Local MVP for turning AI-assisted coding evidence into reviewed learning assets.

## Install

The easiest way to run the platform is the Docker Compose installer. It builds the Spring Boot API, builds the React app, starts PostgreSQL, keeps data in a Docker volume, and exposes one browser URL.

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

Installed app commands:

```sh
./scripts/start.sh
./scripts/status.sh
./scripts/stop.sh
```

To change the browser port, edit `AI_CODE_WEB_PORT` in `.env`, then run:

```sh
./scripts/start.sh
```

Data is stored in the `learnloop_install-postgres-data` Docker volume. `./scripts/stop.sh` stops containers without deleting data.

## Release Bundle

Build a distributable `.tar.gz` package for the current machine architecture:

```sh
./scripts/package-release.sh
```

The archive is written to `dist/release/` and contains:

- runtime `docker-compose.yml`
- `install.sh`, `start.sh`, `status.sh`, `stop.sh`
- generated release metadata
- backend, web, and PostgreSQL Docker image archives

Install from the release bundle:

```sh
tar -xzf dist/release/learnloop-0.1.0-*.tar.gz
cd learnloop-0.1.0-*
./install.sh
```

Release-bundle installation does not build from source. It loads the packaged Docker images, starts the stack, and prints the generated demo password.

## Runtime

The system Node installation on this machine may be broken. The scripts use the bundled Codex Node runtime automatically:

```sh
./scripts/test.sh
./scripts/dev.sh
./scripts/smoke.sh
```

Default dev URL:

```text
http://localhost:4173
```

## Split Stack Preview

For development, run the Spring Boot and React services directly:

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

Create a local demo session:

```sh
curl -X POST http://localhost:8080/api/session \
  -H 'content-type: application/json' \
  -d '{"email":"admin@example.com","password":"demo-password"}'
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

The Node MVP remains as a parity oracle. The installable app runs the Kotlin/Spring Boot backend, React frontend, PostgreSQL persistence, and deterministic local pattern generation. Provider credentials are represented by non-reversible references only.

## License

GNU Affero General Public License v3.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
