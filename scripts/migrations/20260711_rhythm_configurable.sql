-- rhythm-configurable: persistent support receipts and H1 -> G4 active emission gate backfill.
CREATE TABLE IF NOT EXISTS nx_conversation_message_receipt (
  message_id BIGINT PRIMARY KEY,
  conversation_no VARCHAR(40) NOT NULL,
  receipt_status VARCHAR(16) NOT NULL DEFAULT 'sent',
  read_by VARCHAR(64) NULL,
  read_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_conversation_receipt_no (conversation_no, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_config_item (
  config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted
)
SELECT
  'growth.phase.genesis_emissions_open',
  COALESCE(
    (SELECT monthly.config_value
       FROM nx_config_item monthly
      WHERE monthly.config_key = CONCAT(
        'growth.phase.month.',
        COALESCE((SELECT current_month.config_value FROM nx_config_item current_month WHERE current_month.config_key = 'H1.rhythm.currentMonth' AND current_month.is_deleted = 0 LIMIT 1), '1'),
        '.genesisEmissionsOpen')
        AND monthly.is_deleted = 0
      LIMIT 1),
    IF(CAST(COALESCE((SELECT current_month.config_value FROM nx_config_item current_month WHERE current_month.config_key = 'H1.rhythm.currentMonth' AND current_month.is_deleted = 0 LIMIT 1), '1') AS UNSIGNED) >= 7, '1', '0')
  ),
  'BOOLEAN', 'growth', 'ADMIN', 'H1 active Genesis emission gate migration', 1, 0
ON DUPLICATE KEY UPDATE
  config_value = VALUES(config_value),
  value_type = 'BOOLEAN',
  config_group = 'growth',
  visibility = 'ADMIN',
  remark = 'H1 active Genesis emission gate migration',
  status = 1,
  is_deleted = 0;
