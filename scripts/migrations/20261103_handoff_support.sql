-- One independent reception hold per customer. The conversation owner remains the assigned advisor.
CREATE TABLE IF NOT EXISTS nx_support_session_hold (
 customer_id BIGINT NOT NULL PRIMARY KEY,
 assignment_id BIGINT NOT NULL,
 holder_admin_id BIGINT NOT NULL,
 holder_name VARCHAR(100) NOT NULL,
 holder_role VARCHAR(16) NOT NULL,
 conversation_no VARCHAR(40) NOT NULL,
 started_at DATETIME(6) NOT NULL,
 last_sent_at DATETIME(6) NOT NULL,
 ended_at DATETIME(6) NULL,
 end_reason VARCHAR(16) NULL,
 version BIGINT NOT NULL,
 KEY ix_support_session_expiry (ended_at,last_sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_support_welcome_config (
 id TINYINT NOT NULL PRIMARY KEY,
 version BIGINT NOT NULL,
 zh VARCHAR(500) NOT NULL,
 en VARCHAR(500) NOT NULL,
 vi VARCHAR(500) NOT NULL,
 updated_at DATETIME(6) NULL,
 updated_by VARCHAR(100) NULL,
 reason VARCHAR(200) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO nx_support_welcome_config(id,version,zh,en,vi) VALUES
 (1,1,'你好，欢迎加入 UVEL！设备、收益或充值提现有任何问题，直接在这里给我们留言，我们会尽快回复你。',
 'Hi, welcome to UVEL! If you have any questions about your devices, earnings, deposits or withdrawals, just message us here and we''ll get back to you soon.',
 'Xin chào, chào mừng bạn đến với UVEL! Nếu bạn có câu hỏi về thiết bị, thu nhập, nạp hoặc rút tiền, hãy nhắn cho chúng tôi tại đây, chúng tôi sẽ phản hồi sớm nhất.');

-- Empty databases run the migration chain before ConversationTimeoutPolicyBootstrap.
-- Use its table and current default here; an existing operator policy is retained.
CREATE TABLE IF NOT EXISTS nx_conversation_timeout_policy (
 policy_key VARCHAR(64) PRIMARY KEY,
 warn_minutes INT NOT NULL,
 close_minutes INT NOT NULL,
 session_end_minutes INT NOT NULL DEFAULT 10,
 version BIGINT NOT NULL DEFAULT 1,
 updated_by VARCHAR(64) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO nx_conversation_timeout_policy(policy_key,warn_minutes,close_minutes,version,updated_by,reason)
VALUES ('GLOBAL',30,120,1,'system','系统默认会话闲置策略');

SET @sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_conversation_timeout_policy' AND column_name='session_end_minutes'),
 'ALTER TABLE nx_conversation_timeout_policy ADD COLUMN session_end_minutes INT NOT NULL DEFAULT 10', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_conversation_message' AND column_name='support_kind'),
 'ALTER TABLE nx_conversation_message ADD COLUMN support_kind VARCHAR(16) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_conversation_message' AND column_name='sender_role'),
 'ALTER TABLE nx_conversation_message ADD COLUMN sender_role VARCHAR(16) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='nx_conversation_message' AND column_name='origin'),
 'ALTER TABLE nx_conversation_message ADD COLUMN origin VARCHAR(16) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
