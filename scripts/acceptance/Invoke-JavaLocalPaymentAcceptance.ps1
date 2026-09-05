[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$StatePath,
    [string]$DatabasePassword = $env:NEXION_ACCEPTANCE_DB_PASSWORD,
    [long]$ProductId = 4,
    [decimal]$OpeningBalanceUsdt = 2000
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $DatabasePassword) { throw 'NEXION_ACCEPTANCE_DB_PASSWORD or -DatabasePassword is required.' }

$resolvedState = (Resolve-Path -LiteralPath $StatePath).Path
$state = Get-Content -LiteralPath $resolvedState -Raw | ConvertFrom-Json
$database = [string]$state.database
$runDirectory = [IO.Path]::GetFullPath([string]$state.runDirectory).TrimEnd('\')
$stateDirectory = [IO.Path]::GetFullPath((Split-Path -Parent $resolvedState)).TrimEnd('\')
if ($database -notmatch '^nexion_team_acceptance_[a-z0-9_]{8,64}$' -or $runDirectory -ne $stateDirectory -or [int]$state.backendPort -lt 18000 -or [int]$state.backendPort -gt 18999) {
    throw 'Refusing Java payment proof outside an isolated team acceptance runtime.'
}

$mysql = 'D:\software\MySQL\MySQL Server 8.0\bin\mysql.exe'
$env:MYSQL_PWD = $DatabasePassword
function Invoke-MySql {
    param([Parameter(Mandatory)][string]$Sql)
    $rows = & $mysql -uroot -N -B "--execute=$Sql"
    if ($LASTEXITCODE -ne 0) { throw 'Acceptance MySQL command failed.' }
    return @($rows)
}

$manifest = Get-Content -LiteralPath ([string]$state.manifestPath) -Raw | ConvertFrom-Json
$buyer = @($manifest.roles | Where-Object { $_.role -eq 'Buyer' })
if ($buyer.Count -ne 1) { throw 'Buyer role missing from TeamTestRunManifest.' }
$buyerId = [long]$buyer[0].userId
$buyerIp = [string]$buyer[0].sourceIp
$depthGateRoleIds = @($manifest.roles | Where-Object { $_.role -in @('A11','A1','A','R') } | ForEach-Object { [long]$_.userId })
if ($depthGateRoleIds.Count -ne 4) { throw 'Depth-gate ancestor roles missing from TeamTestRunManifest.' }
$depthGateIdsSql = $depthGateRoleIds -join ','
$accounts = @{}
foreach ($account in @($manifest.roles)) { $accounts[[string]$account.role] = $account }
$sponsors = [ordered]@{
    R=$null; A='R'; A1='A'; A11='A1'; A12='A11'; A13='A12'; A14='A13'; Buyer='A14';
    A2='A'; A3='A'; B='R'; B1='B'; B2='B'; B3='B'; C='R'; Q='R'; O=$null
}

$credentialBytes = [Convert]::FromBase64String((Get-Content -LiteralPath ([string]$state.credentialPath) -Raw))
$plainBytes = [System.Security.Cryptography.ProtectedData]::Unprotect(
    $credentialBytes, $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
$credentials = [Text.Encoding]::UTF8.GetString($plainBytes) | ConvertFrom-Json
try {
    $phoneRows = @(Invoke-MySql "SELECT country_code,phone FROM ``$database``.nx_user WHERE id=$buyerId AND status='ACTIVE' AND is_deleted=0 AND sandbox=0;")
    if ($phoneRows.Count -ne 1) { throw 'Canonical acceptance buyer not found.' }
    $phoneParts = ([string]$phoneRows[0]) -split "`t", 2
    if ($phoneParts.Count -ne 2) { throw 'Acceptance buyer phone projection invalid.' }

    Invoke-MySql "INSERT INTO ``$database``.nx_product SELECT * FROM nexion.nx_product WHERE id=$ProductId AND is_deleted=0 ON DUPLICATE KEY UPDATE product_no=VALUES(product_no); INSERT INTO ``$database``.nx_admin_device_sku SELECT s.* FROM nexion.nx_admin_device_sku s JOIN nexion.nx_product p ON p.product_no=s.sku_id WHERE p.id=$ProductId AND s.is_deleted=0 ON DUPLICATE KEY UPDATE sku_id=VALUES(sku_id); INSERT INTO ``$database``.nx_admin_phase_config SELECT * FROM nexion.nx_admin_phase_config WHERE scope='E1' AND status='active' AND is_deleted=0 ON DUPLICATE KEY UPDATE label=VALUES(label); UPDATE ``$database``.nx_product SET unlock_phase=NULL WHERE id=$ProductId; UPDATE ``$database``.nx_user_wallet SET usdt_available=$OpeningBalanceUsdt,version=0 WHERE user_id=$buyerId AND is_deleted=0 AND NOT EXISTS (SELECT 1 FROM ``$database``.nx_order o WHERE o.user_id=$buyerId AND o.payment_status='PAID' AND o.is_deleted=0); UPDATE ``$database``.nx_config_item SET config_value='1.0' WHERE config_key IN ('team.ui.F.influence.clampMin','team.ui.F.influence.clampMax') AND is_deleted=0;"
    $closureSql = [Text.StringBuilder]::new('DELETE FROM `').Append($database).Append('`.nx_team_member;').AppendLine()
    foreach ($role in $sponsors.Keys) {
        $cursor = $role
        $level = 0
        while ($true) {
            $ownerId = [long]$accounts[$cursor].userId
            $memberId = [long]$accounts[$role].userId
            [void]$closureSql.Append("INSERT INTO ``$database``.nx_team_member (user_id,member_user_id,member_no,nickname,v_rank,level,volume,is_deleted) SELECT $ownerId,$memberId,CONCAT('PAY-',id),nickname,v_rank,$level,0,0 FROM ``$database``.nx_user WHERE id=$memberId;").AppendLine()
            if (-not $sponsors[$cursor] -or $level -ge 7) { break }
            $cursor = [string]$sponsors[$cursor]
            $level++
        }
    }
    [void]$closureSql.Append("UPDATE ``$database``.nx_user SET v_rank='V2' WHERE id IN ($depthGateIdsSql) AND is_deleted=0;").AppendLine()
    [void]$closureSql.Append("UPDATE ``$database``.nx_team_member SET v_rank='V2' WHERE user_id=member_user_id AND user_id IN ($depthGateIdsSql) AND is_deleted=0;")
    Invoke-MySql $closureSql.ToString()

    $baseUrl = "http://127.0.0.1:$([int]$state.backendPort)"
    $loginBody = @{ countryCode = $phoneParts[0]; phone = $phoneParts[1]; password = [string]$credentials.commonPassword } | ConvertTo-Json
    $login = Invoke-RestMethod -Uri "$baseUrl/auth/users/login" -Method Post -ContentType 'application/json' -Body $loginBody
    if ([int]$login.code -ne 0 -or -not $login.data.accessToken) { throw 'Acceptance buyer login failed.' }
    $headers = @{ Authorization = "Bearer $($login.data.accessToken)" }

    $createKey = "TEAM-PAY-CREATE-$($state.runId)-V4"
    $createHeaders = $headers.Clone()
    $createHeaders['Idempotency-Key'] = $createKey
    $created = Invoke-RestMethod -Uri "$baseUrl/api/orders" -Method Post -Headers $createHeaders -ContentType 'application/json' -Body (@{ productId = $ProductId; quantity = 1 } | ConvertTo-Json)
    if ([int]$created.code -ne 0 -or -not $created.data.orderNo) { throw "Java order creation failed: $($created.message)" }
    $orderNo = [string]$created.data.orderNo

    $payKey = "TEAM-PAY-CONFIRM-$($state.runId)"
    $payHeaders = $headers.Clone()
    $payHeaders['Idempotency-Key'] = $payKey
    $paid = Invoke-RestMethod -Uri "$baseUrl/api/orders/$orderNo/pay" -Method Post -Headers $payHeaders
    if ([int]$paid.code -ne 0 -or $paid.data.canonicalStatus -ne 'activated') { throw "Java payment failed: $($paid.message)" }

    $deadline = (Get-Date).AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 500
        $counts = @(Invoke-MySql "SELECT (SELECT COUNT(*) FROM ``$database``.nx_event_outbox WHERE aggregate_id='$orderNo' AND event_type='checkout.completed'),(SELECT COUNT(*) FROM ``$database``.nx_commission_event WHERE order_no='$orderNo'),(SELECT COUNT(*) FROM ``$database``.nx_wallet_ledger WHERE biz_no='$orderNo' AND biz_type='ORDER_PURCHASE'),(SELECT COUNT(*) FROM ``$database``.nx_user_device WHERE source_order_no='$orderNo' AND status='ACTIVE' AND is_deleted=0),(SELECT COUNT(*) FROM ``$database``.nx_wallet_ledger WHERE biz_type='TEAM_COMMISSION' AND remark LIKE CONCAT('%','$orderNo','%'));")
        $countParts = ([string]$counts[0]) -split "`t"
    } while ((Get-Date) -lt $deadline -and ([int]$countParts[0] -lt 1 -or [int]$countParts[1] -lt 14))

    $beforeReplay = @(Invoke-MySql "SELECT (SELECT COUNT(*) FROM ``$database``.nx_commission_event WHERE order_no='$orderNo'),(SELECT COUNT(*) FROM ``$database``.nx_wallet_ledger WHERE biz_no LIKE CONCAT('%','$orderNo','%')),(SELECT usdt_available FROM ``$database``.nx_user_wallet WHERE user_id=$buyerId AND is_deleted=0),(SELECT payment_no FROM ``$database``.nx_order WHERE order_no='$orderNo');")
    $beforeParts = ([string]$beforeReplay[0]) -split "`t"
    $replayed = Invoke-RestMethod -Uri "$baseUrl/api/orders/$orderNo/pay" -Method Post -Headers $payHeaders
    $afterReplay = @(Invoke-MySql "SELECT (SELECT COUNT(*) FROM ``$database``.nx_commission_event WHERE order_no='$orderNo'),(SELECT COUNT(*) FROM ``$database``.nx_wallet_ledger WHERE biz_no LIKE CONCAT('%','$orderNo','%')),(SELECT usdt_available FROM ``$database``.nx_user_wallet WHERE user_id=$buyerId AND is_deleted=0);")
    $afterParts = ([string]$afterReplay[0]) -split "`t"

    $commissionRows = @(Invoke-MySql "SELECT id,user_id,layer_no,currency,COALESCE(amount_usdt,0),COALESCE(amount_nex,0),status FROM ``$database``.nx_commission_event WHERE order_no='$orderNo' ORDER BY layer_no,currency,id;")
    $eventRows = @(Invoke-MySql "SELECT event_id,status,retry_count FROM ``$database``.nx_event_outbox WHERE aggregate_id='$orderNo' AND event_type='checkout.completed' ORDER BY id;")
    $assertions = [ordered]@{
        createServerCanonical = [string]$created.data.idSource -eq 'server'
        paidAndActivated = $paid.data.paymentStatus -eq 'PAID' -and $paid.data.orderStatus -eq 'COMPLETED' -and $paid.data.canonicalStatus -eq 'activated'
        purchaseLedgerExactlyOnce = [int]$countParts[2] -eq 1
        deviceActivatedExactlyOnce = [int]$countParts[3] -eq 1
        checkoutCompletedPublished = [int]$countParts[0] -eq 1
        networkCommissionRows = [int]$countParts[1] -eq 14
        commissionLedgerRows = [int]$countParts[4] -eq 14
        paymentReplayStable = [int]$replayed.code -eq 0 -and [int]$beforeParts[0] -eq [int]$afterParts[0] -and [int]$beforeParts[1] -eq [int]$afterParts[1] -and [decimal]$beforeParts[2] -eq [decimal]$afterParts[2]
    }
    if (@($assertions.Values | Where-Object { -not $_ }).Count -gt 0) {
        throw "Java payment acceptance assertion failed: $($assertions | ConvertTo-Json -Compress)"
    }

    $result = [ordered]@{
        runId = [string]$state.runId
        status = 'PASS'
        source = 'server'
        sourceEnvironment = 'PRODUCTION'
        simulatedLocalFunds = $true
        orderNo = $orderNo
        buyerUserId = $buyerId
        productId = $ProductId
        amountUsdt = [decimal]$paid.data.amountUsdt
        paymentNo = [string]$beforeParts[3]
        walletBalanceAfterUsdt = [decimal]$beforeParts[2]
        commissionRows = $commissionRows.Count
        commissionLedgerRows = [int]$countParts[4]
        commissionDetails = @($commissionRows)
        checkoutOutbox = @($eventRows)
        assertions = $assertions
        generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    }
    $resultPath = Join-Path $runDirectory 'java-local-payment-e2e.json'
    [IO.File]::WriteAllText($resultPath, ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))

    $manifest | Add-Member -NotePropertyName orderRecords -NotePropertyValue @() -Force
    $manifest.orderRecords = @([ordered]@{
        orderNo = $orderNo
        buyerUserId = $buyerId
        source = 'JAVA_LOCAL_PAYMENT'
        simulatedLocalFunds = $true
        paymentNo = [string]$beforeParts[3]
        commissionRows = $commissionRows.Count
        checkoutEventIds = @($eventRows | ForEach-Object { ([string]$_ -split "`t")[0] })
    })
    [IO.File]::WriteAllText([string]$state.manifestPath, ($manifest | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
    $result | ConvertTo-Json -Depth 8
} finally {
    [Array]::Clear($plainBytes, 0, $plainBytes.Length)
    Remove-Variable credentials -ErrorAction SilentlyContinue
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
