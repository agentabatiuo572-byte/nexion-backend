-- H1-H8 closure: canonical seeds, H8 RBAC/menu, and A4 growth command contract.
-- MySQL 8; idempotent and safe to execute repeatedly.
SET NAMES utf8mb4;

INSERT INTO nx_admin_operation_mutex (lock_key)
VALUES ('H1_RHYTHM'), ('H4_EVENT'), ('H4_WHEEL'), ('H5_MILESTONE')
ON DUPLICATE KEY UPDATE lock_key=VALUES(lock_key);

-- H8 is a real H-domain module, not a frontend-only route.
INSERT INTO nx_admin_menu
  (menu_code,menu_name,menu_name_zh,menu_name_en,parent_id,route_path,icon,sort_order,remark,status,is_deleted)
SELECT 'H8','新人礼与邀请奖励','新人礼与邀请奖励','Referral rewards',p.id,
       '/growth/referral-rewards','Gift',7,'H8 real wallet and ledger settlement',1,0
  FROM nx_admin_menu p
 WHERE p.menu_code='H' AND p.is_deleted=0
ON DUPLICATE KEY UPDATE
  menu_name=VALUES(menu_name),menu_name_zh=VALUES(menu_name_zh),menu_name_en=VALUES(menu_name_en),
  parent_id=VALUES(parent_id),route_path=VALUES(route_path),sort_order=VALUES(sort_order),
  remark=VALUES(remark),status=1,is_deleted=0;

INSERT INTO nx_admin_permission
  (permission_code,permission_name,resource_type,perm_type,amplifies,resource_path,menu_id,remark,status,is_deleted)
SELECT p.permission_code,p.permission_name,'API',p.perm_type,p.amplifies,
       '/growth/referral-rewards',m.id,p.remark,1,0
  FROM nx_admin_menu m
  JOIN (
    SELECT 'growth_h8_read' permission_code,'H8 邀请奖励页面读' permission_name,'READ' perm_type,0 amplifies,'读取权威邀请奖励配置与结算摘要' remark UNION ALL
    SELECT 'growth_h8_write','H8 邀请奖励参数写','WRITE',1,'配置新人礼与邀请人奖励，放大方向受B1约束' UNION ALL
    SELECT 'growth_h8_settle','H8 真实钱包与资金台账发奖','HIGH',1,'一次性真实钱包、D4台账与必达审计结算'
  ) p
 WHERE m.menu_code='H8' AND m.is_deleted=0
ON DUPLICATE KEY UPDATE
  permission_name=VALUES(permission_name),resource_type='API',perm_type=VALUES(perm_type),
  amplifies=VALUES(amplifies),resource_path=VALUES(resource_path),menu_id=VALUES(menu_id),
  remark=VALUES(remark),status=1,is_deleted=0;

-- Roles that already own the H7 visible/read lane receive H8 read; H7 writers
-- receive H8 parameter write. Real settlement remains SUPER_ADMIN-only.
INSERT INTO nx_admin_role_permission (role_id,permission_id,is_deleted)
SELECT DISTINCT rp.role_id,h8.id,0
  FROM nx_admin_role_permission rp
  JOIN nx_admin_permission h7 ON h7.id=rp.permission_id AND h7.permission_code='growth_h7_read'
  JOIN nx_admin_permission h8 ON h8.permission_code='growth_h8_read'
 WHERE rp.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0;

INSERT INTO nx_admin_role_permission (role_id,permission_id,is_deleted)
SELECT DISTINCT rp.role_id,h8.id,0
  FROM nx_admin_role_permission rp
  JOIN nx_admin_permission h7 ON h7.id=rp.permission_id AND h7.permission_code='growth_h7_write'
  JOIN nx_admin_permission h8 ON h8.permission_code='growth_h8_write'
 WHERE rp.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0;

INSERT INTO nx_admin_role_permission (role_id,permission_id,is_deleted)
SELECT r.id,p.id,0
  FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code='growth_h8_settle'
 WHERE r.role_code='SUPER_ADMIN' AND r.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0;

INSERT INTO nx_admin_role_menu (role_id,menu_id,is_deleted)
SELECT DISTINCT rm.role_id,h8.id,0
  FROM nx_admin_role_menu rm
  JOIN nx_admin_menu h7 ON h7.id=rm.menu_id AND h7.menu_code='H7'
  JOIN nx_admin_menu h8 ON h8.menu_code='H8'
 WHERE rm.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0;

-- H2 authoritative 18-field TrialConfig. Retire historical display aliases so
-- the admin read model cannot expose two names for the same business value.
UPDATE nx_growth_trial_policy
   SET is_deleted=1,updated_at=NOW()
 WHERE policy_key IN ('price','shadow','offsetCap','disc','hq','failRate','trialCooldown','push','autoCharge');

INSERT INTO nx_growth_trial_policy
  (policy_key,policy_name,description,current_value,value_type,hot,section,server_only,sort_order,is_deleted)
VALUES
  ('trialDays','试用天数','新建试用锁定','3','NUMBER',0,'newonly',0,10,0),
  ('graceDays','宽限天数','新建试用锁定','7','NUMBER',0,'newonly',0,20,0),
  ('extensionDays','延长天数','新建试用锁定','3','NUMBER',0,'newonly',0,30,0),
  ('discountRate','购买折扣率','购买时由服务端重算','15','NUMBER',0,'live',0,40,0),
  ('discountCapUSD','抵扣上限','购买时由服务端重算','50','NUMBER',0,'live',0,50,0),
  ('autoChargeAtEnd','到期自动扣款','新建试用锁定','开','STRING',1,'newonly',0,60,0),
  ('highQualityThresholdUSD','高质量延长阈值','达到后可进入延长状态','100','NUMBER',0,'live',0,70,0),
  ('chargeFailRate','扣款失败概率','仅服务端裁决','1','NUMBER',1,'live',1,80,0),
  ('trialProductId','试用产品','由产品版本治理维护','device-trial-standard','STRING',0,'newonly',0,90,0),
  ('trialPriceUSD','试用设备价格','新建试用锁定','1299','NUMBER',1,'newonly',0,100,0),
  ('shadowDailyUSD','每日影子收益 USDT','新建试用锁定','38.52','NUMBER',0,'newonly',0,110,0),
  ('shadowDailyNEX','每日影子收益 NEX','新建试用锁定','65','NUMBER',0,'newonly',0,120,0),
  ('cooldownDays','再次试用冷却天数','实时资格校验','30','NUMBER',0,'live',0,130,0),
  ('phaseOpen','当前阶段是否开放','由 H1 当前阶段派发，只读','开放','STRING',0,'live',0,140,0),
  ('autoPushEnabled','自动推送','实时生效','开','STRING',0,'live',0,150,0),
  ('autoPushDelayMs','自动推送延迟','实时生效','1500','NUMBER',0,'live',0,160,0),
  ('autoPushCooldownHours','自动推送冷却','实时生效','24','NUMBER',0,'live',0,170,0),
  ('autoPushMaxPerSession','单会话最多推送','实时生效','3','NUMBER',0,'live',0,180,0)
ON DUPLICATE KEY UPDATE
  policy_name=VALUES(policy_name),description=VALUES(description),value_type=VALUES(value_type),
  hot=VALUES(hot),section=VALUES(section),server_only=VALUES(server_only),sort_order=VALUES(sort_order),
  is_deleted=0;

-- H1 current and monthly eight-dial matrix. Existing operator values win.
INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
WITH RECURSIVE months(month_no) AS (
  SELECT 1 UNION ALL SELECT month_no + 1 FROM months WHERE month_no < 24
), dials(dial_key) AS (
  SELECT 'newUserBonusMultiplier' UNION ALL SELECT 'inviteRewardMultiplier' UNION ALL
  SELECT 'reinvestMultiplier' UNION ALL SELECT 'withdrawPenaltyFeeRate' UNION ALL
  SELECT 'withdrawCooldownDays' UNION ALL SELECT 'binaryDailyCap' UNION ALL
  SELECT 'questBonusMultiplier' UNION ALL SELECT 'complianceHoldEnabled'
)
SELECT CONCAT('growth.phase.month.',month_no,'.',dial_key),
       CASE
         WHEN dial_key IN ('newUserBonusMultiplier','inviteRewardMultiplier') THEN IF(month_no<=2,'2',IF(month_no<=4,'1.5','1'))
         WHEN dial_key='reinvestMultiplier' THEN IF(month_no BETWEEN 5 AND 6,'2','1')
         WHEN dial_key='withdrawPenaltyFeeRate' THEN IF(month_no<=8,'0.20',IF(month_no<=10,'0.25','0.30'))
         WHEN dial_key='withdrawCooldownDays' THEN IF(month_no<=7,'30',IF(month_no=8,'35','45'))
         WHEN dial_key='binaryDailyCap' THEN IF(month_no<=6,'5000','2000')
         WHEN dial_key='questBonusMultiplier' THEN IF(month_no<=2,'4','1')
         WHEN dial_key='complianceHoldEnabled' THEN IF(month_no>=8,'1','0')
       END,
       'NUMBER','growth','ADMIN','H1 canonical monthly eight-dial matrix',1,0
  FROM months CROSS JOIN dials
 WHERE 1=1
ON DUPLICATE KEY UPDATE status=1,is_deleted=0;

-- H5 server-canonical rules and catalogs. Existing operator values are preserved.
INSERT INTO nx_growth_checkin_rule
  (rule_key,rule_name,description,current_value,value_type,hot,sort_order,is_deleted)
VALUES
  ('baseline','每日基础奖励','签到成功固定奖励','1','NUMBER',0,10,0),
  ('bonus7','连续7天奖励','连续7天额外奖励','5','NUMBER',0,20,0),
  ('luckyMultiplierMax','Lucky最高倍率','服务端RNG倍率上限','2','NUMBER',1,30,0),
  ('p15','1.5倍概率','服务端RNG概率百分比','15','NUMBER',1,40,0),
  ('p2','2倍概率','服务端RNG概率百分比','5','NUMBER',1,50,0),
  ('broken','断签判定小时','超过后断签','48','NUMBER',0,60,0),
  ('revive','Streak Saver张数','默认可恢复次数','1','NUMBER',0,70,0)
ON DUPLICATE KEY UPDATE
  rule_name=VALUES(rule_name),description=VALUES(description),value_type=VALUES(value_type),
  hot=VALUES(hot),sort_order=VALUES(sort_order),is_deleted=0;

INSERT INTO nx_streak_milestone
  (milestone_day,milestone_name,reward_type,reward_amount,reward_name,badge_achievement_code,sort_order,status,is_deleted)
VALUES
  (3,'连续签到3天','POINTS',5,'+5 积分',NULL,10,1,0),
  (7,'连续签到7天','POINTS',15,'+15 积分',NULL,20,1,0),
  (14,'连续签到14天','USDT',1,'+1 USDT',NULL,30,1,0),
  (21,'连续签到21天','NEX',100,'+100 NEX',NULL,40,1,0),
  (30,'连续签到30天','SPIN',1,'Lucky Spin 票 ×1',NULL,50,1,0),
  (60,'连续签到60天','USDT',10,'+10 USDT',NULL,60,1,0),
  (100,'连续签到100天','BADGE',0,'Streak Master Badge','STREAK_MASTER',70,1,0)
ON DUPLICATE KEY UPDATE
  milestone_name=VALUES(milestone_name),reward_type=VALUES(reward_type),reward_amount=VALUES(reward_amount),
  reward_name=VALUES(reward_name),
  badge_achievement_code=VALUES(badge_achievement_code),sort_order=VALUES(sort_order),status=1,is_deleted=0;

INSERT INTO nx_streak_power_up
  (power_up_code,power_up_name,i18n_key,target_path,badge_achievement_code,unlock_streak_days,
   effect_type,effect_value,duration_days,sort_order,status,is_deleted)
VALUES
  ('ROYALTY_BOOST','团队成长徽章','streak.royalty_boost','/team','STREAK_7',7,'ROUTE_BADGE','连续签到 7 天可查看团队成长权益',0,10,1,0),
  ('STREAK_BADGE','连续签到徽章','streak.streak_badge','/me/rewards','STREAK_14',14,'ROUTE_BADGE','连续签到 14 天解锁专属徽章',0,20,1,0),
  ('STAKING_APY','质押活动入口','streak.staking_apy','/wallet/staking','STREAK_30',30,'ROUTE_BADGE','连续签到 30 天解锁质押活动入口',0,30,1,0),
  ('GENESIS_ALLOWLIST','Genesis 资格入口','streak.genesis_allowlist','/market/genesis','STREAK_60',60,'ROUTE_BADGE','连续签到 60 天解锁 Genesis 资格入口',0,40,1,0)
ON DUPLICATE KEY UPDATE
  power_up_name=VALUES(power_up_name),i18n_key=VALUES(i18n_key),target_path=VALUES(target_path),
  badge_achievement_code=VALUES(badge_achievement_code),unlock_streak_days=VALUES(unlock_streak_days),
  effect_type=VALUES(effect_type),effect_value=VALUES(effect_value),duration_days=VALUES(duration_days),
  sort_order=VALUES(sort_order),status=1,is_deleted=0;

UPDATE nx_streak_power_up
   SET is_deleted=1,status=0,updated_at=NOW()
 WHERE power_up_code='PREMIUM_TRIAL';

-- H4 invariant: at most one live, ongoing featured event. Service-level mutexes
-- provide a friendly validation result; this generated unique key is the final
-- database boundary for writers that bypass that service.
SET @h4_guard_column := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_event_quest'
     AND COLUMN_NAME='active_featured_guard'
);
SET @h4_guard_column_sql := IF(
  @h4_guard_column=0,
  'ALTER TABLE nx_event_quest ADD COLUMN active_featured_guard TINYINT GENERATED ALWAYS AS (CASE WHEN is_deleted=0 AND status=1 AND badge_achievement_code=''FEATURED'' THEN 1 ELSE NULL END) STORED',
  'SELECT 1'
);
PREPARE h4_guard_column_stmt FROM @h4_guard_column_sql;
EXECUTE h4_guard_column_stmt;
DEALLOCATE PREPARE h4_guard_column_stmt;

SET @h4_guard_index := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_event_quest'
     AND INDEX_NAME='uk_nx_event_quest_active_featured'
);
SET @h4_guard_index_sql := IF(
  @h4_guard_index=0,
  'ALTER TABLE nx_event_quest ADD UNIQUE KEY uk_nx_event_quest_active_featured (active_featured_guard)',
  'SELECT 1'
);
PREPARE h4_guard_index_stmt FROM @h4_guard_index_sql;
EXECUTE h4_guard_index_stmt;
DEALLOCATE PREPARE h4_guard_index_stmt;

-- Generic admin mirror event: exact business events remain additional contracts;
-- this schema guarantees every H configuration mutation has a durable A4 trail.
INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('admin.growth_config_changed','admin','growth','server','H1-H8/A2/A4/L4',1,
   '100%',90,'ACTIVE','migration:h-domain-closure','H domain canonical mutation mirror',0)
ON DUPLICATE KEY UPDATE
  owner_domain='admin',family_key='growth',producer='server',consumers=VALUES(consumers),
  is_server_authoritative=1,sampling_policy='100%',current_revision=90,status='ACTIVE',
  updated_by='migration:h-domain-closure',reason=VALUES(reason),is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1,p.updated_at=NOW()
 WHERE s.event_name='admin.growth_config_changed'
   AND p.property_name NOT IN ('module_id','operation','target_id','idempotency_key');

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,'string',0,1,s.current_revision,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'module_id' property_name UNION ALL SELECT 'operation' UNION ALL
    SELECT 'target_id' UNION ALL SELECT 'idempotency_key'
  ) p
 WHERE s.event_name='admin.growth_config_changed'
ON DUPLICATE KEY UPDATE property_type='string',pii=0,required_field=1,
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_domain_extension
  (domain_name,event_name,producer,consumer,status,created_by,reason,is_deleted)
VALUES
  ('admin','admin.growth_config_changed','OpsGrowthCommandBoundary','H1-H8/A2/A4/L4',
   'REGISTERED','migration:h-domain-closure','H domain mutation downstream mirror',0)
ON DUPLICATE KEY UPDATE producer=VALUES(producer),consumer=VALUES(consumer),status='REGISTERED',
  reason=VALUES(reason),is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,90)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,90);
