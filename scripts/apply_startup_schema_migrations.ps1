[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
  [string]$MySql = "D:\software\MySQL\MySQL Server 8.0\bin\mysql.exe"
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$databaseEnvironment = & (Join-Path $PSScriptRoot "resolve_nexion_database_environment.ps1")
$JdbcUrl = $databaseEnvironment.JdbcUrl
$Username = $databaseEnvironment.Username
$Password = $databaseEnvironment.Password
$migrations = @(
  (Join-Path $root "scripts\migrations\20260729_a1_admin_account_status_cas.sql"),
  (Join-Path $root "scripts\migrations\20260729_c2_account_list_event_schema.sql"),
  (Join-Path $root "scripts\migrations\20260729_l2_report_export_artifact_event_schema.sql"),
  (Join-Path $root "scripts\migrations\20260729_h003_registration_otp_client_ip.sql"),
  (Join-Path $root "scripts\migrations\20260730_f5_commission_anomaly_event_schema.sql"),
  (Join-Path $root "scripts\migrations\20260801_admin_idempotency_expiry_recovery.sql"),
  (Join-Path $root "scripts\migrations\20260801_admin_idempotency_expiry_claim_index.sql"),
  (Join-Path $root "scripts\migrations\20260807_nexion_hard_blockers.sql"),
  (Join-Path $root "scripts\migrations\20260807_k6_evidence_hardening.sql"),
  (Join-Path $root "scripts\migrations\20260807_remove_kyc_runtime.sql"),
  (Join-Path $root "scripts\migrations\20260808_d7_payout_vnd_config.sql"),
  (Join-Path $root "scripts\migrations\20260809_m5_content_rbac_closure.sql"),
  (Join-Path $root "scripts\migrations\20260809_bep20_withdrawal_toggle.sql"),
  (Join-Path $root "scripts\migrations\20260810_e18_task_assignment_runtime.sql"),
  (Join-Path $root "scripts\migrations\20260810_f5_commission_reissue_atomicity.sql"),
  (Join-Path $root "scripts\migrations\20260810_ab_pending_closure.sql"),
  (Join-Path $root "scripts\migrations\20260810_cd_finance_sandbox.sql"),
  (Join-Path $root "scripts\migrations\20260810_kl_janus_applied_proof.sql"),
  (Join-Path $root "scripts\migrations\20260811_f4_l6_acceptance_schema.sql"),
  (Join-Path $root "scripts\migrations\20260811_f15_leadership_pool_authoritative_config.sql"),
  (Join-Path $root "scripts\migrations\20260811_f15_leadership_pool_config_blocked_event_schema.sql"),
  (Join-Path $root "scripts\migrations\20260811_funds_persistent_sandbox.sql"),
  (Join-Path $root "scripts\migrations\20260811_g2_exchange_execution_mutex.sql"),
  (Join-Path $root "scripts\migrations\20260811_g2_acceptance_sandbox.sql"),
  (Join-Path $root "scripts\migrations\20260811_h8_acceptance_sandbox_referral_ledger.sql"),
  (Join-Path $root "scripts\migrations\20260811_a2_a4_runtime_policy_closure.sql"),
  (Join-Path $root "scripts\migrations\20260811_f5_commission_export_event_schema.sql"),
  (Join-Path $root "scripts\migrations\20260811_l6_source_environment_schema.sql"),
  (Join-Path $root "scripts\migrations\20260811_l6_h5_runtime_contract_fix.sql"),
  (Join-Path $root "scripts\migrations\20260811_l6_h5_active_route_catalog.sql"),
  (Join-Path $root "scripts\migrations\20260811_janus_executor_claim_nonce.sql")
)

if (-not $JdbcUrl.StartsWith("jdbc:mysql://", [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "Only jdbc:mysql URLs are supported by this controlled migration runner."
}
$databaseUri = [Uri]$JdbcUrl.Substring(5)
$database = $databaseUri.AbsolutePath.Trim('/')
if ([string]::IsNullOrWhiteSpace($database)) {
  throw "The JDBC URL must include a database name."
}
$port = if ($databaseUri.IsDefaultPort) { 3306 } else { $databaseUri.Port }

if ($WhatIfPreference) {
  [pscustomobject]@{
    Migrations = $migrations
    Host = $databaseUri.Host
    Port = $port
    Database = $database
    Action = "Would apply the required idempotent startup migrations before backend startup."
  }
  return
}

if (-not (Test-Path -LiteralPath $MySql)) {
  throw "MySQL executable not found: $MySql"
}
if ([string]::IsNullOrWhiteSpace($Password)) {
  throw "NEXION_DB_PASSWORD is required to apply startup schema migrations."
}
foreach ($migration in $migrations) {
  if (-not (Test-Path -LiteralPath $migration)) {
    throw "Required startup migration is missing: $migration"
  }
}
if (-not $PSCmdlet.ShouldProcess("$($databaseUri.Host):$port/$database", "apply required startup schema migrations")) {
  return
}

$previousMySqlPassword = $env:MYSQL_PWD
try {
  $env:MYSQL_PWD = $Password
  $sources = ($migrations | ForEach-Object { "source $($_.Replace('\', '/'));" }) -join " "
  & $MySql --default-character-set=utf8mb4 --protocol=tcp -h $databaseUri.Host -P $port -u $Username $database -e $sources
  if ($LASTEXITCODE -ne 0) {
    throw "Required startup schema migration failed with mysql exit code $LASTEXITCODE. Backend startup has been stopped."
  }

  $databaseSqlLiteral = $database.Replace("'", "''")
  $requiredIndexCount = & $MySql --default-character-set=utf8mb4 --protocol=tcp -N -B -h $databaseUri.Host -P $port -u $Username $database -e "SELECT COUNT(*) FROM (SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS indexed_columns FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = '$databaseSqlLiteral' AND TABLE_NAME = 'nx_admin_idempotency_record' AND INDEX_NAME IN ('idx_admin_idem_status_expires_deleted', 'idx_admin_idem_expiry_claim') GROUP BY INDEX_NAME) actual WHERE (INDEX_NAME = 'idx_admin_idem_status_expires_deleted' AND indexed_columns = 'status,expires_at,is_deleted') OR (INDEX_NAME = 'idx_admin_idem_expiry_claim' AND indexed_columns = 'status,is_deleted,expires_at,id');"
  if ($LASTEXITCODE -ne 0 -or $requiredIndexCount.Trim() -ne "2") {
    throw "Both required idempotency expiry-recovery indexes with their exact column order must exist after migrations. Backend startup has been stopped."
  }
} finally {
  if ($null -eq $previousMySqlPassword) {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
  } else {
    $env:MYSQL_PWD = $previousMySqlPassword
  }
}
