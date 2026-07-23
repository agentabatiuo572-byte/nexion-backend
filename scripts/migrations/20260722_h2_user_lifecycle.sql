-- H2 user lifecycle: terminal state, settlement snapshot and optimistic concurrency.
DROP PROCEDURE IF EXISTS add_h2_trial_column;
DELIMITER $$
CREATE PROCEDURE add_h2_trial_column(
  IN p_name VARCHAR(64), IN p_definition VARCHAR(512)
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_trial_claim' AND COLUMN_NAME=p_name
  ) THEN
    SET @h2_alter_sql=CONCAT('ALTER TABLE nx_trial_claim ADD COLUMN ',p_name,' ',p_definition);
    PREPARE h2_alter_stmt FROM @h2_alter_sql;
    EXECUTE h2_alter_stmt;
    DEALLOCATE PREPARE h2_alter_stmt;
  END IF;
END$$
DELIMITER ;

CALL add_h2_trial_column('shadow_accrued_usdt','DECIMAL(18,6) NOT NULL DEFAULT 0');
CALL add_h2_trial_column('shadow_accrued_nex','DECIMAL(18,6) NOT NULL DEFAULT 0');
CALL add_h2_trial_column('remainder_usdt','DECIMAL(18,6) NOT NULL DEFAULT 0');
CALL add_h2_trial_column('discount_usdt','DECIMAL(18,6) NOT NULL DEFAULT 0');
CALL add_h2_trial_column('settlement_amount_usdt','DECIMAL(18,6) NOT NULL DEFAULT 0');
CALL add_h2_trial_column('cancel_reason','VARCHAR(32) NULL');
CALL add_h2_trial_column('extended_at','DATETIME NULL');
CALL add_h2_trial_column('settled_at','DATETIME NULL');
CALL add_h2_trial_column('closed_at','DATETIME NULL');
CALL add_h2_trial_column('cooldown_until','DATETIME NULL');
CALL add_h2_trial_column('settlement_snapshot','VARCHAR(1000) NULL');
CALL add_h2_trial_column('version','BIGINT NOT NULL DEFAULT 0');
DROP PROCEDURE add_h2_trial_column;

INSERT INTO nx_growth_trial_policy
  (policy_key,policy_name,description,current_value,value_type,hot,section,server_only,sort_order,is_deleted)
VALUES
  ('trialOffsetCapUSD','试用收益抵扣上限','Model A 购前抵扣封顶，超出部分购后入余额','50','NUMBER',1,'pricing',0,125,0)
ON DUPLICATE KEY UPDATE policy_name=VALUES(policy_name),description=VALUES(description),
  value_type='NUMBER',section='pricing',server_only=0,is_deleted=0;
