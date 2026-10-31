-- Payment credentials and command effects are independent of the expiring admin receipt cache.
CREATE TABLE IF NOT EXISTS nx_payment_password (
 user_id BIGINT PRIMARY KEY, password_hash VARCHAR(100) NULL, pepper_version VARCHAR(32) NULL,
 credential_version BIGINT NOT NULL DEFAULT 0, changed_at BIGINT NULL,
 failure_reset_at BIGINT NULL, locked_until BIGINT NULL, recovery_phone_hash CHAR(64) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_payment_password_attempt (
 user_id BIGINT NOT NULL, attempt_no VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 binding_hash CHAR(64) NOT NULL, result_code INT NOT NULL, result_message VARCHAR(100) NOT NULL,
 state_json TEXT NOT NULL, occurred_at BIGINT NOT NULL, purpose VARCHAR(16) NOT NULL DEFAULT 'PAYMENT', channel VARCHAR(8) NOT NULL DEFAULT 'APP', counts_failure TINYINT NOT NULL DEFAULT 1,
 PRIMARY KEY(user_id,attempt_no), KEY idx_pin_failure(user_id,result_message,occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_payment_command (
 user_id BIGINT NOT NULL, kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 command_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, request_hash CHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'READY', response_json MEDIUMTEXT NULL,
 created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 PRIMARY KEY(user_id,kind,command_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_verified_phone (
 user_id BIGINT PRIMARY KEY, phone_hash CHAR(64) NOT NULL, verified_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_payment_password_reset (
 challenge_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 user_id BIGINT NOT NULL, session_hash CHAR(64) NOT NULL, phone_hash CHAR(64) NOT NULL,
 credential_version BIGINT NOT NULL, code_hash VARCHAR(100) NOT NULL, attempt_count INT NOT NULL DEFAULT 0,
 expires_at BIGINT NOT NULL, created_at BIGINT NOT NULL, verified_at BIGINT NULL,
 proof_hash CHAR(64) NULL, proof_expires_at BIGINT NULL, consumed_at BIGINT NULL,
 UNIQUE KEY uk_pin_reset_proof(proof_hash), KEY idx_pin_reset_user(user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_payment_password_policy (
 id INT PRIMARY KEY, version BIGINT NOT NULL DEFAULT 0, max_attempts INT NOT NULL DEFAULT 5,
 window_minutes INT NOT NULL DEFAULT 15, lock_minutes INT NOT NULL DEFAULT 15,
 updated_at BIGINT NULL, updated_by VARCHAR(100) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO nx_payment_password_policy(id) VALUES(1);
CREATE TABLE IF NOT EXISTS nx_payment_security_record (
 record_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, user_id BIGINT NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, type VARCHAR(40) NOT NULL DEFAULT 'PAYMENT_PASSWORD_LOCK',
 state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', reason VARCHAR(200) NOT NULL,
 started_at BIGINT NOT NULL, locked_until BIGINT NULL, failed_attempts INT NOT NULL,
 window_minutes INT NOT NULL, KEY idx_pin_security_user(user_id,started_at), KEY idx_pin_security_time(started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_payment_security_audit (
 audit_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 record_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, user_id BIGINT NOT NULL,
 occurred_at BIGINT NOT NULL, operator VARCHAR(100) NOT NULL, action VARCHAR(50) NOT NULL,
 reason VARCHAR(200) NOT NULL, KEY idx_pin_audit_record(record_no,occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS nx_payment_security_admin_command (
 actor_id BIGINT NOT NULL, surface VARCHAR(16) NOT NULL, command_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id BIGINT NOT NULL, record_no VARCHAR(64) NULL, request_hash CHAR(64) NOT NULL,
 response_json MEDIUMTEXT NULL, created_at BIGINT NOT NULL,
 PRIMARY KEY(actor_id,surface,command_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
