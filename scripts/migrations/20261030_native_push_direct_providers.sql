-- Additive and rerunnable. Existing UniPush CIDs retain identity/proof but never enter direct transports.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_native_push_installation' AND column_name='provider')=0,
 'ALTER TABLE nx_native_push_installation ADD COLUMN provider VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''UNIPUSH'' AFTER client_id','SELECT 1');
PREPARE native_stmt FROM @ddl;
EXECUTE native_stmt;
DEALLOCATE PREPARE native_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_native_push_installation' AND index_name='uk_native_push_cid')>0,
 'ALTER TABLE nx_native_push_installation DROP INDEX uk_native_push_cid','SELECT 1');
PREPARE native_stmt FROM @ddl;
EXECUTE native_stmt;
DEALLOCATE PREPARE native_stmt;
ALTER TABLE nx_native_push_installation MODIFY client_id VARCHAR(4096) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_native_push_installation' AND column_name='token_digest')=0,
 'ALTER TABLE nx_native_push_installation ADD COLUMN token_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER provider','SELECT 1');
PREPARE native_stmt FROM @ddl;
EXECUTE native_stmt;
DEALLOCATE PREPARE native_stmt;
UPDATE nx_native_push_installation SET token_digest=SHA2(CONCAT(provider,CHAR(0),client_id),256) WHERE token_digest IS NULL;
ALTER TABLE nx_native_push_installation MODIFY token_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_native_push_installation' AND index_name='uk_native_push_token_digest')=0,
 'ALTER TABLE nx_native_push_installation ADD UNIQUE INDEX uk_native_push_token_digest(token_digest)','SELECT 1');
PREPARE native_stmt FROM @ddl;
EXECUTE native_stmt;
DEALLOCATE PREPARE native_stmt;
-- Only the first transition deactivates a legacy binding and increments its epoch. Keep proof for explicit native re-registration.
UPDATE nx_native_push_installation SET active=0,binding_epoch=binding_epoch+1,updated_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 8 HOUR) WHERE provider='UNIPUSH' AND active=1;
UPDATE nx_native_push_delivery d JOIN nx_native_push_installation i ON i.installation_id=d.installation_id
 SET d.status='CANCELLED',d.last_error='LEGACY_PROVIDER_RETIRED',d.lease_token=NULL,d.lease_until=NULL,d.updated_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 8 HOUR)
 WHERE i.provider='UNIPUSH' AND d.status IN ('QUEUED','RETRY','BLOCKED','SENDING');

ALTER TABLE nx_native_push_delivery MODIFY provider_task_id VARCHAR(512) NULL;
