-- Source recovery evidence, MySQL 8.0.29+. No historical entry is granted evidence.
-- Counters observe ALL available-balance reductions, including reservations and recoveries.
-- Credits/thaws never lower counters. This proves a conservative lower bound only.
SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_wallet' AND COLUMN_NAME='earnings_usdt_debited')=0,'ALTER TABLE nx_user_wallet ADD COLUMN earnings_usdt_debited DECIMAL(30,6) NOT NULL DEFAULT 0','SELECT 1');
PREPARE earnings_recovery_stmt FROM @sql;
EXECUTE earnings_recovery_stmt;
DEALLOCATE PREPARE earnings_recovery_stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_wallet' AND COLUMN_NAME='earnings_nex_debited')=0,'ALTER TABLE nx_user_wallet ADD COLUMN earnings_nex_debited DECIMAL(30,6) NOT NULL DEFAULT 0','SELECT 1');
PREPARE earnings_recovery_stmt FROM @sql;
EXECUTE earnings_recovery_stmt;
DEALLOCATE PREPARE earnings_recovery_stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_earnings_release_entry' AND COLUMN_NAME='source_debit_baseline')=0,'ALTER TABLE nx_earnings_release_entry ADD COLUMN source_debit_baseline DECIMAL(30,6) NULL','SELECT 1');
PREPARE earnings_recovery_stmt FROM @sql;
EXECUTE earnings_recovery_stmt;
DEALLOCATE PREPARE earnings_recovery_stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_earnings_release_entry' AND COLUMN_NAME='source_wallet_id')=0,'ALTER TABLE nx_earnings_release_entry ADD COLUMN source_wallet_id BIGINT NULL','SELECT 1');
PREPARE earnings_recovery_stmt FROM @sql;
EXECUTE earnings_recovery_stmt;
DEALLOCATE PREPARE earnings_recovery_stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_earnings_release_entry' AND COLUMN_NAME='recovered_amount')=0,'ALTER TABLE nx_earnings_release_entry ADD COLUMN recovered_amount DECIMAL(24,6) NOT NULL DEFAULT 0','SELECT 1');
PREPARE earnings_recovery_stmt FROM @sql;
EXECUTE earnings_recovery_stmt;
DEALLOCATE PREPARE earnings_recovery_stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='nx_earnings_release_entry' AND CONSTRAINT_NAME='chk_earnings_source_recovery')=0,
'ALTER TABLE nx_earnings_release_entry ADD CONSTRAINT chk_earnings_source_recovery CHECK (recovered_amount>=0 AND recovered_amount<=amount AND (source_debit_baseline IS NULL OR source_debit_baseline>=0) AND ((source_debit_baseline IS NULL AND source_wallet_id IS NULL) OR (source_debit_baseline IS NOT NULL AND source_wallet_id IS NOT NULL)))','SELECT 1');
PREPARE earnings_recovery_stmt FROM @sql;
EXECUTE earnings_recovery_stmt;
DEALLOCATE PREPARE earnings_recovery_stmt;

-- IF NOT EXISTS never replaces an existing trigger; verify its exact definition below.
CREATE TRIGGER IF NOT EXISTS nx_wallet_earnings_debit_counter_v1
BEFORE UPDATE ON nx_user_wallet FOR EACH ROW
SET NEW.earnings_usdt_debited=OLD.earnings_usdt_debited+GREATEST(OLD.usdt_available-NEW.usdt_available,0),NEW.earnings_nex_debited=OLD.earnings_nex_debited+GREATEST(OLD.nex_available-NEW.nex_available,0);

SET @earnings_expected_trigger='SET NEW.earnings_usdt_debited=OLD.earnings_usdt_debited+GREATEST(OLD.usdt_available-NEW.usdt_available,0),NEW.earnings_nex_debited=OLD.earnings_nex_debited+GREATEST(OLD.nex_available-NEW.nex_available,0)';
SET @sql=IF((SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
 AND TRIGGER_NAME='nx_wallet_earnings_debit_counter_v1' AND EVENT_OBJECT_TABLE='nx_user_wallet'
 AND ACTION_TIMING='BEFORE' AND EVENT_MANIPULATION='UPDATE'
 AND LOWER(REGEXP_REPLACE(ACTION_STATEMENT,'[[:space:]]',''))=LOWER(REGEXP_REPLACE(@earnings_expected_trigger,'[[:space:]]','')))=1,
 'SELECT 1','CALL earnings_source_recovery_trigger_shape_invalid()');
PREPARE earnings_recovery_stmt FROM @sql;
EXECUTE earnings_recovery_stmt;
DEALLOCATE PREPARE earnings_recovery_stmt;

CREATE TABLE IF NOT EXISTS nx_earnings_source_recovery (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 recovery_no VARCHAR(64) NOT NULL,
 entry_no VARCHAR(64) NOT NULL,
 user_id BIGINT NOT NULL,
 source_type VARCHAR(64) NOT NULL,
 source_ref VARCHAR(128) NOT NULL,
 asset VARCHAR(16) NOT NULL,
 source_environment VARCHAR(16) NOT NULL,
 requested DECIMAL(24,6) NOT NULL,
 recovered DECIMAL(24,6) NOT NULL,
 outstanding DECIMAL(24,6) NOT NULL,
 cumulative_recovered DECIMAL(24,6) NOT NULL,
 remaining_entitlement DECIMAL(24,6) NOT NULL,
 bucket VARCHAR(24) NOT NULL,
 balance_before DECIMAL(24,6) NOT NULL,
 balance_after DECIMAL(24,6) NOT NULL,
 debit_counter_before DECIMAL(30,6) NOT NULL,
 source_debit_baseline DECIMAL(30,6) NULL,
 evidence_status VARCHAR(32) NOT NULL,
 reason VARCHAR(2000) NOT NULL,
 actor VARCHAR(128) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_earnings_source_recovery_no(recovery_no),
 KEY idx_earnings_source_recovery_entry(entry_no,id),
 CONSTRAINT fk_earnings_source_recovery_entry FOREIGN KEY(entry_no) REFERENCES nx_earnings_release_entry(entry_no),
 CONSTRAINT chk_earnings_recovery_amounts CHECK(requested>0 AND recovered>=0 AND outstanding>=0 AND recovered+outstanding=requested AND cumulative_recovered>=recovered AND remaining_entitlement>=0 AND balance_before>=recovered AND balance_after=balance_before-recovered),
 CONSTRAINT chk_earnings_recovery_asset CHECK(asset IN ('USDT','NEX')),
 CONSTRAINT chk_earnings_recovery_environment CHECK(source_environment IN ('PRODUCTION','SANDBOX')),
 CONSTRAINT chk_earnings_recovery_bucket CHECK(bucket IN ('withdrawable','pending_review','bonus_locked')),
 CONSTRAINT chk_earnings_recovery_evidence CHECK((evidence_status='LEGACY_UNPROVEN' AND source_debit_baseline IS NULL AND recovered=0) OR (evidence_status='TRACKED_CONSERVATIVE' AND source_debit_baseline IS NOT NULL AND debit_counter_before>=source_debit_baseline))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
