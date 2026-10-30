param(
    [Parameter(Mandatory = $true)][string]$SnapshotPath,
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)

$generator = Join-Path $PSScriptRoot '../generate-tasklock-recovery.ps1'
$original = Get-Content -LiteralPath $SnapshotPath -Raw
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$positive = Join-Path $OutputDirectory 'positive.sql'
& $generator -SnapshotPath $SnapshotPath -OutputPath $positive | Out-Null
$sql = Get-Content -LiteralPath $positive -Raw
foreach ($expected in @('137071', '136183', '2026-10-30 18:29:37.690437',
        '2026-10-30 20:59:38.000000', 'q.device_id<>1152', "'COMMIT','ROLLBACK'")) {
    if (-not $sql.Contains($expected)) { throw "Missing recovery contract: $expected" }
}
if ($sql -match '(?im)^\s*(UPDATE|DELETE\s+FROM)\s+nx_(compute_task|compute_receipt|wallet|earning|compute_e3_config)\b') {
    throw 'Recovery must not mutate tasks, receipts, earnings, wallets or E3 config'
}

$cases = @{
    wrong_owner = { param($s) ($s.database.locks | Where-Object device -EQ 1204).owner = 1 }
    wrong_instance = { param($s) ($s.database.locks | Where-Object device -EQ 1204).instance = 'UNKNOWN-1152' }
    uncompleted_task = { param($s) ($s.database.locks | Where-Object device -EQ 1204).taskStatus = 'RUNNING' }
    uncredited_receipt = { param($s) ($s.database.locks | Where-Object device -EQ 1204).receiptEarningStatus = 'PENDING' }
    changed_audit_microsecond = { param($s) ($s.database.completionAudits | Where-Object device -EQ 1204).lockUntil = '2026-10-30T21:39:19.377323' }
    incorrectly_truncated_lock = { param($s) ($s.database.locks | Where-Object device -EQ 1208).lockUntil = '2026-10-30 20:59:37.000000' }
}
foreach ($case in $cases.Keys | Sort-Object) {
    $changed = $original | ConvertFrom-Json -Depth 60 -DateKind String
    & $cases[$case] $changed
    $inputFile = Join-Path $OutputDirectory ($case + '.json')
    $outputFile = Join-Path $OutputDirectory ($case + '.sql')
    $changed | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath $inputFile -Encoding utf8NoBOM
    $failure = $null
    try { & $generator -SnapshotPath $inputFile -OutputPath $outputFile | Out-Null }
    catch { $failure = $_ }
    if ($null -eq $failure -or (Test-Path -LiteralPath $outputFile)) { throw "Expected rejected source: $case" }
}
Write-Output 'PASS: exact six-row generation, retained microseconds/rounding, no economic/config mutations, six provenance rejection cases'
