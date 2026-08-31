[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)][string]$StatePath,
    [string]$DatabasePassword = $env:NEXION_ACCEPTANCE_DB_PASSWORD
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$resolvedState = (Resolve-Path -LiteralPath $StatePath).Path
$state = Get-Content -LiteralPath $resolvedState -Raw | ConvertFrom-Json
$database = [string]$state.database
if ($database -notmatch '^nexion_team_acceptance_[a-z0-9_]{8,64}$') {
    throw "Refusing cleanup for unsafe database name: $database"
}
$runDirectory = [IO.Path]::GetFullPath([string]$state.runDirectory).TrimEnd('\')
$stateDirectory = [IO.Path]::GetFullPath((Split-Path -Parent $resolvedState)).TrimEnd('\')
if ($runDirectory -notlike 'D:\nexion\团队模块全流程测试-*\证据\*' -or $runDirectory -ne $stateDirectory) {
    throw "Refusing cleanup outside acceptance evidence tree: $($state.runDirectory)"
}
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..')).TrimEnd('\')
if (-not $state.PSObject.Properties['repositoryPath']) {
    throw 'Refusing cleanup because repositoryPath is absent from runtime state.'
}
$stateRepo = [IO.Path]::GetFullPath([string]$state.repositoryPath).TrimEnd('\')
if ($stateRepo -ne $repo) {
    throw "Refusing cleanup for a runtime started from another repository: $stateRepo"
}

$backendPort = [int]$state.backendPort
$redisPort = [int]$state.redisPort
$formalPorts = @(8110, 5173, 3002, 9000, 11434)
if ($backendPort -lt 18000 -or $backendPort -gt 18999 -or $redisPort -lt 16000 -or $redisPort -gt 16999 -or $backendPort -in $formalPorts -or $redisPort -in $formalPorts) {
    throw "Refusing cleanup for non-acceptance ports: backend=$backendPort redis=$redisPort"
}

function Stop-VerifiedListener {
    param(
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][int]$ExpectedProcessId,
        [Parameter(Mandatory)][string]$ExpectedExecutable
    )
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $listener) { return $false }
    if ([int]$listener.OwningProcess -ne $ExpectedProcessId) {
        throw "Acceptance listener ownership mismatch on ${Port}: expected=$ExpectedProcessId actual=$($listener.OwningProcess)"
    }
    $process = Get-Process -Id $ExpectedProcessId -ErrorAction Stop
    if (-not $process.Path -or [IO.Path]::GetFileName($process.Path) -ine $ExpectedExecutable) {
        throw "Acceptance listener executable mismatch on ${Port}: $($process.Path)"
    }
    Stop-Process -Id $ExpectedProcessId -Force
    return $true
}

$mysql = 'D:\software\MySQL\MySQL Server 8.0\bin\mysql.exe'
if (-not $DatabasePassword) { throw 'NEXION_ACCEPTANCE_DB_PASSWORD or -DatabasePassword is required.' }
$env:MYSQL_PWD = $DatabasePassword
$stoppedProcessIds = @()
if (Stop-VerifiedListener -Port $backendPort -ExpectedProcessId ([int]$state.backendProcessId) -ExpectedExecutable 'java.exe') {
    $stoppedProcessIds += [int]$state.backendProcessId
}
if (Stop-VerifiedListener -Port $redisPort -ExpectedProcessId ([int]$state.redisProcessId) -ExpectedExecutable 'redis-server.exe') {
    $stoppedProcessIds += [int]$state.redisProcessId
}

# The Maven launcher should exit after its verified Java listener is stopped. Only
# terminate it if it is still the original cmd.exe and its command line names this repo.
$launcherId = [int]$state.backendLauncherProcessId
$launcher = Get-CimInstance Win32_Process -Filter "ProcessId=$launcherId" -ErrorAction SilentlyContinue
if ($launcher) {
    $commandLine = [string]$launcher.CommandLine
    $commandHash = [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($commandLine))
    ).ToLowerInvariant()
    $hasExpectedHash = [bool]$state.PSObject.Properties['backendLauncherCommandSha256']
    $isExpectedExecutable = [IO.Path]::GetFileName([string]$launcher.ExecutablePath) -ieq 'cmd.exe'
    $isExpectedCommand = $commandLine -like '*mvn.cmd*spring-boot:run*'
    $hashMatches = $hasExpectedHash -and $commandHash -eq [string]$state.backendLauncherCommandSha256
    if (-not $isExpectedExecutable -or -not $isExpectedCommand -or -not $hashMatches) {
        throw "Acceptance launcher ownership mismatch: $launcherId"
    }
    Stop-Process -Id $launcherId -Force
    $stoppedProcessIds += $launcherId
}

foreach ($port in @($backendPort, $redisPort)) {
    $deadline = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $deadline -and (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)) {
        Start-Sleep -Milliseconds 250
    }
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        throw "Acceptance listener did not stop: $port"
    }
}

& $mysql -uroot -N -B -e "DROP DATABASE ``$database``;"
if ($LASTEXITCODE -ne 0) { throw "Failed to drop isolated acceptance database $database" }

$formalAfter = foreach ($entry in @($state.formalBefore)) {
    $port = [int]$entry.port
    $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    [ordered]@{
        port = $port
        beforeProcessId = $entry.processId
        afterProcessId = if ($listener) { $listener.OwningProcess } else { $null }
        stillListening = [bool]$listener
    }
}
$cleanup = [ordered]@{
    cleanedAt = (Get-Date).ToUniversalTime().ToString('o')
    databaseDropped = $database
    stoppedProcessIds = $stoppedProcessIds
    formalAfter = $formalAfter
}
$cleanupPath = Join-Path ([string]$state.runDirectory) 'cleanup-result.json'
[IO.File]::WriteAllText($cleanupPath, ($cleanup | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
$cleanup | ConvertTo-Json -Depth 5
