# Start the backend against the LOCAL stack (Supabase in Docker), whatever backend/.env says.
#
# backend/.env points at the hosted Supabase project. The settings below are set for this one run
# only and beat the file, so the file is never edited and a normal IDE run is unaffected.
# Objects go to the local Supabase Storage S3 endpoint, so no MinIO is needed.
# Stop the server with Ctrl+C. The environment of your terminal is restored when it stops.
#
#   ./scripts/run-backend-local.ps1
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
Push-Location $Root
$names = 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'AUTH_MODE', 'SUPABASE_JWKS_URI', 'SUPABASE_JWT_ISSUER',
    'SUPABASE_URL', 'SUPABASE_ANON_KEY', 'STORAGE_ENDPOINT', 'STORAGE_PUBLIC_ENDPOINT', 'STORAGE_REGION',
    'STORAGE_ACCESS_KEY', 'STORAGE_SECRET_KEY', 'STORAGE_BUCKET'
$before = @{}
foreach ($n in $names) { $before[$n] = [Environment]::GetEnvironmentVariable($n, 'Process') }

try {
    docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker is not running. Start Docker Desktop, wait until it says it is running, and try again.' }

    $listener = Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) { throw "Port 8080 is already in use (process $($listener.OwningProcess)). Stop that server first." }

    # Supabase: Postgres, Auth and Storage. Data is kept between runs.
    $statusText = npx supabase status -o json 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $statusText) {
        Write-Host '==> Starting Supabase (the first run downloads images and takes a few minutes)'
        npx supabase start
        if ($LASTEXITCODE -ne 0) { throw 'supabase start failed' }
        $statusText = npx supabase status -o json 2>$null
    }
    $s = ($statusText -join "`n") | ConvertFrom-Json

    # The private bucket that holds uploads. If it exists already the call is refused, which is fine.
    $auth = @{ apikey = $s.SERVICE_ROLE_KEY; Authorization = "Bearer $($s.SERVICE_ROLE_KEY)" }
    $made = Invoke-WebRequest -Method Post -Uri "$($s.API_URL)/storage/v1/bucket" -Headers $auth `
        -ContentType 'application/json' -Body '{"id":"media","name":"media","public":false}' `
        -SkipHttpErrorCheck -UseBasicParsing
    if ([int]$made.StatusCode -ge 200 -and [int]$made.StatusCode -lt 300) { Write-Host '==> Created the media bucket' }

    # These are the Supabase CLI's fixed local defaults and keys, not secrets.
    $env:DB_URL = 'jdbc:postgresql://127.0.0.1:54322/postgres'
    $env:DB_USERNAME = 'postgres'
    $env:DB_PASSWORD = 'postgres'
    $env:AUTH_MODE = 'JWKS'
    $env:SUPABASE_JWKS_URI = "$($s.API_URL)/auth/v1/.well-known/jwks.json"
    $env:SUPABASE_JWT_ISSUER = "$($s.API_URL)/auth/v1"
    $env:SUPABASE_URL = $s.API_URL
    $env:SUPABASE_ANON_KEY = $s.ANON_KEY
    $env:STORAGE_ENDPOINT = $s.STORAGE_S3_URL
    $env:STORAGE_PUBLIC_ENDPOINT = $s.STORAGE_S3_URL
    $env:STORAGE_REGION = $s.S3_PROTOCOL_REGION
    $env:STORAGE_ACCESS_KEY = $s.S3_PROTOCOL_ACCESS_KEY_ID
    $env:STORAGE_SECRET_KEY = $s.S3_PROTOCOL_ACCESS_KEY_SECRET
    $env:STORAGE_BUCKET = 'media'

    Write-Host ''
    Write-Host '==> Backend:  http://localhost:8080/api/v1/ping'
    Write-Host '==> Swagger:  http://localhost:8080/swagger-ui.html'
    Write-Host '==> Ctrl+C to stop'
    Write-Host ''
    Push-Location (Join-Path $Root 'backend')
    try { ./mvnw.cmd spring-boot:run }
    finally { Pop-Location }
}
finally {
    foreach ($n in $names) { [Environment]::SetEnvironmentVariable($n, $before[$n], 'Process') }
    Pop-Location
}
