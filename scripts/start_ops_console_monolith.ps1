param(
  [string]$Maven = "D:\software\apache-maven-3.9.9\bin\mvn.cmd",
  [int]$Port = 8110,
  [string]$LogDir = "",
  [string]$MySql = "D:\software\MySQL\MySQL Server 8.0\bin\mysql.exe",
  [bool]$TemporarySuperadminMfaBypass = $false,
  [Nullable[bool]]$EnableLocalNovaAi = $null,
  [string]$NovaAiModel = "gemma4-e4b-ctx32k:latest",
  [string]$AcceptanceRunId = $env:NEXION_ACCEPTANCE_RUN_ID,
  [ValidateSet("dev", "prod")]
  [string]$SpringProfile = "dev"
)

$ErrorActionPreference = "Stop"
$localNovaAiEnabled = if ($null -eq $EnableLocalNovaAi) { $SpringProfile -eq "dev" } else { [bool]$EnableLocalNovaAi }
$acceptanceRunIdValue = if (-not [string]::IsNullOrWhiteSpace($AcceptanceRunId)) {
  $AcceptanceRunId.Trim()
} else {
  ""
}
if ($SpringProfile -eq "dev" -and -not [string]::IsNullOrWhiteSpace($acceptanceRunIdValue) -and $acceptanceRunIdValue -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{7,95}$') {
  throw "AcceptanceRunId must contain 8-96 safe characters"
}
if ($SpringProfile -eq "prod" -and -not [string]::IsNullOrWhiteSpace($acceptanceRunIdValue)) {
  throw "AcceptanceRunId is allowed only for the dev profile"
}

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$databaseEnvironment = & (Join-Path $PSScriptRoot "resolve_nexion_database_environment.ps1") `
  -RequireExplicit:($SpringProfile -eq "prod")
$databaseVariableNames = @(
  "NEXION_DB_URL",
  "NEXION_DB_USERNAME",
  "NEXION_DB_PASSWORD",
  "SPRING_DATASOURCE_URL",
  "SPRING_DATASOURCE_USERNAME",
  "SPRING_DATASOURCE_PASSWORD"
)
$previousDatabaseEnvironment = @{}
foreach ($name in $databaseVariableNames) {
  $previousDatabaseEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}
$mfaBypassValue = & (Join-Path $PSScriptRoot "resolve_ops_console_mfa_bypass.ps1") `
  -Enabled $TemporarySuperadminMfaBypass

$previousFinanceDataKey = [Environment]::GetEnvironmentVariable("NEXION_FINANCE_DATA_KEY", "Process")
$userFinanceDataKey = [Environment]::GetEnvironmentVariable("NEXION_FINANCE_DATA_KEY", "User")
$financeDataKey = if ($SpringProfile -eq "dev" -and -not [string]::IsNullOrWhiteSpace($userFinanceDataKey)) {
  $userFinanceDataKey
} else {
  $previousFinanceDataKey
}
if ([string]::IsNullOrWhiteSpace($financeDataKey)) {
  throw "NEXION_FINANCE_DATA_KEY is required for backend startup"
}
if ($financeDataKey.Trim().Length -lt 32) {
  throw "NEXION_FINANCE_DATA_KEY must contain at least 32 characters"
}

if (-not (Test-Path $Maven)) {
  throw "Maven executable not found: $Maven"
}

try {
  $env:NEXION_DB_URL = $databaseEnvironment.JdbcUrl
  $env:NEXION_DB_USERNAME = $databaseEnvironment.Username
  $env:NEXION_DB_PASSWORD = $databaseEnvironment.Password
  Remove-Item Env:SPRING_DATASOURCE_URL -ErrorAction SilentlyContinue
  Remove-Item Env:SPRING_DATASOURCE_USERNAME -ErrorAction SilentlyContinue
  Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue

  & (Join-Path $PSScriptRoot "apply_startup_schema_migrations.ps1") -MySql $MySql -Confirm:$false

  if ([string]::IsNullOrWhiteSpace($LogDir)) {
    $LogDir = Join-Path $root "logs"
  }

  New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

  $outLog = Join-Path $LogDir "ops-console-monolith.out.log"
  $errLog = Join-Path $LogDir "ops-console-monolith.err.log"

  $commands = @(
    ('cd /d "{0}"' -f $root.Path),
    ('set "SERVER_PORT={0}"' -f $Port),
    ('set "SPRING_PROFILES_ACTIVE={0}"' -f $SpringProfile),
    ('set "NEXION_ACCEPTANCE_RUN_ID={0}"' -f $acceptanceRunIdValue),
    'set "NEXION_ARCHITECTURE_DISTRIBUTED_RUNTIME_ENABLED=false"',
    ('set "NEXION_NOVA_AI_MODE={0}"' -f $(if ($localNovaAiEnabled) { "OLLAMA_LOCAL" } else { "DISABLED" })),
    'set "NEXION_NOVA_AI_RAG_BASE_URL=http://[::1]:8010"',
    'set "NEXION_NOVA_AI_RAG_COLLECTION=customer_support_knowledge_prd_v2_20260814"',
    ('set "NEXION_NOVA_AI_MODEL={0}"' -f $NovaAiModel),
    ('set "NEXION_ADMIN_MFA_TEMPORARY_SUPERADMIN_BYPASS={0}"' -f $mfaBypassValue),
    ('call "{0}" spring-boot:run' -f $Maven)
  )

  $inner = ($commands -join " && ") + (' > "{0}" 2> "{1}"' -f $outLog, $errLog)
  [Environment]::SetEnvironmentVariable("NEXION_FINANCE_DATA_KEY", $financeDataKey, "Process")
  $process = Start-Process -FilePath "cmd.exe" -ArgumentList "/c", $inner `
    -WindowStyle Hidden -PassThru

  [pscustomobject]@{
    Service = "nexion-backend"
    Port = $Port
    ProcessId = $process.Id
    Stdout = $outLog
    Stderr = $errLog
  }
} finally {
  foreach ($name in $databaseVariableNames) {
    [Environment]::SetEnvironmentVariable($name, $previousDatabaseEnvironment[$name], "Process")
  }
  [Environment]::SetEnvironmentVariable("NEXION_FINANCE_DATA_KEY", $previousFinanceDataKey, "Process")
}
