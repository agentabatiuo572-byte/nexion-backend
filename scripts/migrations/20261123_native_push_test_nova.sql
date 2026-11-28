-- NULL keeps existing installations on the deployment's original APNs topic.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_native_push_installation' AND column_name='apns_topic')=0,
  'ALTER TABLE nx_native_push_installation ADD COLUMN apns_topic VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL',
  'SELECT 1');
PREPARE native_stmt FROM @ddl;
EXECUTE native_stmt;
DEALLOCATE PREPARE native_stmt;

-- Start NOVA with newly produced inbox notifications. Restarts and retries keep this cutoff.
INSERT IGNORE INTO nx_native_push_cursor (id,notification_id,updated_at)
SELECT 2,COALESCE(MAX(id),0),NOW(6) FROM nx_notification;
