-- FEAT-TRIAL05 试用提前转购：完整试用权益照拿 + 固定早购立减。
-- 1) earlyPurchaseDiscountUSD：唯一新增运营参数，0 = 关闭整套新报价；首发值 10（产品 2026-10-28 确认）。
--    重跑不覆盖运营已改的 current_value。
-- 2) settled_trial_nex：转购实际发放的 UVT 总额。shadow_accrued_nex 仍是真实累计，不被覆盖；
--    历史 NULL 由读取方回落到 shadow_accrued_nex。重领复用行时清空。
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

CALL add_h2_trial_column('settled_trial_nex','DECIMAL(18,6) NULL AFTER remainder_usdt');
DROP PROCEDURE add_h2_trial_column;

INSERT INTO nx_growth_trial_policy
  (policy_key,policy_name,description,current_value,value_type,hot,section,server_only,sort_order,is_deleted)
VALUES
  ('earlyPurchaseDiscountUSD','试用中提前购买立减','试用原期内转购：补齐完整试用权益并再减此金额（封顶至应付，0=关闭）','10','NUMBER',1,'pricing',0,126,0)
ON DUPLICATE KEY UPDATE policy_name=VALUES(policy_name),description=VALUES(description),
  value_type='NUMBER',section='pricing',server_only=0,is_deleted=0;
