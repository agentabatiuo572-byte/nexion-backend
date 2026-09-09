$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..\\..")
$launcher = Join-Path $root "scripts\\start_ops_console_monolith.ps1"
$guard = Join-Path $root "scripts\\assert_ops_console_monolith_port_available.ps1"

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

if (-not (Test-Path -LiteralPath $guard)) {
  throw "Port guard helper is missing: $guard"
}

. $guard

$freeProbeCalls = 0
Assert-OpsConsoleMonolithPortAvailable -Port 62001 -ListenerProbe {
  param([int]$LocalPort)
  $script:freeProbeCalls += 1
  @()
}
if ($freeProbeCalls -ne 1) {
  throw "Free-port probe must run exactly once; got $freeProbeCalls."
}

$occupiedProbeCalls = 0
Assert-Throws {
  Assert-OpsConsoleMonolithPortAvailable -Port 62002 -ListenerProbe {
    param([int]$LocalPort)
    $script:occupiedProbeCalls += 1
    @(
      [pscustomobject]@{ LocalAddress = "127.0.0.1"; OwningProcess = 101 },
      [pscustomobject]@{ LocalAddress = "::"; OwningProcess = 202 },
      [pscustomobject]@{ LocalAddress = "192.168.8.20"; OwningProcess = 303 }
    )
  }
} "already listening"
if ($occupiedProbeCalls -ne 1) {
  throw "Occupied-port probe must run exactly once; got $occupiedProbeCalls."
}

$probeFailureCalls = 0
Assert-Throws {
  Assert-OpsConsoleMonolithPortAvailable -Port 62003 -ListenerProbe {
    param([int]$LocalPort)
    $script:probeFailureCalls += 1
    throw "socket inventory unavailable"
  }
} "Unable to verify"
if ($probeFailureCalls -ne 1) {
  throw "Failed probe must run exactly once; got $probeFailureCalls."
}

Assert-Throws { Assert-OpsConsoleMonolithPortAvailable -Port 0 } "range"
Assert-Throws { Assert-OpsConsoleMonolithPortAvailable -Port 65536 } "range"

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
try {
  $listener.Start()
  $highPort = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
  Assert-Throws { Assert-OpsConsoleMonolithPortAvailable -Port $highPort } "already listening"
} finally {
  $listener.Stop()
}

$freeListener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
try {
  $freeListener.Start()
  $freePort = ([System.Net.IPEndPoint]$freeListener.LocalEndpoint).Port
} finally {
  $freeListener.Stop()
}
Assert-OpsConsoleMonolithPortAvailable -Port $freePort

$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("nexion-port-guard-" + [guid]::NewGuid().ToString("N"))
$temporaryScripts = Join-Path $temporaryRoot "scripts"
$migrationMarker = Join-Path $temporaryRoot "migration.called"
$processMarker = Join-Path $temporaryRoot "process.called"
$previousFinanceKey = [Environment]::GetEnvironmentVariable("NEXION_FINANCE_DATA_KEY", "Process")
$isolatedListener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
try {
  New-Item -ItemType Directory -Force -Path $temporaryScripts | Out-Null
  $createdTemporaryRoot = (Resolve-Path -LiteralPath $temporaryRoot).Path
  Copy-Item -LiteralPath $launcher -Destination (Join-Path $temporaryScripts "start_ops_console_monolith.ps1")
  Copy-Item -LiteralPath $guard -Destination (Join-Path $temporaryScripts "assert_ops_console_monolith_port_available.ps1")
  Set-Content -LiteralPath (Join-Path $temporaryScripts "resolve_nexion_database_environment.ps1") -Value @"
[pscustomobject]@{ JdbcUrl = 'jdbc:fake'; Username = 'fake'; Password = 'fake' }
"@
  Set-Content -LiteralPath (Join-Path $temporaryScripts "resolve_ops_console_mfa_bypass.ps1") -Value '"false"'
  Set-Content -LiteralPath (Join-Path $temporaryScripts "apply_startup_schema_migrations.ps1") -Value "Set-Content -LiteralPath '$migrationMarker' -Value 'called'"
  $fakeMaven = Join-Path $temporaryRoot "fake-maven.cmd"
  Set-Content -LiteralPath $fakeMaven -Value "@echo off`r`n> `"$processMarker`" echo called"

  [Environment]::SetEnvironmentVariable("NEXION_FINANCE_DATA_KEY", ("x" * 32), "Process")
  $isolatedListener.Start()
  $isolatedPort = ([System.Net.IPEndPoint]$isolatedListener.LocalEndpoint).Port
  $isolatedLauncher = Join-Path $temporaryScripts "start_ops_console_monolith.ps1"
  Assert-Throws { & $isolatedLauncher -Port $isolatedPort -Maven $fakeMaven -LogDir (Join-Path $temporaryRoot "logs") } "already listening"
  if ((Test-Path -LiteralPath $migrationMarker) -or (Test-Path -LiteralPath $processMarker)) {
    throw "Occupied-port launcher path must not invoke migrations or start its child process."
  }
} finally {
  $isolatedListener.Stop()
  [Environment]::SetEnvironmentVariable("NEXION_FINANCE_DATA_KEY", $previousFinanceKey, "Process")
  if (Test-Path -LiteralPath $temporaryRoot) {
    $cleanupRoot = (Resolve-Path -LiteralPath $temporaryRoot).Path
    if ([string]::IsNullOrWhiteSpace($createdTemporaryRoot) -or $cleanupRoot -cne $createdTemporaryRoot) {
      throw "Refusing to remove a temporary directory that was not the unique root created by this test."
    }
    Remove-Item -LiteralPath $cleanupRoot -Recurse -Force
  }
}

$source = Get-Content -LiteralPath $launcher -Raw
if ($source -notmatch '\[ValidateRange\(1,\s*65535\)\]\s*\[int\]\$Port') {
  throw "Launcher must reject invalid ports during parameter binding."
}

$guardIndex = $source.IndexOf("Assert-OpsConsoleMonolithPortAvailable -Port `$Port")
$resolverIndex = $source.IndexOf('$databaseEnvironment =')
$environmentIndex = $source.IndexOf('$env:NEXION_DB_URL =')
$migrationIndex = $source.IndexOf('apply_startup_schema_migrations.ps1')
$startIndex = $source.IndexOf('Start-Process -FilePath')
if ($guardIndex -lt 0 -or $resolverIndex -lt 0 -or $environmentIndex -lt 0 -or $migrationIndex -lt 0 -or $startIndex -lt 0) {
  throw "Launcher port-guard ordering contract is incomplete."
}
if ($guardIndex -ge $resolverIndex -or $guardIndex -ge $environmentIndex -or $guardIndex -ge $migrationIndex -or $guardIndex -ge $startIndex) {
  throw "Port guard must run before dependency resolution, environment changes, migrations, and process launch."
}

Write-Output "START_OPS_CONSOLE_PORT_GUARD_CONTRACT: PASS"
