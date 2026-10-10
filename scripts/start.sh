#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .env ]]; then
  umask 077
  printf 'DATABASE_PASSWORD=%s\nAPP_PORT=8080\nAPP_ALLOWED_ORIGIN=http://localhost:8080\n' "$(openssl rand -hex 32)" > .env
fi
docker compose up --build -d
