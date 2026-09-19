-- I3 通知容量闸策略文案纠偏。
--
-- 后端 `NotificationCampaignMapper.pruneNotificationsOverCap` 的实际语义是
-- **保留最新 N 条**(ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY created_at DESC, id DESC)
-- 取 rn > cap 删除),即淘汰最旧记录。原文案写「超出部分按 LIFO 淘汰」与实现相反:
-- LIFO 会先淘汰最后进入的最新通知,恰好把要保留的丢掉。运营据此判断会误判风险。
--
-- 这里只改策略说明文案(展示给运营),不改 CAP 数值、不锁定状态、不动任何通知行。

UPDATE nx_notification_cap_rule
   SET policy='每用户保留最新 50 条高优通知，超出部分淘汰最旧记录（按创建时间从早到晚）',
       updated_at=NOW()
 WHERE tier='high';

UPDATE nx_notification_cap_rule
   SET policy='每用户保留最新 200 条常规通知，超出部分淘汰最旧记录（按创建时间从早到晚）',
       updated_at=NOW()
 WHERE tier='normal';

UPDATE nx_notification_cap_rule
   SET policy='每用户保留最新 30 条低优通知，超出部分淘汰最旧记录；且超过 48 小时自动淘汰',
       updated_at=NOW()
 WHERE tier='low';
