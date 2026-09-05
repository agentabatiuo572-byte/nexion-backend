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
    $parameters = @{Method=$Method;Uri=($baseUrl+$Path);Headers=$headers;SkipHttpErrorCheck=$true;TimeoutSec=20}
    if ($null -ne $Body) {
        $parameters.ContentType='application/json'
        $parameters.Body=($Body|ConvertTo-Json -Depth 8 -Compress)
    }
    $response=Invoke-WebRequest @parameters
    $json=if($response.Content){$response.Content|ConvertFrom-Json}else{$null}
    [pscustomobject]@{status=[int]$response.StatusCode;json=$json}
}

function Require-Ok {
    param([object]$Response,[string]$Label)
    if($Response.status -lt 200 -or $Response.status -ge 300 -or $Response.json.code -ne 0){
        throw "$Label failed: HTTP $($Response.status), API $($Response.json.code), $($Response.json.message)"
    }
    return $Response.json.data
}

$accounts=@{}
foreach($account in @($manifest.roles)){$accounts[[string]$account.role]=$account}
$ownerId=[long]$accounts['R'].userId
$runTag=([string]$state.runId).ToUpperInvariant()
$date=(Get-Date).ToString('yyyy-MM-dd')
$compactDate=(Get-Date).ToString('yyyyMMdd')

$login=Require-Ok (Invoke-Api -Method POST -Path '/api/admin/auth/login' -Body @{username='superadmin';password=$adminPassword} -Token '') 'admin login'
$token=[string]$login.accessToken
if(-not $token){throw 'Admin token missing.'}

$configKeys=@(
    'team.ui.F.binary.threshold','team.ui.F.binary.matchRate','team.ui.F.binary.paused',
    'team.ui.F.binary.spillover','team.ui.F.binary.settlePeriod','team.ui.F.binary.residualPolicy'
)
$beforeConfig=@{}
foreach($key in $configKeys){
    $line=[string](& $mysql -uroot -N -B -e "SELECT id,HEX(config_value) FROM ``$database``.nx_config_item WHERE config_key='$key' AND status=1 AND is_deleted=0 ORDER BY id DESC LIMIT 1;")
    $parts=$line -split "`t"
    if($parts.Count -ne 2){throw "Missing F3 config: $key"}
    $beforeConfig[$key]=[pscustomobject]@{id=[long]$parts[0];hex=[string]$parts[1]}
}

$fixtureSql=@"
UPDATE ``$database``.nx_config_item SET config_value='1000',updated_at=NOW() WHERE id=$($beforeConfig['team.ui.F.binary.threshold'].id);
UPDATE ``$database``.nx_config_item SET config_value='13%',updated_at=NOW() WHERE id=$($beforeConfig['team.ui.F.binary.matchRate'].id);
UPDATE ``$database``.nx_config_item SET config_value='false',updated_at=NOW() WHERE id=$($beforeConfig['team.ui.F.binary.paused'].id);
UPDATE ``$database``.nx_config_item SET config_value=CONVERT(0xE5B7B2E590AFE794A8 USING utf8mb4),updated_at=NOW() WHERE id=$($beforeConfig['team.ui.F.binary.spillover'].id);
UPDATE ``$database``.nx_config_item SET config_value=CONVERT(0xE6AF8FE697A5 USING utf8mb4),updated_at=NOW() WHERE id=$($beforeConfig['team.ui.F.binary.settlePeriod'].id);
UPDATE ``$database``.nx_config_item SET config_value=CONVERT(0xE6AF8FE69C88E6B885E99BB6 USING utf8mb4),updated_at=NOW() WHERE id=$($beforeConfig['team.ui.F.binary.residualPolicy'].id);
DELETE FROM ``$database``.nx_order WHERE order_no IN ('TFA-$runTag-F3-A','TFA-$runTag-F3-B');
INSERT INTO ``$database``.nx_order
 (user_id,order_no,quantity,order_type,item_count,subtotal_usdt,discount_usdt,status,amount_usdt,
  payment_no,payment_status,order_status,activation_status,paid_at,is_deleted)
VALUES
 ($([long]$accounts['A1'].userId),'TFA-$runTag-F3-A',1,'SINGLE',1,50000,0,'PAID',50000,'PAY-TFA-$runTag-F3-A','PAID','PAID','ACTIVE',NOW(),0),
 ($([long]$accounts['B1'].userId),'TFA-$runTag-F3-B',1,'SINGLE',1,50000,0,'PAID',50000,'PAY-TFA-$runTag-F3-B','PAID','PAID','ACTIVE',NOW(),0);
"@
& $mysql -uroot -e $fixtureSql
if($LASTEXITCODE -ne 0){throw 'F3 configuration/order fixture failed.'}

$afterConfigPath=Join-Path ([string]$state.runDirectory) 'f3-config-effective.tsv'
& $mysql -uroot -N -B -e "SELECT config_key,config_value,updated_at FROM ``$database``.nx_config_item WHERE id IN ($($beforeConfig.Values.id -join ',')) ORDER BY config_key;" |
    Set-Content -LiteralPath $afterConfigPath -Encoding utf8

$assignments=[ordered]@{A='A';B='B';C='A';Q='B'}
$assignmentResults=[System.Collections.Generic.List[object]]::new()
foreach($role in $assignments.Keys){
    $data=Require-Ok (Invoke-Api -Method POST -Path '/api/admin/teams/binary/assignments' -Body @{
        ownerUserId=$ownerId;memberUserId=[long]$accounts[$role].userId;leg=$assignments[$role]
    } -Token $token) "assign $role"
    $assignmentResults.Add($data)
}
$assignmentReplay=Require-Ok (Invoke-Api -Method POST -Path '/api/admin/teams/binary/assignments' -Body @{
    ownerUserId=$ownerId;memberUserId=[long]$accounts['A'].userId;leg='A'
} -Token $token) 'assignment replay'
$immutableResponse=Invoke-Api -Method POST -Path '/api/admin/teams/binary/assignments' -Body @{
    ownerUserId=$ownerId;memberUserId=[long]$accounts['A'].userId;leg='B'
} -Token $token
if($immutableResponse.status -ne 409 -or $immutableResponse.json.message -ne 'BINARY_LEG_ASSIGNMENT_IMMUTABLE'){
    throw "F3 immutable assignment mismatch: $($immutableResponse.status)/$($immutableResponse.json.message)"
}

$reserveNo="TFA-$runTag-B1-RESERVE"
& $mysql -uroot -e "DELETE FROM ``$database``.nx_treasury_reserve_ledger WHERE reserve_no='$reserveNo';"
if($LASTEXITCODE -ne 0){throw 'F3 B1 blocked fixture failed.'}
$blocked=Require-Ok (Invoke-Api -Method POST -Path '/api/admin/teams/binary/settlements' -Body @{
    ownerUserId=$ownerId;settlementDate=$date;reason='Acceptance verifies B1 blocked binary settlement.'
} -Token $token -IdempotencyKey "tfa-$runTag-f3-b1") 'F3 B1 blocked settlement'
if($blocked.status -ne 'BLOCKED' -or $blocked.reason -ne 'COVERAGE_BELOW_REDLINE'){
    throw "F3 B1 block mismatch: $($blocked.status)/$($blocked.reason)"
}
& $mysql -uroot -e "INSERT INTO ``$database``.nx_treasury_reserve_ledger(reserve_no,voucher_no,direction,amount_usd,reason,operator,idempotency_key,status,is_deleted) VALUES('$reserveNo','V-$reserveNo','IN',1000000,'isolated acceptance coverage fixture','team-acceptance','$reserveNo','CONFIRMED',0);"
if($LASTEXITCODE -ne 0){throw 'F3 B1 coverage restore failed.'}

$settlementNo="BINARY-$ownerId-$compactDate"
$eventBefore=[int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_commission_event WHERE order_no='$settlementNo' AND commission_type='binary' AND is_deleted=0;")
$reason='Acceptance verifies F3 matching, cap, idempotency and concurrency.'
$settled=Require-Ok (Invoke-Api -Method POST -Path '/api/admin/teams/binary/settlements' -Body @{
    ownerUserId=$ownerId;settlementDate=$date;reason=$reason
} -Token $token -IdempotencyKey "tfa-$runTag-f3-settle") 'F3 settlement'
$replayed=Require-Ok (Invoke-Api -Method POST -Path '/api/admin/teams/binary/settlements' -Body @{
    ownerUserId=$ownerId;settlementDate=$date;reason=$reason
} -Token $token -IdempotencyKey "tfa-$runTag-f3-settle") 'F3 idempotent replay'

$jobScript={param($url,$auth,$owner,$settlementDate,$key,$bodyReason)
    $r=Invoke-WebRequest -Method Post -Uri "$url/api/admin/teams/binary/settlements" -Headers @{Authorization="Bearer $auth";'Idempotency-Key'=$key} -ContentType 'application/json' -Body (@{ownerUserId=$owner;settlementDate=$settlementDate;reason=$bodyReason}|ConvertTo-Json -Compress) -SkipHttpErrorCheck
    [pscustomobject]@{http=[int]$r.StatusCode;body=$r.Content}
}
$jobs=1..3|ForEach-Object{Start-Job -ScriptBlock $jobScript -ArgumentList $baseUrl,$token,$ownerId,$date,"tfa-$runTag-f3-concurrent-$_",$reason}
$jobRows=@($jobs|Wait-Job|Receive-Job)
$jobs|Remove-Job -Force
$concurrent=@($jobRows|ForEach-Object{
    $body=$_.body|ConvertFrom-Json
    [pscustomobject]@{http=$_.http;code=$body.code;status=$body.data.status;amountUsdt=[decimal]$body.data.amountUsdt;eventId=[long]$body.data.commissionEventId}
})
if(@($concurrent|Where-Object{$_.http-ne 200-or$_.code-ne 0-or$_.eventId-ne [long]$settled.commissionEventId}).Count -ne 0){
    throw 'F3 concurrent settlement responses diverged.'
}

$eventAfter=[int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_commission_event WHERE order_no='$settlementNo' AND commission_type='binary' AND is_deleted=0;")
$ledgerCount=[int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_wallet_ledger WHERE biz_no='F3-BINARY-$ownerId-$compactDate' AND asset='USDT' AND direction='IN' AND is_deleted=0;")
$expectedLeft=[decimal]61000
$expectedRight=[decimal]60500
$expectedConsumed=[Math]::Floor(([decimal]5000/[decimal]0.13)*1000000)/1000000
$expectedAmount=[Math]::Floor(($expectedConsumed*[decimal]0.13)*1000000)/1000000
if($settled.status-ne'PENDING'-or [decimal]$settled.leftVolume-ne$expectedLeft-or [decimal]$settled.rightVolume-ne$expectedRight-or
   [decimal]$settled.matchedVolume-ne$expectedConsumed-or [decimal]$settled.amountUsdt-ne$expectedAmount-or
   $eventAfter-$eventBefore-ne 1-or$ledgerCount-ne 1-or [long]$replayed.commissionEventId-ne [long]$settled.commissionEventId){
    throw "F3 reconciliation mismatch: left=$($settled.leftVolume), right=$($settled.rightVolume), matched=$($settled.matchedVolume), amount=$($settled.amountUsdt), events=$eventBefore->$eventAfter, ledgers=$ledgerCount"
}

$eventPath=Join-Path ([string]$state.runDirectory) 'f3-event-ledger.tsv'
& $mysql -uroot -N -B -e "SELECT e.id,e.user_id,e.order_no,e.order_amount_usd,e.amount_usdt,e.currency,e.status,l.biz_no,l.amount,l.status FROM ``$database``.nx_commission_event e JOIN ``$database``.nx_wallet_ledger l ON l.biz_no='F3-BINARY-$ownerId-$compactDate' WHERE e.id=$([long]$settled.commissionEventId) AND e.is_deleted=0 AND l.is_deleted=0;" |
    Set-Content -LiteralPath $eventPath -Encoding utf8

$restoreSql=[Text.StringBuilder]::new()
foreach($key in $configKeys){
    $row=$beforeConfig[$key]
    [void]$restoreSql.AppendLine("UPDATE ``$database``.nx_config_item SET config_value=CONVERT(0x$($row.hex) USING utf8mb4),updated_at=NOW() WHERE id=$($row.id);")
}
& $mysql -uroot -e $restoreSql.ToString()
if($LASTEXITCODE -ne 0){throw 'F3 config restoration failed.'}
$restoredPath=Join-Path ([string]$state.runDirectory) 'f3-config-restored.tsv'
& $mysql -uroot -N -B -e "SELECT config_key,HEX(config_value),updated_at FROM ``$database``.nx_config_item WHERE id IN ($($beforeConfig.Values.id -join ',')) ORDER BY config_key;" |
    Set-Content -LiteralPath $restoredPath -Encoding utf8

$result=[ordered]@{
    runId=[string]$state.runId
    assignments=$assignmentResults
    assignmentReplay=$assignmentReplay
    immutableAssignment=[ordered]@{http=$immutableResponse.status;message=$immutableResponse.json.message}
    b1Blocked=[ordered]@{status=$blocked.status;reason=$blocked.reason;amountUsdt=$blocked.amountUsdt}
    expected=[ordered]@{leftVolume=$expectedLeft;rightVolume=$expectedRight;matchedVolume=$expectedConsumed;amountUsdt=$expectedAmount;matchRate=0.13;dailyCap=5000}
    actual=$settled
    idempotentReplay=$replayed
    concurrentResponses=$concurrent
    commissionEventDelta=$eventAfter-$eventBefore
    ledgerRows=$ledgerCount
    configRestored=$true
    passed=$true
    generatedAt=(Get-Date).ToUniversalTime().ToString('o')
}
$resultPath=Join-Path ([string]$state.runDirectory) 'f3-reconciliation.json'
[IO.File]::WriteAllText($resultPath,($result|ConvertTo-Json -Depth 9),[Text.UTF8Encoding]::new($false))
$result|ConvertTo-Json -Depth 9

$token=$null
$adminPassword=$null
