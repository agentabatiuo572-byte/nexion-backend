-- L6: privacy-minimized app behavior facts governed by A4 schemas.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS nx_behavior_page_catalog (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  route VARCHAR(160) NOT NULL,
  title_zh VARCHAR(80) NOT NULL,
  page_level TINYINT NOT NULL,
  parent_l1 VARCHAR(160) NOT NULL,
  parent_l2 VARCHAR(160) NOT NULL,
  tracked TINYINT NOT NULL DEFAULT 1,
  source_revision VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_behavior_page_route(route),
  KEY idx_behavior_page_level(page_level,tracked,is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS nx_behavior_event_fact (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id VARCHAR(64) NOT NULL,
  dedupe_key CHAR(64) NOT NULL,
  event_name VARCHAR(64) NOT NULL,
  session_hash CHAR(64) NOT NULL,
  actor_hash CHAR(64) NOT NULL,
  route VARCHAR(160) NOT NULL,
  page_level TINYINT NOT NULL,
  parent_l1 VARCHAR(160) NOT NULL,
  parent_l2 VARCHAR(160) NOT NULL,
  dwell_ms BIGINT NULL,
  x_norm DECIMAL(6,4) NULL,
  y_norm DECIMAL(6,4) NULL,
  zone VARCHAR(16) NULL,
  element_id VARCHAR(64) NULL,
  device_type VARCHAR(16) NOT NULL,
  locale VARCHAR(16) NOT NULL,
  occurred_at DATETIME(3) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_behavior_event_id(event_id),
  UNIQUE KEY uk_behavior_event_dedupe(dedupe_key),
  KEY idx_behavior_event_window(event_name,occurred_at,device_type,locale),
  KEY idx_behavior_event_route(route,occurred_at),
  KEY idx_behavior_event_session(session_hash,occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Trusted build artifact generated from uniapp src/pages.json + zh.headerTitles.
-- Runtime users cannot mutate this registry.
INSERT INTO nx_behavior_page_catalog
  (route,title_zh,page_level,parent_l1,parent_l2,tracked,source_revision,is_deleted)
VALUES
('/pages/index/index','index',1,'/pages/index/index','/pages/index/index',0,'pages-i18n-20260723',0),
('/pages/onboarding/intro','intro',2,'/pages/onboarding/onboarding','/pages/onboarding/intro',0,'pages-i18n-20260723',0),
('/pages/onboarding/estimator','estimator',2,'/pages/onboarding/onboarding','/pages/onboarding/estimator',0,'pages-i18n-20260723',0),
('/pages/onboarding/connect','connect',2,'/pages/onboarding/onboarding','/pages/onboarding/connect',0,'pages-i18n-20260723',0),
('/pages/onboarding/terms','terms',2,'/pages/onboarding/onboarding','/pages/onboarding/terms',0,'pages-i18n-20260723',0),
('/pages/register/register','register',1,'/pages/register/register','/pages/register/register',0,'pages-i18n-20260723',0),
('/pages/login/login','login',1,'/pages/login/login','/pages/login/login',0,'pages-i18n-20260723',0),
('/pages/store/store','商城',1,'/pages/store/store','/pages/store/store',1,'pages-i18n-20260723',0),
('/pages/store/detail','商城',3,'/pages/store/store','/pages/store/store',1,'pages-i18n-20260723',0),
('/pages/store/checkout','结算',2,'/pages/store/store','/pages/store/checkout',1,'pages-i18n-20260723',0),
('/pages/store/orders','我的订单',2,'/pages/store/store','/pages/store/orders',1,'pages-i18n-20260723',0),
('/pages/store/order-detail','订单详情',3,'/pages/store/store','/pages/store/store',0,'pages-i18n-20260723',0),
('/pages/store/bundle','组合套餐',2,'/pages/store/store','/pages/store/bundle',1,'pages-i18n-20260723',0),
('/pages/earn/earn','赚取',1,'/pages/earn/earn','/pages/earn/earn',1,'pages-i18n-20260723',0),
('/pages/me/me','我的',1,'/pages/me/me','/pages/me/me',1,'pages-i18n-20260723',0),
('/pages/me/wallet','钱包',2,'/pages/me/me','/pages/me/wallet',1,'pages-i18n-20260723',0),
('/pages/me/wallet-topup','充值',2,'/pages/me/me','/pages/me/wallet-topup',1,'pages-i18n-20260723',0),
('/pages/me/wallet-withdraw','提现',2,'/pages/me/me','/pages/me/wallet-withdraw',1,'pages-i18n-20260723',0),
('/pages/me/devices','我的',2,'/pages/me/me','/pages/me/devices',1,'pages-i18n-20260723',0),
('/pages/me/profile','个人资料',2,'/pages/me/me','/pages/me/profile',1,'pages-i18n-20260723',0),
('/pages/me/security','安全',2,'/pages/me/me','/pages/me/security',1,'pages-i18n-20260723',0),
('/pages/me/kyc','我的',2,'/pages/me/me','/pages/me/kyc',1,'pages-i18n-20260723',0),
('/pages/me/goals','目标',2,'/pages/me/me','/pages/me/goals',1,'pages-i18n-20260723',0),
('/pages/me/wallet-bills','账单',2,'/pages/me/me','/pages/me/wallet-bills',1,'pages-i18n-20260723',0),
('/pages/me/wallet-exchange','兑换',2,'/pages/me/me','/pages/me/wallet-exchange',1,'pages-i18n-20260723',0),
('/pages/me/wallet-nex','NEX 钱包',2,'/pages/me/me','/pages/me/wallet-nex',1,'pages-i18n-20260723',0),
('/pages/me/wallet-cards','我的',2,'/pages/me/me','/pages/me/wallet-cards',1,'pages-i18n-20260723',0),
('/pages/me/wallet-cards-new','我的',2,'/pages/me/me','/pages/me/wallet-cards-new',1,'pages-i18n-20260723',0),
('/pages/me/receipts','收据',2,'/pages/me/me','/pages/me/receipts',1,'pages-i18n-20260723',0),
('/pages/me/proof','贡献凭证',2,'/pages/me/me','/pages/me/proof',1,'pages-i18n-20260723',0),
('/pages/me/wrapped','年度回顾',2,'/pages/me/me','/pages/me/wrapped',1,'pages-i18n-20260723',0),
('/pages/market/market','行情',1,'/pages/market/market','/pages/market/market',1,'pages-i18n-20260723',0),
('/pages/team/team','团队',1,'/pages/team/team','/pages/team/team',1,'pages-i18n-20260723',0),
('/pages/team/commissions','佣金',2,'/pages/team/team','/pages/team/commissions',1,'pages-i18n-20260723',0),
('/pages/team/binary','平衡匹配',2,'/pages/team/team','/pages/team/binary',1,'pages-i18n-20260723',0),
('/pages/team/agent','区域大使',2,'/pages/team/team','/pages/team/agent',1,'pages-i18n-20260723',0),
('/pages/team/rank','V 级头衔',2,'/pages/team/team','/pages/team/rank',1,'pages-i18n-20260723',0),
('/pages/team/rank-how','团队',2,'/pages/team/team','/pages/team/rank-how',1,'pages-i18n-20260723',0),
('/pages/team/leaderboard','邀请榜',2,'/pages/team/team','/pages/team/leaderboard',1,'pages-i18n-20260723',0),
('/pages/team/leadership-pool','领导池',2,'/pages/team/team','/pages/team/leadership-pool',1,'pages-i18n-20260723',0),
('/pages/team/leadership-pool-how','团队',2,'/pages/team/team','/pages/team/leadership-pool-how',1,'pages-i18n-20260723',0),
('/pages/team/network','影响力网络',2,'/pages/team/team','/pages/team/network',1,'pages-i18n-20260723',0),
('/pages/team/quota','硬件配额',2,'/pages/team/team','/pages/team/quota',1,'pages-i18n-20260723',0),
('/pages/team/tree','族谱',2,'/pages/team/team','/pages/team/tree',1,'pages-i18n-20260723',0),
('/pages/team/unilevel','影响力网络版税',2,'/pages/team/team','/pages/team/unilevel',1,'pages-i18n-20260723',0),
('/pages/team/unilevel-how','团队',2,'/pages/team/team','/pages/team/unilevel-how',1,'pages-i18n-20260723',0),
('/pages/team/binary-how','团队',2,'/pages/team/team','/pages/team/binary-how',1,'pages-i18n-20260723',0),
('/pages/team/commissions-how','团队',2,'/pages/team/team','/pages/team/commissions-how',1,'pages-i18n-20260723',0),
('/pages/genesis/genesis','创世节点',1,'/pages/genesis/genesis','/pages/genesis/genesis',1,'pages-i18n-20260723',0),
('/pages/genesis/holder','创世持有',2,'/pages/genesis/genesis','/pages/genesis/holder',1,'pages-i18n-20260723',0),
('/pages/genesis/marketplace','二级市场',2,'/pages/genesis/genesis','/pages/genesis/marketplace',1,'pages-i18n-20260723',0),
('/pages/genesis/how-it-works','创世节点',3,'/pages/genesis/genesis','/pages/genesis/genesis',1,'pages-i18n-20260723',0),
('/pages/staking/staking','质押',1,'/pages/staking/staking','/pages/staking/staking',1,'pages-i18n-20260723',0),
('/pages/staking/how-it-works','质押',3,'/pages/staking/staking','/pages/staking/staking',1,'pages-i18n-20260723',0),
('/pages/daily/daily','每日签到',1,'/pages/daily/daily','/pages/daily/daily',1,'pages-i18n-20260723',0),
('/pages/missions/missions','任务中心',1,'/pages/missions/missions','/pages/missions/missions',1,'pages-i18n-20260723',0),
('/pages/events/events','活动',1,'/pages/events/events','/pages/events/events',1,'pages-i18n-20260723',0),
('/pages/learn/learn','学习',1,'/pages/learn/learn','/pages/learn/learn',1,'pages-i18n-20260723',0),
('/pages/learn/course','学习',2,'/pages/learn/learn','/pages/learn/course',1,'pages-i18n-20260723',0),
('/pages/globe/globe','全球网络',1,'/pages/globe/globe','/pages/globe/globe',1,'pages-i18n-20260723',0),
('/pages/developer/developer','开发者',1,'/pages/developer/developer','/pages/developer/developer',1,'pages-i18n-20260723',0),
('/pages/search/search','搜索',1,'/pages/search/search','/pages/search/search',1,'pages-i18n-20260723',0),
('/pages/me/achievements','成就',2,'/pages/me/me','/pages/me/achievements',1,'pages-i18n-20260723',0),
('/pages/me/help','帮助中心',2,'/pages/me/me','/pages/me/help',1,'pages-i18n-20260723',0),
('/pages/me/support','客服',2,'/pages/me/me','/pages/me/support',1,'pages-i18n-20260723',0),
('/pages/me/support-tickets','工单',2,'/pages/me/me','/pages/me/support-tickets',1,'pages-i18n-20260723',0),
('/pages/me/language','语言',2,'/pages/me/me','/pages/me/language',1,'pages-i18n-20260723',0),
('/pages/me/notifications','消息',2,'/pages/me/me','/pages/me/notifications',1,'pages-i18n-20260723',0),
('/pages/me/preferences','偏好设置',2,'/pages/me/me','/pages/me/preferences',1,'pages-i18n-20260723',0),
('/pages/me/replay-tour','重播引导',2,'/pages/me/me','/pages/me/replay-tour',1,'pages-i18n-20260723',0),
('/pages/me/risk-disclosure','风险披露',2,'/pages/me/me','/pages/me/risk-disclosure',1,'pages-i18n-20260723',0),
('/pages/me/trial','我的',2,'/pages/me/me','/pages/me/trial',1,'pages-i18n-20260723',0),
('/pages/me/wallet-nex-v2-lock','NEX 锁仓',2,'/pages/me/me','/pages/me/wallet-nex-v2-lock',1,'pages-i18n-20260723',0),
('/pages/me/wallet-repurchase','复投',2,'/pages/me/me','/pages/me/wallet-repurchase',1,'pages-i18n-20260723',0),
('/pages/me/wallet-repurchase-how','我的',2,'/pages/me/me','/pages/me/wallet-repurchase-how',1,'pages-i18n-20260723',0),
('/pages/me/wallet-exchange-how','我的',2,'/pages/me/me','/pages/me/wallet-exchange-how',1,'pages-i18n-20260723',0),
('/pages/me/wallet-withdraw-tracking','提现状态',2,'/pages/me/me','/pages/me/wallet-withdraw-tracking',1,'pages-i18n-20260723',0),
('/pages/trust/trust','信任中心',1,'/pages/trust/trust','/pages/trust/trust',1,'pages-i18n-20260723',0),
('/pages/trust/nex','NEX 信任',2,'/pages/trust/trust','/pages/trust/nex',1,'pages-i18n-20260723',0),
('/pages/ref/code','code',2,'/pages/ref/ref','/pages/ref/code',0,'pages-i18n-20260723',0),
('/pages/tx/hash','交易详情',2,'/pages/tx/tx','/pages/tx/hash',0,'pages-i18n-20260723',0)
ON DUPLICATE KEY UPDATE title_zh=VALUES(title_zh),page_level=VALUES(page_level),
 parent_l1=VALUES(parent_l1),parent_l2=VALUES(parent_l2),tracked=VALUES(tracked),
 source_revision=VALUES(source_revision),is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('app.page_viewed','app','behavior','client','A4/L6',0,'100%',273,'ACTIVE','migration:l6','L6 page-view analytics; client/non-authoritative',0),
  ('app.element_clicked','app','behavior','client','A4/L6',0,'client-throttle-350ms',274,'ACTIVE','migration:l6','L6 normalized click analytics; client/non-authoritative',0)
ON DUPLICATE KEY UPDATE owner_domain='app',family_key='behavior',producer='client',consumers='A4/L6',
  is_server_authoritative=0,sampling_policy=VALUES(sampling_policy),current_revision=VALUES(current_revision),
  status='ACTIVE',reason=VALUES(reason),is_deleted=0,updated_by='migration:l6',updated_at=NOW();

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
SET p.is_deleted=1,p.updated_at=NOW()
WHERE s.event_name IN ('app.page_viewed','app.element_clicked');

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,s.current_revision,0
FROM nx_event_schema_registry s
JOIN (
  SELECT 'app.page_viewed' event_name,'route' property_name,'string' property_type,1 required_field UNION ALL
  SELECT 'app.page_viewed','page_level','number',1 UNION ALL
  SELECT 'app.page_viewed','parent_l1','string',1 UNION ALL
  SELECT 'app.page_viewed','parent_l2','string',1 UNION ALL
  SELECT 'app.page_viewed','dwell_ms','number',1 UNION ALL
  SELECT 'app.element_clicked','route','string',1 UNION ALL
  SELECT 'app.element_clicked','page_level','number',1 UNION ALL
  SELECT 'app.element_clicked','parent_l1','string',1 UNION ALL
  SELECT 'app.element_clicked','parent_l2','string',1 UNION ALL
  SELECT 'app.element_clicked','x_norm','number',1 UNION ALL
  SELECT 'app.element_clicked','y_norm','number',1 UNION ALL
  SELECT 'app.element_clicked','zone','enum',1 UNION ALL
  SELECT 'app.element_clicked','element_id','string',0
) p ON p.event_name=s.event_name
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=VALUES(registry_revision),is_deleted=0;

INSERT INTO nx_event_schema_revision(id,current_revision) VALUES(1,274)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,274);

INSERT INTO nx_admin_permission
  (permission_code,permission_name,resource_type,resource_path,perm_type,amplifies,status,is_deleted)
VALUES ('bi_l6_export','用户行为聚合CSV导出(无PII)','API','/analytics/behavior-heatmap','READ',0,1,0)
ON DUPLICATE KEY UPDATE permission_name=VALUES(permission_name),resource_type='API',
  resource_path='/analytics/behavior-heatmap',perm_type='READ',amplifies=0,status=1,is_deleted=0,updated_at=NOW();

INSERT INTO nx_admin_role_permission(role_id,permission_id,is_deleted)
SELECT r.id,p.id,0 FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code='bi_l6_export'
WHERE r.role_code IN ('SUPER_ADMIN','GROWTH','AUDITOR') AND r.status=1 AND r.is_deleted=0
  AND p.status=1 AND p.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();

-- Risk remains read-only and is explicitly denied export if legacy data granted it.
UPDATE nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
SET rp.is_deleted=1,rp.updated_at=NOW()
WHERE r.role_code='RISK' AND p.permission_code='bi_l6_export' AND rp.is_deleted=0;
