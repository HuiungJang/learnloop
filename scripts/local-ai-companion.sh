#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd -P)
cd "$ROOT_DIR"

. ./scripts/env.sh

PORT="${LEARNLOOP_LOCAL_AI_PORT:-4317}"
HOST="${LEARNLOOP_LOCAL_AI_HOST:-127.0.0.1}"
PID_FILE=".local-ai-companion.pid"
LOG_FILE=".local-ai-companion.log"

case "$HOST" in
  127.0.0.1 | localhost | ::1) ;;
  *)
    echo "LEARNLOOP_LOCAL_AI_HOST must be 127.0.0.1, localhost, or ::1." >&2
    exit 1
    ;;
esac

host_url() {
  if [ "$HOST" = "::1" ]; then
    printf '[::1]'
  else
    printf '%s' "$HOST"
  fi
}

BASE_URL="http://$(host_url):$PORT"

is_running() {
  pid=$(pid_value || true)
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

listener_pid() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | head -n 1
  fi
}

healthcheck() {
  curl -fsS "$BASE_URL/health" >/dev/null 2>&1
}

status_payload() {
  curl -fsS "$BASE_URL/status" 2>/dev/null || true
}

pid_value() {
  if [ ! -f "$PID_FILE" ]; then
    return 1
  fi
  pid=$(cat "$PID_FILE" 2>/dev/null || true)
  case "$pid" in
    '' | *[!0-9]*) return 1 ;;
    *) printf '%s\n' "$pid" ;;
  esac
}

clear_stale_pid() {
  if [ -f "$PID_FILE" ] && ! is_running; then
    rm -f "$PID_FILE"
  fi
}

start_process() {
  STARTED_PID=""
  STARTED_PID=$("$NODE_BIN" scripts/local-ai-companion-launcher.mjs scripts/local-ai-companion.mjs "$LOG_FILE")
  printf '%s\n' "$STARTED_PID" > "$PID_FILE"
}

start() {
  if ! command -v "$NODE_BIN" >/dev/null 2>&1; then
    echo "Node.js is required for the local AI companion. Install Node.js or set NODE_BIN." >&2
    return 1
  fi
  if healthcheck; then
    if ! is_running; then
      current_pid=$(listener_pid || true)
      if [ -n "$current_pid" ]; then
        printf '%s\n' "$current_pid" > "$PID_FILE"
      else
        rm -f "$PID_FILE"
      fi
    fi
    echo "LearnLoop local AI companion is already running on $BASE_URL"
    return
  fi
  clear_stale_pid

  start_process

  i=0
  while [ "$i" -lt 20 ]; do
    if healthcheck; then
      current_pid=$(listener_pid || true)
      if [ -n "$current_pid" ]; then
        printf '%s\n' "$current_pid" > "$PID_FILE"
      elif [ -n "${STARTED_PID:-}" ]; then
        printf '%s\n' "$STARTED_PID" > "$PID_FILE"
      fi
      echo "LearnLoop local AI companion started on $BASE_URL"
      return
    fi
    i=$((i + 1))
    sleep 0.25
  done

  echo "Local AI companion did not become healthy. Check $LOG_FILE" >&2
  return 1
}

stop() {
  if is_running; then
    pid=$(pid_value)
    kill "$pid" 2>/dev/null || true
    wait_for_stop "$pid"
    rm -f "$PID_FILE"
    echo "LearnLoop local AI companion stopped."
  else
    current_pid=$(listener_pid || true)
    if [ -n "$current_pid" ]; then
      kill "$current_pid" 2>/dev/null || true
      wait_for_stop "$current_pid"
      rm -f "$PID_FILE"
      echo "LearnLoop local AI companion stopped."
      return
    fi
    rm -f "$PID_FILE"
    echo "LearnLoop local AI companion is not running."
  fi
}

wait_for_stop() {
  pid="$1"
  i=0
  while [ "$i" -lt 20 ]; do
    if ! kill -0 "$pid" 2>/dev/null; then
      return
    fi
    i=$((i + 1))
    sleep 0.1
  done
}

status() {
  if healthcheck; then
    if ! is_running; then
      current_pid=$(listener_pid || true)
      if [ -n "$current_pid" ]; then
        printf '%s\n' "$current_pid" > "$PID_FILE"
      else
        rm -f "$PID_FILE"
      fi
    fi
    echo "LearnLoop local AI companion is running on $BASE_URL"
    payload=$(status_payload)
    if [ -n "$payload" ]; then
      echo "$payload"
    fi
  else
    clear_stale_pid
    echo "LearnLoop local AI companion is not running. Start it with ./scripts/local-ai-companion.sh start."
    return 1
  fi
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  restart)
    stop
    start
    ;;
  status) status ;;
  *)
    echo "Usage: $0 [start|stop|restart|status]" >&2
    exit 2
    ;;
esac
