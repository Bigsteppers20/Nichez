# Regenerate api-spec/ (OpenAPI JSON and YAML, the endpoint index, the Postman collection) from the code.
#
# Boots the app in a test (embedded Postgres, or Testcontainers when Docker is up), saves springdoc's
# OpenAPI document, then scripts/export-api-spec.py cleans it and writes the other formats.
# Run it after any change to a controller or DTO, and commit the result.
#
#   ./scripts/export-api-spec.ps1
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$Out = Join-Path $Root 'api-spec'
Push-Location (Join-Path $Root 'backend')
try {
    # Quoted: PowerShell otherwise splits -D arguments at the dot.
    ./mvnw.cmd -q -B test '-Dtest=OpenApiExport' "-Dexport.api-spec=$Out" '-Dsurefire.failIfNoSpecifiedTests=false'
    if ($LASTEXITCODE -ne 0) { throw "The export test failed (exit $LASTEXITCODE)." }
} finally {
    Pop-Location
}
python (Join-Path $PSScriptRoot 'export-api-spec.py')
if ($LASTEXITCODE -ne 0) { throw "export-api-spec.py failed (exit $LASTEXITCODE)." }
