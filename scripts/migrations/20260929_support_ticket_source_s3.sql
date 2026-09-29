-- R08: preserve every message. NULL means unverified historical origin, never DIRECT.
-- Back up before DDL. Inspect the result counts and unresolved rows before deployment.
DELIMITER $$
DROP PROCEDURE IF EXISTS support_ticket_source_s3_migrate$$
CREATE PROCEDURE support_ticket_source_s3_migrate()
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_support_ticket' AND column_name='source_conversation_no') THEN
  ALTER TABLE nx_support_ticket ADD COLUMN source_conversation_no VARCHAR(100) NULL;
 END IF;
 DROP TEMPORARY TABLE IF EXISTS s3_ticket_source_events;
 CREATE TEMPORARY TABLE s3_ticket_source_events(original_id BIGINT,action VARCHAR(100),source_no VARCHAR(100),customer_id VARCHAR(32),detail_text LONGTEXT);
 INSERT INTO s3_ticket_source_events
 SELECT id,action,biz_no,CAST(user_id AS CHAR),detail_json FROM nx_audit_log
 WHERE is_deleted=0 AND result='SUCCESS' AND (
   (resource_type='CONVERSATION' AND action IN ('I9_CONVERSATION_CONVERTED_TO_TICKET','APP_CONVERSATION_CONVERTED_TO_TICKET'))
   OR (resource_type='SUPPORT_TICKET' AND action IN ('M2_SUPPORT_TICKET_CREATED','APP_SUPPORT_TICKET_CREATED')));
 IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='nx_audit_log_archive') THEN
  INSERT INTO s3_ticket_source_events
  SELECT original_audit_id,JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.action')),
    JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.bizNo')),
    JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.userId')),
    JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.detail'))
  FROM nx_audit_log_archive WHERE JSON_VALID(cold_payload)
    AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.result'))='SUCCESS'
    AND (
      (JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.resourceType'))='CONVERSATION'
       AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.action')) IN ('I9_CONVERSATION_CONVERTED_TO_TICKET','APP_CONVERSATION_CONVERTED_TO_TICKET'))
      OR (JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.resourceType'))='SUPPORT_TICKET'
       AND JSON_UNQUOTE(JSON_EXTRACT(cold_payload,'$.action')) IN ('M2_SUPPORT_TICKET_CREATED','APP_SUPPORT_TICKET_CREATED')));
 END IF;
 DROP TEMPORARY TABLE IF EXISTS s3_ticket_normalized_events;
 CREATE TEMPORARY TABLE s3_ticket_normalized_events AS
   SELECT DISTINCT original_id,action,
     CASE WHEN action IN ('M2_SUPPORT_TICKET_CREATED','APP_SUPPORT_TICKET_CREATED') THEN 'DIRECT' ELSE source_no END source_no,
     CASE WHEN action IN ('M2_SUPPORT_TICKET_CREATED','APP_SUPPORT_TICKET_CREATED') THEN source_no
       ELSE JSON_UNQUOTE(JSON_EXTRACT(CASE WHEN JSON_VALID(detail_text) THEN detail_text ELSE '{}' END,'$.ticketNo')) END ticket_no,
     CASE WHEN action='APP_SUPPORT_TICKET_CREATED' THEN customer_id
       WHEN action='M2_SUPPORT_TICKET_CREATED' THEN JSON_UNQUOTE(JSON_EXTRACT(CASE WHEN JSON_VALID(detail_text) THEN detail_text ELSE '{}' END,'$.userId')) END claimed_customer,
     JSON_VALID(detail_text) valid_detail
   FROM s3_ticket_source_events;
 -- Conflicting copies invalidate every touched ticket, even with other valid evidence.
 DROP TEMPORARY TABLE IF EXISTS s3_ticket_conflicting_ids;
 CREATE TEMPORARY TABLE s3_ticket_conflicting_ids AS
 SELECT original_id FROM s3_ticket_normalized_events GROUP BY original_id HAVING COUNT(*)>1;
 DROP TEMPORARY TABLE IF EXISTS s3_ticket_conflicting_tickets;
 CREATE TEMPORARY TABLE s3_ticket_conflicting_tickets AS
 SELECT DISTINCT e.ticket_no FROM s3_ticket_normalized_events e
 JOIN s3_ticket_conflicting_ids i ON i.original_id=e.original_id;
 DROP TEMPORARY TABLE IF EXISTS s3_ticket_verified_sources;
 CREATE TEMPORARY TABLE s3_ticket_verified_sources AS
 SELECT e.ticket_no,MIN(e.source_no) source_no
 FROM s3_ticket_normalized_events e
 JOIN nx_support_ticket t ON t.ticket_no=e.ticket_no AND t.is_deleted=0
 LEFT JOIN nx_conversation c ON c.conversation_no=e.source_no AND c.is_deleted=0 AND c.user_id=t.user_id
 LEFT JOIN s3_ticket_conflicting_tickets conflict ON conflict.ticket_no=e.ticket_no
 WHERE conflict.ticket_no IS NULL
 GROUP BY e.ticket_no
 HAVING COUNT(DISTINCT e.source_no)=1 AND COUNT(*)=SUM(
   CASE WHEN e.valid_detail=1 AND ((e.source_no='DIRECT' AND BINARY e.claimed_customer=BINARY CAST(t.user_id AS CHAR))
     OR (e.source_no<>'DIRECT' AND c.id IS NOT NULL)) THEN 1 ELSE 0 END);
 UPDATE nx_support_ticket t JOIN s3_ticket_verified_sources s ON s.ticket_no=t.ticket_no
 SET t.source_conversation_no=s.source_no WHERE t.source_conversation_no IS NULL;
 SELECT COUNT(*) malformed_conversion_evidence FROM s3_ticket_source_events WHERE NOT JSON_VALID(detail_text);
 SELECT CASE WHEN source_conversation_no IS NULL THEN 'UNKNOWN_RESTRICTED'
   WHEN source_conversation_no='DIRECT' THEN 'DIRECT' ELSE 'VERIFIED_CONVERSATION' END source_status,COUNT(*) row_count
 FROM nx_support_ticket WHERE is_deleted=0 GROUP BY source_status;
 SELECT ticket_no,user_id,'NO_UNIQUE_VERIFIED_SOURCE' reason FROM nx_support_ticket
 WHERE is_deleted=0 AND source_conversation_no IS NULL ORDER BY id;
 DROP TEMPORARY TABLE s3_ticket_verified_sources;
 DROP TEMPORARY TABLE s3_ticket_conflicting_tickets;
 DROP TEMPORARY TABLE s3_ticket_conflicting_ids;
 DROP TEMPORARY TABLE s3_ticket_normalized_events;
 DROP TEMPORARY TABLE s3_ticket_source_events;
END$$
CALL support_ticket_source_s3_migrate()$$
DROP PROCEDURE support_ticket_source_s3_migrate$$
DELIMITER ;
