-- A published I6 key is one three-language unit. The earlier placeholder cleanup
-- retired only the offending locale, leaving EN/VI (including old branding) public.
-- Keep the historical text for editing, but take all three locales offline until
-- content owners approve and publish a replacement through I6.
DROP TEMPORARY TABLE IF EXISTS i6_invalid_published_keys;
CREATE TEMPORARY TABLE i6_invalid_published_keys (
  message_key VARCHAR(255) NOT NULL PRIMARY KEY
) ENGINE=MEMORY;

-- Include already partly retired keys: the version can still say PUBLISHED even
-- when the prior migration changed the placeholder locale's runtime status.
INSERT IGNORE INTO i6_invalid_published_keys (message_key)
SELECT message_key
  FROM nx_i18n_message_version
 WHERE status = 'PUBLISHED' AND is_deleted = 0
   AND (
     REGEXP_REPLACE(zh_value, '[[:space:]]+', '') REGEXP '^(.)\\1{4,}$'
     OR REGEXP_REPLACE(en_value, '[[:space:]]+', '') REGEXP '^(.)\\1{4,}$'
     OR REGEXP_REPLACE(vi_value, '[[:space:]]+', '') REGEXP '^(.)\\1{4,}$'
     OR LOWER(REGEXP_REPLACE(REGEXP_REPLACE(zh_value, '[[:space:]]+', ''), '[[:punct:]]+', ''))
        REGEXP '^(test|todo|tbd|placeholder|dummy|样例|测试|占位)+$'
     OR LOWER(REGEXP_REPLACE(REGEXP_REPLACE(en_value, '[[:space:]]+', ''), '[[:punct:]]+', ''))
        REGEXP '^(test|todo|tbd|placeholder|dummy|样例|测试|占位)+$'
     OR LOWER(REGEXP_REPLACE(REGEXP_REPLACE(vi_value, '[[:space:]]+', ''), '[[:punct:]]+', ''))
        REGEXP '^(test|todo|tbd|placeholder|dummy|样例|测试|占位)+$'
     OR REGEXP_LIKE(CONCAT_WS(' ', zh_value, en_value, vi_value), '(^|[^[:alnum:]])nexion', 'i')
   );

INSERT IGNORE INTO i6_invalid_published_keys (message_key)
SELECT message_key
  FROM nx_i18n_message
 WHERE status = 1 AND is_deleted = 0
   AND (
     REGEXP_REPLACE(message_value, '[[:space:]]+', '') REGEXP '^(.)\\1{4,}$'
     OR LOWER(REGEXP_REPLACE(REGEXP_REPLACE(message_value, '[[:space:]]+', ''), '[[:punct:]]+', ''))
        REGEXP '^(test|todo|tbd|placeholder|dummy|样例|测试|占位)+$'
     OR REGEXP_LIKE(message_value, '(^|[^[:alnum:]])nexion', 'i')
   );

UPDATE nx_i18n_message AS m
  JOIN i6_invalid_published_keys AS bad ON bad.message_key = m.message_key
   SET m.status = 0, m.updated_at = NOW()
 WHERE m.status = 1 AND m.is_deleted = 0;

UPDATE nx_i18n_message_version AS v
  JOIN i6_invalid_published_keys AS bad ON bad.message_key = v.message_key
   SET v.status = 'ARCHIVED', v.updated_at = NOW()
 WHERE v.status = 'PUBLISHED' AND v.is_deleted = 0;

-- The App endpoint reads nx_i18n_message one locale at a time with status=1.
-- Thus no EN/VI row of a retired key may remain publicly readable.
SET @i6_retirement_complete = (
  SELECT COUNT(*) = 0
    FROM nx_i18n_message AS m
    JOIN i6_invalid_published_keys AS bad ON bad.message_key = m.message_key
   WHERE m.status = 1 AND m.is_deleted = 0
);
SET @sql = IF(@i6_retirement_complete, 'SELECT 1',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''I6_INVALID_KEY_STILL_PUBLIC''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
DROP TEMPORARY TABLE i6_invalid_published_keys;
