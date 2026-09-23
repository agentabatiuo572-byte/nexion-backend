$ErrorActionPreference = "Stop"
$scripts = Resolve-Path (Join-Path $PSScriptRoot "..")
$runner = Join-Path $scripts "apply_startup_schema_migrations.ps1"
$temporaryRunner = Join-Path $scripts ("apply_startup_schema_migrations_contract_" + [guid]::NewGuid().ToString("N") + ".ps1")
$names = @("NEXION_DB_URL", "NEXION_DB_USERNAME", "NEXION_DB_PASSWORD",
  "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD")
$saved = @{}
foreach ($name in $names) {
  $saved[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

try {
  foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $null, "Process") }
  $env:NEXION_DB_URL = "jdbc:mysql://127.0.0.1:3306/migration_contract"
  $env:NEXION_DB_USERNAME = "contract"
  $env:NEXION_DB_PASSWORD = "contract-only"

  $excluded = "20260921_i5_local_sandbox_fixture_retirement.sql"
  $plan = & $runner -WhatIf
  if (-not $plan -or -not $plan.Migrations) { throw "Normal startup -WhatIf did not return a migration plan." }
  if ($plan.Migrations | Where-Object { [IO.Path]::GetFileName($_) -eq $excluded }) {
    throw "I5 fixture retirement must not run during normal startup."
  }

  $source = Get-Content -LiteralPath $runner -Raw
  $anchor = '  (Join-Path $root "scripts\migrations\20260920_i5_published_disclosure_provisioning.sql"),'
  $retired = '  (Join-Path $root "scripts\migrations\20260921_i5_local_sandbox_fixture_retirement.sql"),'
  if (-not $source.Contains($anchor)) { throw "Canonical I5 provisioning entry is missing." }
  [IO.File]::WriteAllText($temporaryRunner, $source.Replace($anchor, "$anchor`r`n$retired"))

  try {
    & $temporaryRunner -WhatIf | Out-Null
    throw "I5 isolated-rail migration was accepted."
  } catch {
    if ($_.Exception.Message -notmatch "Retired isolated-rail migrations cannot run during normal startup" -or
        $_.Exception.Message -notmatch [regex]::Escape($excluded)) {
      throw
    }
  }

  "startup I5 fixture retirement exclusion contract: PASS"
} finally {
  Remove-Item -LiteralPath $temporaryRunner -ErrorAction SilentlyContinue
  foreach ($name in $names) {
    [Environment]::SetEnvironmentVariable($name, $saved[$name], "Process")
  }
}
