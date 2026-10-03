-- Admission budget is shared by App create, both conversion paths and operations create.
-- Preserve every operator-managed value, including disabled or soft-deleted configuration rows.
INSERT IGNORE INTO nx_config_item
 (config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
VALUES
 ('support.ticket.creation.cooldown_seconds','60','NUMBER','support_ticket_creation','ADMIN','Minimum seconds between new tickets for one account (1..86400)',1,0),
 ('support.ticket.creation.max_per_24h','10','NUMBER','support_ticket_creation','ADMIN','Maximum tickets created per rolling 24 hours, including closed and archived tickets (1..1000)',1,0),
 ('support.ticket.creation.max_active','3','NUMBER','support_ticket_creation','ADMIN','Block new creation when this many OPEN, IN_PROGRESS or PENDING_USER tickets already exist (1..1000)',1,0);

SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_support_ticket' AND INDEX_NAME='idx_support_ticket_creation_window')=0,
 'ALTER TABLE nx_support_ticket ADD INDEX idx_support_ticket_creation_window(user_id,created_at,id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_support_ticket' AND INDEX_NAME='idx_support_ticket_creation_active')=0,
 'ALTER TABLE nx_support_ticket ADD INDEX idx_support_ticket_creation_active(user_id,is_deleted,status,created_at,id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
