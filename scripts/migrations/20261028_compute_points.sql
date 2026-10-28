-- Team contribution is measured in compute points, never in dollars (PRD F04).
-- Each product carries a per-unit point value (initially list price x10); every order
-- snapshots its points when it is created. Rank, team quota and purchase-gate thresholds
-- move from USD to points (x10 of the previous value). Ranks stop at V8.
-- The product has not launched, so the USD columns are renamed in place. Safe to reapply.

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'nx_product' AND COLUMN_NAME = 'compute_points') = 0,
  'ALTER TABLE nx_product ADD COLUMN compute_points BIGINT NOT NULL DEFAULT 0 AFTER price_usdt',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE nx_product SET compute_points = ROUND(price_usdt * 10)
 WHERE compute_points = 0 AND price_usdt > 0;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'nx_order' AND COLUMN_NAME = 'compute_points') = 0,
  'ALTER TABLE nx_order ADD COLUMN compute_points BIGINT NOT NULL DEFAULT 0 AFTER amount_usdt',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE nx_order o
  JOIN (SELECT i.order_no, SUM(i.quantity * p.compute_points) AS points
          FROM nx_order_item i JOIN nx_product p ON p.id = i.product_id
         WHERE i.is_deleted = 0
         GROUP BY i.order_no) bundle ON bundle.order_no = o.order_no
   SET o.compute_points = bundle.points
 WHERE o.order_type = 'BUNDLE' AND o.compute_points = 0;
UPDATE nx_order o JOIN nx_product p ON p.id = o.product_id
   SET o.compute_points = o.quantity * p.compute_points
 WHERE o.order_type <> 'BUNDLE' AND o.compute_points = 0;

SET @scale_self = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'nx_v_rank_config' AND COLUMN_NAME = 'self_buy_usd');
-- Scale while the column is still DECIMAL so fractional dollars are not rounded before x10.
SET @sql = IF(@scale_self = 1, 'UPDATE nx_v_rank_config SET self_buy_usd = self_buy_usd * 10', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(@scale_self = 1,
  'ALTER TABLE nx_v_rank_config CHANGE COLUMN self_buy_usd self_compute_points BIGINT NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @scale_team = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'nx_v_rank_config' AND COLUMN_NAME = 'team_volume_usd');
-- Scale while the column is still DECIMAL so fractional dollars are not rounded before x10.
SET @sql = IF(@scale_team = 1, 'UPDATE nx_v_rank_config SET team_volume_usd = team_volume_usd * 10', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(@scale_team = 1,
  'ALTER TABLE nx_v_rank_config CHANGE COLUMN team_volume_usd team_compute_points BIGINT NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

DELETE FROM nx_v_rank_reward_rule WHERE UPPER(rank_code) IN ('V9','V10','V11','V12');
DELETE FROM nx_v_rank_config WHERE UPPER(rank_code) IN ('V9','V10','V11','V12');

SET @scale_quota = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'nx_team_hardware_quota_tier' AND COLUMN_NAME = 'month_volume_usd');
-- Scale while the column is still DECIMAL so fractional dollars are not rounded before x10.
SET @sql = IF(@scale_quota = 1, 'UPDATE nx_team_hardware_quota_tier SET month_volume_usd = month_volume_usd * 10', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF(@scale_quota = 1,
  'ALTER TABLE nx_team_hardware_quota_tier CHANGE COLUMN month_volume_usd month_compute_points BIGINT NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_admin_device_sku
   SET purchase_gate_json = JSON_REMOVE(
         JSON_SET(purchase_gate_json, '$.teamComputePointsMin',
                  CASE WHEN JSON_TYPE(JSON_EXTRACT(purchase_gate_json, '$.teamVolumeMin')) = 'NULL' THEN NULL
                       ELSE CAST(ROUND(JSON_EXTRACT(purchase_gate_json, '$.teamVolumeMin') * 10) AS SIGNED) END),
         '$.teamVolumeMin'),
       purchase_gate_generation = purchase_gate_generation + 1,
       updated_at = NOW()
 WHERE is_deleted = 0 AND JSON_VALID(purchase_gate_json)
   AND JSON_CONTAINS_PATH(purchase_gate_json, 'one', '$.teamVolumeMin');
