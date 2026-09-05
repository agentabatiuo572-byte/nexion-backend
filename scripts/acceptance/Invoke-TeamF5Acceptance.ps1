[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$StatePath,
    [string]$DatabasePassword = $env:NEXION_ACCEPTANCE_DB_PASSWORD
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$state = Get-Content -LiteralPath (Resolve-Path -LiteralPath $StatePath) -Raw | ConvertFrom-Json
$database = [string]$state.database
if ($database -notmatch '^nexion_team_acceptance_[a-z0-9_]{8,64}$') { throw "Unsafe database: $database" }
$manifest = Get-Content -LiteralPath ([string]$state.manifestPath) -Raw | ConvertFrom-Json
$baseUrl = "http://127.0.0.1:$($state.backendPort)"
$mysql = 'D:\software\MySQL\MySQL Server 8.0\bin\mysql.exe'
if (-not $DatabasePassword) { throw 'NEXION_ACCEPTANCE_DB_PASSWORD or -DatabasePassword is required.' }
$env:MYSQL_PWD = $DatabasePassword
$adminPassword = $env:TEAM_ACCEPTANCE_ADMIN_PASSWORD
if (-not $adminPassword) { throw 'TEAM_ACCEPTANCE_ADMIN_PASSWORD is required.' }

function Invoke-Api {
    param([string]$Method,[string]$Path,[object]$Body,[string]$Token,[string]$IdempotencyKey='')
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    if ($IdempotencyKey) { $headers.'Idempotency-Key' = $IdempotencyKey }
    $parameters = @{
        Method=$Method; Uri=($baseUrl+$Path); Headers=$headers; SkipHttpErrorCheck=$true; TimeoutSec=20
    }
    if ($null -ne $Body) {
        $parameters.ContentType='application/json'
        $parameters.Body=($Body | ConvertTo-Json -Depth 10 -Compress)
    }
    $response = Invoke-WebRequest @parameters
    $json = if ($response.Content) { $response.Content | ConvertFrom-Json } else { $null }
    [pscustomobject]@{ status=[int]$response.StatusCode; json=$json }
}

function Require-Ok {
    param([object]$Response,[string]$Label)
    if ($Response.status -lt 200 -or $Response.status -ge 300 -or $Response.json.code -ne 0) {
        throw "$Label failed: HTTP $($Response.status), API $($Response.json.code), $($Response.json.message)"
    }
    return $Response.json.data
}

function ConvertFrom-Base32 {
    param([Parameter(Mandatory)][string]$Value)
    $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
    $bits = [Text.StringBuilder]::new()
    foreach ($character in $Value.Trim().TrimEnd('=').ToUpperInvariant().ToCharArray()) {
        $index = $alphabet.IndexOf($character)
        if ($index -lt 0) { throw 'Invalid base32 MFA secret.' }
        [void]$bits.Append([Convert]::ToString($index,2).PadLeft(5,'0'))
    }
    $bytes = [System.Collections.Generic.List[byte]]::new()
    for ($offset=0; $offset+8 -le $bits.Length; $offset+=8) {
        $bytes.Add([Convert]::ToByte($bits.ToString($offset,8),2))
    }
    return $bytes.ToArray()
}

function New-TotpCode {
    param([Parameter(Mandatory)][string]$Secret)
    $counter = [Math]::Floor([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()/30)
    $counterBytes = [BitConverter]::GetBytes([int64]$counter)
    if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($counterBytes) }
    $key = ConvertFrom-Base32 -Value $Secret
    try {
        $hmac = [Security.Cryptography.HMACSHA1]::new($key)
        try { $hash = $hmac.ComputeHash($counterBytes) } finally { $hmac.Dispose() }
        $offset = $hash[$hash.Length-1] -band 0x0f
        $binary = (($hash[$offset] -band 0x7f) -shl 24) -bor (($hash[$offset+1] -band 0xff) -shl 16) -bor (($hash[$offset+2] -band 0xff) -shl 8) -bor ($hash[$offset+3] -band 0xff)
        return ($binary % 1000000).ToString('D6')
    } finally {
        [Array]::Clear($key,0,$key.Length)
    }
}

$runTag = ([string]$state.runId).ToUpperInvariant()
$adminSession = Require-Ok (Invoke-Api -Method POST -Path '/api/admin/auth/login' -Body @{username='superadmin';password=$adminPassword} -Token '') 'F5 admin login'
$adminToken = [string]$adminSession.accessToken
if (-not $adminToken) { throw 'F5 admin token missing.' }

$checkerNonce = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$checkerUsername = "acceptance-f5-checker-$checkerNonce"
$checkerSql = @"
INSERT INTO ``$database``.nx_admin
  (username,password_hash,nickname,super_admin,status,version,is_deleted)
SELECT '$checkerUsername',password_hash,'Acceptance F5 checker',1,1,0,0
  FROM ``$database``.nx_admin WHERE username='superadmin' AND is_deleted=0 LIMIT 1;
INSERT INTO ``$database``.nx_admin_role_relation(admin_id,role_id,is_deleted)
SELECT checker.id,relation.role_id,0
  FROM ``$database``.nx_admin checker
  JOIN ``$database``.nx_admin source ON source.username='superadmin' AND source.is_deleted=0
  JOIN ``$database``.nx_admin_role_relation relation ON relation.admin_id=source.id AND relation.is_deleted=0
 WHERE checker.username='$checkerUsername';
"@
& $mysql -uroot -e $checkerSql
if ($LASTEXITCODE -ne 0) { throw 'F5 A2 checker fixture creation failed.' }
$checkerLogin = Require-Ok (Invoke-Api -Method POST -Path '/api/admin/auth/login' -Body @{username=$checkerUsername;password=$adminPassword} -Token '') 'F5 checker login'
$checkerToken = [string]$checkerLogin.accessToken
if (-not $checkerToken) {
    $challengeId = [string]$checkerLogin.mfa.challengeId
    $mfaSecret = [string]$checkerLogin.mfa.manualKey
    if (-not $challengeId -or -not $mfaSecret) { throw 'F5 checker MFA enrollment challenge missing.' }
    $mfaCode = New-TotpCode -Secret $mfaSecret
    $verified = Require-Ok (Invoke-Api -Method POST -Path '/api/admin/auth/mfa/verify' -Body @{challengeId=$challengeId;code=$mfaCode} -Token '') 'F5 checker MFA enrollment'
    $checkerToken = [string]$verified.accessToken
    $mfaCode=$null
    $mfaSecret=$null
}
if (-not $checkerToken) { throw 'F5 checker token missing.' }

# A failed fail-closed replay intentionally leaves its A2 ticket pending. In
# this isolated run, reject only stale F5 tickets so their exact object locks do
# not mask the repaired replay path on a repeat invocation.
$staleF5Tickets = @(& $mysql -uroot -N -B -e "SELECT DISTINCT t.operation_id FROM ``$database``.nx_audit_operation_ticket t JOIN ``$database``.nx_audit_object_lock l ON l.ticket_id=t.operation_id AND l.is_deleted=0 WHERE t.status='pending' AND t.is_deleted=0 AND l.target_domain='F' AND l.target_type IN ('commission_event','commission_user_kind') ORDER BY t.operation_id;")
foreach ($ticketId in $staleF5Tickets) {
    if ([string]::IsNullOrWhiteSpace([string]$ticketId)) { continue }
    [void](Require-Ok (Invoke-Api -Method POST -Path "/api/admin/platform/audit/operations/$ticketId/reject" -Token $checkerToken -IdempotencyKey "tfa-$runTag-reject-$ticketId" -Body @{reason='Reject stale isolated fail-closed F5 replay before repaired rerun.'}) "reject stale F5 ticket $ticketId")
}

function Invoke-A2Replay {
    param(
        [string]$Operation,[hashtable]$Parameters,[string]$Action,[string]$ObjectId,
        [string]$BeforeValue,[string]$AfterValue,[string]$SourceDomain,[string]$BusinessType,
        [bool]$Amplifies,[object]$Target,[object[]]$Targets,[string]$Reason
    )
    $nonce = [Guid]::NewGuid().ToString('N')
    $proposalBody = @{
        action=$Action;obj=$ObjectId;beforeValue=$BeforeValue;afterValue=$AfterValue
        type=$BusinessType;amplifies=$Amplifies;sos=$false;roleGate='superadmin';reason=$Reason;sourceDomain=$SourceDomain
        command=@{domain='F';op=$Operation;params=$Parameters}
    }
    if ($null -ne $Target) { $proposalBody.target = $Target }
    if ($null -ne $Targets -and $Targets.Count -gt 0) { $proposalBody.targets = $Targets }
    $proposal = Require-Ok (Invoke-Api -Method POST -Path '/api/admin/platform/audit/operations' -Token $adminToken -IdempotencyKey "tfa-$runTag-propose-$nonce" -Body $proposalBody) "F5 A2 proposal $Operation"
    $approval = Invoke-Api -Method POST -Path "/api/admin/platform/audit/operations/$($proposal.id)/approve" -Token $checkerToken -IdempotencyKey "tfa-$runTag-approve-$nonce" -Body @{reason='Independent checker approves isolated F5 acceptance mutation.'}
    return [pscustomobject]@{ proposalId=$proposal.id; response=$approval }
}

$orderNo = "TFA-$runTag-F2-01"
$sourceLine = [string](& $mysql -uroot -N -B -e "SELECT id,user_id,amount_usdt,order_no FROM ``$database``.nx_commission_event WHERE order_no='$orderNo' AND commission_type='network' AND currency='USDT' AND status='COOLING' AND is_deleted=0 ORDER BY layer_no,id LIMIT 1;")
if (-not $sourceLine) { throw 'F5 reverse source event missing.' }
$source = $sourceLine -split "`t"
$sourceId = [long]$source[0]
$sourceUserId = [long]$source[1]
$sourceAmount = [decimal]$source[2]
$sourceCommissionId = "CM-$sourceId"
$reason = 'Verified isolated refund reversal for F5 acceptance.'

$directReverse = Invoke-Api -Method POST -Path "/api/admin/commissions/$sourceCommissionId/reverse" -Token $adminToken -IdempotencyKey "tfa-$runTag-f5-direct-reverse" -Body @{
    refundRef=$orderNo;reason=$reason;operator='team-acceptance'
}
if ($directReverse.status -ne 409 -or $directReverse.json.message -ne 'A2_CONFIRMATION_REQUIRED') {
    throw "F5 direct reverse guard mismatch: $($directReverse.status)/$($directReverse.json.message)"
}

$reverse = Invoke-A2Replay -Operation 'f5_commission_reverse' -Action "冲正佣金事件 $sourceCommissionId" -ObjectId $sourceCommissionId -BeforeValue '以服务器执行时状态为准' -AfterValue 'REVERSED' -SourceDomain 'F5' -BusinessType 'fund' -Amplifies $false -Target @{domain='F';type='commission_event';id=$sourceCommissionId} -Targets @() -Reason $reason -Parameters @{
    commissionId=$sourceCommissionId;refundRef=$orderNo;operator='team-acceptance';reason=$reason
}
$reverseData = Require-Ok $reverse.response 'F5 approved reverse'
$sourceStatus = [string](& $mysql -uroot -N -B -e "SELECT status FROM ``$database``.nx_commission_event WHERE id=$sourceId;")
$reverseLedger = [int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_wallet_ledger WHERE biz_no='F5-REVERSE-$sourceId' AND direction='OUT' AND amount=$sourceAmount AND is_deleted=0;")
$reverseOps = [int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_commission_operation WHERE operation_type='REVERSE' AND source_commission_id=$sourceId;")
if ($sourceStatus -ne 'REVERSED' -or $reverseLedger -ne 1 -or $reverseOps -ne 1) {
    throw "F5 reverse chain mismatch: status=$sourceStatus ledger=$reverseLedger operations=$reverseOps"
}

$reissueReason = 'Verified isolated remediation and commission reissue.'
$reissue = Invoke-A2Replay -Operation 'f5_commission_reissue' -Action '批量补发佣金 · 1 笔' -ObjectId $sourceCommissionId -BeforeValue '以服务器执行时状态为准' -AfterValue '重新进入冷却计提' -SourceDomain 'F5' -BusinessType 'fund' -Amplifies $true -Target $null -Targets @(@{domain='F';type='commission_event';id=$sourceCommissionId}) -Reason $reissueReason -Parameters @{
    commissionIds=@($sourceCommissionId);operator='team-acceptance';reason=$reissueReason
}
$reissueData = Require-Ok $reissue.response 'F5 approved reissue'
$reissueLine = [string](& $mysql -uroot -N -B -e "SELECT result_commission_id FROM ``$database``.nx_commission_operation WHERE operation_type='REISSUE' AND source_commission_id=$sourceId ORDER BY id DESC LIMIT 1;")
if (-not $reissueLine) { throw 'F5 reissue result event missing.' }
$reissueEventId = [long]$reissueLine
$reissueStatus = [string](& $mysql -uroot -N -B -e "SELECT status FROM ``$database``.nx_commission_event WHERE id=$reissueEventId;")
$reissueLedger = [int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_wallet_ledger WHERE biz_no='F5-REISSUE-$reissueEventId' AND direction='IN' AND amount=$sourceAmount AND is_deleted=0;")
if ($reissueStatus -ne 'COOLING' -or $reissueLedger -ne 1) {
    throw "F5 reissue chain mismatch: status=$reissueStatus ledger=$reissueLedger"
}

$duplicateReissue = Invoke-A2Replay -Operation 'f5_commission_reissue' -Action '批量补发佣金 · 1 笔' -ObjectId $sourceCommissionId -BeforeValue '以服务器执行时状态为准' -AfterValue '重新进入冷却计提' -SourceDomain 'F5' -BusinessType 'fund' -Amplifies $true -Target $null -Targets @(@{domain='F';type='commission_event';id=$sourceCommissionId}) -Reason $reissueReason -Parameters @{
    commissionIds=@($sourceCommissionId);operator='team-acceptance';reason=$reissueReason
}
if ($duplicateReissue.response.status -ne 409 -or $duplicateReissue.response.json.message -ne "COMMISSION_REISSUE_ALREADY_CONSUMED:$sourceCommissionId") {
    throw "F5 duplicate reissue guard mismatch: $($duplicateReissue.response.status)/$($duplicateReissue.response.json.message)"
}

$suspendLine = [string](& $mysql -uroot -N -B -e "SELECT id,user_id FROM ``$database``.nx_commission_event WHERE order_no='$orderNo' AND commission_type='network' AND currency='USDT' AND status='COOLING' AND id<>$sourceId AND is_deleted=0 ORDER BY layer_no,id LIMIT 1;")
if (-not $suspendLine) { throw 'F5 suspension source event missing.' }
$suspendParts = $suspendLine -split "`t"
$suspendEventId = [long]$suspendParts[0]
$suspendUserId = [long]$suspendParts[1]
$suspendReason = 'Verified isolated commission suspension and resume behavior.'
$directSuspend = Invoke-Api -Method POST -Path "/api/admin/users/$suspendUserId/commission/suspend" -Token $adminToken -IdempotencyKey "tfa-$runTag-f5-direct-suspend" -Body @{
    kinds=@('network');suspended=$true;reason=$suspendReason;operator='team-acceptance'
}
if ($directSuspend.status -ne 409 -or $directSuspend.json.message -ne 'A2_CONFIRMATION_REQUIRED') {
    throw "F5 direct suspension guard mismatch: $($directSuspend.status)/$($directSuspend.json.message)"
}
$suspendTargetId = "$suspendUserId`:network"
$suspend = Invoke-A2Replay -Operation 'f5_commission_suspension' -Action "暂停用户佣金 $suspendUserId" -ObjectId $suspendTargetId -BeforeValue '以服务器执行时状态为准' -AfterValue 'SUSPENDED' -SourceDomain 'F5' -BusinessType 'acct' -Amplifies $false -Target @{domain='F';type='commission_user_kind';id=$suspendTargetId} -Targets @() -Reason $suspendReason -Parameters @{
    userId=$suspendUserId;kinds=@('network');suspended=$true;operator='team-acceptance';reason=$suspendReason
}
$suspendData = Require-Ok $suspend.response 'F5 approved suspension'
$frozenStatus = [string](& $mysql -uroot -N -B -e "SELECT status FROM ``$database``.nx_commission_event WHERE id=$suspendEventId;")
$frozenOpenEvents = [int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_commission_event WHERE user_id=$suspendUserId AND LOWER(commission_type)='network' AND status='FROZEN' AND is_deleted=0;")
if ($frozenStatus -ne 'FROZEN' -or $frozenOpenEvents -lt 1) {
    throw "F5 suspension did not freeze the open event: status=$frozenStatus frozen=$frozenOpenEvents"
}

$resume = Invoke-A2Replay -Operation 'f5_commission_suspension' -Action "恢复用户佣金 $suspendUserId" -ObjectId $suspendTargetId -BeforeValue '以服务器执行时状态为准' -AfterValue 'ACTIVE' -SourceDomain 'F5' -BusinessType 'acct' -Amplifies $false -Target @{domain='F';type='commission_user_kind';id=$suspendTargetId} -Targets @() -Reason $suspendReason -Parameters @{
    userId=$suspendUserId;kinds=@('network');suspended=$false;operator='team-acceptance';reason=$suspendReason
}
$resumeData = Require-Ok $resume.response 'F5 approved resume'
$afterResumeStatus = [string](& $mysql -uroot -N -B -e "SELECT status FROM ``$database``.nx_commission_event WHERE id=$suspendEventId;")
$suspensionState = [string](& $mysql -uroot -N -B -e "SELECT status FROM ``$database``.nx_commission_user_suspension WHERE user_id=$suspendUserId AND kind='network';")
if ($afterResumeStatus -ne 'FROZEN' -or $suspensionState -ne 'ACTIVE') {
    throw "F5 resume must not revive the original event: event=$afterResumeStatus suspension=$suspensionState"
}

$overview = Require-Ok (Invoke-Api -Method GET -Path "/api/admin/commissions?userId=$sourceUserId&limit=200" -Body $null -Token $adminToken) 'F5 overview readback'
$visibleIds = @($overview.items | ForEach-Object { [string]$_.commissionId })
if ($sourceCommissionId -notin $visibleIds -or "CM-$reissueEventId" -notin $visibleIds) {
    throw 'F5 overview does not expose both original and reissued events.'
}

$result = [ordered]@{
    runId=[string]$state.runId
    directMutationGuard=[ordered]@{reverseHttp=$directReverse.status;suspendHttp=$directSuspend.status;message='A2_CONFIRMATION_REQUIRED'}
    reverse=[ordered]@{
        sourceCommissionId=$sourceCommissionId;userId=$sourceUserId;amount=$sourceAmount
        status=$sourceStatus;ledgerRows=$reverseLedger;operationRows=$reverseOps;proposalId=$reverse.proposalId
    }
    reissue=[ordered]@{
        sourceCommissionId=$sourceCommissionId;resultCommissionId="CM-$reissueEventId"
        status=$reissueStatus;ledgerRows=$reissueLedger;proposalId=$reissue.proposalId
        duplicateHttp=$duplicateReissue.response.status;duplicateMessage=$duplicateReissue.response.json.message
    }
    suspension=[ordered]@{
        userId=$suspendUserId;eventId=$suspendEventId;statusAfterSuspend=$frozenStatus
        statusAfterResume=$afterResumeStatus;suspensionStateAfterResume=$suspensionState
        frozenOpenEvents=$frozenOpenEvents;suspendProposalId=$suspend.proposalId;resumeProposalId=$resume.proposalId
    }
    overview=[ordered]@{sourceVisible=($sourceCommissionId -in $visibleIds);reissueVisible=("CM-$reissueEventId" -in $visibleIds)}
    passed=$true
    generatedAt=[DateTimeOffset]::UtcNow.ToString('o')
}
$outputPath = Join-Path ([string]$state.runDirectory) 'f5-reconciliation.json'
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $outputPath -Encoding utf8
$result | ConvertTo-Json -Depth 12
