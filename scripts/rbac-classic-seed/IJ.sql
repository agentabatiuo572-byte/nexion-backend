-- 域 I+J · 内容+应急 · 39 权限点
-- 源：docs/superpowers/specs/rbac-classic/IJ.md（收敛自 rbac-matrix/IJ.md 92 按钮级）
-- 幂等：ON DUPLICATE KEY UPDATE。手动执行（schema.sql 不自动跑，见 schema-manual-init 记忆）：
--   mysql -uroot -p nexion < scripts/rbac-classic-seed/IJ.sql
-- 前置：00-permission-alter.sql（perm_type/amplifies 字段）
-- amplifies=1 共 8 个（I5 受限范围/I7 课程奖励/J1 熔断·恢复·批量/J2 三态·应急封锁/J4 执行）
-- 不含 role_permission（后续统一处理）；不含 menu_id（后续 A8 字典挂菜单时回填）

INSERT INTO nx_admin_permission (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted) VALUES
  -- ===== I 域 · 内容与合规（slug=content）6 页 18 点 =====

  -- I1 转化文案 A/B（4 点）
  ('content_i1_read',                 '转化文案A/B-读',                'API', '/content/copy-ab',      'READ',  0, 1, 0),
  ('content_i1_write',                '转化文案A/B-普通文案写(草稿/发布/回滚/下架/位置/版本目录)', 'API', '/content/copy-ab', 'WRITE', 0, 1, 0),
  ('content_i1_experiment_manage',    '转化文案A/B-实验管理(框架参数/创建/启动/停止/采纳/弃用)', 'API', '/content/copy-ab', 'HIGH', 0, 1, 0),
  ('content_i1_copy_create',          '转化文案A/B-新建文案位(建位即带首版)', 'API', '/content/copy-ab', 'WRITE', 0, 1, 0),

  -- I2 Nova 推送运营（2 点）
  ('content_i2_read',                 'Nova推送-读',                   'API', '/content/nova',         'READ',  0, 1, 0),
  ('content_i2_write',                'Nova推送-写(通道CRUD/模板CRUD/概率调整/池编辑)', 'API', '/content/nova', 'WRITE', 0, 1, 0),

  -- I3 通知 Campaign（4 点）
  ('content_i3_read',                 '通知Campaign-读',               'API', '/content/notifications','READ',  0, 1, 0),
  ('content_i3_write',                '通知Campaign-写(新建/编辑/调度下发/立即下发/取消)', 'API', '/content/notifications', 'WRITE', 0, 1, 0),
  ('content_i3_cap_adjust',           'CAP容量闸调整(高敏·影响合规通知可见性·critical锁定)', 'API', '/content/notifications', 'HIGH', 0, 1, 0),
  ('content_i3_critical_send',         '通知Campaign-critical下发(合规/监管高敏)', 'API', '/content/notifications', 'HIGH', 0, 1, 0),

  -- I4 信任中心 CMS（4 点）
  ('content_i4_read',                 '信任中心CMS-读',                'API', '/content/trust',        'READ',  0, 1, 0),
  ('content_i4_write',                '信任中心CMS-草稿CRUD',          'API', '/content/trust',        'WRITE', 0, 1, 0),
  ('content_i4_publish_standard',     '信任版块发布/回滚/下架(普通版块)', 'API', '/content/trust',       'HIGH', 0, 1, 0),
  ('content_i4_trust_section_manage', '信任版块发布/回滚/下架(高敏·highSensitivity升合规)', 'API', '/content/trust', 'HIGH', 0, 1, 0),

  -- I5 风险披露版本（4 点）
  ('content_i5_read',                 '风险披露版本-读',               'API', '/content/disclosures',  'READ',  0, 1, 0),
  ('content_i5_write',                '风险披露草稿/矩阵CRUD',         'API', '/content/disclosures',  'WRITE', 0, 1, 0),
  ('content_i5_disclosure_publish',   '披露版本发布(高敏·触发re-ack·条款重签字)', 'API', '/content/disclosures', 'HIGH', 0, 1, 0),
  ('content_i5_gate_adjust',          '受限动作范围调整(高敏·放松资金类动作合规拦截)', 'API', '/content/disclosures', 'HIGH', 1, 1, 0),

  -- I6 i18n 文案管理（2 点）
  ('content_i6_read',  'i18n文案-读',        'API', '/content/i18n', 'READ',  0, 1, 0),
  ('content_i6_write', 'i18n文案-写(词条CRUD/多语种/完整性修复/重扫/marketing多版)', 'API', '/content/i18n', 'WRITE', 0, 1, 0),

  -- I7 教程中心（3 点）
  ('content_i7_read',                 '教程中心-读',                                   'API', '/content/learn', 'READ',  0, 1, 0),
  ('content_i7_write',                '教程中心-写(课程发布/下架/新建/推荐课/章节CRUD)', 'API', '/content/learn', 'WRITE', 0, 1, 0),
  ('content_i7_course_reward_adjust', '课程奖励调整(高敏·amplifies·B1兑付覆盖率红线)',  'API', '/content/learn', 'HIGH', 1, 1, 0),

  -- ===== J 域 · 紧急与合规控制（slug=emergency）4 页 16 点 =====

  -- J1 Kill-Switch 矩阵（5 点）
  ('emergency_j1_read',               'Kill-Switch矩阵-读',            'API', '/emergency/kill-switch', 'READ',  0, 1, 0),
  ('emergency_j1_write',              'Kill-Switch矩阵-写(SLA参数/自动规则阈值·配置类)', 'API', '/emergency/kill-switch', 'WRITE', 0, 1, 0),
  ('emergency_j1_gate_kill',          '单闸熔断(高敏·止血·即时拒绝下游)', 'API', '/emergency/kill-switch', 'HIGH', 1, 1, 0),
  ('emergency_j1_gate_resume',        '单闸恢复(高敏·止血·放大流出闸前置B1备付金)', 'API', '/emergency/kill-switch', 'HIGH', 1, 1, 0),
  ('emergency_j1_batch_kill',         '应急批量熔断(高敏·emergency=true·监管点名)', 'API', '/emergency/kill-switch', 'HIGH', 1, 1, 0),

  -- J2 Geo-block（5 点）
  ('emergency_j2_read',               'Geo-block-读',                  'API', '/emergency/geo-block',   'READ',  0, 1, 0),
  ('emergency_j2_write',              'Geo-block-写(endpoint屏蔽编辑·配置类)', 'API', '/emergency/geo-block', 'WRITE', 0, 1, 0),
  ('emergency_j2_edge_source_manage', '边缘IP判定源切换(仅超管)',      'API', '/emergency/geo-block', 'HIGH', 0, 1, 0),
  ('emergency_j2_country_manage',     '三态名单操作(高敏·黑名单/受限加入·解封·涉资金冻结)', 'API', '/emergency/geo-block', 'HIGH', 1, 1, 0),
  ('emergency_j2_emergency_block',    '应急即时封锁(高敏·批量·emergency=true·仅加不减)', 'API', '/emergency/geo-block', 'HIGH', 1, 1, 0),

  -- J3 篡改防御监控（3 点）
  ('emergency_j3_read',               '篡改防御监控-读',               'API', '/emergency/tamper',      'READ',  0, 1, 0),
  ('emergency_j3_export',             '篡改防御监控-脱敏导出',         'API', '/emergency/tamper',      'READ',  0, 1, 0),
  ('emergency_j3_alert_config',       '篡改告警阈值配置(高敏·操作确认·影响K4风险评分)', 'API', '/emergency/tamper', 'HIGH', 0, 1, 0),

  -- J4 监管点名应急 SOP（3 点）
  ('emergency_j4_read',               '监管点名应急SOP-读',            'API', '/emergency/sop',         'READ',  0, 1, 0),
  ('emergency_j4_write',              '监管点名应急SOP-写(新增/编辑剧本/演练·沙箱非实战)', 'API', '/emergency/sop', 'WRITE', 0, 1, 0),
  ('emergency_j4_playbook_execute',   '剧本执行/应急执行(高敏·止血·应急轨SLA压缩·emergency=true)', 'API', '/emergency/sop', 'HIGH', 1, 1, 0)

ON DUPLICATE KEY UPDATE
  permission_name = VALUES(permission_name),
  resource_type   = VALUES(resource_type),
  resource_path   = VALUES(resource_path),
  perm_type       = VALUES(perm_type),
  amplifies       = VALUES(amplifies),
  status          = 1,
  is_deleted      = 0;

-- ===== 统计 =====
-- 39 点 = 12 READ + 11 WRITE + 16 HIGH
-- I 域 23 点（7 页）：7 READ + 8 WRITE + 8 HIGH
-- J 域 16 点（4 页）：5 READ + 3 WRITE + 8 HIGH
-- amplifies=1 共 8 个：
--   I 域 2：content_i5_gate_adjust / content_i7_course_reward_adjust
--   J 域 6：emergency_j1_gate_kill / _gate_resume / _batch_kill /
--           emergency_j2_country_manage / _emergency_block /
--           emergency_j4_playbook_execute
-- amplifies=0 高敏 8 个：content_i1_experiment_manage / content_i3_cap_adjust / content_i3_critical_send /
--                        content_i4_publish_standard / content_i4_trust_section_manage /
--                        content_i5_disclosure_publish / emergency_j2_edge_source_manage / emergency_j3_alert_config
