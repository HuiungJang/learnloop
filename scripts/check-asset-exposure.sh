#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

FRONTEND_DIST=${FRONTEND_DIST:-frontend/dist}
RELEASE_SOURCE=${RELEASE_SOURCE:-packaging/release-bundle}
RELEASE_DIST=${RELEASE_DIST:-dist/release}

if [ ! -d "$FRONTEND_DIST" ]; then
  echo "frontend build output is missing: $FRONTEND_DIST" >&2
  exit 1
fi

if ! rg -q "Content-Security-Policy.*worker-src 'self' blob:.*connect-src 'self' http://127\\.0\\.0\\.1:4317 http://localhost:4317" frontend/nginx.conf; then
  echo "frontend CSP must allow same-origin Monaco workers, same-origin API calls, and the local OAuth companion" >&2
  exit 1
fi

frontend_patterns='ACL_INTERNAL_PATTERN_PROMPT_V1_DO_NOT_EXPOSE|phase48-local-only|local-only-key|APP_RUNNER_TOKEN|APP_DATABASE_PASSWORD|APP_DEMO_PASSWORD|Trim input, split separators|Gemini---OAuth|hidden_test|source_link_ids_json|/var/run/docker.sock'
if rg -a -n "$frontend_patterns" "$FRONTEND_DIST"; then
  echo "frontend asset exposure check failed" >&2
  exit 1
fi

if [ -f .env ]; then
  sensitive_values=$(awk -F= '/^(APP_DATABASE_PASSWORD|APP_DEMO_PASSWORD|APP_RUNNER_TOKEN)=/ { if (length($2) >= 8) print $2 }' .env | sed '/^$/d')
  if [ -n "$sensitive_values" ]; then
    while IFS= read -r value; do
      [ -n "$value" ] || continue
      if rg -a -n --fixed-strings "$value" "$FRONTEND_DIST" "$RELEASE_SOURCE" "$RELEASE_DIST" --glob '!*.dmg' --glob '!*.tar.gz' --glob '!*.sha256' 2>/dev/null; then
        echo "generated secret value leaked into assets or release source" >&2
        exit 1
      fi
    done <<EOF
$sensitive_values
EOF
  fi
fi

if [ -d "$RELEASE_DIST" ]; then
  for archive in "$RELEASE_DIST"/*.tar.gz; do
    [ -f "$archive" ] || continue
    if tar -tzf "$archive" | rg '(^|/)\.env$'; then
      echo "release archive contains generated .env file: $archive" >&2
      exit 1
    fi
    if tar -tzf "$archive" | rg 'e2e-installed|perf-measure|backend/src/test|frontend/src|\.idea'; then
      echo "release archive contains test/source-only files: $archive" >&2
      exit 1
    fi
  done
fi

echo "asset exposure checks passed"
