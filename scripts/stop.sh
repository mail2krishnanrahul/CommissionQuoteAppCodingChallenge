#!/usr/bin/env bash
# Stops the services started by scripts/start.sh, using the process ids it saved in .run/.
set -euo pipefail

run_dir="$(cd "$(dirname "$0")/.." && pwd)/.run"
grace_seconds=10

# The app first, so it isn't left calling a vendor that has gone away.
for name in quote-app vendor-mock; do
  pid_file="$run_dir/$name.pid"
  if [[ ! -f "$pid_file" ]]; then
    echo "$name is not running."
    continue
  fi
  pid="$(cat "$pid_file")"
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    # Give Spring a moment to shut down cleanly before forcing it.
    for ((i = 0; i < grace_seconds; i++)); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$pid" 2>/dev/null || true
    echo "Stopped $name (pid $pid)."
  else
    echo "$name is not running (removing stale pid file)."
  fi
  rm -f "$pid_file"
done
