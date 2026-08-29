[CmdletBinding()]
param(
  [string]$MySql = "D:\software\MySQL\MySQL Server 8.0\bin\mysql.exe"
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$databaseEnvironment = & (Join-Path $PSScriptRoot "resolve_nexion_database_environment.ps1")
if (-not $databaseEnvironment.JdbcUrl.StartsWith("jdbc:mysql://", [StringComparison]::OrdinalIgnoreCase)) {
  throw "Only jdbc:mysql URLs are supported"
}
$uri = [Uri]$databaseEnvironment.JdbcUrl.Substring(5)
$database = $uri.AbsolutePath.Trim('/')
if ($database -ne "nexion" -or $uri.Host -notin @("127.0.0.1", "localhost")) {
  throw "Retirement verification is restricted to the local nexion development database"
}
if (-not (Test-Path -LiteralPath $MySql)) {
  throw "MySQL executable not found: $MySql"
}
if ([string]::IsNullOrWhiteSpace($databaseEnvironment.Password)) {
  throw "NEXION_DB_PASSWORD is required"
}
$port = if ($uri.IsDefaultPort) { 3306 } else { $uri.Port }
$previousPassword = $env:MYSQL_PWD
try {
  $env:MYSQL_PWD = $databaseEnvironment.Password
  $sql = @'
SET @migration_id='sandbox-to-development-v2-classified';
SELECT IF(COUNT(*)=1,'PASS','FAIL') AS migration_gate
  FROM nexion.nx_data_environment_migration
 WHERE migration_id=@migration_id AND status='COMPLETED'
   AND source_environment='RETIRED_SOURCE' AND target_environment='DEVELOPMENT';
SELECT IF(COUNT(*)=80 AND SUM(source_count)=6963 AND SUM(archive_count)=6963
              AND SUM(source_count)=SUM(archive_count),'PASS','FAIL') AS archive_gate
  FROM nexion_development_archive_20260828.sandbox_retirement_manifest
 WHERE migration_id=@migration_id;
SELECT IF(COUNT(*)=80 AND SUM(status<>'COMPLETED')=0
              AND SUM(source_count)=6963 AND SUM(applied_count)=6963
              AND SUM(source_count)=SUM(applied_count)
              AND SUM(disposition='PROMOTE_ACCOUNT_IDENTITY')=1
              AND SUM(CASE WHEN disposition='PROMOTE_ACCOUNT_IDENTITY' THEN source_count ELSE 0 END)=328
              AND SUM(disposition='RESET_WALLET_SCAFFOLD')=1
              AND SUM(CASE WHEN disposition='RESET_WALLET_SCAFFOLD' THEN source_count ELSE 0 END)=329
              AND SUM(disposition='ARCHIVE_ONLY_DELETE')=18
              AND SUM(CASE WHEN disposition='ARCHIVE_ONLY_DELETE' THEN source_count ELSE 0 END)=387
              AND SUM(disposition='ARCHIVE_ONLY_DROP_TABLE')=60
              AND SUM(CASE WHEN disposition='ARCHIVE_ONLY_DROP_TABLE' THEN source_count ELSE 0 END)=5919,
          'PASS','FAIL') AS classification_gate
  FROM nexion_development_archive_20260828.sandbox_retirement_classification
 WHERE migration_id=@migration_id;
SELECT IF(COUNT(*)=0,'PASS','FAIL') AS active_table_gate
  FROM information_schema.tables
 WHERE table_schema='nexion' AND table_type='BASE TABLE'
   AND (table_name LIKE '%sandbox%' OR table_name LIKE '%acceptance%');
SELECT IF(COUNT(*)=0,'PASS','FAIL') AS account_marker_gate
  FROM nexion.nx_user WHERE sandbox<>0;
SELECT IF(COUNT(*)=0,'PASS','FAIL') AS wallet_marker_gate
  FROM nexion.nx_user_wallet WHERE sandbox<>0;
SELECT IF(COUNT(*)=1,'PASS','FAIL') AS imported_wallet_reset_proof_gate
  FROM nexion_development_archive_20260828.sandbox_retirement_wallet_reset_proof p
 WHERE p.migration_id=@migration_id AND p.source_count=p.zero_balance_count
   AND p.mock_amounts_reused=0 AND p.canonical_ledger_cutoff_at IS NOT NULL
   AND p.source_count=(SELECT COUNT(*)
     FROM nexion_development_archive_20260828.sandbox_retirement_wallet_reset_item i
    WHERE i.migration_id=p.migration_id AND i.zero_balance_verified=1
      AND i.proof_origin IN ('DIRECT_RESET','RECONCILED_POST_RESET_LEDGER'));
SELECT IF(COUNT(*)=0,'PASS','FAIL') AS classification_mismatch_gate
  FROM nexion_development_archive_20260828.sandbox_retirement_classification
 WHERE migration_id=@migration_id AND (status<>'COMPLETED' OR source_count<>applied_count);
SELECT IF(COUNT(*)=0,'PASS','FAIL') AS imported_wallet_canonical_ledger_gate
  FROM nexion.nx_user_wallet w
  JOIN nexion_development_archive_20260828.nx_user_wallet a ON a.id=w.id
  JOIN nexion_development_archive_20260828.sandbox_retirement_wallet_reset_proof p
    ON p.migration_id=@migration_id
  LEFT JOIN (
    SELECT l.user_id,
           SUM(CASE WHEN l.asset='USDT' AND l.direction='IN' THEN l.amount
                    WHEN l.asset='USDT' AND l.direction='OUT' THEN -l.amount ELSE 0 END) usdt_net,
           SUM(CASE WHEN l.asset='NEX' AND l.direction='IN' THEN l.amount
                    WHEN l.asset='NEX' AND l.direction='OUT' THEN -l.amount ELSE 0 END) nex_net,
           SUM(CASE WHEN l.biz_type='COMPUTE_TASK_REWARD' AND l.direction='IN'
                    THEN l.amount ELSE 0 END) compute_earned
      FROM nexion.nx_wallet_ledger l
      JOIN nexion_development_archive_20260828.sandbox_retirement_wallet_reset_proof p2
        ON p2.migration_id=@migration_id
     WHERE l.is_deleted=0 AND l.status='SUCCESS'
       AND l.created_at>=p2.canonical_ledger_cutoff_at
     GROUP BY l.user_id
  ) ledger ON ledger.user_id=w.user_id
 WHERE a.sandbox=1
   AND (w.sandbox<>0
     OR w.usdt_available<>COALESCE(ledger.usdt_net,0)
     OR w.nex_available<>COALESCE(ledger.nex_net,0)
     OR w.pending_withdraw<>0
     OR w.lifetime_earned<>COALESCE(ledger.compute_earned,0)
     OR w.cumulative_deposit_usdt<>0);
'@
  $output = & $MySql --default-character-set=utf8mb4 --protocol=tcp `
    -h $uri.Host -P $port -u $databaseEnvironment.Username $database -N -B -e $sql
  if ($LASTEXITCODE -ne 0) {
    throw "Retirement verification query failed with exit code $LASTEXITCODE"
  }
  $results = @($output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
  if ($results.Count -ne 9 -or $results.Where({ $_ -ne "PASS" }).Count -ne 0) {
    throw "Sandbox retirement verification failed: $($results -join ', ')"
  }
  [pscustomobject]@{
    Status = "PASS"
    Gates = $results.Count
    Migration = "sandbox-to-development-v2-classified"
    Archive = "nexion_development_archive_20260828"
  }
} finally {
  if ($null -eq $previousPassword) {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
  } else {
    $env:MYSQL_PWD = $previousPassword
  }
}
