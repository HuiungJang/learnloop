# AI Code Learning Platform MVP

Local MVP for turning AI-assisted coding evidence into reviewed learning assets.

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

The Spring Boot and React migration lives beside the Node MVP until parity is complete.

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

Default local password: `demo-password`. Set `APP_DEMO_PASSWORD` for non-local environments.

## Scope

This MVP is intentionally dependency-free. It uses a JSON file store and deterministic local pattern generation instead of real external AI calls. Provider credentials are represented by non-reversible references only.
