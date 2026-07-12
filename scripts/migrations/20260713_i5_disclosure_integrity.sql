-- I5 disclosure immutable snapshot, monotonic concurrency and single-published invariant.
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE nx_disclosure_draft ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 AFTER status',
  'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_disclosure_draft' AND COLUMN_NAME = 'revision');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE nx_disclosure_draft ADD COLUMN content_hash CHAR(64) NOT NULL DEFAULT '''' AFTER revision',
  'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_disclosure_draft' AND COLUMN_NAME = 'content_hash');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- The canonical format exactly mirrors DisclosureContentHash: UTF-8 byte length:value; in chapter order.
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
WHERE d.content_hash IS NULL OR d.content_hash = '';

-- Repair legacy duplicate published rows deterministically before adding the database invariant.
UPDATE nx_disclosure_draft d
JOIN (
  SELECT jurisdiction_code, MAX(id) AS keep_id
  FROM nx_disclosure_draft
  WHERE status = 'PUBLISHED' AND is_deleted = 0
  GROUP BY jurisdiction_code
) keep_row ON keep_row.jurisdiction_code = d.jurisdiction_code
SET d.status = 'SUPERSEDED'
WHERE d.status = 'PUBLISHED' AND d.is_deleted = 0 AND d.id <> keep_row.keep_id;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE nx_disclosure_draft ADD COLUMN published_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status = ''PUBLISHED'' AND is_deleted = 0 THEN 1 ELSE NULL END) STORED AFTER content_hash',
  'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_disclosure_draft' AND COLUMN_NAME = 'published_slot');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE nx_disclosure_draft ADD UNIQUE KEY uk_disclosure_single_published (jurisdiction_code, published_slot)',
  'SELECT 1')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_disclosure_draft' AND INDEX_NAME = 'uk_disclosure_single_published');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
