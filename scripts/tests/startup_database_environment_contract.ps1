$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$resolver = Join-Path $root "scripts\resolve_nexion_database_environment.ps1"
$runner = Join-Path $root "scripts\apply_startup_schema_migrations.ps1"
$startScript = Join-Path $root "scripts\start_ops_console_monolith.ps1"
$applicationYaml = Join-Path $root "src\main\resources\application.yml"
$readme = Join-Path $root "README.md"
$variableNames = @(
  "NEXION_DB_URL",
  "NEXION_DB_USERNAME",
  "NEXION_DB_PASSWORD",
  "SPRING_DATASOURCE_URL",
  "SPRING_DATASOURCE_USERNAME",
  "SPRING_DATASOURCE_PASSWORD"
)
$savedEnvironment = @{}
foreach ($name in $variableNames) {
  $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

function Clear-ContractDatabaseEnvironment {
  foreach ($name in $variableNames) {
    [Environment]::SetEnvironmentVariable($name, $null, "Process")
  }
}

function Set-ContractVariable {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string]$Value
  )
  [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

function Assert-Equal {
  param(
    [Parameter(Mandatory = $true)]$Actual,
    [Parameter(Mandatory = $true)]$Expected,
    [Parameter(Mandatory = $true)][string]$Message
  )
  if ($Actual -cne $Expected) {
    throw "$Message Expected '$Expected', got '$Actual'."
  }
}

function Assert-Throws {
  param(
    [Parameter(Mandatory = $true)][scriptblock]$Action,
    [Parameter(Mandatory = $true)][string]$MessagePattern
  )
  try {
    & $Action
  } catch {
    if ($_.Exception.Message -notmatch $MessagePattern) {
      throw "Expected failure matching '$MessagePattern', got '$($_.Exception.Message)'."
    }
    return
  }
  throw "Expected failure matching '$MessagePattern', but the action succeeded."
}

try {
  Clear-ContractDatabaseEnvironment
  Set-ContractVariable "NEXION_DB_URL" "jdbc:mysql://nexion-db:3307/nexion_contract"
  Set-ContractVariable "NEXION_DB_USERNAME" "nexion-user"
  Set-ContractVariable "NEXION_DB_PASSWORD" "nexion-pass"
  $resolved = & $resolver
  Assert-Equal $resolved.Source "NEXION_DB" "NEXION_DB must be authoritative."
  Assert-Equal $resolved.JdbcUrl "jdbc:mysql://nexion-db:3307/nexion_contract" "NEXION_DB_URL was not preserved."
  Assert-Equal $resolved.Username "nexion-user" "NEXION_DB_USERNAME was not preserved."
  Assert-Equal $resolved.Password "nexion-pass" "NEXION_DB_PASSWORD was not preserved."

  Clear-ContractDatabaseEnvironment
  Set-ContractVariable "SPRING_DATASOURCE_URL" "jdbc:mysql://legacy-db:3308/legacy_contract"
  Set-ContractVariable "SPRING_DATASOURCE_USERNAME" "legacy-user"
  Set-ContractVariable "SPRING_DATASOURCE_PASSWORD" "legacy-pass"
  $resolved = & $resolver
  Assert-Equal $resolved.Source "SPRING_DATASOURCE_COMPATIBILITY" "A complete legacy bundle must remain an atomic compatibility path."
  Assert-Equal $resolved.JdbcUrl "jdbc:mysql://legacy-db:3308/legacy_contract" "Legacy URL was not preserved atomically."
  Assert-Equal $resolved.Username "legacy-user" "Legacy username was not preserved atomically."
  Assert-Equal $resolved.Password "legacy-pass" "Legacy password was not preserved atomically."

  Clear-ContractDatabaseEnvironment
  Set-ContractVariable "NEXION_DB_URL" "jdbc:mysql://same-db:3306/same_contract"
  Set-ContractVariable "NEXION_DB_USERNAME" "same-user"
  Set-ContractVariable "NEXION_DB_PASSWORD" "same-pass"
  Set-ContractVariable "SPRING_DATASOURCE_URL" "jdbc:mysql://same-db:3306/same_contract"
  Set-ContractVariable "SPRING_DATASOURCE_USERNAME" "same-user"
  Set-ContractVariable "SPRING_DATASOURCE_PASSWORD" "same-pass"
  $resolved = & $resolver
  Assert-Equal $resolved.Source "NEXION_DB" "Equal dual bundles must resolve to the authoritative NEXION_DB bundle."

  foreach ($mismatch in @("URL", "USERNAME", "PASSWORD")) {
    Clear-ContractDatabaseEnvironment
    Set-ContractVariable "NEXION_DB_URL" "jdbc:mysql://authoritative-db:3306/nexion_contract"
    Set-ContractVariable "NEXION_DB_USERNAME" "authoritative-user"
    Set-ContractVariable "NEXION_DB_PASSWORD" "authoritative-pass"
    Set-ContractVariable "SPRING_DATASOURCE_URL" "jdbc:mysql://authoritative-db:3306/nexion_contract"
    Set-ContractVariable "SPRING_DATASOURCE_USERNAME" "authoritative-user"
    Set-ContractVariable "SPRING_DATASOURCE_PASSWORD" "authoritative-pass"
    Set-ContractVariable "SPRING_DATASOURCE_$mismatch" "conflicting-$($mismatch.ToLowerInvariant())"
    Assert-Throws { & $resolver } "conflicting database environment bundles"
  }

  Clear-ContractDatabaseEnvironment
  Set-ContractVariable "SPRING_DATASOURCE_PASSWORD" "legacy-pass-only"
  Assert-Throws { & $resolver } "complete bundle"

  Clear-ContractDatabaseEnvironment
  Set-ContractVariable "NEXION_DB_PASSWORD" "nexion-pass-only"
  Set-ContractVariable "SPRING_DATASOURCE_URL" "jdbc:mysql://legacy-db:3306/legacy_contract"
  Set-ContractVariable "SPRING_DATASOURCE_USERNAME" "legacy-user"
  Set-ContractVariable "SPRING_DATASOURCE_PASSWORD" "legacy-pass"
  Assert-Throws { & $resolver } "NEXION_DB_URL.*complete bundle"

  Clear-ContractDatabaseEnvironment
  Set-ContractVariable "NEXION_DB_PASSWORD" "nexion-defaults-pass"
  $resolved = & $resolver
  Assert-Equal $resolved.JdbcUrl "jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" "Resolver defaults must match application.yml."
  Assert-Equal $resolved.Username "root" "Resolver username default must match application.yml."

  $runnerSource = Get-Content -LiteralPath $runner -Raw
  foreach ($forbiddenParameter in @('[string]$JdbcUrl', '[string]$Username', '[string]$Password')) {
    if ($runnerSource.Contains($forbiddenParameter)) {
      throw "Migration runner must not accept database bundle fragments through command-line parameters: '$forbiddenParameter'."
    }
  }

  $start = Get-Content -LiteralPath $startScript -Raw
  foreach ($requiredFragment in @(
    "resolve_nexion_database_environment.ps1",
    "NEXION_DB_URL",
    "NEXION_DB_USERNAME",
    "NEXION_DB_PASSWORD",
    "Remove-Item Env:SPRING_DATASOURCE_URL",
    "Remove-Item Env:SPRING_DATASOURCE_USERNAME",
    "Remove-Item Env:SPRING_DATASOURCE_PASSWORD"
  )) {
    if (-not $start.Contains($requiredFragment)) {
      throw "Startup script is not normalizing the application environment: missing '$requiredFragment'."
    }
  }

  $yaml = Get-Content -LiteralPath $applicationYaml -Raw
  foreach ($requiredVariable in @("NEXION_DB_URL", "NEXION_DB_USERNAME", "NEXION_DB_PASSWORD")) {
    if (-not $yaml.Contains($requiredVariable)) {
      throw "application.yml is missing authoritative variable '$requiredVariable'."
    }
  }
  if ($yaml -match "SPRING_DATASOURCE_(URL|USERNAME|PASSWORD)") {
    throw "application.yml must not define the legacy datasource variable bundle."
  }

  $springFactoriesPath = Join-Path $root "src\main\resources\META-INF\spring.factories"
  if (-not (Test-Path -LiteralPath $springFactoriesPath)) {
    throw "Application startup database environment guard is not registered."
  }
  $springFactories = Get-Content -LiteralPath $springFactoriesPath -Raw
  if (-not $springFactories.Contains("ffdd.opsconsole.DatabaseEnvironmentPostProcessor")) {
    throw "Application startup is not enforcing the same authoritative database environment contract."
  }

  $documentation = Get-Content -LiteralPath $readme -Raw
  foreach ($requiredVariable in @("NEXION_DB_URL", "NEXION_DB_USERNAME", "NEXION_DB_PASSWORD")) {
    if (-not $documentation.Contains($requiredVariable)) {
      throw "README is missing authoritative variable '$requiredVariable'."
    }
  }
  if ($documentation -match 'Provide database credentials through\s+`SPRING_DATASOURCE_PASSWORD`') {
    throw "README still directs startup migrations to the legacy password variable."
  }

  $environmentExample = Get-Content -LiteralPath (Join-Path $root ".env.example") -Raw
  foreach ($requiredVariable in @("NEXION_DB_URL", "NEXION_DB_USERNAME", "NEXION_DB_PASSWORD")) {
    if (-not $environmentExample.Contains($requiredVariable)) {
      throw ".env.example is missing authoritative variable '$requiredVariable'."
    }
  }

  "startup database environment contract: PASS"
} finally {
  foreach ($name in $variableNames) {
    [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
  }
}
