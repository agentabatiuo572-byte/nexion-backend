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
  # Startup is fail-closed unless the classic API permission graph exists.
  # Keep its rerunnable canonical entry in the controlled sequence so a fresh
  # schema can satisfy the same invariant as an upgraded database.
  (Join-Path $root "scripts\migrations\20260712_rbac_classic.sql"),
  # H1 is a runtime dependency of H8 reward projection and several finance
  # gates.  Fresh schemas must receive the canonical rhythm keys before any
  # acceptance endpoint is opened; H8 also owns its permission/menu contract.
  (Join-Path $root "scripts\migrations\20260711_rhythm_configurable.sql"),
  (Join-Path $root "scripts\migrations\20260722_h_domain_closure.sql"),
  # User registration publishes the canonical auth.register_completed event,
  # and referral binding publishes referral.bound in the same transaction.
  # Register both schemas before any acceptance account is allowed to exist.
  (Join-Path $root "scripts\migrations\20260717_a4_event_governance_closure.sql"),
  (Join-Path $root "scripts\migrations\20260727_l1_kpi_event_chain_closure.sql"),
  (Join-Path $root "scripts\migrations\20260729_a1_admin_account_status_cas.sql"),
  (Join-Path $root "scripts\migrations\20260729_c2_account_list_event_schema.sql"),
  (Join-Path $root "scripts\migrations\20260729_l2_report_export_artifact_event_schema.sql"),
  (Join-Path $root "scripts\migrations\20260729_h003_registration_otp_client_ip.sql"),
  (Join-Path $root "scripts\migrations\20260730_f5_commission_anomaly_event_schema.sql"),
  (Join-Path $root "scripts\migrations\20260801_admin_idempotency_expiry_recovery.sql"),
  (Join-Path $root "scripts\migrations\20260801_admin_idempotency_expiry_claim_index.sql"),
  # The canonical schema intentionally keeps the pre-D2 withdrawal shape. D5
  # migrations below address columns created by this rerunnable prerequisite,
  # so a fresh database must apply it before the 20260807 hard blockers.
  (Join-Path $root "scripts\migrations\20260720_d2_withdrawal_closure.sql"),
  # The classic seed still contains three historical D3 threshold permissions.
  # Apply the authoritative D3 closure immediately afterwards so those retired
  # capabilities cannot be resurrected in a fresh acceptance database.
  (Join-Path $root "scripts\migrations\20260720_d3_treasury_closure.sql"),
  (Join-Path $root "scripts\migrations\20260807_nexion_hard_blockers.sql"),
  (Join-Path $root "scripts\migrations\20260807_k6_evidence_hardening.sql"),
  (Join-Path $root "scripts\migrations\20260807_remove_kyc_runtime.sql"),
  (Join-Path $root "scripts\migrations\20260808_d7_payout_vnd_config.sql"),
  (Join-Path $root "scripts\migrations\20260809_m5_content_rbac_closure.sql"),
  (Join-Path $root "scripts\migrations\20260809_bep20_withdrawal_toggle.sql"),
  (Join-Path $root "scripts\migrations\20260810_e18_task_assignment_runtime.sql"),
  (Join-Path $root "scripts\migrations\20260810_f5_commission_reissue_atomicity.sql"),
  (Join-Path $root "scripts\migrations\20260810_ab_pending_closure.sql"),
  (Join-Path $root "scripts\migrations\20260828_cd_finance_canonical.sql"),
  (Join-Path $root "scripts\migrations\20260810_kl_janus_applied_proof.sql"),
  (Join-Path $root "scripts\migrations\20260812_auth_environment_identity_namespace.sql"),
  (Join-Path $root "scripts\migrations\20260828_development_passkey_event_schema.sql"),
  (Join-Path $root "scripts\migrations\20260813_user_registration_client_ip.sql"),
  (Join-Path $root "scripts\migrations\20260811_f4_l6_acceptance_schema.sql"),
  (Join-Path $root "scripts\migrations\20260811_f15_leadership_pool_authoritative_config.sql"),
  (Join-Path $root "scripts\migrations\20260811_f15_leadership_pool_config_blocked_event_schema.sql"),
  (Join-Path $root "scripts\migrations\20260811_g2_exchange_execution_mutex.sql"),
  (Join-Path $root "scripts\migrations\20260811_a2_a4_runtime_policy_closure.sql"),
  (Join-Path $root "scripts\migrations\20260811_f5_commission_export_event_schema.sql"),
  (Join-Path $root "scripts\migrations\20260811_l6_source_environment_schema.sql"),
  (Join-Path $root "scripts\migrations\20260811_l6_h5_runtime_contract_fix.sql"),
  (Join-Path $root "scripts\migrations\20260811_l6_h5_active_route_catalog.sql"),
  (Join-Path $root "scripts\migrations\20260811_janus_executor_claim_nonce.sql"),
  (Join-Path $root "scripts\migrations\20260813_compute_share_enrollment.sql"),
  (Join-Path $root "scripts\migrations\20260813_developer_access_request.sql"),
  (Join-Path $root "scripts\migrations\20260814_team_ambassador_application.sql"),
  (Join-Path $root "scripts\migrations\20260816_team_ambassador_policy.sql"),
  (Join-Path $root "scripts\migrations\20260813_user_self_service.sql"),
  (Join-Path $root "scripts\migrations\20260816_withdrawal_attempt_authority.sql"),
  (Join-Path $root "scripts\migrations\20260816_developer_api_keys_webhooks.sql"),
  (Join-Path $root "scripts\migrations\20260816_onboarding_calibration_authority.sql"),
  (Join-Path $root "scripts\migrations\20260817_onboarding_phone_activation.sql"),
  (Join-Path $root "scripts\migrations\20260816_store_product_notification.sql"),
  (Join-Path $root "scripts\migrations\20260816_payment_method_run_scope.sql"),
  (Join-Path $root "scripts\migrations\20260817_p2_product_specifications.sql"),
  (Join-Path $root "scripts\migrations\20260817_notification_preferences.sql"),
  (Join-Path $root "scripts\migrations\20260817_developer_access_governance.sql"),
  (Join-Path $root "scripts\migrations\20260817_legal_terms_versioned.sql"),
  (Join-Path $root "scripts\migrations\20260817_h7_voucher_cadence.sql"),
  (Join-Path $root "scripts\migrations\20260817_genesis_holder_policy.sql"),
  (Join-Path $root "scripts\migrations\20260820_home_grid_datacenter_metadata.sql"),
  (Join-Path $root "scripts\migrations\20260820_e2_task_price_history.sql"),
  (Join-Path $root "scripts\migrations\20260820_i4_homepage_trust_content.sql"),
  (Join-Path $root "scripts\migrations\20260821_h2_trial_card_offer.sql"),
  (Join-Path $root "scripts\migrations\20260823_withdrawal_terminal_refund_projection.sql"),
  (Join-Path $root "scripts\migrations\20260823_team_hardware_quota_product_alignment.sql"),
  (Join-Path $root "scripts\migrations\20260823_withdrawal_submitted_schema_alignment.sql"),
  (Join-Path $root "scripts\migrations\20260823_d2_lifecycle_event_schema_alignment.sql"),
  (Join-Path $root "scripts\migrations\20260824_nova_conversation_history.sql"),
  (Join-Path $root "scripts\migrations\20260825_product_inventory_mode.sql"),
  (Join-Path $root "scripts\migrations\20260825_payment_method_expiry_label.sql"),
  (Join-Path $root "scripts\migrations\20260825_vietqr_receipt_evidence.sql"),
  (Join-Path $root "scripts\migrations\20260826_h2_trial_product_catalog.sql"),
  (Join-Path $root "scripts\migrations\20260826_product_trial_eligibility.sql"),
  (Join-Path $root "scripts\migrations\20260826_trial_conversion_order_backfill.sql"),
  # Genesis qualification is exclusively server-authoritative. Seed the three
  # canonical eligibility keys and retire the former four-channel any-of rows.
  (Join-Path $root "scripts\migrations\20260827_genesis_unified_eligibility.sql"),
  # H2's two canonical conversion paths publish one trial.redeemed contract.
  # Register the shared settlement + order fields before the scheduler runs.
  (Join-Path $root "scripts\migrations\20260829_trial_redeemed_event_schema_alignment.sql"),
  (Join-Path $root "scripts\migrations\20260829_commission_paid_schema_revision_closure.sql"),
  (Join-Path $root "scripts\migrations\20260829_vrank_projection_closure.sql"),
  (Join-Path $root "scripts\migrations\20260829_f5_commission_mutation_event_schema.sql")
)

# Retirement invariant: the normal dev/prod startup chain can apply canonical
# prerequisites only. Historical isolated-rail migrations remain in source
# control for forensic restore, but adding one back here is a hard failure.
$retiredEnvironmentName = -join ([char[]](83,65,78,68,66,79,88))
$retiredRailMigrations = $migrations | Where-Object {
  [IO.Path]::GetFileName($_).IndexOf($retiredEnvironmentName, [StringComparison]::OrdinalIgnoreCase) -ge 0
}
if ($retiredRailMigrations.Count -ne 0) {
  throw "Retired isolated-rail migrations cannot run during normal startup: $($retiredRailMigrations -join ', ')"
}

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
Push-Location -LiteralPath $root.Path
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
  Pop-Location
}
