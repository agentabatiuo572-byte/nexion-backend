-- ============================================================
-- F1 V-Rank 晋升引擎 · schema migration (Sprint 1)
-- 目的:为 nx_user_level_log 增加引擎审计列,支持晋升引擎写入:
--   - operator          操作来源(SYSTEM/ENGINE/MANUAL/<admin>)
--   - snapshot          评估快照 JSON(selfBuy/teamVolume/directRefs/legCounts)
--   - trigger_event_id  触发事件 ID(订单/定时任务/手动操作幂等链)
--   - audit_no          审计序号(单次晋升唯一,便于回溯)
--   - is_manual         是否手动覆盖(0=引擎自动,1=运营手动)
-- 幂等:每列 ADD 前先查 information_schema,重复执行不报错。
-- ============================================================

-- operator:操作来源
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_level_log' AND COLUMN_NAME = 'operator') = 0,
  'ALTER TABLE nx_user_level_log ADD COLUMN operator VARCHAR(64) NULL AFTER reason',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- snapshot:评估快照 JSON(selfBuyUSD/teamVolumeUSD/directRefs/legCounts)
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_level_log' AND COLUMN_NAME = 'snapshot') = 0,
  'ALTER TABLE nx_user_level_log ADD COLUMN snapshot JSON NULL AFTER operator',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- trigger_event_id:触发事件 ID(订单支付/定时任务/手动操作幂等链)
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_level_log' AND COLUMN_NAME = 'trigger_event_id') = 0,
  'ALTER TABLE nx_user_level_log ADD COLUMN trigger_event_id VARCHAR(64) NULL AFTER snapshot',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- audit_no:审计序号(单次晋升唯一)
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_level_log' AND COLUMN_NAME = 'audit_no') = 0,
  'ALTER TABLE nx_user_level_log ADD COLUMN audit_no VARCHAR(64) NULL AFTER trigger_event_id',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- is_manual:是否手动覆盖(0=引擎自动,1=运营手动)
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_level_log' AND COLUMN_NAME = 'is_manual') = 0,
  'ALTER TABLE nx_user_level_log ADD COLUMN is_manual TINYINT NOT NULL DEFAULT 0 AFTER audit_no',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- F1 V-Rank 奖励派发流水表(Sprint 3)
-- 目的:晋升达成 → 读 nx_v_rank_reward_rule → 派发(资金/voucher/sku/custom)流水落库,
--      联动 D4 台账(B1 阻断 + nx_commission_event + ledgerPostingFacade)。
-- 幂等:CREATE TABLE IF NOT EXISTS 重复执行不报错。
-- 防重:UNIQUE(user_id, rank_code, reward_type) 同人同阶同类型只派发一次,
--      派发前由 VRankRewardDispatcher 再做 SELECT 检查。
-- ============================================================
CREATE TABLE IF NOT EXISTS nx_v_rank_reward_payout (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    payout_id           VARCHAR(64)  NOT NULL,
    user_id             BIGINT       NOT NULL,
    rank_code           VARCHAR(16)  NOT NULL,
    reward_type         VARCHAR(32)  NOT NULL,
    amount              DECIMAL(18,6) NULL,
    voucher_id          VARCHAR(64)  NULL,
    sku_id              VARCHAR(64)  NULL,
    custom_label        VARCHAR(255) NULL,
    -- sponsor_user_id:培育类 NEX 奖励实际接收方(L1 上线);NULL=本人接收。
    sponsor_user_id     BIGINT       NULL,
    -- status:GRANTED 已派发 / REVERSED 已红冲 / REISSUED 已重发 / PENDING_GRANT 权益类待 H7/E 域接入。
    status              VARCHAR(32)  NOT NULL DEFAULT 'GRANTED',
    commission_event_id BIGINT       NULL,
    bill_id             VARCHAR(64)  NULL,
    trigger_event_id    VARCHAR(64)  NULL,
    granted_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reversed_at         DATETIME     NULL,
    operator            VARCHAR(64)  NULL,
    reason              VARCHAR(255) NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted          TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_v_rank_reward_payout_id        (payout_id),
    -- 防重:同人同阶同类型只派发一次(资金/权益类同阶不重发)
    UNIQUE KEY uk_v_rank_reward_payout_user_rank (user_id, rank_code, reward_type),
    KEY idx_v_rank_reward_payout_user_status     (user_id, status),
    KEY idx_v_rank_reward_payout_rank_type       (rank_code, reward_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
