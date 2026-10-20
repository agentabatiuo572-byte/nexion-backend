-- Forward-only and replayable. Existing display labels are not authentication evidence.
-- Unknown legacy sessions must reauthenticate; no revoked chain is resurrected.
SET @session_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_user_session' AND column_name='session_group')=0, 'ALTER TABLE nx_user_session ADD COLUMN session_group VARCHAR(16) NOT NULL DEFAULT ''UNKNOWN''', 'SELECT 1');
PREPARE session_stmt FROM @session_ddl; EXECUTE session_stmt; DEALLOCATE PREPARE session_stmt;
SET @session_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_user_session' AND column_name='installation_id')=0, 'ALTER TABLE nx_user_session ADD COLUMN installation_id VARCHAR(128) COLLATE utf8mb4_bin NULL', 'SELECT 1');
PREPARE session_stmt FROM @session_ddl; EXECUTE session_stmt; DEALLOCATE PREPARE session_stmt;
SET @session_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_user_session' AND column_name='app_session_version')=0, 'ALTER TABLE nx_user_session ADD COLUMN app_session_version BIGINT NULL', 'SELECT 1');
PREPARE session_stmt FROM @session_ddl; EXECUTE session_stmt; DEALLOCATE PREPARE session_stmt;
SET @session_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_user_session' AND column_name='execution_supported')=0, 'ALTER TABLE nx_user_session ADD COLUMN execution_supported TINYINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE session_stmt FROM @session_ddl; EXECUTE session_stmt; DEALLOCATE PREPARE session_stmt;
SET @session_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_user_session' AND column_name='login_attempt_id')=0, 'ALTER TABLE nx_user_session ADD COLUMN login_attempt_id VARCHAR(80) COLLATE utf8mb4_bin NULL', 'SELECT 1');
PREPARE session_stmt FROM @session_ddl; EXECUTE session_stmt; DEALLOCATE PREPARE session_stmt;
SET @session_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_user_session' AND column_name='session_ended_reason')=0, 'ALTER TABLE nx_user_session ADD COLUMN session_ended_reason VARCHAR(48) NULL', 'SELECT 1');
PREPARE session_stmt FROM @session_ddl; EXECUTE session_stmt; DEALLOCATE PREPARE session_stmt;

CREATE TABLE IF NOT EXISTS nx_app_session_state (
 user_id BIGINT NOT NULL PRIMARY KEY,
 app_session_version BIGINT NOT NULL DEFAULT 0,
 session_chain_id VARCHAR(64) COLLATE utf8mb4_bin NULL,
 installation_id VARCHAR(128) COLLATE utf8mb4_bin NULL,
 execution_supported TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_app_login_attempt (
 attempt_id VARCHAR(80) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 user_id BIGINT NOT NULL,
 context_hash CHAR(64) COLLATE ascii_bin NOT NULL,
 recovery_hash CHAR(64) COLLATE ascii_bin NOT NULL,
 session_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_app_attempt_session(session_id), KEY idx_app_attempt_user(user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- No historic source is trustworthy in the deployed schema: retain UNKNOWN, rather than
-- deriving authority from device_name/IP. New fixed-context rows are never rewritten.
UPDATE nx_user_session SET session_group='UNKNOWN',revoked_at=COALESCE(revoked_at,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 8 HOUR)),session_ended_reason=COALESCE(session_ended_reason,'REAUTH_REQUIRED')
 WHERE session_group IS NULL OR session_group='UNKNOWN';

INSERT IGNORE INTO nx_event_schema_registry
(event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES ('account.app_session_replaced','account','app-session','server','A2/A4/C5',1,'100%',1,'ACTIVE','migration:app-session','Atomic App session takeover',0);
INSERT IGNORE INTO nx_event_schema_property(schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,0
FROM nx_event_schema_registry s CROSS JOIN (
 SELECT 'old_session_version' property_name,'number' property_type UNION ALL
 SELECT 'new_session_version','number' UNION ALL SELECT 'old_installation_id','id' UNION ALL
 SELECT 'new_installation_id','id' UNION ALL SELECT 'operation_id','id' UNION ALL SELECT 'committed_at','timestamp'
) p WHERE s.event_name='account.app_session_replaced' AND s.is_deleted=0;
