-- I3 notification campaign closure:
-- 1) optimistic revision for stale-editor/concurrent action rejection;
-- 2) canonical four-tier CAP defaults and low-tier 48h TTL policy text.
SET NAMES utf8mb4;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE()
                  AND TABLE_NAME='nx_notification_campaign'
                  AND COLUMN_NAME='revision')=0,
  'ALTER TABLE nx_notification_campaign ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 AFTER last_operator',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO nx_notification_cap_rule
  (tier, cap_label, policy, locked, sort_order, status, last_operator, created_at, updated_at, is_deleted)
VALUES
  ('critical', 'Infinity', '合规/风控紧急通知永久保留，不执行数量或 TTL 淘汰', 1, 10, 1, 'migration:i3-closure', NOW(), NOW(), 0),
  ('high', '50 条', '每用户保留最新 50 条，高优运营事件按 LIFO 淘汰', 0, 20, 1, 'migration:i3-closure', NOW(), NOW(), 0),
  ('normal', '200 条', '每用户保留最新 200 条常规通知，按 LIFO 淘汰', 0, 30, 1, 'migration:i3-closure', NOW(), NOW(), 0),
  ('low', '30 条', '每用户保留最新 30 条，且超过 48 小时自动淘汰', 0, 40, 1, 'migration:i3-closure', NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE
  locked=CASE WHEN VALUES(tier)='critical' THEN 1 ELSE locked END,
  status=1,
  is_deleted=0;

UPDATE nx_notification_cap_rule
   SET cap_label='Infinity',
       policy='合规/风控紧急通知永久保留，不执行数量或 TTL 淘汰',
       locked=1, sort_order=10, status=1, is_deleted=0,
       last_operator='migration:i3-closure', updated_at=NOW()
 WHERE tier='critical';

UPDATE nx_notification_cap_rule
   SET cap_label='50 条',
       policy='每用户保留最新 50 条，高优运营事件按 LIFO 淘汰',
       locked=0, sort_order=20, status=1, is_deleted=0,
       last_operator='migration:i3-closure', updated_at=NOW()
 WHERE tier='high' AND (cap_label='6 msg/day' OR cap_label='50' OR cap_label='50条');

UPDATE nx_notification_cap_rule
   SET cap_label='200 条',
       policy='每用户保留最新 200 条常规通知，按 LIFO 淘汰',
       locked=0, sort_order=30, status=1, is_deleted=0,
       last_operator='migration:i3-closure', updated_at=NOW()
 WHERE tier='normal' AND (cap_label IN ('20 万/日','200','200条') OR cap_label LIKE '20%万%');

UPDATE nx_notification_cap_rule
   SET cap_label='30 条',
       policy='每用户保留最新 30 条，且超过 48 小时自动淘汰',
       locked=0, sort_order=40, status=1, is_deleted=0,
       last_operator='migration:i3-closure', updated_at=NOW()
 WHERE tier='low' AND (cap_label IN ('不限','30','30条') OR cap_label LIKE '%无限%');

UPDATE nx_notification_cap_rule
   SET policy='每用户按当前 CAP 保留最新高优通知，超出部分按 LIFO 淘汰',
       locked=0, sort_order=20, status=1, is_deleted=0
 WHERE tier='high';

UPDATE nx_notification_cap_rule
   SET policy='每用户按当前 CAP 保留最新常规通知，超出部分按 LIFO 淘汰',
       locked=0, sort_order=30, status=1, is_deleted=0
 WHERE tier='normal';

UPDATE nx_notification_cap_rule
   SET policy='每用户按当前 CAP 保留最新低优通知，且超过 48 小时自动淘汰',
       locked=0, sort_order=40, status=1, is_deleted=0
 WHERE tier='low';
