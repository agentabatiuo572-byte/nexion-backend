USE nexion;

CREATE TABLE IF NOT EXISTS admin_menu (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  menu_code VARCHAR(96) NOT NULL,
  menu_name VARCHAR(96) NOT NULL,
  parent_id BIGINT NULL,
  route_path VARCHAR(255) NOT NULL,
  icon VARCHAR(64) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  remark VARCHAR(255) NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_menu_code (menu_code),
  KEY idx_admin_menu_parent (parent_id),
  KEY idx_admin_menu_sort (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS admin_role_menu (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_id BIGINT NOT NULL,
  menu_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_role_menu (role_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

UPDATE admin_permission
SET resource_path = '/auth/access-control/roles/*/permissions,/auth/access-control/roles/*/menus',
    updated_at = NOW()
WHERE permission_code = 'PERM_ROLE_PERMISSION_ASSIGN'
  AND resource_type = 'API';

INSERT INTO admin_menu (id, menu_code, menu_name, parent_id, route_path, icon, sort_order, remark, status)
VALUES
  (10, 'MENU_HOME', '首页', NULL, '/home', 'HomeFilled', 10, '后台首页菜单', 1),
  (20, 'MENU_UMS', '权限', NULL, '/ums', 'Lock', 20, '权限管理目录', 1),
  (21, 'MENU_UMS_ADMIN', '管理员列表', 20, '/ums/admin', 'User', 21, '管理员维护菜单', 1),
  (22, 'MENU_UMS_ROLE', '角色列表', 20, '/ums/role', 'UserFilled', 22, '角色维护菜单', 1),
  (23, 'MENU_UMS_MENU', '菜单管理', 20, '/ums/menu', 'Menu', 23, '后台菜单维护入口', 1),
  (24, 'MENU_UMS_PERMISSION', 'API 权限', 20, '/ums/permission', 'Key', 24, 'API 权限维护菜单', 1),
  (40, 'MENU_TEAM', '团队', NULL, '/team', 'Share', 40, '团队管理目录', 1),
  (41, 'MENU_TEAM_RANK_CONFIG', '等级配置', 40, '/team/rank-config', 'Medal', 41, '等级配置菜单', 1),
  (42, 'MENU_TEAM_RANK_EVALUATE', '等级评估', 40, '/team/rank-evaluate', 'Trophy', 42, '等级评估菜单', 1),
  (43, 'MENU_TEAM_COMMISSION_RECORDS', '佣金记录', 40, '/team/commission-records', 'Tickets', 43, '佣金记录菜单', 1),
  (44, 'MENU_TEAM_COMMISSION_SETTLE', '佣金结算', 40, '/team/commission-settle', 'Money', 44, '佣金结算菜单', 1),
  (130, 'MENU_COMMERCE', '商城交易', NULL, '/commerce', 'ShoppingCart', 130, '商城交易目录', 1),
  (131, 'MENU_COMMERCE_PRODUCTS', '商品 SKU', 130, '/commerce/products', 'Goods', 131, '商品 SKU 配置', 1),
  (132, 'MENU_COMMERCE_ORDERS', '订单管理', 130, '/commerce/orders', 'Tickets', 132, '订单运营', 1),
  (133, 'MENU_COMMERCE_PAYMENTS', '支付对账', 130, '/commerce/payments', 'CreditCard', 133, '支付对账运营', 1),
  (134, 'MENU_COMMERCE_TRADEINS', 'Trade-in', 130, '/commerce/tradeins', 'Refresh', 134, 'Trade-in 运营', 1),
  (150, 'MENU_GENESIS', 'Genesis', NULL, '/genesis', 'Star', 150, 'Genesis 目录', 1),
  (151, 'MENU_GENESIS_SERIES', '系列配置', 150, '/genesis/series', 'Collection', 151, 'Genesis 系列配置', 1),
  (152, 'MENU_GENESIS_ORDERS', 'Genesis 订单', 150, '/genesis/orders', 'Tickets', 152, 'Genesis 订单', 1),
  (153, 'MENU_GENESIS_HOLDINGS', 'Genesis 持仓', 150, '/genesis/holdings', 'Medal', 153, 'Genesis 持仓', 1),
  (160, 'MENU_COMPUTE', '设备算力', NULL, '/compute', 'Cpu', 160, '设备算力目录', 1),
  (161, 'MENU_COMPUTE_DEVICES', '设备实例', 160, '/compute/devices', 'Cpu', 161, '设备实例', 1),
  (162, 'MENU_COMPUTE_LIFECYCLE', '生命周期', 160, '/compute/lifecycle', 'Timer', 162, '生命周期规则', 1),
  (163, 'MENU_COMPUTE_TASKS', '计算任务', 160, '/compute/tasks', 'Operation', 163, '计算任务', 1),
  (164, 'MENU_COMPUTE_RECEIPTS', 'Receipt', 160, '/compute/receipts', 'DocumentChecked', 164, '计算凭证', 1),
  (165, 'MENU_COMPUTE_NODE_MAP', '节点地图', 160, '/compute/node-map', 'MapLocation', 165, '节点地图', 1),
  (170, 'MENU_WALLET', '钱包运营', NULL, '/wallet', 'Wallet', 170, '钱包运营目录', 1),
  (171, 'MENU_WALLET_OVERVIEW', '钱包概览', 170, '/wallet/overview', 'Wallet', 171, '钱包概览', 1),
  (172, 'MENU_WALLET_LEDGERS', '钱包流水', 170, '/wallet/ledgers', 'Tickets', 172, '钱包流水', 1),
  (173, 'MENU_WALLET_DEPOSITS', '充值记录', 170, '/wallet/deposits', 'Download', 173, '充值运营', 1),
  (174, 'MENU_WALLET_WITHDRAWALS', '提现广播', 170, '/wallet/withdrawals', 'Upload', 174, '提现广播运营', 1),
  (180, 'MENU_COMPLIANCE', '合规风控', NULL, '/compliance', 'Checked', 180, '合规风控目录', 1),
  (181, 'MENU_COMPLIANCE_KYC', 'KYC', 180, '/compliance/kyc', 'UserFilled', 181, 'KYC 运营', 1),
  (182, 'MENU_COMPLIANCE_RISK', '风险决策', 180, '/compliance/risk-decisions', 'DataAnalysis', 182, '风险决策', 1),
  (183, 'MENU_COMPLIANCE_REVIEW', '人工复核', 180, '/compliance/review', 'Warning', 183, '人工复核', 1),
  (184, 'MENU_COMPLIANCE_BLACKLISTS', '黑名单', 180, '/compliance/blacklists', 'CircleClose', 184, '黑名单运营', 1),
  (185, 'MENU_COMPLIANCE_PROOF', 'Proof 资产', 180, '/compliance/proof-assets', 'Files', 185, 'Proof 资产', 1),
  (190, 'MENU_SYSTEM', '系统配置', NULL, '/system', 'Setting', 190, '系统配置目录', 1),
  (191, 'MENU_SYSTEM_CONFIGS', '后台配置', 190, '/system/configs', 'Setting', 191, '后台配置', 1),
  (192, 'MENU_SYSTEM_PUBLIC_CONFIG', '公共配置', 190, '/system/public-config', 'View', 192, '公共配置', 1),
  (193, 'MENU_SYSTEM_I18N', '多语言', 190, '/system/i18n', 'ChatLineSquare', 193, '多语言', 1),
  (194, 'MENU_SYSTEM_CONTENT', '内容页', 190, '/system/content', 'Document', 194, '内容页', 1),
  (195, 'MENU_SYSTEM_HELP', '帮助中心', 190, '/system/help', 'QuestionFilled', 195, '帮助中心', 1),
  (196, 'MENU_SYSTEM_SUPPORT_TICKETS', '客服工单', 190, '/system/support-tickets', 'Service', 196, '客服工单运营', 1),
  (210, 'MENU_OPENAPI', 'OpenAPI', NULL, '/openapi', 'Connection', 210, 'OpenAPI 目录', 1),
  (211, 'MENU_OPENAPI_APPS', '应用管理', 210, '/openapi/apps', 'Key', 211, 'OpenAPI 应用', 1),
  (212, 'MENU_OPENAPI_CALL_AUDITS', '调用审计', 210, '/openapi/call-audits', 'DataLine', 212, 'OpenAPI 调用审计', 1),
  (213, 'MENU_OPENAPI_WEBHOOKS', 'Webhook', 210, '/openapi/webhooks', 'Position', 213, 'Webhook 投递', 1),
  (220, 'MENU_AUDIT', '审计', NULL, '/audit', 'DataLine', 220, '审计目录', 1),
  (221, 'MENU_AUDIT_LOGS', '审计日志', 220, '/audit/logs', 'Document', 221, '审计日志', 1),
  (222, 'MENU_AUDIT_STATS', '审计统计', 220, '/audit/stats', 'DataAnalysis', 222, '审计统计', 1),
  (230, 'MENU_MISSION', '任务运营', NULL, '/mission', 'Flag', 230, '任务运营目录', 1),
  (231, 'MENU_MISSION_OVERVIEW', '任务概览', 230, '/mission/overview', 'DataBoard', 231, '任务服务概览', 1),
  (232, 'MENU_MISSION_CONSUMER', '消费事件', 230, '/mission/consumer', 'Connection', 232, 'Mission 消费事件治理', 1),
  (240, 'MENU_NOTIFICATION', '通知触达', NULL, '/notification', 'Bell', 240, '通知触达目录', 1),
  (241, 'MENU_NOTIFICATION_OVERVIEW', '通知概览', 240, '/notification/overview', 'DataBoard', 241, '通知服务概览', 1),
  (242, 'MENU_NOTIFICATION_PUSH', '待推送处理', 240, '/notification/push', 'Position', 242, '待推送处理', 1),
  (243, 'MENU_NOTIFICATION_CONSUMER', '消费事件', 240, '/notification/consumer', 'Connection', 243, 'Notification 消费事件治理', 1)
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name),
  parent_id = VALUES(parent_id),
  route_path = VALUES(route_path),
  icon = VALUES(icon),
  sort_order = VALUES(sort_order),
  remark = VALUES(remark),
  status = VALUES(status),
  is_deleted = 0;

UPDATE admin_menu
SET status = 0,
    is_deleted = 1,
    updated_at = NOW()
WHERE menu_code IN ('MENU_OPS', 'MENU_OPS_DEVICE', 'MENU_OPS_WALLET', 'MENU_OPS_CONFIG');

INSERT IGNORE INTO admin_role_menu (role_id, menu_id)
SELECT rp.role_id, m.id
FROM admin_role_permission rp
JOIN admin_permission p ON p.id = rp.permission_id
JOIN admin_menu m ON m.route_path = p.resource_path
WHERE rp.is_deleted = 0
  AND p.resource_type = 'MENU';

INSERT IGNORE INTO admin_role_menu (role_id, menu_id)
SELECT 1, id FROM admin_menu;

UPDATE admin_role_permission rp
JOIN admin_permission p ON p.id = rp.permission_id
SET rp.is_deleted = 1, rp.updated_at = NOW()
WHERE p.resource_type = 'MENU';

UPDATE admin_permission
SET is_deleted = 1, status = 0, updated_at = NOW()
WHERE resource_type = 'MENU';
