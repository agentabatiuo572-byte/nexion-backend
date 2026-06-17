param(
  [string]$Maven = "D:\software\apache-maven-3.9.9\bin\mvn.cmd",
  [int]$Port = 8110,
  [string]$LogDir = ""
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")

if (-not (Test-Path $Maven)) {
  throw "Maven executable not found: $Maven"
}

if ([string]::IsNullOrWhiteSpace($LogDir)) {
  $LogDir = Join-Path $root "logs"
}

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$outLog = Join-Path $LogDir "ops-console-monolith.out.log"
$errLog = Join-Path $LogDir "ops-console-monolith.err.log"

$commands = @(
  ('cd /d "{0}"' -f $root.Path),
  ('set "SERVER_PORT={0}"' -f $Port),
  'set "NEXION_ARCHITECTURE_DISTRIBUTED_RUNTIME_ENABLED=false"',
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
