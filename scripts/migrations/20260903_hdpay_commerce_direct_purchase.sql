-- LEGACY QUARANTINE SCHEMA ONLY. New HDPay/VietQR intents must always target
-- WALLET_TOPUP; application code must never create a COMMERCE_ORDER intent.
-- The historical target columns and constraints remain so already-created
-- commerce sessions can be detected, blocked from wallet settlement, and sent
-- to manual review without losing their audit trail.

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_vietqr_intent'
    AND column_name='settlement_target_type')=0,
  'ALTER TABLE nx_vietqr_intent ADD COLUMN settlement_target_type VARCHAR(24) NOT NULL DEFAULT ''WALLET_TOPUP'' AFTER user_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Keep the old COMMERCE_ORDER branch only for rows created before direct
-- commerce payment was retired. It is compatibility validation, not authority
-- for any current endpoint to create a commerce payment intent.
SET @requested_constraint_present=(SELECT COUNT(*)
  FROM information_schema.check_constraints
  WHERE constraint_schema=DATABASE()
    AND constraint_name='chk_vietqr_intent_requested');
SET @requested_constraint_target_aware=(SELECT COUNT(*)
  FROM information_schema.check_constraints
  WHERE constraint_schema=DATABASE()
    AND constraint_name='chk_vietqr_intent_requested'
    AND LOWER(check_clause) LIKE '%settlement_target_type%');
SET @sql=IF(@requested_constraint_present=0,
  'ALTER TABLE nx_vietqr_intent ADD CONSTRAINT chk_vietqr_intent_requested CHECK ((settlement_target_type=''WALLET_TOPUP'' AND requested_usdt >= 10) OR (settlement_target_type=''COMMERCE_ORDER'' AND requested_usdt > 0))',
  IF(@requested_constraint_target_aware=0,
    'ALTER TABLE nx_vietqr_intent DROP CHECK chk_vietqr_intent_requested, ADD CONSTRAINT chk_vietqr_intent_requested CHECK ((settlement_target_type=''WALLET_TOPUP'' AND requested_usdt >= 10) OR (settlement_target_type=''COMMERCE_ORDER'' AND requested_usdt > 0))',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_vietqr_intent'
    AND column_name='target_order_no')=0,
  'ALTER TABLE nx_vietqr_intent ADD COLUMN target_order_no VARCHAR(96) NULL AFTER settlement_target_type',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='nx_vietqr_intent'
    AND index_name='uk_vietqr_intent_target_order')=0,
  'ALTER TABLE nx_vietqr_intent ADD UNIQUE KEY uk_vietqr_intent_target_order (target_order_no)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.check_constraints
  WHERE constraint_schema=DATABASE() AND constraint_name='chk_vietqr_intent_settlement_target')=0,
  'ALTER TABLE nx_vietqr_intent ADD CONSTRAINT chk_vietqr_intent_settlement_target CHECK ((settlement_target_type=''WALLET_TOPUP'' AND target_order_no IS NULL) OR (settlement_target_type=''COMMERCE_ORDER'' AND target_order_no IS NOT NULL))',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
