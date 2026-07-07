#!/usr/bin/env bash
# Kills any process currently listening on port 8081 (the webApp dev server port).
#
# Usage: ./kill-port-8081.sh
#
# Useful when the webpack dev server fails to start with:
#   listen EADDRINUSE: address already in use :::8081

set -euo pipefail

PORT=8081

PIDS="$(lsof -ti tcp:"${PORT}" 2>/dev/null || true)"

if [ -z "${PIDS}" ]; then
  echo "No process is listening on port ${PORT}."
  exit 0
fi

echo "Killing process(es) listening on port ${PORT}: ${PIDS}"
kill -9 ${PIDS}
echo "Done."
