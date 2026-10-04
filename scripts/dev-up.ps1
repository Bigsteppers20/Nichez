# Start the local stack: Supabase (Postgres, Auth, Studio, ...) and MinIO.
# Run from anywhere; paths resolve relative to the repo root.
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
Push-Location $Root
try {
    if (-not (Test-Path 'infra/.env')) {
        throw 'infra/.env not found. Copy infra/.env.example to infra/.env and set the values.'
    }

    Write-Host '==> Starting Supabase'
    npx supabase start
    if ($LASTEXITCODE -ne 0) { throw 'supabase start failed' }

    Write-Host '==> Starting MinIO'
    docker compose -f infra/docker-compose.yml --env-file infra/.env up -d
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed' }

    Write-Host ''
    Write-Host '==> Supabase status'
    npx supabase status

    Write-Host ''
    Write-Host 'MinIO console: http://localhost:9001   (log in with MINIO_ROOT_USER from infra/.env)'
    Write-Host 'MinIO S3 API:  http://localhost:9000   (bucket: media)'
}
finally {
    Pop-Location
}
