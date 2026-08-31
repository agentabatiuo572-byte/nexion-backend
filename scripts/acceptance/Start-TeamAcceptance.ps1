[CmdletBinding()]
param(
    [string]$RunId = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [string]$EvidenceRoot = 'D:\nexion\团队模块全流程测试-20260829\证据',
    [int]$BackendPort = 18110,
    [int]$RedisPort = 16379,
    [string]$DatabasePassword = $env:NEXION_ACCEPTANCE_DB_PASSWORD,
    [string]$MinioSecretKey = $env:NEXION_ACCEPTANCE_MINIO_SECRET_KEY
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $DatabasePassword) { throw 'NEXION_ACCEPTANCE_DB_PASSWORD or -DatabasePassword is required.' }
if (-not $MinioSecretKey) { $MinioSecretKey = $DatabasePassword }

function Wait-Listener {
    param([int]$Port, [int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { return }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for listener $Port"
}

function Wait-Health {
    param([string]$Url, [int]$TimeoutSeconds, [Diagnostics.Process]$Process, [string]$ErrorLog)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if ($Process.HasExited) {
            $tail = if (Test-Path -LiteralPath $ErrorLog) { Get-Content -LiteralPath $ErrorLog -Tail 80 | Out-String } else { '' }
            throw "Backend exited before health check. $tail"
        }
        try {
            $health = Invoke-WebRequest -Uri $Url -TimeoutSec 2 -SkipHttpErrorCheck
            if ($health.StatusCode -in @(200, 401)) { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for backend health at $Url"
}

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$formalPorts = @(8110, 5173, 3002, 9000, 11434)
$backendPortSafe = $BackendPort -ge 18000 -and $BackendPort -le 18999 -and $BackendPort -notin $formalPorts
$redisPortSafe = $RedisPort -ge 16000 -and $RedisPort -le 16999 -and $RedisPort -notin $formalPorts
if (-not $backendPortSafe -or -not $redisPortSafe -or $BackendPort -eq $RedisPort) {
    throw "Acceptance ports must stay inside backend 18000-18999 and Redis 16000-16999, outside formal ports: backend=$BackendPort redis=$RedisPort"
}
$safeRunId = ($RunId -replace '[^A-Za-z0-9_-]', '-')
if ($safeRunId.Length -lt 8 -or $safeRunId.Length -gt 64) {
    throw 'RunId must normalize to 8-64 safe characters.'
}
$database = ('nexion_team_acceptance_' + ($safeRunId -replace '-', '_')).ToLowerInvariant()
if ($database -notmatch '^nexion_team_acceptance_[a-z0-9_]{8,64}$') {
    throw "Refusing unsafe acceptance database name: $database"
}

$mysql = 'D:\software\MySQL\MySQL Server 8.0\bin\mysql.exe'
$mysqldump = 'D:\software\MySQL\MySQL Server 8.0\bin\mysqldump.exe'
$redisServer = 'D:\software\Redis-8.6.1\redis-server.exe'
$maven = 'D:\software\apache-maven-3.9.9\bin\mvn.cmd'
foreach ($tool in @($mysql, $mysqldump, $redisServer, $maven)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Required tool missing: $tool" }
}

$runDirectory = Join-Path $EvidenceRoot $safeRunId
$runtimeDirectory = Join-Path $runDirectory 'runtime'
New-Item -ItemType Directory -Force -Path $runtimeDirectory | Out-Null
$statePath = Join-Path $runDirectory 'runtime-state.json'
$manifestPath = Join-Path $runDirectory 'team-test-run-manifest.json'
$configSnapshotPath = Join-Path $runDirectory 'config-snapshot.tsv'
$credentialPath = Join-Path $runDirectory 'credentials.dpapi'
$backendOut = Join-Path $runtimeDirectory 'backend.stdout.log'
$backendErr = Join-Path $runtimeDirectory 'backend.stderr.log'
$redisOut = Join-Path $runtimeDirectory 'redis.stdout.log'
$redisErr = Join-Path $runtimeDirectory 'redis.stderr.log'

if (Test-Path -LiteralPath $statePath) { throw "Runtime state already exists: $statePath" }
foreach ($port in @($BackendPort, $RedisPort)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        throw "Acceptance port already in use: $port"
    }
}

$formalBefore = foreach ($port in $formalPorts) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    [ordered]@{ port = $port; processId = if ($listener) { $listener.OwningProcess } else { $null } }
}
if (@($formalBefore | Where-Object { $_.port -in @(8110, 5173, 3002, 9000) -and -not $_.processId }).Count -gt 0) {
    throw 'One or more required formal services are not listening before acceptance start.'
}

$previousMysqlPassword = $env:MYSQL_PWD
$env:MYSQL_PWD = $DatabasePassword
$redisProcess = $null
$backendProcess = $null
$schemaDump = Join-Path $repo "target\team-acceptance-$safeRunId-schema.sql"
try {
    & $mysql -uroot -N -B -e "CREATE DATABASE ``$database`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
    if ($LASTEXITCODE -ne 0) { throw 'Acceptance database creation failed.' }

    & $mysqldump -uroot --no-data --skip-triggers --single-transaction --result-file=$schemaDump nexion
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $schemaDump -PathType Leaf)) {
        throw 'Acceptance schema dump failed.'
    }
    $schemaSource = $schemaDump.Replace('\','/')
    & $mysql -uroot $database -e "SOURCE $schemaSource;"
    if ($LASTEXITCODE -ne 0) { throw 'Acceptance schema clone failed.' }
    Remove-Item -LiteralPath $schemaDump -Force

    $seedTables = @(
        'nx_admin', 'nx_admin_account_state', 'nx_admin_menu', 'nx_admin_permission',
        'nx_admin_role', 'nx_admin_role_menu', 'nx_admin_role_permission', 'nx_admin_role_relation',
        'nx_admin_rbac_action', 'nx_admin_rbac_grant', 'nx_admin_security_baseline',
        'nx_admin_risk_param', 'nx_config_item', 'nx_commission_rule',
        'nx_v_rank_config', 'nx_v_rank_reward_rule',
        'nx_event_schema_registry', 'nx_event_schema_revision', 'nx_event_schema_property'
    )
    foreach ($table in $seedTables) {
        if ($table -notmatch '^nx_[a-z0-9_]+$') { throw "Unsafe seed table: $table" }
        & $mysql -uroot -N -B -e "SET FOREIGN_KEY_CHECKS=0; INSERT INTO ``$database``.``$table`` SELECT * FROM ``nexion``.``$table``; SET FOREIGN_KEY_CHECKS=1;"
        if ($LASTEXITCODE -ne 0) { throw "Seed failed for $table" }
    }
    foreach ($migrationName in @(
        '20260829_commission_paid_schema_revision_closure.sql',
        '20260829_vrank_projection_closure.sql',
        '20260829_f5_commission_mutation_event_schema.sql'
    )) {
        $migrationPath = Join-Path $repo "scripts\migrations\$migrationName"
        Get-Content -LiteralPath $migrationPath -Raw | & $mysql -uroot $database
        if ($LASTEXITCODE -ne 0) { throw "Acceptance migration failed: $migrationName" }
    }

    & $mysql -uroot -N -B -e "SELECT config_key,COALESCE(config_value,''),COALESCE(status,0),COALESCE(updated_at,'') FROM ``$database``.nx_config_item WHERE config_key LIKE 'team.ui.F.%' OR config_key LIKE 'commission/%' ORDER BY config_key; SELECT commission_type,COALESCE(layer_no,''),COALESCE(rank_code,''),usdt_rate,nex_per_usd,fixed_nex,daily_cap_usdt,cooldown_days,status FROM ``$database``.nx_commission_rule ORDER BY commission_type,layer_no,rank_code;" |
        Set-Content -LiteralPath $configSnapshotPath -Encoding utf8

    $passwordBytes = [byte[]]::new(24)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($passwordBytes)
    $acceptancePassword = 'Nx!' + [Convert]::ToHexString($passwordBytes)
    $credentialJson = [Text.Encoding]::UTF8.GetBytes((@{ runId = $safeRunId; commonPassword = $acceptancePassword } | ConvertTo-Json -Compress))
    $protected = [System.Security.Cryptography.ProtectedData]::Protect(
        $credentialJson, $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
    [IO.File]::WriteAllText($credentialPath, [Convert]::ToBase64String($protected), [Text.UTF8Encoding]::new($false))

    $redisStart = @{
        FilePath = $redisServer
        WindowStyle = 'Hidden'
        PassThru = $true
        ArgumentList = @('--bind','127.0.0.1','--port',"$RedisPort",'--save','','--appendonly','no','--dir',$runtimeDirectory)
        RedirectStandardOutput = $redisOut
        RedirectStandardError = $redisErr
    }
    $redisProcess = Start-Process @redisStart
    Wait-Listener -Port $RedisPort -TimeoutSeconds 30

    $env:SPRING_PROFILES_ACTIVE = 'dev'
    $env:SERVER_PORT = "$BackendPort"
    $env:NEXION_DB_URL = "jdbc:mysql://127.0.0.1:3306/${database}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
    $env:NEXION_DB_USERNAME = 'root'
    $env:NEXION_DB_PASSWORD = $env:MYSQL_PWD
    $env:NEXION_REDIS_HOST = '127.0.0.1'
    $env:NEXION_REDIS_PORT = "$RedisPort"
    $env:NEXION_REDIS_PASSWORD = ''
    $env:NEXION_LOG_FILE = (Join-Path $runtimeDirectory 'backend.log')
    $env:NEXION_ADMIN_MFA_TEMPORARY_SUPERADMIN_BYPASS = 'true'
    $env:NEXION_FINANCE_FUNDS_SANDBOX_MODE = 'DISABLED'
    $env:NEXION_CREGIS_MODE = 'DISABLED'
    $env:NEXION_MINIO_ACCESS_KEY = 'minioadmin'
    $env:NEXION_MINIO_SECRET_KEY = $MinioSecretKey
    $env:NEXION_FINANCE_DATA_KEY = $acceptancePassword
    $env:NEXION_ADMIN_MFA_ENCRYPTION_KEY = $acceptancePassword
    $env:NEXION_TREASURY_DEVELOPMENT_RESERVE_ENABLED = 'false'
    $env:NEXION_HOME_DEVELOPMENT_SETTLEMENT_ENABLED = 'false'
    $env:NEXION_FINANCE_DEVELOPMENT_PAYOUT_ADDRESS_ENABLED = 'false'
    $env:NEXION_GEO_BLOCK_ENABLED = 'false'

    $backendStart = @{
        FilePath = $maven
        WorkingDirectory = $repo
        WindowStyle = 'Hidden'
        PassThru = $true
        ArgumentList = @('-q','-DskipTests','spring-boot:run')
        RedirectStandardOutput = $backendOut
        RedirectStandardError = $backendErr
    }
    $backendProcess = Start-Process @backendStart
    Wait-Health -Url "http://127.0.0.1:$BackendPort/actuator/health" -TimeoutSeconds 180 -Process $backendProcess -ErrorLog $backendErr
    $backendListenerProcessId = (Get-NetTCPConnection -State Listen -LocalPort $BackendPort | Select-Object -First 1).OwningProcess
    if (-not $backendListenerProcessId) { throw 'Acceptance backend listener process was not resolved.' }
    $backendLauncher = Get-CimInstance Win32_Process -Filter "ProcessId=$($backendProcess.Id)" -ErrorAction Stop
    $launcherCommandLine = [string]$backendLauncher.CommandLine
    $launcherCommandHash = [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($launcherCommandLine))
    ).ToLowerInvariant()

    $env:TEAM_ACCEPTANCE_BASE_URL = "http://127.0.0.1:$BackendPort"
    $env:TEAM_ACCEPTANCE_RUN_ID = $safeRunId
    $env:TEAM_ACCEPTANCE_PASSWORD = $acceptancePassword
    $env:TEAM_ACCEPTANCE_OUTPUT = $manifestPath
    & node (Join-Path $repo 'scripts\acceptance\register-team-topology.mjs')
    if ($LASTEXITCODE -ne 0) { throw 'Team registration topology failed.' }

    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if (@($manifest.roles).Count -ne 17) { throw 'Manifest does not contain exactly 17 accounts.' }
    $state = [ordered]@{
        runId = $safeRunId
        database = $database
        backendPort = $BackendPort
        redisPort = $RedisPort
        backendProcessId = $backendListenerProcessId
        backendLauncherProcessId = $backendProcess.Id
        backendLauncherCommandSha256 = $launcherCommandHash
        redisProcessId = $redisProcess.Id
        repositoryPath = $repo
        runDirectory = $runDirectory
        manifestPath = $manifestPath
        credentialPath = $credentialPath
        configSnapshotPath = $configSnapshotPath
        formalBefore = $formalBefore
        startedAt = (Get-Date).ToUniversalTime().ToString('o')
    }
    [IO.File]::WriteAllText($statePath, ($state | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
    $state | ConvertTo-Json -Depth 6
} catch {
    if (Test-Path -LiteralPath $schemaDump -PathType Leaf) { Remove-Item -LiteralPath $schemaDump -Force }
    if ($backendProcess -and -not $backendProcess.HasExited) { Stop-Process -Id $backendProcess.Id -Force -ErrorAction SilentlyContinue }
    $backendListener = Get-NetTCPConnection -State Listen -LocalPort $BackendPort -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($backendListener -and $backendListener.OwningProcess -gt 0) {
        Stop-Process -Id $backendListener.OwningProcess -Force -ErrorAction SilentlyContinue
    }
    if ($redisProcess -and -not $redisProcess.HasExited) { Stop-Process -Id $redisProcess.Id -Force -ErrorAction SilentlyContinue }
    if ($database -match '^nexion_team_acceptance_[a-z0-9_]{8,64}$') {
        & $mysql -uroot -N -B -e "DROP DATABASE IF EXISTS ``$database``;" | Out-Null
    }
    throw
} finally {
    $env:MYSQL_PWD = $previousMysqlPassword
    Remove-Item Env:TEAM_ACCEPTANCE_PASSWORD -ErrorAction SilentlyContinue
}
