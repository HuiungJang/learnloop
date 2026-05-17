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

## Demo Users

Use the UI role selector or send `x-user-id` headers:

- `u-admin`
- `u-contributor`
- `u-reviewer`
- `u-learner`

Default local password: `demo-password`. Set `APP_DEMO_PASSWORD` for non-local environments.

## Scope

This MVP is intentionally dependency-free. It uses a JSON file store and deterministic local pattern generation instead of real external AI calls. Provider credentials are represented by non-reversible references only.
