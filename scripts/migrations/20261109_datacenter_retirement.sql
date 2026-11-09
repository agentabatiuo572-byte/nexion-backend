-- Deploy with the application STOPPED, then start only the new application.
-- Run the PRD read-only preflight first. Never call the retired resume/delete APIs.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_schema_migration_state (
  migration_key VARCHAR(128) PRIMARY KEY,
  phase VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP PROCEDURE IF EXISTS nx_retire_datacenter_20261109;
DELIMITER $$
CREATE PROCEDURE nx_retire_datacenter_20261109()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM nx_schema_migration_state
                 WHERE migration_key='20261109_datacenter_retirement' AND phase IN ('UNWOUND','FINAL')) THEN
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_compute_dc_ops_state') THEN
      -- The old mapper wrote raw #{reason}, just like user pause, and could overwrite
      -- an earlier ordinary pause. Equality is a MANUAL candidate, never proof.
      -- No nonempty automatic-clear predicate exists; no runtime pause is cleared here.
      SELECT d.id AS device_id,d.user_id,d.dc_location,r.paused_reason,r.updated_at,
             s.paused_at,s.updated_by,'MANUAL_PAUSE_PROVENANCE_REQUIRED' AS resolution
        FROM nx_user_device d
        JOIN nx_user_device_runtime r ON r.user_device_id=d.id AND r.is_deleted=0
        JOIN nx_compute_dc_ops_state s
          ON s.dc_location=COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED') AND s.is_deleted=0
       WHERE d.is_deleted=0 AND s.dispatch_paused=1
         AND r.paused_reason IS NOT NULL AND BINARY r.paused_reason=BINARY s.paused_reason;
      IF EXISTS (
        SELECT 1 FROM nx_user_device d
        JOIN nx_user_device_runtime r ON r.user_device_id=d.id AND r.is_deleted=0
        JOIN nx_compute_dc_ops_state s
          ON s.dc_location=COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED') AND s.is_deleted=0
        WHERE d.is_deleted=0 AND s.dispatch_paused=1
          AND r.paused_reason IS NOT NULL AND BINARY r.paused_reason=BINARY s.paused_reason
      ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='DC_RETIREMENT_MANUAL_PAUSE_RESOLUTION_REQUIRED';
      END IF;
    END IF;

    START TRANSACTION;
    CREATE TEMPORARY TABLE dc_retired_tickets (operation_id VARCHAR(64) PRIMARY KEY);
    INSERT INTO dc_retired_tickets
    SELECT t.operation_id FROM nx_audit_operation_ticket t
     WHERE t.status='pending' AND t.is_deleted=0 AND (
       JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(t.command_json),t.command_json,'{}'),'$.op')) IN
         ('e5_datacenter_create','e5_datacenter_update','e5_datacenter_delete','e5_datacenter_pause','e5_datacenter_resume')
       OR EXISTS (SELECT 1 FROM nx_audit_object_lock l
                   WHERE l.ticket_id=t.operation_id AND l.target_type='device_datacenter' AND l.is_deleted=0));

    INSERT INTO nx_audit_operation_history (operation_id,action,status,chain_text,time_label,note,is_deleted)
    SELECT t.operation_id,t.action,'expired','migration:datacenter-retirement','已作废',
           '托管机房登记与暂停功能退役，请勿回放旧机房操作',0
      FROM nx_audit_operation_ticket t JOIN dc_retired_tickets x ON x.operation_id=t.operation_id
    ON DUPLICATE KEY UPDATE status='expired',chain_text=VALUES(chain_text),time_label=VALUES(time_label),
                            note=VALUES(note),is_deleted=0;
    UPDATE nx_audit_operation_ticket t JOIN dc_retired_tickets x ON x.operation_id=t.operation_id
       SET t.status='expired',t.decision_reason='DATACENTER_RETIRED',t.decided_at=NOW(),t.updated_at=NOW();
    DELETE l FROM nx_audit_object_lock l LEFT JOIN dc_retired_tickets x ON x.operation_id=l.ticket_id
     WHERE l.target_type='device_datacenter' OR x.operation_id IS NOT NULL;
    DROP TEMPORARY TABLE dc_retired_tickets;

    DELETE rp FROM nx_admin_role_permission rp JOIN nx_admin_permission p ON p.id=rp.permission_id
     WHERE p.permission_code='device_e5_datacenter_pause';
    DELETE FROM nx_admin_permission WHERE permission_code='device_e5_datacenter_pause';
    DELETE p FROM nx_event_schema_property p JOIN nx_event_schema_registry s ON s.id=p.schema_id
     WHERE s.event_name IN ('admin.datacenter_created','admin.datacenter_updated','admin.datacenter_deleted');
    DELETE FROM nx_event_domain_extension
     WHERE event_name IN ('admin.datacenter_created','admin.datacenter_updated','admin.datacenter_deleted');
    DELETE FROM nx_event_schema_registry
     WHERE event_name IN ('admin.datacenter_created','admin.datacenter_updated','admin.datacenter_deleted');

    INSERT INTO nx_schema_migration_state (migration_key,phase)
    VALUES ('20261109_datacenter_retirement','UNWOUND')
    ON DUPLICATE KEY UPDATE phase='UNWOUND';
    COMMIT;
  END IF;
  -- DDL commits independently. UNWOUND prevents a restart from repeating data handling.
  DROP TABLE IF EXISTS nx_compute_dc_ops_state;
  DROP TABLE IF EXISTS nx_compute_datacenter;
  UPDATE nx_schema_migration_state SET phase='FINAL'
   WHERE migration_key='20261109_datacenter_retirement' AND phase<>'FINAL';
END$$
DELIMITER ;
CALL nx_retire_datacenter_20261109();
DROP PROCEDURE nx_retire_datacenter_20261109;
