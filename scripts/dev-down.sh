#!/usr/bin/env bash
# Stop the local stack. Data is kept (Supabase volumes and the minio-data volume).
# To wipe the database instead, see docs/LOCAL_DEV.md ("Reset everything").
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "==> Stopping MinIO"
if [ -f infra/.env ]; then
  docker compose -f infra/docker-compose.yml --env-file infra/.env down
else
  docker compose -f infra/docker-compose.yml down
fi

echo "==> Stopping Supabase"
npx supabase stop

echo
echo "==> Supabase status"
npx supabase status || true

echo
echo "MinIO console (stopped): http://localhost:9001"
