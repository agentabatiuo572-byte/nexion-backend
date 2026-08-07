-- Retire every active KYC decision path without deleting historical audit evidence.
CREATE TABLE IF NOT EXISTS nx_user_payout_address (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  network VARCHAR(32) NOT NULL,
  address VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  effective_at DATETIME NOT NULL,
  next_change_allowed_at DATETIME NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_user_payout_address_network (user_id, network),
  KEY idx_user_payout_address_effective (user_id, effective_at, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_user_payout_address_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  network VARCHAR(32) NOT NULL,
  previous_address VARCHAR(255) NULL,
  new_address VARCHAR(255) NOT NULL,
  change_type VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_payout_address_history (user_id, network, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One-time compatibility import. It does not keep an identity dependency: the new row is
-- user-scoped, network-scoped and becomes the sole payout-address authority.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_kyc_profile'
                  AND COLUMN_NAME IN ('user_id','paired_address','network','paired_at','updated_at','is_deleted'))=6,
  'INSERT INTO nx_user_payout_address (user_id,network,address,status,effective_at,next_change_allowed_at,created_at,updated_at,is_deleted)
   SELECT user_id,
          CASE UPPER(network)
            WHEN ''TRC20'' THEN ''USDT-TRC20''
            WHEN ''USDT-TRC20'' THEN ''USDT-TRC20''
            WHEN ''ERC20'' THEN ''USDT-ERC20''
            WHEN ''USDT-ERC20'' THEN ''USDT-ERC20''
          END,
          TRIM(paired_address),''ACTIVE'',COALESCE(paired_at,updated_at,NOW()),
          DATE_ADD(COALESCE(paired_at,updated_at,NOW()),INTERVAL 7 DAY),NOW(),NOW(),0
     FROM nx_kyc_profile
    WHERE is_deleted=0 AND paired_address IS NOT NULL AND TRIM(paired_address)<>''''
      AND UPPER(network) IN (''TRC20'',''USDT-TRC20'',''ERC20'',''USDT-ERC20'')
   ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

DELETE FROM nx_admin_role_permission
 WHERE permission_id IN (SELECT id FROM nx_admin_permission
                           WHERE permission_code LIKE 'user_c4_%' OR permission_code LIKE 'risk_k5_%');
DELETE FROM nx_admin_role_menu
 WHERE menu_id IN (SELECT id FROM nx_admin_menu WHERE menu_code IN ('C4','K5'));
UPDATE nx_admin_menu SET status=0,is_deleted=1,updated_at=NOW() WHERE menu_code IN ('C4','K5');
UPDATE nx_admin_permission SET status=0,is_deleted=1,updated_at=NOW()
 WHERE permission_code LIKE 'user_c4_%' OR permission_code LIKE 'risk_k5_%';

UPDATE nx_config_item SET status=0,is_deleted=1,updated_at=NOW()
 WHERE config_key='wallet.exchange.kyc_threshold_usdt'
    OR config_key='genesis.sale.eligibility.kycRequired'
    OR config_key LIKE 'risk.kyc.%';

-- Stop generating identity rows and remove the denormalized user gate after the
-- one-time payout-address import has completed.
DROP TRIGGER IF EXISTS trg_nx_user_kyc_profile;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user' AND COLUMN_NAME='kyc_status')=1,
  'ALTER TABLE nx_user DROP COLUMN kyc_status','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Release orders that were held only by the retired identity workflow. Ordinary
-- risk, balance and regional controls continue to apply in their own services.
UPDATE nx_exchange_order SET status='QUEUED',updated_at=NOW()
 WHERE status='KYC_REQUIRED' AND is_deleted=0;
UPDATE nx_withdrawal_order SET status='REVIEW_PENDING',failure_reason=NULL,updated_at=NOW()
 WHERE status='FROZEN' AND failure_reason LIKE 'K5_REVIEW:%' AND is_deleted=0;

-- Retain historical evidence but make every former workflow row inert.
SET @sql = IF((SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_risk_kyc_review_ticket')=1,
  'UPDATE nx_admin_risk_kyc_review_ticket SET is_deleted=1,updated_at=NOW() WHERE is_deleted=0',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_risk_kyc_alert')=1,
  'UPDATE nx_admin_risk_kyc_alert SET is_deleted=1 WHERE is_deleted=0','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_risk_param')=1,
  'UPDATE nx_admin_risk_param SET is_deleted=1,updated_at=NOW() WHERE section_key=''k5'' AND is_deleted=0','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_admin_risk_score_dimension')=1,
  'UPDATE nx_admin_risk_score_dimension SET is_deleted=1,updated_at=NOW() WHERE dim_key=''kycStatus'' AND is_deleted=0','SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_event_schema_registry SET status='RETIRED',updated_at=NOW()
 WHERE (event_name LIKE '%kyc%' OR event_name LIKE '%KYC%') AND is_deleted=0;
UPDATE nx_support_sla_rule SET status=0,is_deleted=1,updated_at=NOW()
 WHERE category='kyc' AND is_deleted=0;
UPDATE nx_support_ticket SET category='other',updated_at=NOW()
 WHERE category='kyc' AND is_deleted=0;
UPDATE nx_admin_fourth_batch_report SET status='ARCHIVED',updated_at=NOW()
 WHERE report_type='KYC_REGULATORY' AND is_deleted=0;
