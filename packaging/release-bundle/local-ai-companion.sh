#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd -P)
cd "$ROOT_DIR"

NODE_BIN="${NODE_BIN:-node}"
PORT="${LEARNLOOP_LOCAL_AI_PORT:-4317}"
PID_FILE=".local-ai-companion.pid"
LOG_FILE=".local-ai-companion.log"

is_running() {
  [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

healthcheck() {
  curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null 2>&1
}

start() {
  if ! command -v "$NODE_BIN" >/dev/null 2>&1; then
    echo "Node.js is required for the local AI companion. Install Node.js or set NODE_BIN." >&2
    return 1
  fi
  if healthcheck; then
    if ! is_running; then
      rm -f "$PID_FILE"
    fi
    echo "LearnLoop local AI companion is already running on http://127.0.0.1:$PORT"
    return
  fi
  rm -f "$PID_FILE"
  nohup "$NODE_BIN" local-ai-companion.mjs >"$LOG_FILE" 2>&1 &
  echo "$!" > "$PID_FILE"

  i=0
  while [ "$i" -lt 20 ]; do
    if healthcheck; then
      echo "LearnLoop local AI companion started on http://127.0.0.1:$PORT"
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
    kill "$(cat "$PID_FILE")" 2>/dev/null || true
    rm -f "$PID_FILE"
    echo "LearnLoop local AI companion stopped."
  else
    rm -f "$PID_FILE"
    echo "LearnLoop local AI companion is not running."
  fi
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  restart)
    stop
    start
    ;;
  status)
    if healthcheck; then
      echo "LearnLoop local AI companion is running on http://127.0.0.1:$PORT"
    else
      echo "LearnLoop local AI companion is not running."
      exit 1
    fi
    ;;
  *)
    echo "Usage: $0 [start|stop|restart|status]" >&2
    exit 2
    ;;
esac
