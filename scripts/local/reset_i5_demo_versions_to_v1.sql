-- LOCAL DEVELOPMENT ONLY.
-- Re-labels the four fixture-owned I5 disclosure snapshots to the lifecycle baseline v1.
-- The guards deliberately fail closed on another MySQL instance or after real user activity.
USE nexion;

DELIMITER $$
DROP PROCEDURE IF EXISTS local_reset_i5_demo_versions_to_v1$$
CREATE PROCEDURE local_reset_i5_demo_versions_to_v1()
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    RESIGNAL;
  END;

  IF @@server_uuid <> 'b640b1cb-4dad-11f1-b9b9-a40c6626955e' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Refusing I5 reset: this is not the approved local MySQL instance';
  END IF;
  IF DATABASE() <> 'nexion' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Refusing I5 reset: database must be nexion';
  END IF;
  IF (SELECT COUNT(*) FROM nx_disclosure_draft) <> 4
     OR (SELECT COUNT(*) FROM nx_disclosure_draft WHERE is_deleted = 0 AND last_operator = 'fixture:i5-demo') <> 4
     OR (SELECT COUNT(*) FROM nx_disclosure_draft
         WHERE is_deleted = 0 AND version_label IN ('v2024.3', 'v2024.4')) <> 4
     OR (SELECT COUNT(*) FROM nx_disclosure_chapter WHERE is_deleted = 0 AND last_operator = 'fixture:i5-demo') <> 28 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Refusing I5 reset: disclosure rows are no longer the expected local fixture set';
  END IF;
  IF (SELECT COUNT(*) FROM nx_disclosure_ack_status WHERE is_deleted = 0) <> 0
     OR (SELECT COUNT(*) FROM nx_disclosure_read_token) <> 0
     OR (SELECT COUNT(*) FROM nx_disclosure_gate_block_event) <> 0
     OR (SELECT COUNT(*) FROM nx_notification WHERE biz_no LIKE 'i5:reack:%') <> 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Refusing I5 reset: real disclosure activity already exists';
  END IF;

  START TRANSACTION;
  UPDATE nx_disclosure_chapter
  SET version_label = 'v1', updated_at = NOW()
  WHERE is_deleted = 0 AND last_operator = 'fixture:i5-demo';

  UPDATE nx_disclosure_draft
  SET version_label = 'v1', status = 'PUBLISHED', requires_reack = 1,
      revision = revision + 1, content_hash = '', updated_at = NOW()
  WHERE is_deleted = 0 AND last_operator = 'fixture:i5-demo';

  UPDATE nx_disclosure_jurisdiction j
  JOIN nx_disclosure_draft d ON d.jurisdiction_code = j.jurisdiction_code AND d.is_deleted = 0
  SET j.version_label = 'v1', j.status = 'PUBLISHED', j.ack_progress_pct = 0,
      j.blocked_count = 0, j.published_at_label = DATE_FORMAT(CURRENT_DATE, '%m-%d'),
      j.last_operator = 'fixture:i5-demo-reset', j.updated_at = NOW()
  WHERE j.is_deleted = 0;

  SET SESSION group_concat_max_len = 16777216;
  UPDATE nx_disclosure_draft d
  LEFT JOIN (
    SELECT jurisdiction_code, version_label,
      GROUP_CONCAT(CONCAT(
        OCTET_LENGTH(COALESCE(chapter_no, '')), ':', COALESCE(chapter_no, ''), ';',
        OCTET_LENGTH(COALESCE(zh_title, '')), ':', COALESCE(zh_title, ''), ';',
        OCTET_LENGTH(COALESCE(vi_title, '')), ':', COALESCE(vi_title, ''), ';',
        OCTET_LENGTH(COALESCE(en_title, '')), ':', COALESCE(en_title, ''), ';',
        OCTET_LENGTH(COALESCE(zh_body, '')), ':', COALESCE(zh_body, ''), ';',
        OCTET_LENGTH(COALESCE(vi_body, '')), ':', COALESCE(vi_body, ''), ';',
        OCTET_LENGTH(COALESCE(en_body, '')), ':', COALESCE(en_body, ''), ';'
      ) ORDER BY sort_order, id SEPARATOR '') AS chapter_canonical
    FROM nx_disclosure_chapter
    WHERE is_deleted = 0
    GROUP BY jurisdiction_code, version_label
  ) c ON c.jurisdiction_code = d.jurisdiction_code AND c.version_label = d.version_label
  SET d.content_hash = LOWER(SHA2(CONCAT(
    OCTET_LENGTH(COALESCE(d.version_label, '')), ':', COALESCE(d.version_label, ''), ';',
    OCTET_LENGTH(COALESCE(d.jurisdiction_code, '')), ':', COALESCE(d.jurisdiction_code, ''), ';',
    OCTET_LENGTH(COALESCE(d.language_scope, '')), ':', COALESCE(d.language_scope, ''), ';',
    OCTET_LENGTH(COALESCE(d.effective_date, '')), ':', COALESCE(d.effective_date, ''), ';',
    OCTET_LENGTH(IF(d.requires_reack = 1, 'true', 'false')), ':', IF(d.requires_reack = 1, 'true', 'false'), ';',
    OCTET_LENGTH(COALESCE(d.zh_body, '')), ':', COALESCE(d.zh_body, ''), ';',
    OCTET_LENGTH(COALESCE(d.vi_body, '')), ':', COALESCE(d.vi_body, ''), ';',
    OCTET_LENGTH(COALESCE(d.en_body, '')), ':', COALESCE(d.en_body, ''), ';',
    COALESCE(c.chapter_canonical, '')
  ), 256))
  WHERE d.is_deleted = 0 AND d.last_operator = 'fixture:i5-demo';

  IF (SELECT COUNT(*) FROM nx_disclosure_draft
      WHERE is_deleted = 0 AND version_label = 'v1' AND status = 'PUBLISHED'
        AND requires_reack = 1 AND CHAR_LENGTH(content_hash) = 64) <> 4
     OR (SELECT COUNT(*) FROM nx_disclosure_chapter
         WHERE is_deleted = 0 AND version_label = 'v1') <> 28
     OR (SELECT COUNT(*) FROM nx_disclosure_jurisdiction
         WHERE is_deleted = 0 AND version_label = 'v1' AND status = 'PUBLISHED'
           AND published_at_label IS NOT NULL AND published_at_label <> '') <> 4 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'I5 reset verification failed; transaction will be rolled back';
  END IF;

  INSERT INTO nx_audit_log
    (service_name, action, resource_type, resource_id, actor_type, actor_username, result, risk_level, detail_json)
  VALUES
    ('nexion-backend', 'LOCAL_I5_DEMO_VERSION_RESET', 'DISCLOSURE_FIXTURE', 'all',
     'SYSTEM', 'local-maintenance', 'SUCCESS', 'WARN',
     JSON_OBJECT('from', JSON_ARRAY('v2024.3', 'v2024.4'), 'to', 'v1', 'fixtureRows', 4));
  COMMIT;
END$$
CALL local_reset_i5_demo_versions_to_v1()$$
DROP PROCEDURE local_reset_i5_demo_versions_to_v1$$
DELIMITER ;
