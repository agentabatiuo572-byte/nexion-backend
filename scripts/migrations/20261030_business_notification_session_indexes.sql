-- Additive indexes for the bounded first-sign-in notification projection. No source data or receipt changes.
SET @notification_session_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_user_session' AND index_name='idx_user_session_notify_created')=0,
 'ALTER TABLE nx_user_session ADD INDEX idx_user_session_notify_created(is_deleted,created_at), ALGORITHM=INPLACE, LOCK=NONE','SELECT 1');
PREPARE notification_session_stmt FROM @notification_session_ddl;
EXECUTE notification_session_stmt;
DEALLOCATE PREPARE notification_session_stmt;
SET @notification_session_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_user_session' AND index_name='idx_user_session_notify_peer')=0,
 'ALTER TABLE nx_user_session ADD INDEX idx_user_session_notify_peer(user_id,client_ip,session_group,is_deleted,installation_id,created_at), ALGORITHM=INPLACE, LOCK=NONE','SELECT 1');
PREPARE notification_session_stmt FROM @notification_session_ddl;
EXECUTE notification_session_stmt;
DEALLOCATE PREPARE notification_session_stmt;
