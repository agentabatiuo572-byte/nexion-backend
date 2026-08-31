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
$manifestPath = [string]$state.manifestPath
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$mysql = 'D:\software\MySQL\MySQL Server 8.0\bin\mysql.exe'
if (-not $DatabasePassword) { throw 'NEXION_ACCEPTANCE_DB_PASSWORD or -DatabasePassword is required.' }
$env:MYSQL_PWD = $DatabasePassword

function Read-RequiredJson([string]$Name) {
    $path = Join-Path $runDirectory $Name
    if (-not (Test-Path -LiteralPath $path)) { throw "Required acceptance evidence missing: $Name" }
    $value = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    if (-not $value.passed) { throw "Acceptance evidence is not passed: $Name" }
    return $value
}

$f2 = Read-RequiredJson 'f2-reconciliation.json'
$f1f4 = Read-RequiredJson 'f1-f4-admin-reconciliation.json'
$f3 = Read-RequiredJson 'f3-reconciliation.json'
$f5 = Read-RequiredJson 'f5-reconciliation.json'
$configHash = (Get-FileHash -LiteralPath ([string]$state.configSnapshotPath) -Algorithm SHA256).Hash.ToLowerInvariant()
$roleIds = @($manifest.roles | ForEach-Object { [long]$_.userId })
$idList = $roleIds -join ','
$runTag = ([string]$state.runId).ToUpperInvariant()

$orders = @(& $mysql -uroot -N -B -e "SELECT id,order_no,user_id FROM ``$database``.nx_order WHERE user_id IN ($idList) AND order_no LIKE 'TFA-$runTag-%' ORDER BY id;" | ForEach-Object {
    $p = $_ -split "`t"
    [ordered]@{id=[long]$p[0];orderNo=$p[1];userId=[long]$p[2]}
})
$events = @(& $mysql -uroot -N -B -e "SELECT id,user_id,commission_type,currency,status,COALESCE(order_no,'') FROM ``$database``.nx_commission_event WHERE user_id IN ($idList) AND is_deleted=0 ORDER BY id;" | ForEach-Object {
    $p = $_ -split "`t"
    [ordered]@{id=[long]$p[0];userId=[long]$p[1];kind=$p[2];currency=$p[3];status=$p[4];orderNo=$p[5]}
})
$ledgers = @(& $mysql -uroot -N -B -e "SELECT id,user_id,biz_no,biz_type,asset,direction,amount,status FROM ``$database``.nx_wallet_ledger WHERE user_id IN ($idList) AND (remark LIKE '%$runTag%' OR biz_no LIKE 'F3-BINARY-%' OR biz_no LIKE 'F5-%') AND is_deleted=0 ORDER BY id;" | ForEach-Object {
    $p = $_ -split "`t"
    [ordered]@{id=[long]$p[0];userId=[long]$p[1];bizNo=$p[2];bizType=$p[3];asset=$p[4];direction=$p[5];amount=[decimal]$p[6];status=$p[7]}
})
$proposals = @(& $mysql -uroot -N -B -e "SELECT operation_id,status FROM ``$database``.nx_audit_operation_ticket WHERE operation_id IN ('$($f1f4.leadershipPool.proposalId)','$($f5.reverse.proposalId)','$($f5.reissue.proposalId)','$($f5.suspension.suspendProposalId)','$($f5.suspension.resumeProposalId)') ORDER BY id;" | ForEach-Object {
    $p = $_ -split "`t"
    [ordered]@{operationId=$p[0];status=$p[1]}
})

$financialArtifacts = [ordered]@{
    configSnapshotSha256=$configHash
    orderRecords=$orders
    commissionEventRecords=$events
    walletLedgerRecords=$ledgers
    outboxEventIds=@($f2.outboxEventIds)
    auditProposals=$proposals
    reports=@(
        'f2-reconciliation.json','f1-f4-admin-reconciliation.json',
        'f3-reconciliation.json','f5-reconciliation.json'
    )
    reconciliationChain='order/refund -> outbox -> commission_event -> wallet_ledger -> PC F5 -> App'
    finalizedAt=[DateTimeOffset]::UtcNow.ToString('o')
}
$manifest | Add-Member -NotePropertyName financialArtifacts -NotePropertyValue $financialArtifacts -Force
$manifest | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $manifestPath -Encoding utf8
$manifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
$hashPath = "$manifestPath.sha256"
"$manifestHash  $([IO.Path]::GetFileName($manifestPath))" | Set-Content -LiteralPath $hashPath -Encoding ascii

[ordered]@{
    runId=[string]$state.runId
    userIds=$roleIds.Count
    orders=$orders.Count
    commissionEvents=$events.Count
    walletLedgers=$ledgers.Count
    auditProposals=$proposals.Count
    configSnapshotSha256=$configHash
    manifestSha256=$manifestHash
    manifestPath=$manifestPath
    hashPath=$hashPath
} | ConvertTo-Json -Depth 5
