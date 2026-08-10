-- H9/D5/G4/K1/F5/K6 additive production contracts. MySQL 8, rerunnable.
SET NAMES utf8mb4;

-- H9 server-canonical whole aggregate. No client fallback is permitted.
INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
VALUES
  ('growth.public_stats.values',
   '{"fleetDevices":28432,"onlineRatePct":100,"onlineJitter":24,"registeredUsersBase":1420000,"registeredUsersMonthlyGrowthPct":2.9,"registeredUsersAnchorAt":1786097730000,"effectiveAt":1786097730000,"virtualUserCount":12000,"hashratePercentileTable":[{"tops":5,"cumPct":20},{"tops":20,"cumPct":55},{"tops":60,"cumPct":82},{"tops":150,"cumPct":96},{"tops":700,"cumPct":97.6},{"tops":2700,"cumPct":98.7},{"tops":5400,"cumPct":99.3},{"tops":11000,"cumPct":99.6},{"tops":27000,"cumPct":99.8},{"tops":53000,"cumPct":100}]}',
   'JSON','growth','ADMIN','H9 public stats whole aggregate',1,0),
  ('growth.public_stats.version','1','NUMBER','growth','ADMIN','H9 public stats aggregate version',1,0),
  ('dailyUsdtPerBaseline','0.06','NUMBER','device','PUBLIC',
   'H9 published daily USD per baseline device; shared with E6 yield estimate',1,0)
ON DUPLICATE KEY UPDATE value_type=VALUES(value_type),config_group='growth',visibility='ADMIN',
  remark=VALUES(remark),status=1,is_deleted=0,updated_at=NOW();

UPDATE nx_config_item
   SET config_value=JSON_SET(config_value,'$.effectiveAt',
       COALESCE(JSON_EXTRACT(config_value,'$.effectiveAt'),JSON_EXTRACT(config_value,'$.registeredUsersAnchorAt'))),
       updated_at=NOW()
 WHERE config_key='growth.public_stats.values' AND JSON_VALID(config_value)
   AND JSON_EXTRACT(config_value,'$.effectiveAt') IS NULL;

INSERT INTO nx_admin_menu
  (menu_code,menu_name,menu_name_zh,menu_name_en,parent_id,route_path,icon,sort_order,remark,status,is_deleted)
SELECT 'H9','对外公布数据','对外公布数据','Public stats',p.id,
       '/growth/public-stats','DataLine',9,'H9 server-canonical public statistics',1,0
  FROM nx_admin_menu p WHERE p.menu_code='H' AND p.is_deleted=0
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name),menu_name_zh=VALUES(menu_name_zh),
  menu_name_en=VALUES(menu_name_en),parent_id=VALUES(parent_id),route_path=VALUES(route_path),
  sort_order=VALUES(sort_order),remark=VALUES(remark),status=1,is_deleted=0;

INSERT INTO nx_admin_permission
  (permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,menu_id,remark,status,is_deleted)
SELECT p.permission_code,p.permission_name,'API','/growth/public-stats',p.perm_type,p.amplifies,m.id,p.remark,1,0
  FROM nx_admin_menu m
  JOIN (
    SELECT 'growth_h9_read' permission_code,'H9 对外公布数据读取' permission_name,'READ' perm_type,0 amplifies,'读取服务端权威公布数据' remark UNION ALL
    SELECT 'growth_h9_write','H9 对外公布数据修改','WRITE',1,'整组CAS修改并写入强制审计'
  ) p
 WHERE m.menu_code='H9' AND m.is_deleted=0
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),resource_type='API',
  resource_path=VALUES(resource_path),perm_type=VALUES(perm_type),amplifies=VALUES(amplifies),
  menu_id=VALUES(menu_id),remark=VALUES(remark),status=1,is_deleted=0;

INSERT INTO nx_admin_role_permission(role_id,permission_id,is_deleted)
SELECT DISTINCT rp.role_id,h9.id,0
  FROM nx_admin_role_permission rp
  JOIN nx_admin_permission h8 ON h8.id=rp.permission_id AND h8.permission_code='growth_h8_read'
  JOIN nx_admin_permission h9 ON h9.permission_code='growth_h9_read'
 WHERE rp.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0;

-- D5 fixed network fees and immutable accepted-policy evidence for new orders.
-- D7 deliberately remains a read-only HOLD surface.  It must stay visible so
-- operators see the real-channel-not-integrated boundary instead of relying on
-- the retired browser-local fake save path.
INSERT INTO nx_admin_menu
  (menu_code,menu_name,menu_name_zh,menu_name_en,parent_id,route_path,icon,sort_order,remark,status,is_deleted)
SELECT 'D7','法币提现参数','法币提现参数','Fiat payout parameters',p.id,
       '/finance/payout-vnd','BankCard',7,'D7 HOLD: real payout channel is not integrated',1,0
  FROM nx_admin_menu p WHERE p.menu_code='D' AND p.is_deleted=0
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name),menu_name_zh=VALUES(menu_name_zh),
  menu_name_en=VALUES(menu_name_en),parent_id=VALUES(parent_id),route_path=VALUES(route_path),
  sort_order=VALUES(sort_order),remark=VALUES(remark),status=1,is_deleted=0;

INSERT INTO nx_admin_role_menu(role_id,menu_id,is_deleted)
SELECT DISTINCT rm.role_id,d7.id,0
  FROM nx_admin_role_menu rm
  JOIN nx_admin_menu d6 ON d6.id=rm.menu_id AND d6.menu_code='D6'
  JOIN nx_admin_menu d7 ON d7.menu_code='D7'
 WHERE rm.is_deleted=0 AND d6.is_deleted=0 AND d7.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0;

INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
VALUES
  ('withdrawal.network_confirm_fee_usd.trc20','1','NUMBER','wallet','ADMIN','D5 TRC20 fixed network confirmation fee USD',1,0),
  ('withdrawal.network_confirm_fee_usd.bep20','1','NUMBER','wallet','ADMIN','D5 BEP20 fixed network confirmation fee USD',1,0),
  ('withdrawal.network_confirm_fee_usd.erc20','5','NUMBER','wallet','ADMIN','D5 ERC20 fixed network confirmation fee USD',1,0),
  ('withdrawal.small_amount_threshold_usd','50','NUMBER','wallet','ADMIN','D5 small withdrawal cold-start bypass threshold USD',1,0),
  ('withdrawal.payout_sla_hours','24','NUMBER','wallet','ADMIN','D5 normal payout SLA hours',1,0),
  ('wallet.withdrawal.small_amount_threshold_usd','50','NUMBER','wallet','ADMIN','D5 small withdrawal threshold mirror',1,0),
  ('wallet.withdrawal.payout_sla_hours','24','NUMBER','wallet','ADMIN','D5 payout SLA mirror',1,0),
  ('withdrawal.bep20.enabled','true','BOOLEAN','wallet','ADMIN','D5 BEP20 withdrawal switch',1,0)
ON DUPLICATE KEY UPDATE value_type=VALUES(value_type),config_group='wallet',visibility='ADMIN',
  remark=VALUES(remark),status=1,is_deleted=0,updated_at=NOW();

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_withdrawal_order' AND column_name='d5_policy_version')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d5_policy_version VARCHAR(64) NULL AFTER d2_net_receive',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- G4 catalog versions, soft-delete tiers and real one-time invite codes.
CREATE TABLE IF NOT EXISTS nx_genesis_catalog_state (
  id BIGINT PRIMARY KEY,
  tiers_version BIGINT NOT NULL DEFAULT 1,
  market_open_state VARCHAR(16) NOT NULL DEFAULT 'closed',
  market_open_state_version BIGINT NOT NULL DEFAULT 1,
  closed_notice_key VARCHAR(64) NOT NULL DEFAULT 'default',
  last_change VARCHAR(512) NOT NULL DEFAULT 'seeded fail-closed',
  next_tier_seq BIGINT NOT NULL DEFAULT 2,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT chk_genesis_market_open_state CHECK (market_open_state IN ('open','closed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO nx_genesis_catalog_state(id,tiers_version,market_open_state,market_open_state_version,closed_notice_key,last_change,next_tier_seq)
VALUES(1,1,'closed',1,'default','seeded fail-closed',2)
ON DUPLICATE KEY UPDATE id=VALUES(id);

CREATE TABLE IF NOT EXISTS nx_genesis_tier (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tier_id VARCHAR(32) NOT NULL,
  range_from INT NOT NULL,
  range_to INT NOT NULL,
  price_usdt DECIMAL(18,6) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_genesis_tier_id(tier_id),
  KEY idx_genesis_tier_active_range(status,is_deleted,range_from),
  CONSTRAINT chk_genesis_tier_range CHECK (range_from>=0 AND range_to>range_from),
  CONSTRAINT chk_genesis_tier_price CHECK (price_usdt>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO nx_genesis_tier(tier_id,range_from,range_to,price_usdt,status,is_deleted)
SELECT 't1',0,GREATEST(COALESCE(MAX(total_supply),10000),10000),
       GREATEST(ROUND(COALESCE(MAX(price_usdt),1000),0),1),'ACTIVE',0
  FROM nx_genesis_series
 WHERE is_deleted=0
ON DUPLICATE KEY UPDATE tier_id=VALUES(tier_id);

CREATE TABLE IF NOT EXISTS nx_genesis_invite_code (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'unused',
  issued_by VARCHAR(64) NOT NULL,
  issued_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  note VARCHAR(60) NOT NULL DEFAULT '',
  redeemed_by BIGINT NULL,
  redeemed_at DATETIME NULL,
  voided_by VARCHAR(64) NULL,
  voided_at DATETIME NULL,
  void_reason VARCHAR(200) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_genesis_invite_code(code),
  UNIQUE KEY uk_genesis_invite_redeemed_account(redeemed_by),
  KEY idx_genesis_invite_status(status,is_deleted,issued_at),
  CONSTRAINT chk_genesis_invite_status CHECK (status IN ('unused','used','void'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- K1 release parameters and immutable three-bucket earnings entries.
INSERT INTO nx_config_item(config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
VALUES
 ('risk.k1.release.version','1','NUMBER','risk','ADMIN','K1 release aggregate version',1,0),
 ('risk.k1.release.freePhoneSlotsPerCluster','1','NUMBER','risk','ADMIN','K1 free phone slots per cluster',1,0),
 ('risk.k1.release.duplicateAccountPendingFrom','2','NUMBER','risk','ADMIN','K1 pending threshold',1,0),
 ('risk.k1.release.duplicateAccountFreezeFrom','4','NUMBER','risk','ADMIN','K1 freeze threshold',1,0),
 ('risk.k1.release.pendingReleaseHours','72','NUMBER','risk','ADMIN','K1 cluster release statistics window only',1,0),
 ('risk.k1.release.appAttestationReleaseHours','2','NUMBER','risk','ADMIN','K1 trusted app attestation window',1,0),
 ('risk.k1.release.releaseMode','manual_only','STRING','risk','ADMIN','K1 release mode; trusted attestation stays fail-closed until the signed carrier is enabled',1,0),
 ('risk.k1.release.freeSlotRequiresBinding','true','BOOLEAN','risk','ADMIN','K1 free slot binding requirement',1,0)
ON DUPLICATE KEY UPDATE value_type=VALUES(value_type),config_group='risk',visibility='ADMIN',remark=VALUES(remark),status=1,is_deleted=0,updated_at=NOW();

-- OPEN-ENG-001 is not signed: never leave an older workspace value advertising an unreachable
-- automatic proof path. A future signed carrier rollout may explicitly enable and update it.
UPDATE nx_config_item
   SET config_value='manual_only',updated_at=NOW()
 WHERE config_key='risk.k1.release.releaseMode'
   AND config_value='attest_or_manual';

CREATE TABLE IF NOT EXISTS nx_earnings_release_entry (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 entry_no VARCHAR(64) NOT NULL,
 user_id BIGINT NOT NULL,
 cluster_id VARCHAR(64) NULL,
 source_type VARCHAR(64) NOT NULL,
 source_ref VARCHAR(128) NOT NULL,
 asset VARCHAR(16) NOT NULL,
 amount DECIMAL(24,6) NOT NULL,
 bucket VARCHAR(24) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 release_source VARCHAR(64) NULL,
 released_at DATETIME NULL,
 idempotency_key VARCHAR(128) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 is_deleted TINYINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_earnings_release_entry_no(entry_no),
 UNIQUE KEY uk_earnings_release_source(source_type,source_ref,user_id),
 UNIQUE KEY uk_earnings_release_idem(idempotency_key),
 KEY idx_earnings_release_user_bucket(user_id,bucket,status,is_deleted),
 CONSTRAINT chk_earnings_release_bucket CHECK (bucket IN ('withdrawable','pending_review','bonus_locked')),
 CONSTRAINT chk_earnings_release_amount CHECK (amount>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_earnings_release_attestation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 device_id VARCHAR(128) NOT NULL,
 first_seen_at DATETIME NOT NULL,
 last_seen_at DATETIME NOT NULL,
 online_seconds BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_earnings_release_attestation(user_id,device_id),
 KEY idx_earnings_release_attestation_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_withdrawal_order' AND column_name='d5_use_nex_fee_offset')=0,
  'ALTER TABLE nx_withdrawal_order ADD COLUMN d5_use_nex_fee_offset TINYINT(1) NULL AFTER d5_policy_version',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO nx_admin_role_permission(role_id,permission_id,is_deleted)
SELECT DISTINCT rp.role_id,h9.id,0
  FROM nx_admin_role_permission rp
  JOIN nx_admin_permission h8 ON h8.id=rp.permission_id AND h8.permission_code='growth_h8_write'
  JOIN nx_admin_permission h9 ON h9.permission_code='growth_h9_write'
 WHERE rp.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0;

INSERT INTO nx_admin_role_menu(role_id,menu_id,is_deleted)
SELECT DISTINCT rm.role_id,h9.id,0
  FROM nx_admin_role_menu rm
  JOIN nx_admin_menu h8 ON h8.id=rm.menu_id AND h8.menu_code='H8'
  JOIN nx_admin_menu h9 ON h9.menu_code='H9'
 WHERE rm.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0;

-- F5 per-event optimistic lock, frozen provenance and exactly-once operation evidence.
SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_commission_event' AND column_name='version')=0,
  'ALTER TABLE nx_commission_event ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER status',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_commission_event' AND column_name='frozen_from_status')=0,
  'ALTER TABLE nx_commission_event ADD COLUMN frozen_from_status VARCHAR(32) NULL AFTER version',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='nx_commission_operation' AND column_name='expected_version')=0,
  'ALTER TABLE nx_commission_operation ADD COLUMN expected_version BIGINT NULL AFTER idempotency_key',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql=IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='nx_commission_operation' AND index_name='uk_commission_operation_idem')=0,
  'ALTER TABLE nx_commission_operation ADD UNIQUE KEY uk_commission_operation_idem(idempotency_key)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- K6 authoritative fifteen-phase execution ledger and reconciliation evidence.
CREATE TABLE IF NOT EXISTS nx_janus_takeover_execution (
  sid VARCHAR(96) NOT NULL PRIMARY KEY,
  phase VARCHAR(32) NOT NULL DEFAULT 'NONE',
  command_id VARCHAR(128) NULL,
  command_type VARCHAR(24) NULL,
  command_version BIGINT NOT NULL DEFAULT 0,
  delivery_attempts INT NOT NULL DEFAULT 0,
  expected_target_id VARCHAR(64) NULL,
  expected_target_version INT NULL,
  expected_target_catalog_version BIGINT NULL,
  actual_target_id VARCHAR(64) NULL,
  actual_target_version INT NULL,
  actual_target_catalog_version BIGINT NULL,
  device_applied_version BIGINT NULL,
  device_app_version VARCHAR(64) NULL,
  handoff_receipt VARCHAR(256) NULL,
  cause_request_id VARCHAR(128) NULL,
  cause_audit_id VARCHAR(128) NULL,
  cause_decision_id VARCHAR(128) NULL,
  failure_code VARCHAR(64) NULL,
  failure_class VARCHAR(24) NULL,
  failure_phase VARCHAR(32) NULL,
  failure_message VARCHAR(500) NULL,
  reconciliation_id VARCHAR(128) NULL,
  reconciliation_requested_at DATETIME(3) NULL,
  reconciled_at DATETIME(3) NULL,
  requested_at DATETIME(3) NULL,
  acknowledged_at DATETIME(3) NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_janus_takeover_command(command_id),
  KEY idx_janus_takeover_phase(phase,updated_at),
  CONSTRAINT chk_janus_takeover_phase CHECK (phase IN ('NONE','HIT_NOT_REQUESTED','COMMAND_PENDING_ACK','RECEIVED','WAITING_SESSION_EDGE','LOADING','HANDOFF_FETCHING','HANDOFF_MERGING','HANDOFF_ACKED','SUCCEEDED','FAILED','CANCELLED','REVOKE_PENDING_ACK','REVOKE_FAILED','REVOKED')),
  CONSTRAINT chk_janus_takeover_command CHECK (command_type IS NULL OR command_type IN ('ACTIVATE','REVOKE','CHANGE_TARGET')),
  CONSTRAINT chk_janus_takeover_failure CHECK (failure_class IS NULL OR failure_class IN ('delivery','target','webview','handoff','lease','cleanup','contract'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
