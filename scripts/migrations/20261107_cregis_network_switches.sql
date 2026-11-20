-- Adds an independent pay-in authorization under the existing deployment and global ceilings.
CREATE TABLE IF NOT EXISTS nx_cregis_network_switch (
  network VARCHAR(8) PRIMARY KEY,
  deposit_enabled TINYINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT chk_cregis_network_switch CHECK (network IN ('BEP20','ERC20'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO nx_cregis_network_switch(network,deposit_enabled)
SELECT n.network, IF(g.assign_enabled=1 AND g.credit_enabled=1,1,0)
FROM (SELECT 'BEP20' network UNION ALL SELECT 'ERC20') n
JOIN nx_cregis_provision_gate g ON g.id=1;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_cregis_switch_case' AND COLUMN_NAME='network')=0,
  'ALTER TABLE nx_cregis_switch_case ADD COLUMN network VARCHAR(8) NULL, ADD COLUMN expected_network_version BIGINT NULL, ADD COLUMN before_deposit_enabled TINYINT NULL', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
