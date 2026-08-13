param(
  [string]$Maven = "D:\software\apache-maven-3.9.9\bin\mvn.cmd",
  [int]$Port = 8110,
  [string]$LogDir = "",
  [string]$MySql = "D:\software\MySQL\MySQL Server 8.0\bin\mysql.exe",
  [bool]$TemporarySuperadminMfaBypass = $false,
  [bool]$EnableLocalNovaAi = $true,
  [string]$NovaAiModel = "gemma4-e4b-ctx32k:latest",
  [string]$SpringProfile = "local-sandbox"
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$databaseEnvironment = & (Join-Path $PSScriptRoot "resolve_nexion_database_environment.ps1")
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
    'set "NEXION_ARCHITECTURE_DISTRIBUTED_RUNTIME_ENABLED=false"',
    ('set "NEXION_NOVA_AI_MODE={0}"' -f $(if ($EnableLocalNovaAi) { "OLLAMA_LOCAL" } else { "DISABLED" })),
    'set "NEXION_NOVA_AI_BASE_URL=http://127.0.0.1:11434"',
    ('set "NEXION_NOVA_AI_MODEL={0}"' -f $NovaAiModel),
    ('set "NEXION_ADMIN_MFA_TEMPORARY_SUPERADMIN_BYPASS={0}"' -f $mfaBypassValue),
    ('call "{0}" spring-boot:run' -f $Maven)
  )

  $inner = ($commands -join " && ") + (' > "{0}" 2> "{1}"' -f $outLog, $errLog)
  $process = Start-Process -FilePath "cmd.exe" -ArgumentList "/c", $inner -WindowStyle Hidden -PassThru

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
}
