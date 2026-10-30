param(
    [Parameter(Mandatory = $true)][string]$SnapshotPath,
    [Parameter(Mandatory = $true)][string]$OutputPath
)

# Generates reviewed SQL only. It never connects to a database or runs the SQL.
$snapshotBytes = [IO.File]::ReadAllBytes([IO.Path]::GetFullPath($SnapshotPath))
$snapshot = [Text.Encoding]::UTF8.GetString($snapshotBytes).TrimStart([char]0xfeff) | ConvertFrom-Json -Depth 60 -DateKind String
$digest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($snapshotBytes)).ToLowerInvariant()
$owner = 60723153007L
$bindings = @{
    1204 = @(24551, 'DEV-TI-27D1233EBF21434FB85EC6B49D66507F', 150)
    1208 = @(23438, 'NEX-ORD-AA102D940A5C447696E538F5', 150)
    1209 = @(23439, 'NEX-ORD-5A61BFA35341348072293238', 30)
    1210 = @(23440, 'NEX-ORD-79B3CF0AFC5E63E4B0D191A5', 480)
    1212 = @(24320, 'DEV-TI-ADFCB790173045B89BC7E90F1058F275', 30)
    1214 = @(24328, 'DEV-CPR-A70B39752516410385509B908766F165', 30)
}
$culture = [Globalization.CultureInfo]::InvariantCulture
function Read-Time([string]$value) {
    return [datetime]::Parse($value.Replace('T', ' '), $culture)
}
function Round-Second([datetime]$value) {
    $ticks = $value.Ticks % [TimeSpan]::TicksPerSecond
    return $value.AddTicks(-$ticks).AddSeconds([int]($ticks -ge 5000000))
}
function Sql-String([string]$value) {
    return "'" + $value.Replace("'", "''") + "'"
}
$rows = foreach ($device in $bindings.Keys | Sort-Object) {
    $binding = $bindings[$device]
    $lock = @($snapshot.database.locks | Where-Object device -EQ $device)
    if ($lock.Count -ne 1) { throw "Expected one reviewed lock for device $device" }
    $lock = $lock[0]
    if ($lock.id -ne $binding[0] -or $lock.instance -cne $binding[1] -or $lock.owner -ne $owner `
            -or $lock.taskOwner -ne $owner -or $lock.taskDevice -ne $device `
            -or $lock.status -cne 'ACTIVE' -or $lock.taskStatus -cne 'COMPLETED' `
            -or $lock.environment -cne 'PRODUCTION' -or $lock.taskMinutes -ne $binding[2] `
            -or $lock.receiptEarningStatus -cne 'CREDITED' `
            -or $lock.lastTask -cnotmatch '^CTA-[A-F0-9]{32}$' `
            -or $lock.receiptNo -cne ('CTR-' + $lock.lastTask.Substring(4))) {
        throw "Reviewed binding, completed task or credited receipt mismatch for device $device"
    }
    $audit = @($snapshot.database.completionAudits | Where-Object {
        $_.task -ceq $lock.lastTask -and $_.device -eq $device `
            -and $_.receipt -ceq $lock.receiptNo -and $_.action -ceq 'TASK_ASSIGNMENT_COMPLETED'
    })
    if ($audit.Count -ne 1) { throw "Expected one source completion audit for device $device" }
    $completed = Read-Time $lock.completedAt
    $auditUntil = Read-Time $audit[0].lockUntil
    if ($auditUntil -ne $completed.AddMinutes($lock.taskMinutes) `
            -or (Read-Time $lock.lockUntil) -ne (Round-Second $auditUntil) `
            -or (Read-Time $lock.updatedAt) -ne (Round-Second $completed)) {
        throw "Source audit and DATETIME(0) rounding do not prove the misused threshold for device $device"
    }
    '(' + (@($lock.id, $owner, $device) + @(
        (Sql-String $lock.instance), (Sql-String $lock.lastTask), (Sql-String $lock.receiptNo),
        (Sql-String $lock.createdAt), (Sql-String $lock.lockUntil), (Sql-String $lock.updatedAt),
        (Sql-String $completed.ToString('yyyy-MM-dd HH:mm:ss.ffffff', $culture)), $lock.taskMinutes,
        $audit[0].id, (Sql-String $auditUntil.ToString('yyyy-MM-dd HH:mm:ss.ffffff', $culture))) -join ',') + ')'
}
$config = @{}
foreach ($key in @('taskLockS1', 'taskLockPro', 'taskLockRack')) {
    $entry = @($snapshot.database.config | Where-Object key -CEQ $key)
    if ($entry.Count -ne 1 -or $entry[0].value -cnotmatch '^\d+$') { throw "Invalid source USDT config $key" }
    $config[$key] = Sql-String $entry[0].value
}
$sql = @'
-- TEST ONLY. Generated from an immutable snapshot; not a startup migration.
-- Stop the TEST fleet worker, obtain a fresh snapshot, regenerate, review, then execute
-- in one MySQL session without --force. No network action is performed by the generator.
-- Source SHA256: __DIGEST__
-- Six reviewed ACTIVE physical bindings only. 1152 and all other locks are excluded.
-- Any stale binding/task/audit/receipt/config causes the whole operation to ROLLBACK.
SET @tasklock_recovery_trace = 'tasklock-usdt-20261030-__DIGEST_SHORT__';
SET @tasklock_recovery_digest = '__DIGEST__';
DROP TEMPORARY TABLE IF EXISTS tmp_tasklock_review_20261030;
DROP TEMPORARY TABLE IF EXISTS tmp_tasklock_match_20261030;
CREATE TEMPORARY TABLE tmp_tasklock_review_20261030 (
  lock_id BIGINT PRIMARY KEY, owner_id BIGINT NOT NULL, device_id BIGINT NOT NULL UNIQUE,
  instance_no VARCHAR(96) NOT NULL, task_no VARCHAR(96) NOT NULL, receipt_no VARCHAR(96) NOT NULL,
  lock_created_at DATETIME(0) NOT NULL, old_lock_until DATETIME(0) NOT NULL,
  old_updated_at DATETIME(0) NOT NULL, completed_at DATETIME(6) NOT NULL,
  task_lock_minutes INT NOT NULL, completion_audit_id BIGINT NOT NULL,
  audit_lock_until DATETIME(6) NOT NULL
) ENGINE=InnoDB;
INSERT INTO tmp_tasklock_review_20261030 VALUES
__ROWS__;
CREATE TEMPORARY TABLE tmp_tasklock_match_20261030 LIKE tmp_tasklock_review_20261030;

START TRANSACTION;
-- Same user -> device -> task locking order as the application settlement path.
SELECT id FROM nx_user WHERE id=60723153007 FOR UPDATE;
SELECT d.id FROM nx_user_device d JOIN tmp_tasklock_review_20261030 q ON q.device_id=d.id ORDER BY d.id FOR UPDATE;
SELECT t.id FROM nx_compute_task t JOIN tmp_tasklock_review_20261030 q ON q.task_no=t.task_no ORDER BY t.id FOR UPDATE;
SELECT l.id FROM nx_compute_device_task_lock l JOIN tmp_tasklock_review_20261030 q ON q.lock_id=l.id ORDER BY l.id FOR UPDATE;
SELECT r.id FROM nx_compute_receipt r JOIN tmp_tasklock_review_20261030 q ON q.receipt_no=r.receipt_no ORDER BY r.id FOR UPDATE;
SELECT a.id FROM nx_audit_log a JOIN tmp_tasklock_review_20261030 q ON q.completion_audit_id=a.id ORDER BY a.id FOR UPDATE;
SELECT config_key,config_value FROM nx_compute_e3_config
 WHERE config_key IN ('taskLockS1','taskLockPro','taskLockRack') AND is_deleted=0 ORDER BY config_key FOR UPDATE;

INSERT INTO tmp_tasklock_match_20261030
SELECT q.* FROM tmp_tasklock_review_20261030 q
JOIN nx_user u ON u.id=q.owner_id AND u.status='ACTIVE' AND u.sandbox=0 AND u.is_deleted=0
JOIN nx_user_device d ON d.id=q.device_id AND d.user_id=q.owner_id AND d.instance_no=q.instance_no
 AND d.status='ACTIVE' AND d.ownership_status='OWNED' AND d.source_environment='PRODUCTION'
 AND COALESCE(d.run_id,'')='' AND d.activated_at IS NOT NULL AND d.deactivated_at IS NULL
 AND d.pending_deactivate=0 AND d.is_deleted=0
JOIN nx_compute_device_task_lock l ON l.id=q.lock_id AND l.user_id=q.owner_id AND l.user_device_id=q.device_id
 AND l.source_environment='PRODUCTION' AND l.is_deleted=0 AND l.last_task_no=q.task_no
 AND l.created_at=q.lock_created_at AND l.lock_until=q.old_lock_until AND l.updated_at=q.old_updated_at
JOIN nx_compute_task t ON t.task_no=q.task_no AND t.user_id=q.owner_id AND t.user_device_id=q.device_id
 AND t.source_environment='PRODUCTION' AND t.status='COMPLETED' AND t.is_deleted=0
 AND t.completed_at=q.completed_at AND t.task_lock_minutes=q.task_lock_minutes AND t.task_lock_minutes>0
JOIN nx_compute_receipt r ON r.receipt_no=q.receipt_no AND r.task_no=q.task_no
 AND r.user_id=q.owner_id AND r.user_device_id=q.device_id AND r.source_environment='PRODUCTION'
 AND r.earning_status='CREDITED' AND r.is_deleted=0
JOIN nx_audit_log a ON a.id=q.completion_audit_id AND a.user_id=q.owner_id
 AND a.action='TASK_ASSIGNMENT_COMPLETED' AND a.resource_type='COMPUTE_TASK'
 AND a.resource_id=q.task_no AND a.biz_no=q.receipt_no AND a.result='SUCCESS' AND a.is_deleted=0
 AND CAST(JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'$.deviceId')) AS UNSIGNED)=q.device_id
 AND JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'$.receiptNo'))=q.receipt_no
 AND CAST(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'$.lockUntil')),'T',' ') AS DATETIME(6))=q.audit_lock_until
WHERE q.owner_id=60723153007 AND q.device_id IN (1204,1208,1209,1210,1212,1214) AND q.device_id<>1152
 AND q.audit_lock_until=DATE_ADD(t.completed_at,INTERVAL t.task_lock_minutes MINUTE)
 AND l.lock_until=CAST(q.audit_lock_until AS DATETIME(0))
 AND l.updated_at=CAST(t.completed_at AS DATETIME(0))
 AND t.completed_at<=CURRENT_TIMESTAMP(6) AND CAST(t.completed_at AS DATETIME(0))<l.lock_until
 AND NOT EXISTS(SELECT 1 FROM nx_compute_task active WHERE active.user_id=q.owner_id
   AND active.user_device_id=q.device_id AND active.source_environment='PRODUCTION'
   AND active.status IN ('CLAIMED','RUNNING') AND active.is_deleted=0);

SET @tasklock_recovery_targets=(SELECT COUNT(*) FROM tmp_tasklock_review_20261030);
SET @tasklock_recovery_matched=(SELECT COUNT(*) FROM tmp_tasklock_match_20261030);
SET @tasklock_recovery_config_matched=(SELECT COUNT(*) FROM nx_compute_e3_config WHERE is_deleted=0 AND
 ((config_key='taskLockS1' AND config_value=__S1__) OR
  (config_key='taskLockPro' AND config_value=__PRO__) OR
  (config_key='taskLockRack' AND config_value=__RACK__)));

INSERT INTO nx_audit_log(trace_id,service_name,action,resource_type,resource_id,biz_no,user_id,
 actor_type,actor_username,result,risk_level,detail_json,created_at,is_deleted)
SELECT @tasklock_recovery_trace,'nexion-backend','TASK_LOCK_USDT_HINT_RECOVERED','COMPUTE_DEVICE_TASK_LOCK',
 CAST(q.lock_id AS CHAR),q.task_no,q.owner_id,'SYSTEM','test:tasklock-usdt-recovery-20261030','SUCCESS','HIGH',
 JSON_OBJECT('reason','User-authorized TEST correction: E3 USDT hint threshold was misused as cooldown minutes',
   'sourceSnapshotSha256',@tasklock_recovery_digest,'databaseName',DATABASE(),
   'sourceCompletionAuditId',q.completion_audit_id,'sourceCompletionAuditDetail',a.detail_json,
   'deviceId',q.device_id,'instanceNo',q.instance_no,'taskNo',q.task_no,'receiptNo',q.receipt_no,
   'taskCompletedAt',q.completed_at,'legacyTaskLockMinutes',q.task_lock_minutes,
   'oldLock',JSON_OBJECT('id',q.lock_id,'userId',q.owner_id,'userDeviceId',q.device_id,
     'sourceEnvironment','PRODUCTION','lastTaskNo',q.task_no,'lockUntil',q.old_lock_until,
     'createdAt',q.lock_created_at,'updatedAt',q.old_updated_at,'isDeleted',0),
   'newLockUntil',CAST(q.completed_at AS DATETIME(0)),
   'unchangedE3UsdtHintThresholds',JSON_OBJECT('taskLockS1',__S1__,'taskLockPro',__PRO__,'taskLockRack',__RACK__)),
 CURRENT_TIMESTAMP(0),0
FROM tmp_tasklock_match_20261030 q JOIN nx_audit_log a ON a.id=q.completion_audit_id
WHERE @tasklock_recovery_targets=6 AND @tasklock_recovery_matched=6 AND @tasklock_recovery_config_matched=3
 AND NOT EXISTS(SELECT 1 FROM nx_audit_log previous WHERE previous.trace_id=@tasklock_recovery_trace
   AND previous.action='TASK_LOCK_USDT_HINT_RECOVERED');
SET @tasklock_recovery_audited=ROW_COUNT();

UPDATE nx_compute_device_task_lock l JOIN tmp_tasklock_match_20261030 q ON q.lock_id=l.id
SET l.lock_until=CAST(q.completed_at AS DATETIME(0)),l.updated_at=CURRENT_TIMESTAMP(0)
WHERE @tasklock_recovery_audited=6 AND @tasklock_recovery_matched=6 AND @tasklock_recovery_config_matched=3
 AND l.user_id=q.owner_id AND l.user_device_id=q.device_id AND l.source_environment='PRODUCTION'
 AND l.last_task_no=q.task_no AND l.created_at=q.lock_created_at AND l.lock_until=q.old_lock_until
 AND l.updated_at=q.old_updated_at AND l.is_deleted=0 AND l.user_device_id<>1152;
SET @tasklock_recovery_updated=ROW_COUNT();
SET @tasklock_recovery_success=(@tasklock_recovery_targets=6 AND @tasklock_recovery_matched=6
 AND @tasklock_recovery_config_matched=3 AND @tasklock_recovery_audited=6 AND @tasklock_recovery_updated=6);
SET @tasklock_recovery_finish=IF(@tasklock_recovery_success,'COMMIT','ROLLBACK');
PREPARE tasklock_recovery_finish FROM @tasklock_recovery_finish;
EXECUTE tasklock_recovery_finish;
DEALLOCATE PREPARE tasklock_recovery_finish;

SELECT @tasklock_recovery_trace AS trace_id,@tasklock_recovery_targets AS reviewed,
 @tasklock_recovery_matched AS source_matched,@tasklock_recovery_config_matched AS unchanged_e3_keys,
 @tasklock_recovery_audited AS inserted_audits,@tasklock_recovery_updated AS updated_locks,
 @tasklock_recovery_success AS committed;
SELECT q.device_id,q.instance_no,l.id,l.last_task_no,q.old_lock_until,l.lock_until,l.updated_at,
 t.task_lock_minutes,t.completed_at,r.receipt_no,r.earning_status
FROM tmp_tasklock_review_20261030 q JOIN nx_compute_device_task_lock l ON l.id=q.lock_id
JOIN nx_compute_task t ON t.task_no=q.task_no JOIN nx_compute_receipt r ON r.receipt_no=q.receipt_no ORDER BY q.device_id;
SELECT id,trace_id,resource_id,biz_no,detail_json FROM nx_audit_log
 WHERE trace_id=@tasklock_recovery_trace AND action='TASK_LOCK_USDT_HINT_RECOVERED' ORDER BY id;
SELECT config_key,config_value FROM nx_compute_e3_config
 WHERE config_key IN ('taskLockS1','taskLockPro','taskLockRack') AND is_deleted=0 ORDER BY config_key;
DROP TEMPORARY TABLE tmp_tasklock_match_20261030;
DROP TEMPORARY TABLE tmp_tasklock_review_20261030;
'@
$sql = $sql.Replace('__DIGEST_SHORT__', $digest.Substring(0, 32)).Replace('__DIGEST__', $digest)
$sql = $sql.Replace('__ROWS__', ($rows -join ",`n")).Replace('__S1__', $config.taskLockS1)
$sql = $sql.Replace('__PRO__', $config.taskLockPro).Replace('__RACK__', $config.taskLockRack)
[IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputPath), $sql + "`n", [Text.UTF8Encoding]::new($false))
Write-Output ([pscustomobject]@{ Output = [IO.Path]::GetFullPath($OutputPath); SourceSha256 = $digest; ReviewedLocks = $rows.Count })
