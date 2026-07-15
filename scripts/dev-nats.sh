#!/usr/bin/env bash
#
# Run the NATS broker this project's NATS/JetStream demos need, in a container,
# using Apple's `container` runtime (https://github.com/apple/container).
#
# The app itself still runs on the host via `sbt run` -- only the broker is
# containerised, which is the part the README otherwise asks you to `brew
# install`. JetStream is enabled (-js) because the /jetstream-chat demo needs it.
#
#   ./scripts/dev-nats.sh up       # start broker (idempotent)
#   ./scripts/dev-nats.sh down     # stop and remove it
#   ./scripts/dev-nats.sh status   # is it up, and is JetStream on?
#   ./scripts/dev-nats.sh logs     # follow broker logs
#   ./scripts/dev-nats.sh run      # up, then `sbt run` wired to it
#
# The container is named `nats-hotwire`, so it shows up under that name in
# Davit (https://github.com/wouterdebie/davit) -- Davit is a GUI over this same
# daemon, so anything started here appears there and vice versa.

set -euo pipefail

NAME="nats-hotwire"
IMAGE="docker.io/library/nats:latest"
CLIENT_PORT=4222
MONITOR_PORT=8222
NATS_URL="nats://localhost:${CLIENT_PORT}"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

require_container_cli() {
  command -v container >/dev/null 2>&1 || die \
    "the 'container' CLI is not installed. See https://github.com/apple/container (brew install container)"
}

# The daemon is not running by default after a reboot, and every other
# subcommand fails with an opaque XPC error if it is down -- so check first.
ensure_daemon() {
  if ! container system status >/dev/null 2>&1; then
    echo "==> starting container system service"
    container system start
  fi
}

is_running() {
  container ls --format json 2>/dev/null | grep -q "\"${NAME}\""
}

exists() {
  container ls --all --format json 2>/dev/null | grep -q "\"${NAME}\""
}

up() {
  require_container_cli
  ensure_daemon

  if is_running; then
    echo "==> ${NAME} already running on ${NATS_URL}"
    return 0
  fi

  # A stopped container of the same name would make `run` fail on the name
  # collision, so clear it out first.
  if exists; then
    echo "==> removing stopped ${NAME}"
    container rm "${NAME}" >/dev/null
  fi

  echo "==> starting ${NAME} (${IMAGE}, JetStream enabled)"
  container run -d \
    --name "${NAME}" \
    -p "${CLIENT_PORT}:${CLIENT_PORT}" \
    -p "${MONITOR_PORT}:${MONITOR_PORT}" \
    "${IMAGE}" -js >/dev/null

  wait_ready
}

# `container run -d` returns as soon as the VM is up, which is before NATS is
# accepting clients. Poll the client port rather than sleeping a fixed guess.
wait_ready() {
  echo -n "==> waiting for NATS on ${NATS_URL} "
  for _ in $(seq 1 60); do
    if nc -z -w1 localhost "${CLIENT_PORT}" 2>/dev/null; then
      echo
      echo "==> ready: ${NATS_URL}"
      return 0
    fi
    echo -n .
    sleep 1
  done
  echo
  die "NATS did not come up within 60s. Try: $0 logs"
}

down() {
  require_container_cli
  ensure_daemon
  if exists; then
    echo "==> stopping and removing ${NAME}"
    container stop "${NAME}" >/dev/null 2>&1 || true
    container rm "${NAME}" >/dev/null 2>&1 || true
  else
    echo "==> ${NAME} not present"
  fi
}

status() {
  require_container_cli
  ensure_daemon
  if exists; then
    container ls --all 2>/dev/null | grep -E "(^ID|${NAME})"
  else
    echo "==> ${NAME} not present"
  fi

  # Ask NATS itself rather than trusting that the container is up: the INFO
  # line it sends on connect reports whether JetStream is actually enabled.
  if nc -z -w1 localhost "${CLIENT_PORT}" 2>/dev/null; then
    local info
    info="$( (printf ''; sleep 1) | nc -w2 localhost "${CLIENT_PORT}" 2>/dev/null | head -c 400 || true)"
    case "${info}" in
      *'"jetstream":true'*) echo "==> ${NATS_URL} reachable, JetStream: enabled" ;;
      *'"jetstream":false'*) echo "==> ${NATS_URL} reachable, JetStream: DISABLED (needs -js)" ;;
      *) echo "==> ${NATS_URL} reachable (no INFO read)" ;;
    esac
  else
    echo "==> ${NATS_URL} not reachable"
  fi
}

logs() {
  require_container_cli
  ensure_daemon
  container logs -f "${NAME}"
}

# Wire the host-side app to the containerised broker. PORT is overridable so
# you can run a second node for the fan-out demo:
#   PORT=8081 ./scripts/dev-nats.sh run
run_app() {
  up
  local port="${PORT:-8080}"
  echo "==> NATS_URL=${NATS_URL} PORT=${port} sbt run"
  echo "==> then open http://localhost:${port}/chat/lobby"
  cd "$(dirname "$0")/.."
  NATS_URL="${NATS_URL}" PORT="${port}" sbt run
}

usage() {
  sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
}

case "${1:-}" in
  up)     up ;;
  down)   down ;;
  status) status ;;
  logs)   logs ;;
  run)    run_app ;;
  ""|-h|--help|help) usage ;;
  *) die "unknown command: $1 (try: up, down, status, logs, run)" ;;
esac
