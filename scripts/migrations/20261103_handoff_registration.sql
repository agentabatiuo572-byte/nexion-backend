CREATE TABLE IF NOT EXISTS nx_support_invite_code (
 id BIGINT NOT NULL PRIMARY KEY AUTO_INCREMENT,
 agent_admin_id BIGINT NOT NULL,
 code CHAR(8) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
 version BIGINT NOT NULL, state VARCHAR(12) NOT NULL,
 active_owner BIGINT GENERATED ALWAYS AS (IF(state='ACTIVE',agent_admin_id,NULL)) STORED,
 issued_at DATETIME(6) NOT NULL, issued_by BIGINT NOT NULL, issued_reason VARCHAR(200) NOT NULL,
 revoked_at DATETIME(6), revoked_by BIGINT, revoked_reason VARCHAR(200), revoke_key VARCHAR(96), revoke_type VARCHAR(8),
 UNIQUE KEY uk_support_invite_code(code), UNIQUE KEY uk_support_invite_active(active_owner),
 UNIQUE KEY uk_support_invite_version(agent_admin_id,version), KEY ix_support_invite_revoke(revoked_by,revoke_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_user_registration_origin (
 user_id BIGINT NOT NULL PRIMARY KEY, attempt_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 surface VARCHAR(8) NOT NULL, installation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin,
 sandbox TINYINT NOT NULL, registered_at DATETIME(6) NOT NULL, freeze_gift TINYINT NOT NULL,
 sponsor_code VARCHAR(64), sponsor_display_name VARCHAR(128), source_environment VARCHAR(16) NOT NULL,
 gift_status VARCHAR(32) NOT NULL, gift_usdt DECIMAL(28,6), gift_nex DECIMAL(28,6),
 UNIQUE KEY uk_registration_attempt(attempt_id), KEY ix_registration_device(sandbox,installation_id,registered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @registration_ip_index = IF(EXISTS(SELECT 1 FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='nx_user' AND index_name='ix_h5_registration_ip'),'SELECT 1',
 'ALTER TABLE nx_user ADD INDEX ix_h5_registration_ip(client_ip,sandbox,created_at), ALGORITHM=INPLACE, LOCK=NONE');
PREPARE reg_stmt FROM @registration_ip_index; EXECUTE reg_stmt; DEALLOCATE PREPARE reg_stmt;

CREATE TABLE IF NOT EXISTS nx_registration_h5_otp (
 challenge_no VARCHAR(96) NOT NULL PRIMARY KEY, installation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 captcha_required TINYINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @registration_reward_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='nx_referral_reward_settlement' AND column_name='inviter_user_id' AND is_nullable='NO'),
 'ALTER TABLE nx_referral_reward_settlement MODIFY inviter_user_id BIGINT NULL','SELECT 1');
PREPARE reg_stmt FROM @registration_reward_ddl; EXECUTE reg_stmt; DEALLOCATE PREPARE reg_stmt;
SET @registration_reward_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='nx_h8_sandbox_referral_settlement' AND column_name='inviter_user_id' AND is_nullable='NO'),
 'ALTER TABLE nx_h8_sandbox_referral_settlement MODIFY inviter_user_id BIGINT NULL','SELECT 1');
PREPARE reg_stmt FROM @registration_reward_ddl; EXECUTE reg_stmt; DEALLOCATE PREPARE reg_stmt;


SET @registration_reward_ddl = IF(EXISTS(SELECT 1 FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name='nx_referral_reward_settlement') AND NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
 WHERE table_schema=DATABASE() AND table_name='nx_referral_reward_settlement' AND constraint_name='ck_h8_newcomer_only_inviter'),
 'ALTER TABLE nx_referral_reward_settlement ADD CONSTRAINT ck_h8_newcomer_only_inviter CHECK (inviter_user_id IS NOT NULL OR inviter_nex=0)','SELECT 1');
PREPARE reg_stmt FROM @registration_reward_ddl; EXECUTE reg_stmt; DEALLOCATE PREPARE reg_stmt;

SET @registration_reward_ddl = IF(EXISTS(SELECT 1 FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name='nx_h8_sandbox_referral_settlement') AND NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
 WHERE table_schema=DATABASE() AND table_name='nx_h8_sandbox_referral_settlement' AND constraint_name='ck_h8_sbx_newcomer_only_inviter'),
 'ALTER TABLE nx_h8_sandbox_referral_settlement ADD CONSTRAINT ck_h8_sbx_newcomer_only_inviter CHECK (inviter_user_id IS NOT NULL OR inviter_nex=0)','SELECT 1');
PREPARE reg_stmt FROM @registration_reward_ddl; EXECUTE reg_stmt; DEALLOCATE PREPARE reg_stmt;

INSERT IGNORE INTO nx_config_item(config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted) VALUES
 ('auth.risk.h5_register_captcha','1','NUMBER','admin_platform_param','ADMIN','Require CAPTCHA for H5 registration OTP',1,0),
 ('auth.risk.h5_register_device_limit','3','NUMBER','admin_platform_param','ADMIN','Maximum H5 registrations per browser in 24 hours; 0 disables this limit',1,0),
 ('auth.risk.h5_freeze_newcomer_gift','1','NUMBER','admin_platform_param','ADMIN','Release approved H5 newcomer gift after the first paid purchase',1,0);

CREATE TABLE IF NOT EXISTS nx_registration_gift_hold (
 settlement_no VARCHAR(64) NOT NULL PRIMARY KEY, user_id BIGINT NOT NULL,
 sandbox TINYINT NOT NULL, run_id VARCHAR(64), usdt DECIMAL(28,6) NOT NULL, nex DECIMAL(28,6) NOT NULL,
 state VARCHAR(16) NOT NULL, frozen_at DATETIME(6) NOT NULL, released_at DATETIME(6), paid_order_no VARCHAR(96),
 KEY ix_gift_hold_user(user_id,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
