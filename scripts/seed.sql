USE nexion;

INSERT INTO admin (id, username, password_hash, nickname, email, phone, super_admin, status)
VALUES
  (1, 'superadmin', '$2a$10$OiCP0hEdnWNuxl/Q6PQ.juoWSzQCXFbvrLheJ.TYywnoyiZY7s19C', 'Super Admin', 'admin@nexion.ai', NULL, 1, 1)
ON DUPLICATE KEY UPDATE
  password_hash = VALUES(password_hash),
  nickname = VALUES(nickname),
  email = VALUES(email),
  super_admin = VALUES(super_admin),
  status = VALUES(status);

INSERT INTO admin_role (id, role_code, role_name, remark, status)
VALUES
  (1, 'SUPER_ADMIN', 'Super Administrator', 'Platform owner role with all permissions.', 1),
  (2, 'OPS_ADMIN', 'Operations Administrator', 'Daily operations role for compute, wallet, team, and content.', 1)
ON DUPLICATE KEY UPDATE
  role_name = VALUES(role_name),
  remark = VALUES(remark),
  status = VALUES(status);

UPDATE admin_permission
SET permission_code = CONCAT(permission_code, '_LEGACY_', id),
    status = 0,
    is_deleted = 1,
    updated_at = NOW()
WHERE id BETWEEN 101 AND 110
  AND resource_type = 'API';

INSERT INTO admin_permission (id, permission_code, permission_name, resource_type, resource_path, remark, status)
VALUES
  (1, 'PERM_ADMIN_READ', 'Read admins', 'API', '/auth/admins/**', NULL, 1),
  (2, 'PERM_ADMIN_WRITE', 'Write admins', 'API', '/auth/admins/**', NULL, 1),
  (3, 'PERM_ADMIN_ROLE_ASSIGN', 'Assign admin roles', 'API', '/auth/admins/*/roles', NULL, 1),
  (4, 'PERM_ROLE_READ', 'Read roles', 'API', '/auth/access-control/roles/**', NULL, 1),
  (5, 'PERM_ROLE_WRITE', 'Write roles', 'API', '/auth/access-control/roles/**', NULL, 1),
  (6, 'PERM_ROLE_PERMISSION_ASSIGN', 'Assign role permissions', 'API', '/auth/access-control/roles/*/api-permissions,/auth/access-control/roles/*/menus', NULL, 1),
  (7, 'PERM_PERMISSION_READ', 'Read permissions', 'API', '/auth/access-control/permissions/**', NULL, 1),
  (8, 'PERM_PERMISSION_WRITE', 'Write permissions', 'API', '/auth/access-control/permissions/**', NULL, 1),
  (9, 'PERM_MENU_READ', 'Read menus', 'API', '/auth/access-control/menus/**', NULL, 1),
  (10, 'PERM_MENU_WRITE', 'Write menus', 'API', '/auth/access-control/menus/**', NULL, 1),
  (101, 'PERM_BFF_READ', 'Read BFF aggregation', 'API', '/bff/**', NULL, 1),
  (102, 'PERM_COMPUTE_READ', 'Read compute operations', 'API', '/compute/**', NULL, 1),
  (117, 'PERM_COMPUTE_WRITE', 'Write compute task and device status', 'API', '/compute/tasks/**,/compute/devices/*/status', NULL, 1),
  (103, 'PERM_COMMERCE_READ', 'Read commerce operations', 'API', '/commerce/**', NULL, 1),
  (104, 'PERM_COMMERCE_WRITE', 'Write commerce operations', 'API', '/commerce/orders/**', NULL, 1),
  (105, 'PERM_WALLET_READ', 'Read wallet operations', 'API', '/wallet/**', NULL, 1),
  (112, 'PERM_WALLET_WRITE', 'Write wallet operations', 'API', '/wallet/**', NULL, 1),
  (118, 'PERM_EARNINGS_WRITE', 'Write earnings tick and milestone operations', 'API', '/earnings/ticks/**,/earnings/milestones/**,/earnings/events/settle-receipt', NULL, 1),
  (106, 'PERM_TEAM_READ', 'Read team operations', 'API', '/team/**', NULL, 1),
  (116, 'PERM_TEAM_WRITE', 'Write team operations', 'API', '/team/**', NULL, 1),
  (107, 'PERM_NOTIFICATION_READ', 'Read notifications', 'API', '/notifications/**', NULL, 1),
  (119, 'PERM_NOTIFICATION_WRITE', 'Write notification operations', 'API', '/notifications/ops/**', NULL, 1),
  (108, 'PERM_EARNINGS_READ', 'Read earnings operations', 'API', '/earnings/**', NULL, 1),
  (109, 'PERM_MISSION_READ', 'Read mission operations', 'API', '/missions/**', NULL, 1),
  (110, 'PERM_COMPLIANCE_READ', 'Read compliance operations', 'API', '/compliance/**', NULL, 1),
  (113, 'PERM_COMPLIANCE_WRITE', 'Write compliance operations', 'API', '/compliance/**', NULL, 1),
  (111, 'PERM_SYSTEM_READ', 'Read system operations', 'API', '/system/**', NULL, 1),
  (115, 'PERM_SYSTEM_WRITE', 'Write system operations', 'API', '/system/**', NULL, 1),
  (114, 'PERM_OPENAPI_ADMIN', 'Admin OpenAPI apps, quotas, audits, and webhook delivery', 'API', '/openapi/ops/**,/openapi/webhooks/deliveries/**', NULL, 1)
ON DUPLICATE KEY UPDATE
  permission_code = VALUES(permission_code),
  permission_name = VALUES(permission_name),
  resource_type = VALUES(resource_type),
  resource_path = VALUES(resource_path),
  remark = VALUES(remark),
  status = VALUES(status),
  is_deleted = 0;

INSERT INTO admin_menu (id, menu_code, menu_name, parent_id, route_path, icon, sort_order, remark, status)
VALUES
  (10, 'MENU_HOME', 'Home', NULL, '/home', 'HomeFilled', 10, 'Admin dashboard entry.', 1),
  (20, 'MENU_UMS', 'Access Control', NULL, '/ums', 'Lock', 20, 'Admin access control group.', 1),
  (21, 'MENU_UMS_ADMIN', 'Admins', 20, '/ums/admin', 'User', 21, 'Admin account management.', 1),
  (22, 'MENU_UMS_ROLE', 'Roles', 20, '/ums/role', 'UserFilled', 22, 'Role management.', 1),
  (23, 'MENU_UMS_MENU', 'Menus', 20, '/ums/menu', 'Menu', 23, 'Menu management.', 1),
  (24, 'MENU_UMS_PERMISSION', 'API Permissions', 20, '/ums/permission', 'Key', 24, 'API permission management.', 1),
  (30, 'MENU_OPS', 'Operations', NULL, '/ops', 'Monitor', 30, 'Operations group.', 1),
  (31, 'MENU_OPS_DEVICE', 'Compute Ops', 30, '/ops/device', 'Cpu', 31, 'Compute device operations.', 1),
  (32, 'MENU_OPS_WALLET', 'Wallet Ops', 30, '/ops/wallet', 'Wallet', 32, 'Wallet, receipt, and notification operations.', 1),
  (40, 'MENU_TEAM', 'Team', NULL, '/team', 'Share', 40, 'Team management group.', 1),
  (41, 'MENU_TEAM_RANK_CONFIG', 'Rank Config', 40, '/team/rank-config', 'Medal', 41, 'Rank configuration.', 1),
  (42, 'MENU_TEAM_RANK_EVALUATE', 'Rank Evaluate', 40, '/team/rank-evaluate', 'Trophy', 42, 'Rank evaluation.', 1),
  (43, 'MENU_TEAM_COMMISSION_RECORDS', 'Commission Records', 40, '/team/commission-records', 'Tickets', 43, 'Commission records.', 1),
  (44, 'MENU_TEAM_COMMISSION_SETTLE', 'Commission Settle', 40, '/team/commission-settle', 'Money', 44, 'Commission settlement.', 1)
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name),
  parent_id = VALUES(parent_id),
  route_path = VALUES(route_path),
  icon = VALUES(icon),
  sort_order = VALUES(sort_order),
  remark = VALUES(remark),
  status = VALUES(status);

INSERT IGNORE INTO admin_role_relation (admin_id, role_id)
VALUES (1, 1);

INSERT IGNORE INTO admin_role_permission (role_id, permission_id)
SELECT 1, id FROM admin_permission WHERE resource_type = 'API';

INSERT IGNORE INTO admin_role_permission (role_id, permission_id)
SELECT 2, id FROM admin_permission
WHERE permission_code IN (
  'PERM_BFF_READ',
  'PERM_COMPUTE_READ',
  'PERM_COMPUTE_WRITE',
  'PERM_COMMERCE_READ',
  'PERM_COMMERCE_WRITE',
  'PERM_WALLET_READ',
  'PERM_TEAM_READ',
  'PERM_TEAM_WRITE',
  'PERM_NOTIFICATION_READ',
  'PERM_NOTIFICATION_WRITE',
  'PERM_EARNINGS_READ',
  'PERM_MISSION_READ',
  'PERM_SYSTEM_READ',
  'PERM_SYSTEM_WRITE',
  'PERM_EARNINGS_WRITE',
  'PERM_OPENAPI_ADMIN'
);

INSERT IGNORE INTO admin_role_menu (role_id, menu_id)
SELECT 1, id FROM admin_menu;

INSERT IGNORE INTO admin_role_menu (role_id, menu_id)
SELECT 2, id FROM admin_menu WHERE menu_code IN (
  'MENU_HOME',
  'MENU_OPS',
  'MENU_OPS_DEVICE',
  'MENU_OPS_WALLET',
  'MENU_TEAM',
  'MENU_TEAM_RANK_CONFIG',
  'MENU_TEAM_RANK_EVALUATE',
  'MENU_TEAM_COMMISSION_RECORDS',
  'MENU_TEAM_COMMISSION_SETTLE'
);

INSERT INTO nx_user (id, country_code, phone, password_hash, nickname, referral_code, sponsor_code, kyc_status, user_level, v_rank, status, language, region)
VALUES
  (10001, '+1', '4150004892', '$2a$10$0Q7Qw2lVhKjKq6C8E2bVKuDg4bQ9zq1N9bG4.cHk0xvS3MSB6tQdu', 'Stella Miner', 'NX4892', 'SPONSOR7', 'PENDING', 'L1', 'V0', 'ACTIVE', 'en-US', 'US')
ON DUPLICATE KEY UPDATE
  nickname = VALUES(nickname),
  kyc_status = VALUES(kyc_status),
  user_level = VALUES(user_level),
  v_rank = VALUES(v_rank),
  status = VALUES(status);

INSERT INTO nx_user_profile (id, user_id, display_name, email, wallet_address)
VALUES
  (1, 10001, 'Stella Miner', 'stella@nexion.ai', '0x4892nexiondemo')
ON DUPLICATE KEY UPDATE
  display_name = VALUES(display_name),
  email = VALUES(email),
  wallet_address = VALUES(wallet_address);

INSERT INTO nx_user_security (id, user_id, two_factor_enabled, login_fail_count)
VALUES
  (1, 10001, 0, 0)
ON DUPLICATE KEY UPDATE
  two_factor_enabled = VALUES(two_factor_enabled),
  login_fail_count = VALUES(login_fail_count);

INSERT INTO nx_user_level_config (id, level_code, level_name, entry_condition, core_goal, sort_order, status)
VALUES
  (1, 'L0', 'Visitor', 'Not registered', 'Complete registration', 0, 1),
  (2, 'L1', 'New Contributor', 'Registered with mobile compute access', 'Stay online for 24 hours and receive first earning', 1, 1),
  (3, 'L2', 'Active Contributor', 'Earned 5+ USDT', 'Complete first withdrawal', 2, 1),
  (4, 'L3', 'Upgrade Candidate', 'Viewed hardware commerce', 'Complete device purchase', 3, 1),
  (5, 'L4', 'Device Holder', 'Purchased at least one NexionBox', 'Add or renew devices', 4, 1),
  (6, 'L5', 'Ambassador', 'Three successful referrals', 'Scale referral growth', 5, 1)
ON DUPLICATE KEY UPDATE
  level_name = VALUES(level_name),
  entry_condition = VALUES(entry_condition),
  core_goal = VALUES(core_goal),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

INSERT INTO nx_v_rank_config
  (id, rank_code, title_en, title_cn, self_buy_usd, direct_refs, team_volume_usd, required_downline_rank, required_downline_count, downline_requirement, unilevel_depth, peer_bonus_rate, leadership_votes, physical_reward, sort_order, status)
VALUES
  (1, 'V0', 'Cadet', 'Cadet', 0, 0, 0, NULL, 0, 'Registered account', 'L1', 0, 0, NULL, 0, 1),
  (2, 'V1', 'Pilot', 'Pilot', 299, 3, 0, NULL, 0, 'Self buy >= 299 USDT and 3 directs', 'L2', 0, 0, 'Pilot badge', 1, 1),
  (3, 'V2', 'Operator', 'Operator', 0, 0, 5000, NULL, 0, 'Team volume >= 5000 USDT', 'L2-L3', 0, 0, 'Operator badge', 2, 1),
  (4, 'V3', 'Captain', 'Captain', 0, 0, 20000, 'V1', 2, 'Team volume >= 20000 USDT and 2 V1 legs', 'L2-L4', 0.0500, 1, 'Apple Watch SE', 3, 1),
  (5, 'V4', 'Commander', 'Commander', 0, 0, 50000, 'V2', 3, 'Team volume >= 50000 USDT and 3 V2 legs', 'L2-L5', 0.0500, 2, 'iPhone 16 Pro', 4, 1),
  (6, 'V5', 'Wing Leader', 'Wing Leader', 0, 0, 150000, 'V3', 4, 'Team volume >= 150000 USDT and 4 V3 legs', 'L2-L6', 0.0500, 4, 'Apple Vision Pro', 5, 1)
ON DUPLICATE KEY UPDATE
  title_en = VALUES(title_en),
  title_cn = VALUES(title_cn),
  self_buy_usd = VALUES(self_buy_usd),
  direct_refs = VALUES(direct_refs),
  team_volume_usd = VALUES(team_volume_usd),
  required_downline_rank = VALUES(required_downline_rank),
  required_downline_count = VALUES(required_downline_count),
  downline_requirement = VALUES(downline_requirement),
  unilevel_depth = VALUES(unilevel_depth),
  peer_bonus_rate = VALUES(peer_bonus_rate),
  leadership_votes = VALUES(leadership_votes),
  physical_reward = VALUES(physical_reward),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

INSERT INTO nx_commission_rule
  (id, commission_type, layer_no, rank_code, usdt_rate, nex_per_usd, fixed_nex, daily_cap_usdt, cooldown_days, status)
VALUES
  (1, 'UNILEVEL', 1, NULL, 0.100000, 50.000000, 0, 0, 30, 1),
  (2, 'UNILEVEL', 2, NULL, 0.050000, 20.000000, 0, 0, 30, 1),
  (3, 'UNILEVEL', 3, NULL, 0.030000, 10.000000, 0, 0, 30, 1),
  (4, 'UNILEVEL', 4, NULL, 0.020000, 5.000000, 0, 0, 30, 1),
  (5, 'UNILEVEL', 5, NULL, 0.010000, 2.500000, 0, 0, 30, 1),
  (6, 'UNILEVEL', 6, NULL, 0.005000, 1.000000, 0, 0, 30, 1),
  (7, 'UNILEVEL', 7, NULL, 0.005000, 1.000000, 0, 0, 30, 1),
  (8, 'BINARY', NULL, NULL, 0.100000, 0, 0, 5000.000000, 1, 1),
  (9, 'PEER', NULL, NULL, 0.050000, 0, 0, 0, 30, 1),
  (10, 'LEADERSHIP', NULL, NULL, 0.050000, 0, 0, 0, 0, 1),
  (11, 'CULTIVATION', NULL, 'V1', 0, 0, 500.000000, 0, 0, 1),
  (12, 'CULTIVATION', NULL, 'V2', 0, 0, 2000.000000, 0, 0, 1),
  (13, 'CULTIVATION', NULL, 'V3', 0, 0, 10000.000000, 0, 0, 1),
  (14, 'CULTIVATION', NULL, 'V4', 0, 0, 50000.000000, 0, 0, 1),
  (15, 'CULTIVATION', NULL, 'V5', 0, 0, 200000.000000, 0, 0, 1)
ON DUPLICATE KEY UPDATE
  rank_code = VALUES(rank_code),
  usdt_rate = VALUES(usdt_rate),
  nex_per_usd = VALUES(nex_per_usd),
  fixed_nex = VALUES(fixed_nex),
  daily_cap_usdt = VALUES(daily_cap_usdt),
  cooldown_days = VALUES(cooldown_days),
  status = VALUES(status);

INSERT INTO nx_product (id, product_no, name, product_type, tier, status, price_usdt, hashrate, estimated_daily_usdt, daily_nex, stock, cover_url)
VALUES
  (1, 'NX-S1', 'NexionBox S1', 'NEXION_BOX', 'S1', 'ON_SALE', 299.00, 4.200000, 38.560000, 720.000000, 280, '/img/products/nexionbox-s1.png'),
  (2, 'NX-PRO', 'NexionBox Pro', 'NEXION_BOX', 'PRO', 'ON_SALE', 899.00, 11.500000, 106.800000, 1880.000000, 96, '/img/products/nexionbox-pro.png'),
  (3, 'NX-RACK', 'NexionRack', 'NEXION_RACK', 'RACK', 'ON_SALE', 3499.00, 48.000000, 438.200000, 7600.000000, 18, '/img/products/nexionrack.png')
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  product_type = VALUES(product_type),
  tier = VALUES(tier),
  status = VALUES(status),
  price_usdt = VALUES(price_usdt),
  hashrate = VALUES(hashrate),
  estimated_daily_usdt = VALUES(estimated_daily_usdt),
  daily_nex = VALUES(daily_nex),
  stock = VALUES(stock),
  cover_url = VALUES(cover_url);

INSERT INTO nx_user_device (id, user_id, source_order_no, product_id, instance_no, name, device_type, status, hashrate, daily_usdt, daily_nex, last_seen_at, activated_at)
VALUES
  (1, 10001, NULL, NULL, 'UD-10001-PHONE', 'Mobile Compute', 'MOBILE', 'ONLINE', 0.250000, 0.060000, 12.000000, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  device_type = VALUES(device_type),
  status = VALUES(status),
  hashrate = VALUES(hashrate),
  daily_usdt = VALUES(daily_usdt),
  daily_nex = VALUES(daily_nex),
  last_seen_at = VALUES(last_seen_at);

INSERT INTO nx_compute_task (id, task_no, user_id, user_device_id, task_type, client_name, status, started_at, completed_at)
VALUES
  (1, 'TASK-20260522-0001', 10001, 1, 'IMAGE_INFERENCE', 'Nexion Demo', 'COMPLETED', NOW(), NOW())
ON DUPLICATE KEY UPDATE
  status = VALUES(status),
  completed_at = VALUES(completed_at);

INSERT INTO nx_compute_receipt (id, user_id, user_device_id, task_no, receipt_no, task_type, client_name, reward_usdt, reward_nex, earning_status, proof_hash, completed_at)
VALUES
  (1, 10001, 1, 'TASK-20260522-0001', 'POC-20260522-0001', 'IMAGE_INFERENCE', 'Nexion Demo', 0.018000, 3.200000, 'PENDING', '0x8a22c91f6f8e7c2d', NOW())
ON DUPLICATE KEY UPDATE
  receipt_no = VALUES(receipt_no),
  user_device_id = VALUES(user_device_id),
  task_no = VALUES(task_no),
  reward_usdt = VALUES(reward_usdt),
  reward_nex = VALUES(reward_nex),
  earning_status = VALUES(earning_status),
  proof_hash = VALUES(proof_hash),
  completed_at = VALUES(completed_at);

INSERT INTO nx_earning_event (id, event_no, user_id, user_device_id, receipt_no, asset, amount, status, wallet_posted_at)
VALUES
  (1, 'EARN-20260522-USDT-0001', 10001, 1, 'POC-20260522-0001', 'USDT', 0.018000, 'PENDING_WALLET', NULL),
  (2, 'EARN-20260522-NEX-0001', 10001, 1, 'POC-20260522-0001', 'NEX', 3.200000, 'PENDING_WALLET', NULL)
ON DUPLICATE KEY UPDATE
  amount = VALUES(amount),
  status = VALUES(status),
  wallet_posted_at = VALUES(wallet_posted_at);

INSERT INTO nx_earning_summary (id, user_id, summary_date, usdt_amount, nex_amount)
VALUES
  (1, 10001, CURRENT_DATE, 0.018000, 3.200000)
ON DUPLICATE KEY UPDATE
  usdt_amount = VALUES(usdt_amount),
  nex_amount = VALUES(nex_amount);

INSERT INTO nx_user_wallet (id, user_id, usdt_available, nex_available, pending_withdraw, lifetime_earned)
VALUES
  (1, 10001, 128.640000, 8420.000000, 20.000000, 612.440000)
ON DUPLICATE KEY UPDATE
  usdt_available = VALUES(usdt_available),
  nex_available = VALUES(nex_available),
  pending_withdraw = VALUES(pending_withdraw),
  lifetime_earned = VALUES(lifetime_earned);

DELETE FROM nx_wallet_ledger
WHERE biz_no IN ('EARN-20260522-USDT-0001', 'EARN-20260522-NEX-0001')
  AND biz_type = 'EARNING';

INSERT INTO nx_mission (id, mission_code, mission_name, mission_type, reward_points, status)
VALUES
  (1, 'DAY_ONE_ONLINE', 'Day-One Online', 'DAY_ONE', 100, 1),
  (2, 'FIRST_RECEIPT', 'First Compute Receipt', 'GROWTH', 200, 1),
  (3, 'DAILY_CHECK_IN', 'Daily Check-in', 'DAILY', 30, 1)
ON DUPLICATE KEY UPDATE
  mission_name = VALUES(mission_name),
  mission_type = VALUES(mission_type),
  reward_points = VALUES(reward_points),
  status = VALUES(status);

INSERT INTO nx_notification (id, biz_no, user_id, type, title, body, read_flag, push_status)
VALUES
  (1, 'seed:EARN-20260522-USDT-0001', 10001, 'EARNING', 'Overnight compute completed', '+0.018 USDT and +3.2 NEX are ready.', 0, 'PENDING')
ON DUPLICATE KEY UPDATE
  biz_no = VALUES(biz_no),
  type = VALUES(type),
  title = VALUES(title),
  body = VALUES(body),
  read_flag = VALUES(read_flag),
  push_status = VALUES(push_status);

INSERT INTO nx_kyc_profile (id, user_id, kyc_no, status, country)
VALUES
  (1, 10001, 'KYC-10001', 'PENDING', 'US')
ON DUPLICATE KEY UPDATE
  status = VALUES(status),
  country = VALUES(country);

INSERT INTO nx_config_item (id, config_key, config_value, value_type, remark, status)
VALUES
  (1, 'product.phase', 'MVP', 'STRING', 'Current product phase.', 1),
  (2, 'withdrawal.min_usdt', '10', 'NUMBER', 'Minimum USDT withdrawal amount.', 1),
  (3, 'risk.withdrawal.kyc_required', 'true', 'BOOLEAN', 'Require KYC before withdrawal.', 1),
  (4, 'openapi.default_qps_limit', '20', 'NUMBER', 'Default OpenAPI per-app QPS quota.', 1),
  (5, 'openapi.default_daily_limit', '10000', 'NUMBER', 'Default OpenAPI per-app daily quota.', 1),
  (6, 'risk.withdrawal.review_amount', '1000', 'NUMBER', 'Withdrawal amount that triggers manual review.', 1),
  (7, 'risk.exchange.review_amount', '5000', 'NUMBER', 'Exchange amount that triggers manual review.', 1),
  (8, 'feature.genesis.enabled', 'false', 'BOOLEAN', 'Genesis feature launch switch.', 1)
ON DUPLICATE KEY UPDATE
  config_value = VALUES(config_value),
  value_type = VALUES(value_type),
  remark = VALUES(remark),
  status = VALUES(status);

INSERT INTO nx_i18n_message (id, message_key, locale, message_value, status)
VALUES
  (1, 'app.name', 'en-US', 'Nexion', 1),
  (2, 'app.name', 'zh-CN', 'Nexion', 1),
  (3, 'home.compute_marketplace.title', 'en-US', 'Distributed AI compute marketplace', 1),
  (4, 'home.compute_marketplace.title', 'zh-CN', '分布式 AI 算力商城', 1)
ON DUPLICATE KEY UPDATE
  message_value = VALUES(message_value),
  status = VALUES(status);

INSERT INTO nx_content_page (id, page_code, title, content, status)
VALUES
  (1, 'terms.service', 'Terms of Service', 'Nexion service terms baseline content.', 1),
  (2, 'privacy.policy', 'Privacy Policy', 'Nexion privacy policy baseline content.', 1)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  content = VALUES(content),
  status = VALUES(status);

INSERT INTO nx_help_article (id, article_code, title, content, sort_order, status)
VALUES
  (1, 'compute.getting_started', 'Getting started with compute devices', 'Activate a device after a paid order, then wait for task dispatch.', 10, 1),
  (2, 'wallet.withdrawal', 'Withdrawal basics', 'Complete KYC and submit a withdrawal request for review and broadcast.', 20, 1)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  content = VALUES(content),
  sort_order = VALUES(sort_order),
  status = VALUES(status);
