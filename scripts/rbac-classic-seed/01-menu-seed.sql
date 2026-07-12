-- 经典 RBAC 菜单 seed（以【高保真】nexion-高保真/.../lib/nav/console-nav.ts 为准）：
-- 13 域级 + 73 页级 = 86 节点 + role_menu 绑定。
-- 已下线页面（菜单不含）：G5(Premium) / G6(NEXv2) / H6(里程碑) / I5(风险披露)。高保真 K6(Janus C2) 保留。
-- 幂等。手动执行（带 utf8mb4）：
--   mysql --default-character-set=utf8mb4 -uroot -p'<pwd>' nexion < scripts/rbac-classic-seed/01-menu-seed.sql

-- ===== 1. 域级菜单（13，parent_id NULL）=====
INSERT INTO nx_admin_menu (menu_code, menu_name, menu_name_zh, parent_id, route_path, icon, sort_order, remark, status, is_deleted) VALUES
  ('A','平台基础','平台基础',NULL,'','shield-check',1,'A roles=[] 仅superadmin',1,0),
  ('B','总览驾驶舱','总览驾驶舱',NULL,'','gauge',2,'B 全角色',1,0),
  ('C','用户与账户','用户与账户',NULL,'','users',3,'C support+risk',1,0),
  ('D','资金与财务','资金与财务',NULL,'','wallet',4,'D finance+risk',1,0),
  ('E','设备与商城','设备与商城',NULL,'','server',5,'E growth+support',1,0),
  ('F','分销与团队','分销与团队',NULL,'','network',6,'F growth',1,0),
  ('G','金融产品','金融产品',NULL,'','landmark',7,'G finance+growth',1,0),
  ('H','增长与运营节奏','增长与运营节奏',NULL,'','trending-up',8,'H growth',1,0),
  ('I','内容与合规 CMS','内容与合规 CMS',NULL,'','megaphone',9,'I content+support',1,0),
  ('J','紧急与合规控制','紧急与合规控制',NULL,'','siren',10,'J risk',1,0),
  ('K','风控与反作弊','风控与反作弊',NULL,'','radar',11,'K risk',1,0),
  ('L','数据与分析 BI','数据与分析 BI',NULL,'','bar-chart-3',12,'L 全角色',1,0),
  ('M','客服中心','客服中心',NULL,'','headset',13,'M support+risk',1,0)
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name), menu_name_zh=VALUES(menu_name_zh), icon=VALUES(icon), sort_order=VALUES(sort_order), status=1, is_deleted=0;

-- ===== 2. 页级菜单（72，parent_id 指向域级）=====
-- A 域 (8)
INSERT INTO nx_admin_menu (menu_code, menu_name, menu_name_zh, parent_id, route_path, sort_order, status, is_deleted)
SELECT v.code, v.name, v.name, p.id, v.path, v.sort, 1, 0
FROM nx_admin_menu p JOIN (SELECT 'A' d) x ON p.menu_code='A'
JOIN (SELECT 'A1' code,'运营账号 & RBAC' name,'/platform/rbac' path,1 sort UNION ALL
      SELECT 'A2','审计 & 操作确认','/platform/audit',2 UNION ALL
      SELECT 'A3','系统配置','/platform/config',3 UNION ALL
      SELECT 'A4','埋点事件体系','/platform/events',4 UNION ALL
      SELECT 'A5','平台参数寄存器','/platform/params-registry',5 UNION ALL
      SELECT 'A6','角色管理','/platform/roles',6 UNION ALL
      SELECT 'A7','菜单管理','/platform/menus',7 UNION ALL
      SELECT 'A8','权限字典','/platform/permissions',8) v
ON 1=1
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name), menu_name_zh=VALUES(menu_name_zh), route_path=VALUES(route_path), sort_order=VALUES(sort_order), status=1, is_deleted=0;
-- B 域 (5)
INSERT INTO nx_admin_menu (menu_code, menu_name, menu_name_zh, parent_id, route_path, sort_order, status, is_deleted)
SELECT v.code, v.name, v.name, p.id, v.path, v.sort, 1, 0
FROM nx_admin_menu p JOIN (SELECT 'B' d) x ON p.menu_code='B'
JOIN (SELECT 'B1' code,'双账本总览' name,'/overview/dual-ledger' path,1 sort UNION ALL
      SELECT 'B2','资金池水位','/overview/liquidity',2 UNION ALL
      SELECT 'B3','转化漏斗','/overview/funnel',3 UNION ALL
      SELECT 'B4','节奏状态','/overview/rhythm',4 UNION ALL
      SELECT 'B5','风险雷达','/overview/risk-radar',5) v
ON 1=1
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name), route_path=VALUES(route_path), sort_order=VALUES(sort_order), status=1, is_deleted=0;
-- C 域 (6)
INSERT INTO nx_admin_menu (menu_code, menu_name, menu_name_zh, parent_id, route_path, sort_order, status, is_deleted)
SELECT v.code, v.name, v.name, p.id, v.path, v.sort, 1, 0
FROM nx_admin_menu p JOIN (SELECT 'C' d) x ON p.menu_code='C'
JOIN (SELECT 'C1' code,'检索 & 画像' name,'/users/search' path,1 sort UNION ALL
      SELECT 'C2','账户操作','/users/actions',2 UNION ALL
      SELECT 'C3','余额 & 资产调整','/users/assets',3 UNION ALL
      SELECT 'C4','KYC 合规台账','/users/kyc',4 UNION ALL
      SELECT 'C5','安全 & 会话','/users/security',5 UNION ALL
      SELECT 'C6','注册/登录风控','/users/reg-risk',6) v
ON 1=1
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name), route_path=VALUES(route_path), sort_order=VALUES(sort_order), status=1, is_deleted=0;
-- D 域 (5)
INSERT INTO nx_admin_menu (menu_code, menu_name, menu_name_zh, parent_id, route_path, sort_order, status, is_deleted)
SELECT v.code, v.name, v.name, p.id, v.path, v.sort, 1, 0
FROM nx_admin_menu p JOIN (SELECT 'D' d) x ON p.menu_code='D'
JOIN (SELECT 'D1' code,'充值对账中心' name,'/finance/recon' path,1 sort UNION ALL
      SELECT 'D2','提现审核队列','/finance/withdrawals',2 UNION ALL
      SELECT 'D3','资金池水位仪表盘','/finance/pool',3 UNION ALL
      SELECT 'D4','账本/账单审计','/finance/ledger',4 UNION ALL
      SELECT 'D5','提现参数配置','/finance/params',5) v
ON 1=1
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name), route_path=VALUES(route_path), sort_order=VALUES(sort_order), status=1, is_deleted=0;
-- E 域 (6)
INSERT INTO nx_admin_menu (menu_code, menu_name, menu_name_zh, parent_id, route_path, sort_order, status, is_deleted)
SELECT v.code, v.name, v.name, p.id, v.path, v.sort, 1, 0
FROM nx_admin_menu p JOIN (SELECT 'E' d) x ON p.menu_code='E'
JOIN (SELECT 'E1' code,'商品目录 & 代际门' name,'/devices/pricing' path,1 sort UNION ALL
      SELECT 'E2','收益 & 任务引擎','/devices/tasks',2 UNION ALL
      SELECT 'E3','生命周期 & Trade-in','/devices/trade-in',3 UNION ALL
      SELECT 'E4','订单状态机','/devices/orders',4 UNION ALL
      SELECT 'E5','设备运维','/devices/ops',5 UNION ALL
      SELECT 'E6','算力与设备配置','/devices/compute-config',6) v
ON 1=1
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name), route_path=VALUES(route_path), sort_order=VALUES(sort_order), status=1, is_deleted=0;
-- F 域 (5)
INSERT INTO nx_admin_menu (menu_code, menu_name, menu_name_zh, parent_id, route_path, sort_order, status, is_deleted)
SELECT v.code, v.name, v.name, p.id, v.path, v.sort, 1, 0
FROM nx_admin_menu p JOIN (SELECT 'F' d) x ON p.menu_code='F'
JOIN (SELECT 'F1' code,'V-Rank 晋升' name,'/network/v-rank' path,1 sort UNION ALL
      SELECT 'F2','网络版税费率','/network/royalty',2 UNION ALL
      SELECT 'F3','双轨结算引擎','/network/binary',3 UNION ALL
      SELECT 'F4','池 / 配额 / 大使 / 榜','/network/leadership-pool',4 UNION ALL
      SELECT 'F5','佣金事件审计','/network/commissions',5) v
ON 1=1
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name), route_path=VALUES(route_path), sort_order=VALUES(sort_order), status=1, is_deleted=0;
-- G 域 (5)
INSERT INTO nx_admin_menu (menu_code, menu_name, menu_name_zh, parent_id, route_path, sort_order, status, is_deleted)
SELECT v.code, v.name, v.name, p.id, v.path, v.sort, 1, 0
FROM nx_admin_menu p JOIN (SELECT 'G' d) x ON p.menu_code='G'
JOIN (SELECT 'G1' code,'Staking 池配置' name,'/finance-products/staking' path,1 sort UNION ALL
      SELECT 'G2','兑换风控','/finance-products/exchange',2 UNION ALL
      SELECT 'G3','NEX 行情引擎','/finance-products/market',3 UNION ALL
      SELECT 'G4','Genesis 经济','/finance-products/genesis',4 UNION ALL
      SELECT 'G7','复投激励','/finance-products/repurchase',5) v
ON 1=1
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name), route_path=VALUES(route_path), sort_order=VALUES(sort_order), status=1, is_deleted=0;
-- H 域 (6 页，H6 里程碑已下线)
INSERT INTO nx_admin_menu (menu_code, menu_name, menu_name_zh, parent_id, route_path, sort_order, status, is_deleted)
SELECT v.code, v.name, v.name, p.id, v.path, v.sort, 1, 0
FROM nx_admin_menu p JOIN (SELECT 'H' d) x ON p.menu_code='H'
JOIN (SELECT 'H1' code,'Phase 调度器' name,'/growth/phase' path,1 sort UNION ALL
      SELECT 'H2','免费试用引擎','/growth/trial',2 UNION ALL
      SELECT 'H3','Quest 引擎','/growth/quest',3 UNION ALL
      SELECT 'H4','活动中心 CMS','/growth/events',4 UNION ALL
      SELECT 'H5','签到 & NEX','/growth/daily',5 UNION ALL
      SELECT 'H7','代金券','/growth/vouchers',6) v
ON 1=1
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name), route_path=VALUES(route_path), sort_order=VALUES(sort_order), status=1, is_deleted=0;
-- I 域 (7 页)
INSERT INTO nx_admin_menu (menu_code, menu_name, menu_name_zh, parent_id, route_path, sort_order, status, is_deleted)
SELECT v.code, v.name, v.name, p.id, v.path, v.sort, 1, 0
FROM nx_admin_menu p JOIN (SELECT 'I' d) x ON p.menu_code='I'
JOIN (SELECT 'I1' code,'转化文案 A/B' name,'/content/copy-ab' path,1 sort UNION ALL
      SELECT 'I2','Nova 推送运营','/content/nova',2 UNION ALL
      SELECT 'I3','通知 Campaign','/content/notifications',3 UNION ALL
      SELECT 'I4','信任中心 CMS','/content/trust',4 UNION ALL
      SELECT 'I5','风险披露版本','/content/disclosures',5 UNION ALL
      SELECT 'I6','i18n 文案管理','/content/i18n',6 UNION ALL
      SELECT 'I7','教程中心','/content/learn',7) v
ON 1=1
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name), route_path=VALUES(route_path), sort_order=VALUES(sort_order), status=1, is_deleted=0;
-- J 域 (4)
INSERT INTO nx_admin_menu (menu_code, menu_name, menu_name_zh, parent_id, route_path, sort_order, status, is_deleted)
SELECT v.code, v.name, v.name, p.id, v.path, v.sort, 1, 0
FROM nx_admin_menu p JOIN (SELECT 'J' d) x ON p.menu_code='J'
JOIN (SELECT 'J1' code,'Kill-Switch 矩阵' name,'/emergency/kill-switch' path,1 sort UNION ALL
      SELECT 'J2','Geo-block','/emergency/geo-block',2 UNION ALL
      SELECT 'J3','篡改防御监控','/emergency/tamper',3 UNION ALL
      SELECT 'J4','监管点名应急 SOP','/emergency/sop',4) v
ON 1=1
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name), route_path=VALUES(route_path), sort_order=VALUES(sort_order), status=1, is_deleted=0;
-- K 域 (6，含 K6 Janus C2)
INSERT INTO nx_admin_menu (menu_code, menu_name, menu_name_zh, parent_id, route_path, sort_order, status, is_deleted)
SELECT v.code, v.name, v.name, p.id, v.path, v.sort, 1, 0
FROM nx_admin_menu p JOIN (SELECT 'K' d) x ON p.menu_code='K'
JOIN (SELECT 'K1' code,'反多账户引擎' name,'/risk/multi-account' path,1 sort UNION ALL
      SELECT 'K2','套利 & 刷量检测','/risk/abuse',2 UNION ALL
      SELECT 'K3','提现风控规则引擎','/risk/withdrawal-rules',3 UNION ALL
      SELECT 'K4','风险评分模型','/risk/scoring',4 UNION ALL
      SELECT 'K5','大额 KYC 复审 & 告警','/risk/kyc-review',5 UNION ALL
      SELECT 'K6','Janus C2 控制台','/risk/janus-c2',6) v
ON 1=1
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name), route_path=VALUES(route_path), sort_order=VALUES(sort_order), status=1, is_deleted=0;
-- L 域 (6)
INSERT INTO nx_admin_menu (menu_code, menu_name, menu_name_zh, parent_id, route_path, sort_order, status, is_deleted)
SELECT v.code, v.name, v.name, p.id, v.path, v.sort, 1, 0
FROM nx_admin_menu p JOIN (SELECT 'L' d) x ON p.menu_code='L'
JOIN (SELECT 'L1' code,'KPI 看板' name,'/analytics/kpi' path,1 sort UNION ALL
      SELECT 'L2','漏斗/cohort/留存','/analytics/funnel-cohort',2 UNION ALL
      SELECT 'L3','财务报表','/analytics/financial',3 UNION ALL
      SELECT 'L4','设备/任务/网络报表','/analytics/operations',4 UNION ALL
      SELECT 'L5','导出 & 监管报告','/analytics/export',5 UNION ALL
      SELECT 'L6','用户行为热力图','/analytics/behavior-heatmap',6) v
ON 1=1
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name), route_path=VALUES(route_path), sort_order=VALUES(sort_order), status=1, is_deleted=0;
-- M 域 (5)
INSERT INTO nx_admin_menu (menu_code, menu_name, menu_name_zh, parent_id, route_path, sort_order, status, is_deleted)
SELECT v.code, v.name, v.name, p.id, v.path, v.sort, 1, 0
FROM nx_admin_menu p JOIN (SELECT 'M' d) x ON p.menu_code='M'
JOIN (SELECT 'M1' code,'客服总览' name,'/service/overview' path,1 sort UNION ALL
      SELECT 'M2','工单台','/service/tickets',2 UNION ALL
      SELECT 'M3','即时会话台','/service/sessions',3 UNION ALL
      SELECT 'M4','知识库与 SLA','/service/kb-sla',4 UNION ALL
      SELECT 'M5','话术与模板配置','/service/scripts',5) v
ON 1=1
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name), route_path=VALUES(route_path), sort_order=VALUES(sort_order), status=1, is_deleted=0;

-- 回填 permission.menu_id（按 resource_path 匹配页级菜单）
UPDATE nx_admin_permission p JOIN nx_admin_menu m ON m.route_path = p.resource_path
SET p.menu_id = m.id WHERE p.menu_id IS NULL;

-- ===== 3. role_menu 绑定（角色×菜单可见性）=====
-- 规则（高保真域 roles + 业务）：superadmin/审计 全可见；各域主操作角色见该域；B/L 全角色；A 仅 superadmin(+审计)。
INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM nx_admin_role r JOIN nx_admin_menu m WHERE r.role_code IN ('SUPER_ADMIN','AUDITOR');
-- C: support+risk
INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM nx_admin_role r JOIN nx_admin_menu m ON m.menu_code LIKE 'C%' WHERE r.role_code IN ('SUPPORT','RISK');
-- D: finance+risk
INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM nx_admin_role r JOIN nx_admin_menu m ON m.menu_code LIKE 'D%' WHERE r.role_code IN ('FINANCE','RISK');
-- E: growth+support
INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM nx_admin_role r JOIN nx_admin_menu m ON m.menu_code LIKE 'E%' WHERE r.role_code IN ('GROWTH','SUPPORT');
-- F: growth
INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM nx_admin_role r JOIN nx_admin_menu m ON m.menu_code LIKE 'F%' WHERE r.role_code='GROWTH';
-- G: finance+growth
INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM nx_admin_role r JOIN nx_admin_menu m ON m.menu_code LIKE 'G%' WHERE r.role_code IN ('FINANCE','GROWTH');
-- H: growth
INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM nx_admin_role r JOIN nx_admin_menu m ON m.menu_code LIKE 'H%' WHERE r.role_code='GROWTH';
-- I: content+support
INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM nx_admin_role r JOIN nx_admin_menu m ON m.menu_code LIKE 'I%' WHERE r.role_code IN ('CONTENT','SUPPORT');
-- J: risk
INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM nx_admin_role r JOIN nx_admin_menu m ON m.menu_code LIKE 'J%' WHERE r.role_code='RISK';
-- K: risk
INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM nx_admin_role r JOIN nx_admin_menu m ON m.menu_code LIKE 'K%' WHERE r.role_code='RISK';
-- M: support+risk
INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM nx_admin_role r JOIN nx_admin_menu m ON m.menu_code LIKE 'M%' WHERE r.role_code IN ('SUPPORT','RISK');
-- B/L: 全角色
INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM nx_admin_role r JOIN nx_admin_menu m ON m.menu_code LIKE 'B%' WHERE r.role_code IN ('FINANCE','RISK','CONTENT','GROWTH','SUPPORT','CONFIG_ADMIN');
INSERT IGNORE INTO nx_admin_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM nx_admin_role r JOIN nx_admin_menu m ON m.menu_code LIKE 'L%' WHERE r.role_code IN ('FINANCE','RISK','CONTENT','GROWTH','SUPPORT','CONFIG_ADMIN');
