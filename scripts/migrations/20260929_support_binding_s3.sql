-- Run only after the read-only preflight below has been saved. Never USE a different database.
-- Duplicate/invalid/orphan assignments require explicit adjudication; this migration aborts.
DELIMITER $$
DROP PROCEDURE IF EXISTS support_binding_s3_migrate$$
CREATE PROCEDURE support_binding_s3_migrate()
BEGIN
 IF EXISTS(SELECT user_id FROM nx_support_agent_user_assignment WHERE status='ACTIVE' AND is_deleted=0 GROUP BY user_id HAVING COUNT(*)>1) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUPPORT_DUPLICATE_ASSIGNMENT_REVIEW_REQUIRED';
 END IF;
 IF EXISTS(SELECT 1 FROM nx_support_agent_user_assignment x
   LEFT JOIN nx_admin a ON a.id=x.agent_admin_id LEFT JOIN nx_support_agent_profile p ON p.admin_id=a.id
   WHERE x.status='ACTIVE' AND x.is_deleted=0 AND (a.status<>1 OR a.is_deleted<>0 OR p.admin_id IS NULL
     OR p.enabled<>1 OR p.is_deleted<>0 OR p.seat_type<>'DEDICATED' OR FIND_IN_SET('advisor',REPLACE(LOWER(p.service_types),' ',''))=0
     OR NOT EXISTS(SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id
       WHERE rr.admin_id=a.id AND rr.is_deleted=0 AND r.is_deleted=0 AND r.status=1 AND r.role_code='SUPPORT'))) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUPPORT_INVALID_AGENT_REVIEW_REQUIRED';
 END IF;
 IF EXISTS(SELECT 1 FROM nx_support_agent_user_assignment x LEFT JOIN nx_user u ON u.id=x.user_id LEFT JOIN nx_admin a ON a.id=x.agent_admin_id WHERE x.status='ACTIVE' AND x.is_deleted=0 AND (u.id IS NULL OR u.is_deleted<>0 OR a.id IS NULL)) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUPPORT_ORPHAN_ASSIGNMENT_REVIEW_REQUIRED';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_support_agent_user_assignment' AND column_name='version') THEN
  ALTER TABLE nx_support_agent_user_assignment ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_support_agent_user_assignment' AND column_name='source') THEN
  ALTER TABLE nx_support_agent_user_assignment ADD COLUMN source VARCHAR(16) DEFAULT NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_support_agent_user_assignment' AND column_name='segment_root_id') THEN
  ALTER TABLE nx_support_agent_user_assignment ADD COLUMN segment_root_id BIGINT DEFAULT NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_support_agent_user_assignment' AND column_name='depth') THEN
  ALTER TABLE nx_support_agent_user_assignment ADD COLUMN depth INT DEFAULT NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_support_agent_user_assignment' AND column_name='parent_assignment_id') THEN
  ALTER TABLE nx_support_agent_user_assignment ADD COLUMN parent_assignment_id BIGINT DEFAULT NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_support_agent_user_assignment' AND column_name='rule_version') THEN
  ALTER TABLE nx_support_agent_user_assignment ADD COLUMN rule_version BIGINT DEFAULT NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_support_agent_user_assignment' AND column_name='operation_id') THEN
  ALTER TABLE nx_support_agent_user_assignment ADD COLUMN operation_id VARCHAR(128) DEFAULT NULL;
 END IF;
CREATE TABLE IF NOT EXISTS nx_support_rules (
 id BIGINT PRIMARY KEY, version BIGINT NOT NULL DEFAULT 1,
 dormant_days INT NULL, maintenance_days INT NULL, activity_window_days INT NULL,
 inheritance_mode VARCHAR(16) NOT NULL DEFAULT 'UNCONFIGURED', max_inheritance_depth INT NULL,
 updated_by BIGINT NULL,reason VARCHAR(200) NULL,updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
INSERT IGNORE INTO nx_support_rules(id) VALUES(1);
CREATE TABLE IF NOT EXISTS nx_support_binding_pool (
 customer_id BIGINT PRIMARY KEY, reason VARCHAR(32) NOT NULL, version BIGINT NOT NULL DEFAULT 1,
 entered_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS nx_support_reply_cursor (
 conversation_no VARCHAR(64) PRIMARY KEY,through_message_id BIGINT NOT NULL,reply_message_id BIGINT NOT NULL
) ENGINE=InnoDB;
-- Preserve the mapping by enriching the original row, never end or replace a legacy assignment.
CREATE TABLE IF NOT EXISTS nx_support_migration (id VARCHAR(64) PRIMARY KEY,committed_at DATETIME(6) NOT NULL) ENGINE=InnoDB;
START TRANSACTION;
SET @s3_first = NOT EXISTS(SELECT 1 FROM nx_support_migration WHERE id='support-s3-20260929');
INSERT IGNORE INTO nx_support_reply_cursor(conversation_no,through_message_id,reply_message_id)
 SELECT m.conversation_no,MAX(m.id),a.reply_id FROM nx_conversation_message m
 JOIN (SELECT conversation_no,MAX(id) reply_id FROM nx_conversation_message
       WHERE sender_type='agent' AND is_deleted=0 GROUP BY conversation_no) a ON a.conversation_no=m.conversation_no
 WHERE @s3_first AND m.sender_type='user' AND m.is_deleted=0 AND m.id<a.reply_id GROUP BY m.conversation_no,a.reply_id;
UPDATE nx_support_agent_user_assignment SET source='MIGRATED',segment_root_id=user_id,depth=0,
 operation_id='support-s3-migration-20260929'
 WHERE source IS NULL AND status='ACTIVE' AND is_deleted=0;
INSERT IGNORE INTO nx_support_binding_pool(customer_id,reason,entered_at)
 SELECT u.id,'MIGRATION_REVIEW',UTC_TIMESTAMP(6) FROM nx_user u
 WHERE u.is_deleted=0 AND NOT EXISTS(SELECT 1 FROM nx_support_agent_user_assignment x WHERE x.user_id=u.id AND x.status='ACTIVE' AND x.is_deleted=0);
INSERT IGNORE INTO nx_support_migration(id,committed_at) VALUES('support-s3-20260929',UTC_TIMESTAMP(6));
COMMIT;
END$$
CALL support_binding_s3_migrate()$$
DROP PROCEDURE support_binding_s3_migrate$$
DELIMITER ;
