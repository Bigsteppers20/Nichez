#!/usr/bin/env bash
# Start the local stack: Supabase (Postgres, Auth, Studio, ...) and MinIO.
# Run from anywhere; paths resolve relative to the repo root.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ ! -f infra/.env ]; then
  echo "infra/.env not found. Copy infra/.env.example to infra/.env and set the values." >&2
  exit 1
fi

echo "==> Starting Supabase"
npx supabase start

echo "==> Starting MinIO"
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d

echo
echo "==> Supabase status"
npx supabase status

echo
echo "MinIO console: http://localhost:9001   (log in with MINIO_ROOT_USER from infra/.env)"
echo "MinIO S3 API:  http://localhost:9000   (bucket: media)"
