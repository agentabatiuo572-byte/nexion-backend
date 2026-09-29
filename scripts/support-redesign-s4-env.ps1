param([switch]$Migrate)
$ErrorActionPreference='Stop'
$applyS4Migrations=$Migrate.IsPresent
. "$PSScriptRoot/support-redesign-s3-env.ps1"
$env:S4_EVIDENCE_DIR='C:/Users/jason/.codex/workflow-runs/customer-service-20260929/s4'
New-Item -ItemType Directory -Path $env:S4_EVIDENCE_DIR -Force | Out-Null
$env:NEXION_LOG_FILE="$env:S4_EVIDENCE_DIR/backend.log"
# Isolated acceptance fixture values, never production defaults.
$env:NEXION_SUPPORT_ATTACHMENTS_ALLOWEDMIMETYPES='image/png,image/jpeg'
$env:NEXION_SUPPORT_ATTACHMENTS_MAXBYTES='1048576'
$env:NEXION_SUPPORT_ATTACHMENTS_MAXPIXELS='1000000'
$env:NEXION_SUPPORT_ATTACHMENTS_TTLSECONDS='300'
if($applyS4Migrations) {
 $db=Get-Content -Raw 'C:/Users/jason/.codex/workflow-runs/customer-service-20260929/local-db-credentials.json' | ConvertFrom-Json
 $env:MYSQL_PWD=$db.password
 $client='D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/mysql-verified/mysql-8.4.6-winx64/bin/mysql.exe'
 try { foreach($file in @('20260929_support_maintenance_s4.sql','20260929_support_message_s4.sql','20260929_support_attachment_s4.sql')) {
  $stamp=Get-Date -Format 'yyyyMMddTHHmmssfff'
  Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot "migrations/$file") | & $client --no-defaults --default-character-set=utf8mb4 --protocol=tcp --host=127.0.0.1 --port=33329 --user=$($db.username) cs_redesign *> "$env:S4_EVIDENCE_DIR/$stamp-$file.log"
  if($LASTEXITCODE -ne 0){throw "S4 additive migration failed: $file; inspect saved log"}
 } } finally {Remove-Item Env:MYSQL_PWD}
}
