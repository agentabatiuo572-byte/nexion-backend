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
        Method=$Method; Uri=($baseUrl+$Path); Headers=$headers; SkipHttpErrorCheck=$true; TimeoutSec=15
    }
    if ($null -ne $Body) {
        $parameters.ContentType='application/json'
        $parameters.Body=($Body | ConvertTo-Json -Depth 8 -Compress)
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

$accounts = @{}
foreach ($account in @($manifest.roles)) { $accounts[[string]$account.role] = $account }

$credentialBytes = [Convert]::FromBase64String((Get-Content -LiteralPath ([string]$state.credentialPath) -Raw))
$plainBytes = [System.Security.Cryptography.ProtectedData]::Unprotect(
    $credentialBytes,$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser)
$credential = [Text.Encoding]::UTF8.GetString($plainBytes) | ConvertFrom-Json
$userPassword = [string]$credential.commonPassword
[Array]::Clear($plainBytes,0,$plainBytes.Length)

$adminLogin = Invoke-Api -Method POST -Path '/api/admin/auth/login' -Body @{username='superadmin';password=$adminPassword} -Token ''
$adminSession = Require-Ok $adminLogin 'admin login'
$adminToken = [string]$adminSession.accessToken
if (-not $adminToken) { throw 'Admin token missing.' }

$orderAmounts = [ordered]@{
    R=500; A=500; A1=9000; A2=500; A3=500;
    B=500; B1=9000; B2=500; B3=500; C=500
}
$runTag = ([string]$state.runId).ToUpperInvariant()
$sql = [Text.StringBuilder]::new()
foreach ($role in @('R','A','B')) {
    $id = [long]$accounts[$role].userId
    [void]$sql.AppendLine("UPDATE ``$database``.nx_team_member SET v_rank='V0' WHERE member_user_id=$id AND is_deleted=0;")
    [void]$sql.AppendLine("UPDATE ``$database``.nx_user SET v_rank='V0' WHERE id=$id AND is_deleted=0;")
}
foreach ($entry in $orderAmounts.GetEnumerator()) {
    $role = [string]$entry.Key
    $amount = [decimal]$entry.Value
    $id = [long]$accounts[$role].userId
    $orderNo = "TFA-$runTag-F1-$role"
    [void]$sql.AppendLine("DELETE FROM ``$database``.nx_order WHERE order_no='$orderNo';")
    [void]$sql.AppendLine(@"
INSERT INTO ``$database``.nx_order
  (user_id,order_no,quantity,order_type,item_count,subtotal_usdt,discount_usdt,status,
   amount_usdt,payment_no,payment_status,order_status,activation_status,paid_at,is_deleted)
VALUES
  ($id,'$orderNo',1,'SINGLE',1,$amount,0,'PAID',$amount,'PAY-$orderNo','PAID','PAID','ACTIVE',NOW(),0);
"@)
}
& $mysql -uroot -e $sql.ToString()
if ($LASTEXITCODE -ne 0) { throw 'F1 order fixture creation failed.' }

$reserveNo = "TFA-$runTag-B1-RESERVE"
$priceLabel = "TFA-$runTag-NEX"
& $mysql -uroot -e "DELETE FROM ``$database``.nx_treasury_reserve_ledger WHERE reserve_no='$reserveNo'; DELETE FROM ``$database``.nx_price_index WHERE metric_label='$priceLabel';"
if ($LASTEXITCODE -ne 0) { throw 'F1 B1 blocked-fixture reset failed.' }

$blockedRankBefore = [string](& $mysql -uroot -N -B -e "SELECT v_rank FROM ``$database``.nx_user WHERE id=$([long]$accounts['A'].userId) AND is_deleted=0;")
$blockedPayoutBefore = [int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_v_rank_reward_payout WHERE user_id=$([long]$accounts['A'].userId) AND is_deleted=0;")
$blockedResponse = Invoke-Api -Method POST -Path "/api/admin/teams/vrank/evaluate/$([long]$accounts['A'].userId)" -Body $null -Token $adminToken
$blockedRankAfter = [string](& $mysql -uroot -N -B -e "SELECT v_rank FROM ``$database``.nx_user WHERE id=$([long]$accounts['A'].userId) AND is_deleted=0;")
$blockedPayoutAfter = [int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_v_rank_reward_payout WHERE user_id=$([long]$accounts['A'].userId) AND is_deleted=0;")
if ($blockedResponse.status -ne 422 -or [int]$blockedResponse.json.code -ne 422 -or [string]$blockedResponse.json.message -ne 'COVERAGE_BELOW_REDLINE' -or $blockedRankBefore -ne 'V0' -or $blockedRankAfter -ne 'V0' -or $blockedPayoutAfter -ne $blockedPayoutBefore) {
    throw "F1 B1 rollback mismatch: HTTP $($blockedResponse.status)/API $($blockedResponse.json.code), rank $blockedRankBefore->$blockedRankAfter, payout $blockedPayoutBefore->$blockedPayoutAfter"
}

$coverageSql = @"
DELETE FROM ``$database``.nx_treasury_reserve_ledger WHERE reserve_no='$reserveNo';
INSERT INTO ``$database``.nx_treasury_reserve_ledger
  (reserve_no,voucher_no,direction,amount_usd,reason,operator,idempotency_key,status,is_deleted)
VALUES
  ('$reserveNo','V-$reserveNo','IN',1000000,'isolated acceptance coverage fixture','team-acceptance','$reserveNo','CONFIRMED',0);
DELETE FROM ``$database``.nx_price_index WHERE metric_label='$priceLabel';
INSERT INTO ``$database``.nx_price_index
  (metric_code,metric_label,unit_label,price_usdt,delta_percent,volume_24h_usdt,sparkline,status,sampled_at,is_deleted)
VALUES
  ('NEX_USDT','$priceLabel','USDT',0.05000000,0,0,JSON_ARRAY(0.05),'ACTIVE',NOW(),0);
INSERT INTO ``$database``.nx_growth_voucher
  (voucher_id,voucher_name,voucher_type,amount_usd,percent_value,min_purchase_usd,max_discount_usd,
   applicable_skus,audience,start_at,end_at,claim_surfaces,popup_enabled,issuance_limit,version,status,
   created_by,updated_by,is_deleted)
VALUES
  ('VC-F1-001','V1 isolated acceptance voucher','fixed',10,0,0,10,JSON_ARRAY(),'all',0,0,JSON_ARRAY('vrank'),0,100,1,'active','team-acceptance','team-acceptance',0),
  ('VC-F2-001','V2 isolated acceptance voucher','fixed',20,0,0,20,JSON_ARRAY(),'all',0,0,JSON_ARRAY('vrank'),0,100,1,'active','team-acceptance','team-acceptance',0)
ON DUPLICATE KEY UPDATE status='active',start_at=0,end_at=0,issuance_limit=100,is_deleted=0,updated_by='team-acceptance';
"@
& $mysql -uroot -e $coverageSql
if ($LASTEXITCODE -ne 0) { throw 'Isolated B1 coverage fixture creation failed.' }

$evaluation = [System.Collections.Generic.List[object]]::new()
foreach ($role in @('A','A','B','B')) {
    $id = [long]$accounts[$role].userId
    $response = Invoke-Api -Method POST -Path "/api/admin/teams/vrank/evaluate/$id" -Body $null -Token $adminToken
    $data = Require-Ok $response "F1 evaluate $role"
    $evaluation.Add([pscustomobject]@{ role=$role; before=$data.before; after=$data.after; promoted=$data.promoted })
}

# A/B promotion-completed events legitimately cascade into R. Let the outbox
# consume them, then invoke only the still-required R steps. The engine row lock
# must make a simultaneous manual/cascade evaluation serialize without 500s or
# duplicate payouts.
Start-Sleep -Milliseconds 1800
$rId = [long]$accounts['R'].userId
for ($attempt=1; $attempt -le 3; $attempt++) {
    $currentR = [string](& $mysql -uroot -N -B -e "SELECT v_rank FROM ``$database``.nx_user WHERE id=$rId AND is_deleted=0;")
    if ($currentR -eq 'V3') { break }
    $response = Invoke-Api -Method POST -Path "/api/admin/teams/vrank/evaluate/$rId" -Body $null -Token $adminToken
    $data = Require-Ok $response 'F1 evaluate R'
    $evaluation.Add([pscustomobject]@{ role='R'; before=$data.before; after=$data.after; promoted=$data.promoted })
    Start-Sleep -Milliseconds 350
}
Start-Sleep -Milliseconds 800

$expectedRanks = @{ A='V2'; B='V2'; R='V3' }
$rankRows = [System.Collections.Generic.List[object]]::new()
foreach ($role in $expectedRanks.Keys) {
    $id = [long]$accounts[$role].userId
    $row = @(& $mysql -uroot -N -B -e "SELECT self_row.v_rank,u.v_rank,(SELECT COUNT(*) FROM ``$database``.nx_team_member p WHERE p.member_user_id=$id AND p.v_rank=self_row.v_rank AND p.is_deleted=0),(SELECT COUNT(*) FROM ``$database``.nx_team_member p WHERE p.member_user_id=$id AND p.is_deleted=0) FROM ``$database``.nx_team_member self_row JOIN ``$database``.nx_user u ON u.id=self_row.user_id WHERE self_row.user_id=$id AND self_row.member_user_id=$id AND self_row.is_deleted=0 ORDER BY self_row.id DESC LIMIT 1;")
    $parts = $row[0] -split "`t"
    if ($parts[0] -ne $expectedRanks[$role] -or $parts[1] -ne $expectedRanks[$role]) {
        throw "$role rank projection mismatch: self=$($parts[0]) user=$($parts[1]) expected=$($expectedRanks[$role])"
    }
    if ([int]$parts[2] -ne [int]$parts[3]) { throw "$role ancestor projections are stale." }
    $rankRows.Add([pscustomobject]@{role=$role;rank=$parts[0];projectionRows=[int]$parts[3];syncedRows=[int]$parts[2]})
}

$qId = [long]$accounts['Q'].userId
$qPhoneLine = [string](& $mysql -uroot -N -B -e "SELECT country_code,phone FROM ``$database``.nx_user WHERE id=$qId;")
$qParts = $qPhoneLine -split "`t"
$qLogin = Require-Ok (Invoke-Api -Method POST -Path '/auth/users/login' -Body @{countryCode=$qParts[0];phone=$qParts[1];password=$userPassword} -Token '') 'Q login'
$qRank = Require-Ok (Invoke-Api -Method GET -Path '/api/team/rank' -Body $null -Token ([string]$qLogin.accessToken)) 'Q rank'
if ($qRank.rankCode -ne 'V0' -or [decimal]$qRank.progress.selfBuyUSD -ne 0 -or [int]$qRank.progress.directRefs -ne 0) {
    throw 'Q must remain V0 without qualified business performance.'
}

$leadershipRead = Require-Ok (Invoke-Api -Method GET -Path '/api/admin/teams/leadership-pool' -Body $null -Token $adminToken) 'F4 read'
$poolEventsBefore = [int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_commission_event WHERE commission_type='leadership' AND is_deleted=0;")
$poolLedgerBefore = [int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_wallet_ledger WHERE biz_type='TEAM_COMMISSION' AND remark LIKE 'F4%' AND is_deleted=0;")
$settleResponse = Invoke-Api -Method POST -Path '/api/admin/teams/leadership-pool/settle' -Body $null -Token $adminToken
$checkerUsername = "acceptance-checker-$([string]$state.runId)"
$checkerSql = @"
INSERT INTO ``$database``.nx_admin
  (username,password_hash,nickname,super_admin,status,version,is_deleted)
SELECT '$checkerUsername',password_hash,'Acceptance checker',1,1,0,0
  FROM ``$database``.nx_admin WHERE username='superadmin' AND is_deleted=0 LIMIT 1
ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash),super_admin=1,status=1,is_deleted=0;
INSERT INTO ``$database``.nx_admin_role_relation(admin_id,role_id,is_deleted)
SELECT checker.id,relation.role_id,0
  FROM ``$database``.nx_admin checker
  JOIN ``$database``.nx_admin source ON source.username='superadmin' AND source.is_deleted=0
  JOIN ``$database``.nx_admin_role_relation relation ON relation.admin_id=source.id AND relation.is_deleted=0
 WHERE checker.username='$checkerUsername'
ON DUPLICATE KEY UPDATE is_deleted=0;
"@
& $mysql -uroot -e $checkerSql
if ($LASTEXITCODE -ne 0) { throw 'F4 A2 checker fixture creation failed.' }
$checkerLogin = Require-Ok (Invoke-Api -Method POST -Path '/api/admin/auth/login' -Body @{username=$checkerUsername;password=$adminPassword} -Token '') 'F4 checker login'
$checkerToken = [string]$checkerLogin.accessToken
if (-not $checkerToken) {
    $challengeId = [string]$checkerLogin.mfa.challengeId
    $mfaSecret = [string]$checkerLogin.mfa.manualKey
    if (-not $challengeId -or -not $mfaSecret) { throw 'F4 checker MFA enrollment challenge missing.' }
    $mfaCode = New-TotpCode -Secret $mfaSecret
    $verified = Require-Ok (Invoke-Api -Method POST -Path '/api/admin/auth/mfa/verify' -Body @{challengeId=$challengeId;code=$mfaCode} -Token '') 'F4 checker MFA enrollment'
    $checkerToken = [string]$verified.accessToken
    $mfaCode=$null
    $mfaSecret=$null
}
if (-not $checkerToken) { throw 'F4 checker token missing.' }
$proposalNonce = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$proposalReason = 'Isolated acceptance verifies unconfigured F4 settlement has zero financial side effects.'
$proposal = Require-Ok (Invoke-Api -Method POST -Path '/api/admin/platform/audit/operations' -Token $adminToken -IdempotencyKey "tfa-$runTag-f4-propose-$proposalNonce" -Body @{
    action='F4 leadership pool settlement';obj="current-week-$proposalNonce";beforeValue='unconfigured';afterValue='settle'
    type='fund';amplifies=$true;sos=$false;roleGate='superadmin';reason=$proposalReason;sourceDomain='F'
    command=@{domain='F';op='f4_pool_settle';params=@{operator='team-acceptance';reason=$proposalReason}}
    target=@{domain='F';type='leadership_pool';id="current-week-$proposalNonce"}
}) 'F4 A2 proposal'
$approvedResponse = Invoke-Api -Method POST -Path "/api/admin/platform/audit/operations/$($proposal.id)/approve" -Token $checkerToken -IdempotencyKey "tfa-$runTag-f4-approve-$proposalNonce" -Body @{reason='Independent checker approves isolated unconfigured guard verification.'}
$poolEventsAfter = [int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_commission_event WHERE commission_type='leadership' AND is_deleted=0;")
$poolLedgerAfter = [int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_wallet_ledger WHERE biz_type='TEAM_COMMISSION' AND remark LIKE 'F4%' AND is_deleted=0;")
if ($settleResponse.status -ne 409 -or $settleResponse.json.message -ne 'A2_CONFIRMATION_REQUIRED' -or
    $approvedResponse.status -ne 503 -or $approvedResponse.json.message -ne 'F4_SETTLEMENT_CONFIG_UNAVAILABLE' -or
    $poolEventsAfter -ne $poolEventsBefore -or $poolLedgerAfter -ne $poolLedgerBefore) {
    throw "F4 unconfigured guard mismatch: direct=$($settleResponse.status)/$($settleResponse.json.message), approved=$($approvedResponse.status)/$($approvedResponse.json.message), events $poolEventsBefore->$poolEventsAfter, ledger $poolLedgerBefore->$poolLedgerAfter"
}

$levelLogCount = [int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_user_level_log WHERE user_id IN ($([long]$accounts['A'].userId),$([long]$accounts['B'].userId),$([long]$accounts['R'].userId)) AND level_type='VRANK';")
$rewardPayoutCount = [int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_v_rank_reward_payout WHERE user_id IN ($([long]$accounts['A'].userId),$([long]$accounts['B'].userId),$([long]$accounts['R'].userId)) AND is_deleted=0;")
$nonAdjacentPromotionCount = [int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM ``$database``.nx_user_level_log WHERE user_id IN ($([long]$accounts['A'].userId),$([long]$accounts['B'].userId),$([long]$accounts['R'].userId)) AND level_type='VRANK' AND CAST(REPLACE(to_code,'V','') AS SIGNED)-CAST(REPLACE(from_code,'V','') AS SIGNED)<>1;")
$duplicatePayoutCount = [int](& $mysql -uroot -N -B -e "SELECT COUNT(*) FROM (SELECT user_id,rank_code,reward_type,COUNT(*) c FROM ``$database``.nx_v_rank_reward_payout WHERE user_id IN ($([long]$accounts['A'].userId),$([long]$accounts['B'].userId),$([long]$accounts['R'].userId)) AND is_deleted=0 GROUP BY user_id,rank_code,reward_type HAVING COUNT(*)>1) duplicates;")
if ($nonAdjacentPromotionCount -ne 0 -or $duplicatePayoutCount -ne 0) {
    throw "F1 concurrency/idempotency mismatch: nonAdjacent=$nonAdjacentPromotionCount duplicatePayouts=$duplicatePayoutCount"
}

$result = [ordered]@{
    runId=[string]$state.runId
    evaluations=$evaluation
    ranks=$rankRows
    qUnqualified=[ordered]@{rankCode=$qRank.rankCode;selfBuyUSD=$qRank.progress.selfBuyUSD;directRefs=$qRank.progress.directRefs}
    b1Rollback=[ordered]@{
        http=$blockedResponse.status
        apiCode=$blockedResponse.json.code
        message=$blockedResponse.json.message
        rankBefore=$blockedRankBefore
        rankAfter=$blockedRankAfter
        rewardPayoutDelta=$blockedPayoutAfter-$blockedPayoutBefore
    }
    levelLogCount=$levelLogCount
    rewardPayoutCount=$rewardPayoutCount
    nonAdjacentPromotionCount=$nonAdjacentPromotionCount
    duplicatePayoutCount=$duplicatePayoutCount
    leadershipPool=[ordered]@{
        ratio=$leadershipRead.configValues.'F.pool.ratio'
        directSettleHttp=$settleResponse.status
        directSettleCode=$settleResponse.json.code
        directSettleMessage=$settleResponse.json.message
        approvedSettleHttp=$approvedResponse.status
        approvedSettleCode=$approvedResponse.json.code
        approvedSettleMessage=$approvedResponse.json.message
        proposalId=$proposal.id
        commissionEventsDelta=$poolEventsAfter-$poolEventsBefore
        ledgerRowsDelta=$poolLedgerAfter-$poolLedgerBefore
        status='HOLD'
    }
    passed=$true
    generatedAt=(Get-Date).ToUniversalTime().ToString('o')
}
$resultPath = Join-Path ([string]$state.runDirectory) 'f1-f4-admin-reconciliation.json'
[IO.File]::WriteAllText($resultPath,($result | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
$result | ConvertTo-Json -Depth 8

$adminToken=$null
$userPassword=$null
$adminPassword=$null
