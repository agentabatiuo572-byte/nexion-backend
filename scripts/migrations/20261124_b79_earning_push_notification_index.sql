-- B79-R01: look up the frozen target by notification and recipient.
SET @ddl = IF(EXISTS (
  SELECT 1
    FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_earning_push_task'
     AND INDEX_TYPE='BTREE' AND IS_VISIBLE='YES'
   GROUP BY INDEX_NAME
  HAVING SUM(SEQ_IN_INDEX=1 AND COLUMN_NAME='notification_id')=1
     AND SUM(SEQ_IN_INDEX=2 AND COLUMN_NAME='user_id')=1
), 'SELECT 1',
   'ALTER TABLE nx_earning_push_task ADD INDEX ix_earning_push_task_notification_user (notification_id, user_id)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
