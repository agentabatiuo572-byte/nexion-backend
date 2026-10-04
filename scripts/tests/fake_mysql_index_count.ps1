# An in-process PowerShell adapter: legacy cmd.exe cannot carry the complete SOURCE batch.
# No database access. Only the exact controlled startup statements below are accepted.
$ErrorActionPreference = 'Stop'
$taskArguments = @($args)
$executePosition = [Array]::IndexOf($taskArguments, '-e')
if ($executePosition -lt 0 -or $executePosition -ne $taskArguments.Count - 2) {
  throw 'Startup MySQL fixture requires exactly one final -e SQL argument.'
}
$taskSql = [string]$taskArguments[$executePosition + 1]
$isSources = $taskSql.StartsWith('source ', [StringComparison]::Ordinal)
$expectedArguments = @('--default-character-set=utf8mb4', '--protocol=tcp')
if (-not $isSources) { $expectedArguments += @('-N', '-B') }
$expectedArguments += @('-h', '127.0.0.1', '-P', '3306', '-u', 'contract', 'idempotency_runner_contract')
if ($executePosition -ne $expectedArguments.Count) { throw 'Unexpected startup MySQL fixture arguments.' }
for ($taskIndex = 0; $taskIndex -lt $expectedArguments.Count; $taskIndex++) {
  if ([string]$taskArguments[$taskIndex] -cne $expectedArguments[$taskIndex]) {
    throw 'Unexpected startup MySQL fixture arguments.'
  }
}

if ($isSources) {
  $fixtureRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
  $runner = Join-Path $fixtureRoot 'scripts\apply_startup_schema_migrations.ps1'
  $runnerSource = Get-Content -LiteralPath $runner -Raw
  $declarations = [regex]::Matches($runnerSource, '\(Join-Path \$root "([^"\r\n]+\.sql)"\)')
  if ($declarations.Count -eq 0) { throw 'Startup MySQL fixture found no declared migrations.' }
  $relativeMigrations = @($declarations | ForEach-Object { $_.Groups[1].Value })
  $expectedSources = @($relativeMigrations | ForEach-Object {
    $migration = Join-Path $fixtureRoot $_
    if (-not (Test-Path -LiteralPath $migration -PathType Leaf)) {
      throw 'Startup MySQL fixture received a missing migration file.'
    }
    'source ' + $migration.Replace('\', '/') + ';'
  }) -join ' '
  if ($taskSql -cne $expectedSources) { throw 'Startup MySQL fixture requires the complete ordered SOURCE batch.' }
  $dependency = [Array]::IndexOf($relativeMigrations, 'scripts\migrations\20260907_hdpay_optional_manual_bank.sql')
  $manualConfirmation = [Array]::IndexOf($relativeMigrations, 'scripts\migrations\20261004_hdpay_manual_confirmation.sql')
  if ($dependency -lt 0 -or $manualConfirmation -le $dependency) {
    throw 'HDPay manual confirmation migration must follow its canonical bank dependency.'
  }
  exit 0
}

# Exact query expectations prevent an unrelated or weakened postcondition from passing.
$expectedLegalQuery = 'SELECT COUNT(DISTINCT locale) FROM nx_legal_terms_version WHERE locale IN (''vi'',''zh'',''en'') AND jurisdiction=''GLOBAL'' AND status=''PUBLISHED'' AND is_deleted=0 AND JSON_LENGTH(sections_json) >= 1 AND NOT (locale=''en'' AND version_label=''v4'' AND title=''Nexion Acceptance Terms seven-closures-20260817 post-fix-v4'' AND summary=''QA acceptance fixture'') AND NOT (version_label=''v5'' AND last_operator=''migration:formal-terms-v5'');'
$expectedIndexQuery = 'SELECT COUNT(*) FROM (SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS indexed_columns FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = ''idempotency_runner_contract'' AND TABLE_NAME = ''nx_admin_idempotency_record'' AND INDEX_NAME IN (''idx_admin_idem_status_expires_deleted'', ''idx_admin_idem_expiry_claim'') GROUP BY INDEX_NAME) actual WHERE (INDEX_NAME = ''idx_admin_idem_status_expires_deleted'' AND indexed_columns = ''status,expires_at,is_deleted'') OR (INDEX_NAME = ''idx_admin_idem_expiry_claim'' AND indexed_columns = ''status,is_deleted,expires_at,id'');'
if ($taskSql -ceq $expectedLegalQuery) {
  '3'
  exit 0
}
if ($taskSql -ceq $expectedIndexQuery) {
  $indexCount = $env:NEXION_FAKE_INDEX_COUNT
  if ($indexCount -notin @('2', '1', '0')) { throw 'Startup MySQL fixture requires an explicit index-count case.' }
  $indexCount
  exit 0
}
throw 'Unknown SQL for the startup MySQL contract fixture.'