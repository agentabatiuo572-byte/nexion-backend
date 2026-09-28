param([string]$Mysql = 'D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/mysql-verified/mysql-8.4.6-winx64/bin/mysql.exe', [int]$Port = 33317)
$ErrorActionPreference='Stop'
$database='phone_acceptance_'+[Guid]::NewGuid().ToString('N')
function Query([string]$Sql) {
  $result=& $Mysql --no-defaults --host=127.0.0.1 "--port=$Port" --user=root --batch --skip-column-names "--execute=$Sql"
  if($LASTEXITCODE -ne 0){throw 'MySQL statement failed'}
  return ($result -join "`n")
}
function AssertValue([string]$Sql,[string]$Expected) {
  $actual=Query "USE $database; $Sql"
  if($actual -cne $Expected){throw "Expected [$Expected], got [$actual]"}
}
$repo=Split-Path $PSScriptRoot
$mapper=Get-Content -Raw "$repo/src/main/java/ffdd/opsconsole/onboarding/mapper/OnboardingCalibrationMapper.java"
function RuntimeSql([string]$Name) {
  $match=[regex]::Matches($mapper,'(?s)@Update\("""(?<sql>(?:(?!""").)*)"""\)\s*int (?<name>\w+)') | Where-Object {$_.Groups['name'].Value -eq $Name}
  if(@($match).Count -ne 1){throw "Missing mapper statement $Name"}
  return $match.Groups['sql'].Value.Replace('#{userId}','1').Replace('#{keepUserDeviceId}','-1').Replace('#{userDeviceId}','12')
}
Query "CREATE DATABASE $database CHARACTER SET utf8mb4" | Out-Null
try {
  Query @"
USE $database;
CREATE TABLE nx_user_device(id BIGINT PRIMARY KEY,user_id BIGINT,source_channel VARCHAR(32),source_environment VARCHAR(16),run_id VARCHAR(96),device_type VARCHAR(16),ownership_status VARCHAR(16),status VARCHAR(16),activated_at DATETIME(6),deactivated_at DATETIME(6),is_deleted INT);
CREATE TABLE nx_onboarding_calibration(user_id BIGINT,device_id VARCHAR(128),user_device_id BIGINT,source_environment VARCHAR(16),run_id VARCHAR(96),activation_status VARCHAR(16),updated_at DATETIME(6),is_deleted INT) CHARACTER SET utf8mb4;
CREATE TABLE nx_user_device_runtime(user_device_id BIGINT PRIMARY KEY,active_task_no VARCHAR(64),online_status VARCHAR(16),paused_reason VARCHAR(64),heartbeat_at DATETIME(6),updated_at DATETIME(6),is_deleted INT);
INSERT INTO nx_user_device VALUES
(11,1,'ONBOARDING','PRODUCTION','','PHONE','OWNED','INACTIVE',NULL,'2026-08-01',0),
(12,1,'ONBOARDING','PRODUCTION','','PHONE','OWNED','INACTIVE',NULL,'2026-09-01',0),
(13,1,'STORE','PRODUCTION','','BOX','OWNED','ACTIVE','2026-07-01',NULL,0),
(14,1,'ONBOARDING','PRODUCTION','','PHONE','OWNED','INACTIVE',NULL,'2026-07-01',0);
INSERT INTO nx_onboarding_calibration VALUES
(1,'OldPhone',11,'PRODUCTION','','CALIBRATED','2026-09-28',0),
(1,'CurrentPhone',12,'PRODUCTION','','DEFERRED','2026-09-01',0);
INSERT INTO nx_user_device_runtime VALUES
(11,'old-task','ONLINE',NULL,NOW(),NOW(),0),(12,'current-task','ONLINE',NULL,NOW(),NOW(),0),
(13,'purchased-task','ONLINE',NULL,NOW(),NOW(),0),(14,NULL,'OFFLINE','ADMIN_PAUSED',NOW(),NOW(),0);
"@ | Out-Null
  $migration=Get-Content -Raw "$repo/scripts/migrations/20260928_phone_calibration_policy.sql"
  Query "USE $database; $migration" | Out-Null
  AssertValue 'SELECT installation_id FROM nx_phone_binding' 'CurrentPhone'
  AssertValue "SELECT COUNT(*) FROM nx_phone_binding WHERE installation_id='currentphone'" '0'
  Query "USE $database; UPDATE nx_phone_binding SET changed_at='2026-06-01',execution_installation_id='NewPhone'; $migration" | Out-Null
  AssertValue "SELECT CONCAT(installation_id,':',execution_installation_id,':',DATE(changed_at)) FROM nx_phone_binding" 'CurrentPhone:NewPhone:2026-06-01'
  Query "USE $database; $(RuntimeSql 'clearReplacedPhoneRuntime')" | Out-Null
  AssertValue "SELECT CONCAT(paused_reason,':',online_status,':',heartbeat_at IS NULL,':',active_task_no IS NULL) FROM nx_user_device_runtime WHERE user_device_id=12" 'PHONE_REPLACED:OFFLINE:1:1'
  AssertValue "SELECT CONCAT(active_task_no,':',online_status) FROM nx_user_device_runtime WHERE user_device_id=13" 'purchased-task:ONLINE'
  AssertValue 'SELECT paused_reason FROM nx_user_device_runtime WHERE user_device_id=14' 'ADMIN_PAUSED'
  Query "USE $database; $(RuntimeSql 'restoreBoundPhoneRuntime')" | Out-Null
  AssertValue "SELECT CONCAT(paused_reason IS NULL,':',online_status,':',heartbeat_at IS NULL) FROM nx_user_device_runtime WHERE user_device_id=12" '1:OFFLINE:1'
  AssertValue 'SELECT paused_reason FROM nx_user_device_runtime WHERE user_device_id=14' 'ADMIN_PAUSED'
  Write-Output 'PASS MySQL: legacy binding selection, exact identity, repeat migration, execution ownership, original phone recovery, purchased device isolation, operator pause preservation.'
} finally { Query "DROP DATABASE $database" | Out-Null }
