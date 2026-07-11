-- 域 E+F · 设备+分销 · 43 权限点
-- 来源：docs/superpowers/specs/rbac-classic/EF.md（11 页 = 11 READ + 11 WRITE + 21 HIGH）
-- 依赖：scripts/rbac-classic-seed/00-permission-alter.sql 已执行（perm_type/amplifies/menu_id 字段存在）
-- 幂等：permission_code 唯一键，重复执行只更新不报错。
-- 手动执行（schema.sql 不自动跑，见 schema-manual-init 记忆）：
--   mysql -uroot -p nexion < scripts/rbac-classic-seed/EF.sql

USE nexion;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

INSERT INTO nx_admin_permission (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted) VALUES
  -- E1 商品目录 & 代际门 /devices/pricing
  ('device_e1_read',                          'E1 商品目录与代际门 - 读',         'API', '/devices/pricing',        'READ',  0, 1, 0),
  ('device_e1_write',                         'E1 商品目录与代际门 - 常规写',     'API', '/devices/pricing',        'WRITE', 0, 1, 0),
  ('device_e1_generation_gate_force_unlock',  '代际门强制提前开放',               'API', '/devices/pricing',        'HIGH',  1, 1, 0),
  ('device_e1_generation_gate_force_lock',    '撤销代际门强制提前开放',           'API', '/devices/pricing',        'HIGH',  1, 1, 0),
  -- E2 收益 & 任务引擎 /devices/tasks
  ('device_e2_read',                          'E2 收益与任务引擎 - 读',           'API', '/devices/tasks',          'READ',  0, 1, 0),
  ('device_e2_write',                         'E2 收益与任务引擎 - 常规写',       'API', '/devices/tasks',          'WRITE', 0, 1, 0),
  ('device_e2_phone_tier_usdt',               '手机档位日产 USDT 调整',           'API', '/devices/tasks',          'HIGH',  1, 1, 0),
  ('device_e2_phone_tier_nex',                '手机档位日产 NEX 调整',            'API', '/devices/tasks',          'HIGH',  1, 1, 0),
  -- E3 生命周期 & Trade-in /devices/trade-in
  ('device_e3_read',                          'E3 生命周期与 Trade-in - 读',      'API', '/devices/trade-in',       'READ',  0, 1, 0),
  ('device_e3_write',                         'E3 生命周期与 Trade-in - 常规写',  'API', '/devices/trade-in',       'WRITE', 0, 1, 0),
  ('device_e3_degrade_late',                  '晚期衰减率调整',                   'API', '/devices/trade-in',       'HIGH',  1, 1, 0),
  ('device_e3_salvage_pct',                   '残值率调整',                       'API', '/devices/trade-in',       'HIGH',  1, 1, 0),
  ('device_e3_promo_mult',                    '置换活动倍率调整',                 'API', '/devices/trade-in',       'HIGH',  1, 1, 0),
  -- E4 订单状态机 /devices/orders
  ('device_e4_read',                          'E4 订单状态机 - 读',               'API', '/devices/orders',         'READ',  0, 1, 0),
  ('device_e4_write',                         'E4 订单状态机 - 常规写',           'API', '/devices/orders',         'WRITE', 0, 1, 0),
  ('device_e4_order_refund',                  '订单退款',                         'API', '/devices/orders',         'HIGH',  1, 1, 0),
  -- E5 设备运维 /devices/ops
  ('device_e5_read',                          'E5 设备运维 - 读',                 'API', '/devices/ops',            'READ',  0, 1, 0),
  ('device_e5_write',                         'E5 设备运维 - 常规写',             'API', '/devices/ops',            'WRITE', 0, 1, 0),
  ('device_e5_device_force_activate',         '强制激活设备',                     'API', '/devices/ops',            'HIGH',  1, 1, 0),
  ('device_e5_device_unbind',                 '解绑设备',                         'API', '/devices/ops',            'HIGH',  1, 1, 0),
  ('device_e5_datacenter_pause',              '数据中心批量暂停',                 'API', '/devices/ops',            'HIGH',  1, 1, 0),
  -- E6 算力与设备配置 /devices/compute-config（全常规，无高敏）
  ('device_e6_read',                          'E6 算力与设备配置 - 读',           'API', '/devices/compute-config', 'READ',  0, 1, 0),
  ('device_e6_write',                         'E6 算力与设备配置 - 常规写',       'API', '/devices/compute-config', 'WRITE', 0, 1, 0),
  -- F1 V-Rank 晋升 /network/v-rank
  ('network_f1_read',                         'F1 V-Rank 晋升 - 读',              'API', '/network/v-rank',         'READ',  0, 1, 0),
  ('network_f1_write',                        'F1 V-Rank 晋升 - 常规写',          'API', '/network/v-rank',         'WRITE', 0, 1, 0),
  ('network_f1_permanent_protection',         'V-Rank 不降级保护开关',            'API', '/network/v-rank',         'HIGH',  1, 1, 0),
  -- F2 网络版税费率 /network/royalty
  ('network_f2_read',                         'F2 网络版税费率 - 读',             'API', '/network/royalty',        'READ',  0, 1, 0),
  ('network_f2_write',                        'F2 网络版税费率 - 常规写',         'API', '/network/royalty',        'WRITE', 0, 1, 0),
  ('network_f2_royalty_rate',                 'L1-L7 版税费率放宽',               'API', '/network/royalty',        'HIGH',  1, 1, 0),
  ('network_f2_policy_amplify',               'Partner 档位与费率杠杆参数',       'API', '/network/royalty',        'HIGH',  1, 1, 0),
  -- F3 双轨结算引擎 /network/binary
  ('network_f3_read',                         'F3 双轨结算引擎 - 读',             'API', '/network/binary',         'READ',  0, 1, 0),
  ('network_f3_write',                        'F3 双轨结算引擎 - 常规写',         'API', '/network/binary',         'WRITE', 0, 1, 0),
  ('network_f3_match_rate',                   '平衡匹配比例调整',                 'API', '/network/binary',         'HIGH',  1, 1, 0),
  ('network_f3_engine_pause',                 '暂停双轨结算引擎',                 'API', '/network/binary',         'HIGH',  1, 1, 0),
  -- F4 池 / 配额 / 大使 / 榜 /network/leadership-pool
  ('network_f4_read',                         'F4 池配额大使榜 - 读',             'API', '/network/leadership-pool','READ',  0, 1, 0),
  ('network_f4_write',                        'F4 池配额大使榜 - 常规写',         'API', '/network/leadership-pool','WRITE', 0, 1, 0),
  ('network_f4_pool_fund',                    '奖池资金类调整',                   'API', '/network/leadership-pool','HIGH',  1, 1, 0),
  ('network_f4_ambassador_approve',           '区域大使审批',                     'API', '/network/leadership-pool','HIGH',  1, 1, 0),
  ('network_f4_leaderboard_control',          '排行榜控制(暂停/取消资格)',        'API', '/network/leadership-pool','HIGH',  1, 1, 0),
  -- F5 佣金事件审计 /network/commissions
  ('network_f5_read',                         'F5 佣金事件审计 - 读',             'API', '/network/commissions',    'READ',  0, 1, 0),
  ('network_f5_write',                        'F5 佣金事件审计 - 常规写',         'API', '/network/commissions',    'WRITE', 0, 1, 0),
  ('network_f5_commission_dispose',           '佣金资金状态处置(冻结/解锁/解冻)', 'API', '/network/commissions',    'HIGH',  1, 1, 0),
  ('network_f5_commission_reject',            '佣金驳回(红冲)',                  'API', '/network/commissions',    'HIGH',  1, 1, 0)
ON DUPLICATE KEY UPDATE
  permission_name = VALUES(permission_name),
  resource_type   = VALUES(resource_type),
  resource_path   = VALUES(resource_path),
  perm_type       = VALUES(perm_type),
  amplifies       = VALUES(amplifies),
  status          = 1,
  is_deleted      = 0;

-- 统计：43 行 = 11 READ + 11 WRITE + 21 HIGH
--   E 域 6 页 24 点（6R + 6W + 12 高敏）
--   F 域 5 页 19 点（5R + 5W + 9 高敏）
-- amplifies=1 共 21 条（所有 HIGH 类型）；READ/WRITE 均 amplifies=0
-- 未生成 role_permission（后续统一处理）
