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
$runDirectory = [string]$state.runDirectory
$manifest = Get-Content -LiteralPath ([string]$state.manifestPath) -Raw | ConvertFrom-Json
if (@($manifest.roles).Count -ne 17) { throw 'Exactly 17 manifest roles are required.' }

$mysql = 'D:\software\MySQL\MySQL Server 8.0\bin\mysql.exe'
if (-not $DatabasePassword) { throw 'NEXION_ACCEPTANCE_DB_PASSWORD or -DatabasePassword is required.' }
$env:MYSQL_PWD = $DatabasePassword

function Insert-CheckoutOutbox {
    param([string]$EventId,[string]$PayloadSql)
    if ($EventId -notmatch '^[a-f0-9]{32}$') { throw "Unsafe event id: $EventId" }
    & $mysql -uroot -e @"
INSERT INTO ``$database``.nx_event_outbox
  (event_id,aggregate_type,aggregate_id,event_type,event_name,family_key,event_ts,phase,
   account_age_months,cohort,is_server_authoritative,schema_revision,schema_registered,
   analytics_event,payload,status,retry_count,is_deleted)
VALUES
  ('$EventId','ORDER','$orderNo','checkout.completed','checkout.completed','commerce',NOW(3),'P1',
   0,'2026-W35',1,NULL,0,0,$PayloadSql,'PENDING',0,0);
"@
    if ($LASTEXITCODE -ne 0) { throw "Outbox fixture insert failed: $EventId" }
}

function Wait-Outbox {
    param([string]$EventId,[string]$ExpectedStatus,[int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $status = [string](& $mysql -uroot -N -B -e "SELECT status FROM ``$database``.nx_event_outbox WHERE event_id='$EventId';")
        if ($status -eq $ExpectedStatus) { return }
        if ($status -in @('FAILED','DEAD')) {
            $error = [string](& $mysql -uroot -N -B -e "SELECT COALESCE(last_error,'') FROM ``$database``.nx_event_outbox WHERE event_id='$EventId';")
            throw "Outbox $EventId failed: $error"
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for outbox $EventId ($status)"
}

function Wait-F2MoneyChain {
    param([string]$OrderNo,[int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $eventCount = [int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_commission_event WHERE order_no='$OrderNo' AND is_deleted=0;")
        $ledgerCount = [int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_wallet_ledger WHERE remark LIKE CONCAT('%', '$OrderNo', '%') AND is_deleted=0;")
        if ($eventCount -eq 14 -and $ledgerCount -eq 14) { return }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for F2 money chain ($eventCount events, $ledgerCount ledgers)"
}

function Read-ConfigDecimal {
    param([string]$Key,[decimal]$Fallback)
    $raw = [string](& $mysql -uroot -N -B -e "SELECT config_value FROM ``$database``.nx_config_item WHERE config_key='$Key' AND status=1 AND is_deleted=0 ORDER BY id DESC LIMIT 1;")
    if (-not $raw) { return $Fallback }
    $parsed = [decimal]0
    if ([decimal]::TryParse(($raw.Trim().Replace('%','').Replace('×','')),[ref]$parsed)) { return $parsed }
    return $Fallback
}

function Read-MonthlyNetworkVolume {
    param([long]$UserId)
    $query = @"
WITH RECURSIVE subtree AS (
  SELECT member_user_id,1 AS depth FROM ``$database``.nx_team_member
   WHERE user_id=$UserId AND level=1 AND is_deleted=0
  UNION ALL
  SELECT c.member_user_id,s.depth+1 FROM subtree s
    JOIN ``$database``.nx_team_member c ON c.user_id=s.member_user_id AND c.level=1 AND c.is_deleted=0
   WHERE s.depth<7
)
SELECT COALESCE(SUM(o.subtotal_usdt),0) FROM subtree s
LEFT JOIN ``$database``.nx_order o ON o.user_id=s.member_user_id
 AND o.payment_status IN ('PAID','CONFIRMED','SUCCESS')
 AND o.order_status NOT IN ('REFUNDED','CHARGEBACK')
 AND DATE_FORMAT(COALESCE(o.paid_at,o.created_at),'%Y-%m')=DATE_FORMAT(NOW(),'%Y-%m')
 AND o.is_deleted=0;
"@
    return [decimal](& $mysql -uroot -N -B -e $query)
}

$accounts = @{}
foreach ($account in @($manifest.roles)) {
    $id = [long]$account.userId
    if ($id -le 0) { throw "Invalid user id for $($account.role)" }
    $accounts[[string]$account.role] = $account
}

$sponsors = [ordered]@{
    R=$null; A='R'; A1='A'; A11='A1'; A12='A11'; A13='A12'; A14='A13'; Buyer='A14';
    A2='A'; A3='A'; B='R'; B1='B'; B2='B'; B3='B'; C='R'; Q='R'; O=$null
}
$relations = [System.Collections.Generic.List[object]]::new()
foreach ($role in $sponsors.Keys) {
    $relations.Add([pscustomobject]@{ owner=$role; member=$role; level=0 })
    $cursor = $role
    $level = 0
    while ($sponsors[$cursor]) {
        $owner = [string]$sponsors[$cursor]
        $level++
        $relations.Add([pscustomobject]@{ owner=$owner; member=$role; level=$level })
        if ($level -ge 7) { break }
        $cursor = $owner
    }
}

$sql = [Text.StringBuilder]::new()
[void]$sql.AppendLine("DELETE FROM ``$database``.nx_team_member;")
foreach ($relation in $relations) {
    $ownerId = [long]$accounts[$relation.owner].userId
    $memberId = [long]$accounts[$relation.member].userId
    $level = [int]$relation.level
    [void]$sql.AppendLine(@"
INSERT INTO ``$database``.nx_team_member
  (user_id,member_user_id,member_no,nickname,v_rank,level,volume,is_deleted)
SELECT $ownerId,$memberId,CONCAT('ACC-',id),nickname,v_rank,$level,0,0
  FROM ``$database``.nx_user WHERE id=$memberId;
"@)
}

foreach ($role in @('A14','A13','A12','A11','A1','A','R')) {
    $id = [long]$accounts[$role].userId
    [void]$sql.AppendLine("UPDATE ``$database``.nx_team_member SET v_rank='V2' WHERE user_id=$id AND member_user_id=$id AND level=0 AND is_deleted=0;")
}
$buyerFixtureId = [long]$accounts['Buyer'].userId
[void]$sql.AppendLine("UPDATE ``$database``.nx_team_member SET v_rank='V12' WHERE user_id=$buyerFixtureId AND member_user_id=$buyerFixtureId AND level=0 AND is_deleted=0;")
& $mysql -uroot -e $sql.ToString()
if ($LASTEXITCODE -ne 0) { throw 'Team closure fixture creation failed.' }

$buyerId = [long]$accounts['Buyer'].userId
$orderNo = ('TFA-' + [string]$state.runId + '-F2-01').ToUpperInvariant()
if ($orderNo.Length -gt 64 -or $orderNo -notmatch '^[A-Z0-9-]+$') { throw "Unsafe order number: $orderNo" }
$eventId1 = [Guid]::NewGuid().ToString('N')
$eventId2 = [Guid]::NewGuid().ToString('N')
$payloadSql = "JSON_OBJECT('user_id',$buyerId,'order_no','$orderNo','order_subtotal_usdt',1000,'is_server_authoritative',true)"

Insert-CheckoutOutbox -EventId $eventId1 -PayloadSql $payloadSql
Wait-Outbox -EventId $eventId1 -ExpectedStatus 'PUBLISHED' -TimeoutSeconds 30
Wait-F2MoneyChain -OrderNo $orderNo -TimeoutSeconds 30

$eventTsv = Join-Path $runDirectory 'f2-commission-events.tsv'
$ledgerTsv = Join-Path $runDirectory 'f2-wallet-ledger.tsv'
& $mysql -uroot -N -B -e "SELECT user_id,source_user_id,layer_no,currency,amount_usdt,amount_nex,status,order_no,unlock_at,id FROM ``$database``.nx_commission_event WHERE order_no='$orderNo' AND is_deleted=0 ORDER BY layer_no,currency;" |
    Set-Content -LiteralPath $eventTsv -Encoding utf8
& $mysql -uroot -N -B -e "SELECT user_id,biz_no,biz_type,asset,direction,amount,status,id FROM ``$database``.nx_wallet_ledger WHERE remark LIKE CONCAT('%', '$orderNo', '%') AND is_deleted=0 ORDER BY user_id,asset,id;" |
    Set-Content -LiteralPath $ledgerTsv -Encoding utf8

$eventRows = @(Get-Content -LiteralPath $eventTsv | Where-Object { $_.Trim() } | ForEach-Object {
    $columns = $_ -split "`t"
    [pscustomobject]@{
        userId=[long]$columns[0]; sourceUserId=[long]$columns[1]; layer=[int]$columns[2]; currency=$columns[3]
        amountUsdt=[decimal]$columns[4]; amountNex=[decimal]$columns[5]; status=$columns[6]; orderNo=$columns[7]
        unlockAt=$columns[8]; eventId=[long]$columns[9]
    }
})
if ($eventRows.Count -ne 14) { throw "Expected 14 F2 commission events, got $($eventRows.Count)" }

$rates = @{}
$rateLines = @(& $mysql -uroot -N -B -e "SELECT layer_no,usdt_rate,nex_per_usd FROM ``$database``.nx_commission_rule WHERE LOWER(commission_type)='unilevel' AND status=1 AND is_deleted=0 ORDER BY layer_no;")
foreach ($line in $rateLines) {
    $parts = $line -split "`t"
    $rates[[int]$parts[0]] = [pscustomobject]@{ usdt=[decimal]$parts[1]; nex=[decimal]$parts[2] }
}

$upline = @('A14','A13','A12','A11','A1','A','R')
$expected = [System.Collections.Generic.List[object]]::new()
$allocated = [decimal]0
$clampMin = Read-ConfigDecimal -Key 'team.ui.F.influence.clampMin' -Fallback 1
$clampMax = Read-ConfigDecimal -Key 'team.ui.F.influence.clampMax' -Fallback 5
$promoMultiplier = Read-ConfigDecimal -Key 'team.ui.F.promo.weekMultiplier' -Fallback 1
$mergeExitMaxPct = Read-ConfigDecimal -Key 'team.ui.F.unilevel.mergeExitMaxPct' -Fallback 25
$mergeExitCap = [Math]::Floor(([decimal]1000 * $mergeExitMaxPct / 100) * 1000000) / 1000000
for ($index=0; $index -lt $upline.Count; $index++) {
    $layer = $index + 1
    $rate = $rates[$layer]
    $recipient = [long]$accounts[$upline[$index]].userId
    $volume = if ($layer -eq 1) { [decimal]0 } else { Read-MonthlyNetworkVolume -UserId $recipient }
    $score = [decimal]1
    if ($layer -ge 2) {
        $rawScore = if ($volume -le 0) { [double]$clampMin } else { 1 + [Math]::Log10([Math]::Max([double]$volume,1) / 100) }
        $score = [Math]::Round([decimal]([Math]::Max([double]$clampMin,[Math]::Min([double]$clampMax,$rawScore))),6,[MidpointRounding]::AwayFromZero)
    }
    $baseUsdt = [Math]::Round([decimal]1000 * $rate.usdt, 6, [MidpointRounding]::AwayFromZero)
    $usdt = [Math]::Round($baseUsdt * $score,6,[MidpointRounding]::AwayFromZero)
    $usdt = [Math]::Round($usdt * $promoMultiplier,6,[MidpointRounding]::AwayFromZero)
    $remaining = [Math]::Max([decimal]0,$mergeExitCap - $allocated)
    if ($usdt -gt $remaining) { $usdt = $remaining }
    $usdt = [Math]::Floor($usdt * 1000000) / 1000000
    $nex = [Math]::Round($usdt * $rate.nex, 6, [MidpointRounding]::AwayFromZero)
    $expected.Add([pscustomobject]@{ userId=$recipient; sourceUserId=$buyerId; layer=$layer; currency='USDT'; amount=$usdt;monthlyNetworkVolume=$volume;influenceScore=$score })
    $expected.Add([pscustomobject]@{ userId=$recipient; sourceUserId=$buyerId; layer=$layer; currency='NEX'; amount=$nex;monthlyNetworkVolume=$volume;influenceScore=$score })
    $allocated += $usdt
}
foreach ($item in $expected) {
    $actual = $eventRows | Where-Object { $_.userId -eq $item.userId -and $_.layer -eq $item.layer -and $_.currency -eq $item.currency }
    if (@($actual).Count -ne 1) { throw "Missing or duplicate F2 event for L$($item.layer) $($item.currency)" }
    $actualAmount = if ($item.currency -eq 'USDT') { [decimal]$actual.amountUsdt } else { [decimal]$actual.amountNex }
    if ($actualAmount -ne [decimal]$item.amount) {
        throw "F2 mismatch L$($item.layer) $($item.currency): expected $($item.amount), got $actualAmount"
    }
    if ($actual.sourceUserId -ne $buyerId -or $actual.status -ne 'COOLING') {
        throw "F2 provenance/status mismatch L$($item.layer) $($item.currency)"
    }
}

$ledgerRows = @(Get-Content -LiteralPath $ledgerTsv | Where-Object { $_.Trim() })
if ($ledgerRows.Count -ne 14) { throw "Expected 14 F2 ledger rows, got $($ledgerRows.Count)" }

$countBeforeReplay = $eventRows.Count
Insert-CheckoutOutbox -EventId $eventId2 -PayloadSql $payloadSql
Wait-Outbox -EventId $eventId2 -ExpectedStatus 'PUBLISHED' -TimeoutSeconds 30
$countAfterReplay = [int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_commission_event WHERE order_no='$orderNo' AND is_deleted=0;")
if ($countAfterReplay -ne $countBeforeReplay) { throw "Idempotency failed: $countBeforeReplay -> $countAfterReplay" }

$outboxTsv = Join-Path $runDirectory 'f2-outbox.tsv'
& $mysql -uroot -N -B -e "SELECT event_id,event_type,status,retry_count,COALESCE(last_error,'') FROM ``$database``.nx_event_outbox WHERE event_id IN ('$eventId1','$eventId2') ORDER BY id;" |
    Set-Content -LiteralPath $outboxTsv -Encoding utf8

$result = [ordered]@{
    runId = [string]$state.runId
    orderNo = $orderNo
    buyerUserId = $buyerId
    outboxEventIds = @($eventId1,$eventId2)
    expectedCommissionEvents = 14
    actualCommissionEvents = $countAfterReplay
    actualLedgerRows = $ledgerRows.Count
    duplicateEventsAfterReplay = $countAfterReplay - $countBeforeReplay
    totalUsdt = [Math]::Round(($eventRows | Measure-Object amountUsdt -Sum).Sum,6)
    totalNex = [Math]::Round(($eventRows | Measure-Object amountNex -Sum).Sum,6)
    capUsdt = $mergeExitCap
    promoMultiplier = $promoMultiplier
    influenceClamp = [ordered]@{min=$clampMin;max=$clampMax}
    expectedRecords = $expected
    coolingFallbackDays = 30
    passed = $true
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
}
$resultPath = Join-Path $runDirectory 'f2-reconciliation.json'
[IO.File]::WriteAllText($resultPath, ($result | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
$result | ConvertTo-Json -Depth 5
