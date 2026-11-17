-- Nicknames are unique (UserNicknames). Per duplicated name the earliest-registered live account keeps it;
-- every other holder is renamed, then the index is added. Re-runnable: once the index exists there are no
-- duplicates left to rename and the ALTER is skipped.
UPDATE nx_user u
  JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY nickname ORDER BY is_deleted, created_at, id) holder_rank
          FROM nx_user) d
    ON u.id = d.id AND d.holder_rank > 1
   SET u.nickname = CONCAT(LEFT(u.nickname, 50), '-', CONV(u.id, 10, 36)), u.updated_at = NOW();
SET @sql=IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user' AND INDEX_NAME='uk_user_nickname'),'SELECT 1','ALTER TABLE nx_user ADD UNIQUE KEY uk_user_nickname(nickname)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
