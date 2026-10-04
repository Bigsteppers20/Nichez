# Stop the local stack. Data is kept (Supabase volumes and the minio-data volume).
# To wipe the database instead, see docs/LOCAL_DEV.md ("Reset everything").
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
Push-Location $Root
try {
    Write-Host '==> Stopping MinIO'
    if (Test-Path 'infra/.env') {
        docker compose -f infra/docker-compose.yml --env-file infra/.env down
    } else {
        docker compose -f infra/docker-compose.yml down
    }
    if ($LASTEXITCODE -ne 0) { throw 'docker compose down failed' }

    Write-Host '==> Stopping Supabase'
    npx supabase stop
    if ($LASTEXITCODE -ne 0) { throw 'supabase stop failed' }

    Write-Host ''
    Write-Host '==> Supabase status'
    npx supabase status

    Write-Host ''
    Write-Host 'MinIO console (stopped): http://localhost:9001'
}
finally {
    Pop-Location
}
