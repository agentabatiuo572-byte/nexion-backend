USE nexion;

INSERT INTO admin (id, username, password_hash, nickname, email, phone, super_admin, status)
VALUES
  (1, 'superadmin', '$2a$10$0Q7Qw2lVhKjKq6C8E2bVKuDg4bQ9zq1N9bG4.cHk0xvS3MSB6tQdu', 'Super Admin', 'admin@nexion.ai', NULL, 1, 1)
ON DUPLICATE KEY UPDATE
  nickname = VALUES(nickname),
  super_admin = VALUES(super_admin),
  status = VALUES(status);

INSERT INTO admin_role (id, role_code, role_name, remark, status)
VALUES
  (1, 'SUPER_ADMIN', 'Super Administrator', 'Platform owner role, can allocate admin accounts and roles.', 1),
  (2, 'CONFIG_ADMIN', 'Configuration Administrator', 'Lower-level administrator for platform configuration.', 1)
ON DUPLICATE KEY UPDATE
  role_name = VALUES(role_name),
  remark = VALUES(remark),
  status = VALUES(status);

INSERT INTO admin_permission (id, permission_code, permission_name, resource_type, resource_path, remark)
VALUES
  (1, 'PERM_ADMIN_READ', 'Read admins', 'API', '/auth/admins/**', NULL),
  (2, 'PERM_ADMIN_WRITE', 'Write admins', 'API', '/auth/admins/**', NULL),
  (3, 'PERM_ADMIN_ROLE_ASSIGN', 'Assign admin roles', 'API', '/auth/admins/*/roles', NULL),
  (4, 'PERM_ROLE_READ', 'Read roles', 'API', '/auth/access-control/roles/**', NULL),
  (5, 'PERM_ROLE_WRITE', 'Write roles', 'API', '/auth/access-control/roles/**', NULL),
  (6, 'PERM_ROLE_PERMISSION_ASSIGN', 'Assign role permissions', 'API', '/auth/access-control/roles/*/permissions', NULL),
  (7, 'PERM_PERMISSION_READ', 'Read permissions', 'API', '/auth/access-control/permissions/**', NULL),
  (8, 'PERM_PERMISSION_WRITE', 'Write permissions', 'API', '/auth/access-control/permissions/**', NULL),
  (9, 'PERM_CONFIG_MANAGE', 'Manage platform config', 'API', '/config/**', NULL)
ON DUPLICATE KEY UPDATE
  permission_name = VALUES(permission_name),
  resource_type = VALUES(resource_type),
  resource_path = VALUES(resource_path),
  remark = VALUES(remark);

INSERT IGNORE INTO admin_role_relation (admin_id, role_id)
VALUES (1, 1);

INSERT IGNORE INTO admin_role_permission (role_id, permission_id)
SELECT 1, id FROM admin_permission;

INSERT IGNORE INTO admin_role_permission (role_id, permission_id)
SELECT 2, id FROM admin_permission WHERE permission_code IN ('PERM_CONFIG_MANAGE', 'PERM_ROLE_READ', 'PERM_PERMISSION_READ');

INSERT INTO nx_user (id, country_code, phone, password_hash, nickname, referral_code, sponsor_code, kyc_status, user_level, v_rank, status)
VALUES
  (10001, '+1', '4150004892', '$2a$10$0Q7Qw2lVhKjKq6C8E2bVKuDg4bQ9zq1N9bG4.cHk0xvS3MSB6tQdu', 'Stella Miner', 'NX4892', 'SPONSOR7', 'PENDING', 'L1', 'V0', 'ACTIVE')
ON DUPLICATE KEY UPDATE
  nickname = VALUES(nickname),
  status = VALUES(status);

INSERT INTO nx_user_level_config (id, level_code, level_name, entry_condition, core_goal, sort_order, status)
VALUES
  (1, 'L0', 'Visitor', '未注册', '点击注册', 0, 1),
  (2, 'L1', 'New Contributor', '已注册,手机算力自动接入', '24h 在线 + 首笔收益', 1, 1),
  (3, 'L2', 'Active Contributor', '累计 $5+ 收益', '首次提现成功', 2, 1),
  (4, 'L3', 'Upgrade Candidate', '主动查看硬件商城', '完成设备购买', 3, 1),
  (5, 'L4', 'Device Holder', '已购 NexionBox 1+ 台', '复购 / 追加台数', 4, 1),
  (6, 'L5', 'Ambassador', '推荐 3+ 成功注册', '裂变传播', 5, 1)
ON DUPLICATE KEY UPDATE
  level_name = VALUES(level_name),
  entry_condition = VALUES(entry_condition),
  core_goal = VALUES(core_goal),
  sort_order = VALUES(sort_order),
  status = VALUES(status);

INSERT INTO nx_v_rank_config
  (id, rank_code, title_en, title_cn, self_buy_usd, direct_refs, team_volume_usd, required_downline_rank, required_downline_count, downline_requirement, unilevel_depth, peer_bonus_rate, leadership_votes, physical_reward, sort_order, status)
VALUES
  (1, 'V0', 'Cadet', '学员', 0, 0, 0, NULL, 0, '注册即得', 'L1', 0, 0, NULL, 0, 1),
  (2, 'V1', 'Pilot', '飞行员', 299, 3, 0, NULL, 0, '自买 >= $299 + 直推 3', 'L2', 0, 0, 'Pilot 徽章', 1, 1),
  (3, 'V2', 'Operator', '操作员', 0, 0, 5000, NULL, 0, '团队 $5K', 'L2-3', 0, 0, '操作员勋章', 2, 1),
  (4, 'V3', 'Captain', '舰长', 0, 0, 20000, 'V1', 2, '$20K + 2xV1', 'L2-4', 0.0500, 1, 'Apple Watch SE', 3, 1),
  (5, 'V4', 'Commander', '指挥官', 0, 0, 50000, 'V2', 3, '$50K + 3xV2', 'L2-5', 0.0500, 2, 'iPhone 16 Pro', 4, 1),
  (6, 'V5', 'Wing Leader', '翼领', 0, 0, 150000, 'V3', 4, '$150K + 4xV3', 'L2-6', 0.0500, 4, 'Apple Vision Pro', 5, 1),
  (7, 'V6', 'Squadron', '中队长', 0, 0, 500000, 'V4', 5, '$500K + 5xV4', 'L2-7', 0.0500, 8, 'Rolex Submariner', 6, 1),
  (8, 'V7', 'Fleet Cmdr', '舰队司令', 0, 0, 1000000, 'V5', 6, '$1M + 6xV5', 'L2-8', 0.0500, 16, 'Tesla Model Y', 7, 1),
  (9, 'V8', 'Star Admiral', '星上将', 0, 0, 3000000, 'V6', 7, '$3M + 7xV6', '+L9', 0.0500, 32, 'Porsche 911', 8, 1),
  (10, 'V9', 'Galaxy Lord', '星河领主', 0, 0, 10000000, NULL, 0, '$10M', '+L10', 0.0500, 64, 'Lamborghini Urus', 9, 1),
  (11, 'V10', 'Nexion Founder', '联合创始', 0, 0, 30000000, NULL, 0, '$30M', '无限层 0.5%', 0.0500, 128, '私人飞机包月', 10, 1),
  (12, 'V11', 'Cosmic Sovereign', '宇宙至尊', 0, 0, 100000000, NULL, 0, '$100M', '无限层 1%', 0.0500, 256, '加勒比游艇', 11, 1),
  (13, 'V12', 'Singularity', '奇点', 0, 0, 500000000, NULL, 0, '$500M', '无限层 1.5%', 0.0500, 512, '全网交易 1% 永久分红', 12, 1)
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
  (8, 'CULTIVATION', NULL, 'V1', 0, 0, 500.000000, 0, 0, 1),
  (9, 'CULTIVATION', NULL, 'V2', 0, 0, 2000.000000, 0, 0, 1),
  (10, 'CULTIVATION', NULL, 'V3', 0, 0, 10000.000000, 0, 0, 1),
  (11, 'CULTIVATION', NULL, 'V4', 0, 0, 50000.000000, 0, 0, 1),
  (12, 'CULTIVATION', NULL, 'V5', 0, 0, 200000.000000, 0, 0, 1),
  (13, 'BINARY', NULL, NULL, 0.100000, 0, 0, 5000.000000, 30, 1),
  (14, 'PEER', NULL, NULL, 0.050000, 0, 0, 0, 30, 1),
  (15, 'LEADERSHIP', NULL, NULL, 0.050000, 0, 0, 0, 0, 1)
ON DUPLICATE KEY UPDATE
  rank_code = VALUES(rank_code),
  usdt_rate = VALUES(usdt_rate),
  nex_per_usd = VALUES(nex_per_usd),
  fixed_nex = VALUES(fixed_nex),
  daily_cap_usdt = VALUES(daily_cap_usdt),
  cooldown_days = VALUES(cooldown_days),
  status = VALUES(status);

INSERT INTO nx_device (id, device_no, name, type, tier, status, price_usdt, hashrate, estimated_daily_usdt, daily_usdt, daily_nex, stock, cover_url)
VALUES
  (1, 'NX-S1', 'NexionBox S1', 'NEXION_BOX', 'S1', 'ON_SALE', 299.00, 4.200000, 38.560000, 38.560000, 720.000000, 280, '/img/products/nexionbox-s1-v4.png'),
  (2, 'NX-PRO', 'NexionBox Pro', 'NEXION_BOX', 'PRO', 'ON_SALE', 899.00, 11.500000, 106.800000, 106.800000, 1880.000000, 96, '/img/products/nexionbox-pro-v2.png'),
  (3, 'NX-RACK', 'NexionRack', 'NEXION_RACK', 'RACK', 'ON_SALE', 3499.00, 48.000000, 438.200000, 438.200000, 7600.000000, 18, '/img/products/nexionrack-p1-v2.png')
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  type = VALUES(type),
  tier = VALUES(tier),
  status = VALUES(status),
  price_usdt = VALUES(price_usdt),
  hashrate = VALUES(hashrate),
  estimated_daily_usdt = VALUES(estimated_daily_usdt),
  daily_usdt = VALUES(daily_usdt),
  daily_nex = VALUES(daily_nex),
  stock = VALUES(stock),
  cover_url = VALUES(cover_url);

INSERT INTO nx_user_device (id, user_id, device_id, instance_no, name, status, daily_usdt, daily_nex, last_seen_at, activated_at)
VALUES
  (1, 10001, 1, 'UD-10001-PHONE', 'Mobile Compute', 'ONLINE', 0.060000, 12.000000, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  status = VALUES(status),
  daily_usdt = VALUES(daily_usdt),
  daily_nex = VALUES(daily_nex),
  last_seen_at = VALUES(last_seen_at);
