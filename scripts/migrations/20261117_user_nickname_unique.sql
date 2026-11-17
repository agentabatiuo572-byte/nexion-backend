-- Nicknames are unique (UserNicknames). Rename every duplicate but the oldest holder, then add the index.
-- Re-runnable: once the index exists there are no duplicates left to rename and the ALTER is skipped.
UPDATE nx_user u
  JOIN (SELECT nickname, MIN(id) keep_id FROM nx_user GROUP BY nickname HAVING COUNT(*) > 1) d
    ON u.nickname = d.nickname AND u.id <> d.keep_id
   SET u.nickname = CONCAT(LEFT(u.nickname, 50), '-', CONV(u.id, 10, 36)), u.updated_at = NOW();
SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user' AND INDEX_NAME='uk_user_nickname'),'SELECT 1','ALTER TABLE nx_user ADD UNIQUE KEY uk_user_nickname(nickname)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
