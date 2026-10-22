#!/usr/bin/env bash
# Starts the vendor mock (:8081) and the Quote App (:8080) in the background with the `local` profile.
#
# Builds the jars first if they are missing (or always, with --build), starts both services, waits until both
# report healthy, and prints where they run. Logs and process ids go to .run/ (git-ignored).
# Environment variables are passed through, e.g. MOCK_CHAOS_FAILURE_RATE=1 MOCK_CHAOS_SLOW_RATE=0 scripts/start.sh
# Stop with scripts/stop.sh.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
run_dir="$root/.run"
health_timeout_seconds=60
# The mock starts first: the app doesn't need it to start, but a quote needs it to be up.
services=("vendor-mock:8081" "quote-app:8080")

build=false
if [[ "${1:-}" == "--build" ]]; then
  build=true
fi

find_jar() {
  local name="$1"
  ls "$root/$name/target/$name-"*.jar 2>/dev/null | grep -v '\.original$' | head -n 1 || true
}

is_running() {
  local pid_file="$run_dir/$1.pid"
  [[ -f "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null
}

is_healthy() {
  curl -sf -o /dev/null "http://localhost:$1/actuator/health"
}

mkdir -p "$run_dir"

for service in "${services[@]}"; do
  if [[ -z "$(find_jar "${service%%:*}")" ]]; then
    build=true
  fi
done
if $build; then
  echo "Building jars (tests skipped)..."
  (cd "$root" && ./mvnw -B -q package -DskipTests)
fi

for service in "${services[@]}"; do
  name="${service%%:*}"
  port="${service##*:}"
  if is_running "$name"; then
    echo "$name is already running (pid $(cat "$run_dir/$name.pid"))."
    continue
  fi
  if curl -s -o /dev/null "http://localhost:$port/"; then
    echo "Port $port is already in use by another process, so $name can't start." >&2
    exit 1
  fi
  nohup java -jar "$(find_jar "$name")" --spring.profiles.active=local > "$run_dir/$name.log" 2>&1 &
  echo $! > "$run_dir/$name.pid"
  echo "Started $name (pid $!), logging to .run/$name.log"
done

for service in "${services[@]}"; do
  name="${service%%:*}"
  port="${service##*:}"
  for ((waited = 0; ; waited++)); do
    if is_healthy "$port"; then
      break
    fi
    if ! is_running "$name"; then
      tail -n 30 "$run_dir/$name.log"
      echo "$name stopped during startup; the end of its log is above." >&2
      exit 1
    fi
    if ((waited >= health_timeout_seconds)); then
      echo "$name did not become healthy within ${health_timeout_seconds}s; see .run/$name.log" >&2
      exit 1
    fi
    sleep 1
  done
done

echo
echo "Both services are up:"
echo "  Quote App    http://localhost:8080   <- open this in a browser"
echo "  Vendor mock  http://localhost:8081   (POST /v1/commission-quotes)"
echo "Stop them with scripts/stop.sh"
