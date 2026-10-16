-- Register only existing draft scripts/templates that have never had an I6 entry.
-- Keep all existing translations/history, and leave English/Vietnamese for operators.
-- No template or translation is published by this migration. Re-running is a no-op.
INSERT INTO nx_i18n_message_version
  (message_key, version_no, zh_value, en_value, vi_value, status,
   created_at, updated_at, is_deleted)
SELECT CONCAT('conversation.',
              CASE WHEN h.format='session_script' THEN 'script.' ELSE 'template.' END,
              LOWER(h.article_code)),
       1, TRIM(h.content), '', '', 'DRAFT', NOW(), NOW(), 0
  FROM nx_help_article h
 WHERE h.is_deleted=0 AND h.status=0
   AND h.format IN ('session_script','session_reply_template')
   AND CHAR_LENGTH(TRIM(h.content)) BETWEEN 1 AND 400
   AND NOT EXISTS (
       SELECT 1 FROM nx_i18n_message_version v
        WHERE v.message_key=CONCAT('conversation.',
              CASE WHEN h.format='session_script' THEN 'script.' ELSE 'template.' END,
              LOWER(h.article_code)))
   AND NOT EXISTS (
       SELECT 1 FROM nx_i18n_message m
        WHERE m.message_key=CONCAT('conversation.',
              CASE WHEN h.format='session_script' THEN 'script.' ELSE 'template.' END,
              LOWER(h.article_code)));
