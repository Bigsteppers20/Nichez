# Create the four local test users through the local Supabase Auth admin API.
#
#   creator@test.local  +1 202$1555-0101   (OTP 101010)
#   viewer@test.local   +1 202$1555-0102   (OTP 202020)
#   viewer2@test.local  +1 202$1555-0103   (OTP 303030)
#   admin@test.local    +1 202$1555-0104   (OTP 404040)  app_metadata.role = admin
#
# Phones are confirmed; the OTP codes come from [auth.sms.test_otp] in supabase/config.toml.
# Email+password sign-in uses $env:SEED_PASSWORD, default "LocalDev-Passw0rd!".
# Safe to re-run: existing users are updated in place.
#
# Refuses to run unless the Supabase API is on localhost / 127.0.0.1.
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
Push-Location $Root
try {
    # --- Read API URL and service_role key from the local stack -------------
    $statusLines = npx supabase status -o env 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'supabase status failed. Is the local stack running? (scripts/dev-up.ps1)' }

    $status = @{}
    foreach ($line in $statusLines) {
        if ($line -match '^([A-Z_]+)="?(.*?)"?$') { $status[$Matches[1]] = $Matches[2] }
    }
    $apiUrl = $status['API_URL']
    $serviceKey = $status['SERVICE_ROLE_KEY']
    if (-not $apiUrl -or -not $serviceKey) { throw 'Could not read API_URL / SERVICE_ROLE_KEY from supabase status.' }

    # --- Safety: local only -------------------------------------------------
    $apiHost = ([Uri]$apiUrl).Host
    if ($apiHost -ne 'localhost' -and $apiHost -ne '127.0.0.1') {
        throw "Refusing to run: API host is '$apiHost'. seed-users only runs against localhost or 127.0.0.1."
    }

    $password = if ($env:SEED_PASSWORD) { $env:SEED_PASSWORD } else { 'LocalDev-Passw0rd!' }
    $headers = @{ apikey = $serviceKey; Authorization = "Bearer $serviceKey" }
    $adminUsers = "$apiUrl/auth/v1/admin/users"

    $users = @(
        @{ email = 'creator@test.local'; phone = '12025550101'; admin = $false },
        @{ email = 'viewer@test.local';  phone = '12025550102'; admin = $false },
        @{ email = 'viewer2@test.local'; phone = '12025550103'; admin = $false },
        @{ email = 'admin@test.local';   phone = '12025550104'; admin = $true }
    )

    $existing = $null
    foreach ($u in $users) {
        $body = [ordered]@{
            email         = $u.email
            password      = $password
            email_confirm = $true
            phone         = $u.phone
            phone_confirm = $true
        }
        if ($u.admin) { $body.app_metadata = @{ role = 'admin' } }
        $json = $body | ConvertTo-Json -Depth 5

        try {
            Invoke-RestMethod -Method Post -Uri $adminUsers -Headers $headers `
                -ContentType 'application/json' -Body $json | Out-Null
            Write-Host "created  $($u.email)"
        }
        catch {
            $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
            if ($code -ne 422) { throw "Creating $($u.email) failed (HTTP $code): $($_.ErrorDetails.Message)" }

            # Already exists: find it and bring it back in line.
            if (-not $existing) {
                $existing = (Invoke-RestMethod -Uri "${adminUsers}?page=1&per_page=1000" -Headers $headers).users
            }
            $match = $existing | Where-Object { $_.email -eq $u.email } | Select-Object -First 1
            if (-not $match) { throw "$($u.email): create returned 422 but no user with that email exists (phone already used by another user?)" }

            Invoke-RestMethod -Method Put -Uri "$adminUsers/$($match.id)" -Headers $headers `
                -ContentType 'application/json' -Body $json | Out-Null
            Write-Host "updated  $($u.email)"
        }
    }

    Write-Host ''
    Write-Host 'Done. Sign in with email + password, or phone + the OTP from supabase/config.toml.'
}
finally {
    Pop-Location
}
