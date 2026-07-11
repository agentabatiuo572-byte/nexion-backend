-- 域 K+L+M · 风控+BI+客服 · 54 权限点
-- 源：docs/superpowers/specs/rbac-classic/KLM.md（收敛自 rbac-matrix/KLM.md 147 按钮级）
-- 幂等：ON DUPLICATE KEY UPDATE。手动执行（schema.sql 不自动跑，见 schema-manual-init 记忆）：
--   mysql -uroot -p nexion < scripts/rbac-classic-seed/KLM.sql
-- 前置：00-permission-alter.sql（perm_type/amplifies 字段）
-- amplifies=1 共 21 个（K 高敏 16 + L 敏感导出/监管 5）；M 客服全 write=0；K/L read/write=0
-- 不含 role_permission（后续统一处理）；不含 menu_id（后续 A8 字典挂菜单时回填）

INSERT INTO nx_admin_permission (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted) VALUES
  -- ===== K 域 · 风控与反作弊（slug=risk）6 页 28 点 =====

  -- K1 反多账户引擎（6 点）
  ('risk_k1_read',                 '反多账户引擎-读(命中簇/阈值参数/IP白名单)',            'API', '/risk/multi-account',     'READ',  0, 1, 0),
  ('risk_k1_write',                '反多账户引擎-常规写(复审备注/4阈值/3权重/白名单增删)', 'API', '/risk/multi-account',     'WRITE', 0, 1, 0),
  ('risk_k1_cluster_freeze',       '批量冻结关联账户(高敏·止血·冻结簇)',                  'API', '/risk/multi-account',     'HIGH',  1, 1, 0),
  ('risk_k1_cluster_release',      '解除误判(高敏·amplifies·放大资金流出)',               'API', '/risk/multi-account',     'HIGH',  1, 1, 0),
  ('risk_k1_cluster_cleared',      '判定正常(高敏·移出监控·放大)',                        'API', '/risk/multi-account',     'HIGH',  1, 1, 0),
  ('risk_k1_cluster_flag',         '标可疑账户簇(高敏·进风险评分/雷达)',                  'API', '/risk/multi-account',     'HIGH',  1, 1, 0),

  -- K2 套利 & 刷量检测（6 点）
  ('risk_k2_read',                 '套利刷量检测-读(命中列表/阈值参数)',                  'API', '/risk/abuse',             'READ',  0, 1, 0),
  ('risk_k2_write',                '套利刷量检测-常规写(3阈值·试用/新人礼/刷榜)',         'API', '/risk/abuse',             'WRITE', 0, 1, 0),
  ('risk_k2_row_freeze',           '联动K1冻结(高敏·止血·复用冻结链路)',                  'API', '/risk/abuse',             'HIGH',  1, 1, 0),
  ('risk_k2_row_flag',             '标记套利账户(高敏·风控处置·进评分)',                  'API', '/risk/abuse',             'HIGH',  1, 1, 0),
  ('risk_k2_row_blockgift',        '拦截新人礼(高敏·止血·预防性阻断)',                    'API', '/risk/abuse',             'HIGH',  1, 1, 0),
  ('risk_k2_row_boardflag',        '标记刷榜账户(高敏·风控处置·产信号)',                  'API', '/risk/abuse',             'HIGH',  1, 1, 0),

  -- K3 提现风控规则引擎（5 点）
  ('risk_k3_read',                 '提现风控规则-读(维度/规则总表/路由/命中日志)',         'API', '/risk/withdrawal-rules',  'READ',  0, 1, 0),
  ('risk_k3_write',                '提现风控规则-常规写(4维阈值调整/沙盒模拟)',            'API', '/risk/withdrawal-rules',  'WRITE', 0, 1, 0),
  ('risk_k3_rule_create',          '新建提现规则(高敏·资金·喂D2放行决策)',                'API', '/risk/withdrawal-rules',  'HIGH',  1, 1, 0),
  ('risk_k3_rule_toggle',          '规则启停(高敏·合并启用/停用/提交生效·影响每笔提现)',   'API', '/risk/withdrawal-rules',  'HIGH',  1, 1, 0),
  ('risk_k3_rule_archive',         '归档规则(高敏·不可逆·终态不可再启用)',                'API', '/risk/withdrawal-rules',  'HIGH',  1, 1, 0),

  -- K4 风险评分模型（4 点）
  ('risk_k4_read',                 '风险评分模型-读(概览/维度权重/分档/查询/覆盖记录)',    'API', '/risk/scoring',           'READ',  0, 1, 0),
  ('risk_k4_write',                '风险评分模型-常规写(6维权重/分档线低高/升级线/维度开关)', 'API', '/risk/scoring',         'WRITE', 0, 1, 0),
  ('risk_k4_user_override',        '单用户人工覆盖评分(高敏·强制理由·影响提现路由)',      'API', '/risk/scoring',           'HIGH',  1, 1, 0),
  ('risk_k4_user_recompute',       '重算回模型分(高敏·丢弃覆盖值·还原模型)',              'API', '/risk/scoring',           'HIGH',  1, 1, 0),

  -- K5 大额 KYC 复审 & 告警（5 点）
  ('risk_k5_read',                 '大额KYC复审-读(队列/触发线/工单详情/告警)',           'API', '/risk/kyc-review',        'READ',  0, 1, 0),
  ('risk_k5_write',                '大额KYC复审-常规写(4触发线/告警订阅)',                'API', '/risk/kyc-review',        'WRITE', 0, 1, 0),
  ('risk_k5_ticket_pass',          '通过KYC复审(高敏·amplifies·回写实名+冻结单据)',       'API', '/risk/kyc-review',        'HIGH',  1, 1, 0),
  ('risk_k5_ticket_reject',        '驳回KYC复审(高敏·影响提现放行·维持冻结)',             'API', '/risk/kyc-review',        'HIGH',  1, 1, 0),
  ('risk_k5_ticket_manual',        '手动补触发复审(高敏·强制触发工单·阻断提现)',           'API', '/risk/kyc-review',        'HIGH',  1, 1, 0),

  -- K6 Janus C2 控制台（2 点）
  ('risk_k6_read',  'Janus C2控制台-读(规则编排/命中日志/设备指纹簇)',                'API', '/risk/janus-c2', 'READ',  0, 1, 0),
  ('risk_k6_write', 'Janus C2-写(策略下发/规则热更新·高敏·amplifies·影响实时拦截)', 'API', '/risk/janus-c2', 'HIGH', 1, 1, 0),

  -- ===== L 域 · 数据与分析 BI（slug=bi）6 页 16 点 =====

  -- L1 KPI 看板（2 点）
  ('bi_l1_read',                   'KPI看板-读(8项KPI序列/目标/环比)',                    'API', '/analytics/kpi',          'READ',  0, 1, 0),
  ('bi_l1_write',                  'KPI看板-导出CSV(聚合·无PII·落审计)',                  'API', '/analytics/kpi',          'WRITE', 0, 1, 0),

  -- L2 漏斗/Cohort/留存（2 点）
  ('bi_l2_read',                   '漏斗cohort留存-读(五级漏斗/留存矩阵/多维交叉)',        'API', '/analytics/funnel-cohort','READ',  0, 1, 0),
  ('bi_l2_write',                  '漏斗cohort-导出CSV(聚合·无PII)',                      'API', '/analytics/funnel-cohort','WRITE', 0, 1, 0),

  -- L3 财务报表（3 点）
  ('bi_l3_read',                   '财务报表-读(收入/兑付/敞口/负债到期)',                 'API', '/analytics/financial',    'READ',  0, 1, 0),
  ('bi_l3_write',                  '财务报表-聚合汇总导出CSV(无用户明细)',                 'API', '/analytics/financial',    'WRITE', 0, 1, 0),
  ('bi_l3_export_detail',          '导出含资金明细(高敏·PII敏感·操作确认·财务→超管)',     'API', '/analytics/financial',    'HIGH',  1, 1, 0),

  -- L4 设备/任务/网络报表（3 点）
  ('bi_l4_read',                   '运营报表-读(设备/任务/算力/网络团队)',                'API', '/analytics/operations',   'READ',  0, 1, 0),
  ('bi_l4_write',                  '运营报表-聚合导出CSV(无用户明细)',                    'API', '/analytics/operations',   'WRITE', 0, 1, 0),
  ('bi_l4_export_tree',            '导出网络团队结构明细(高敏·PII敏感·userId维度·操作确认)', 'API', '/analytics/operations', 'HIGH',  1, 1, 0),

  -- L5 导出 & 监管报告（5 点）
  ('bi_l5_read',                   '导出监管报告-读(任务/安全参数/模板/排程/审计/脱敏规则)', 'API', '/analytics/export',     'READ',  0, 1, 0),
  ('bi_l5_write',                  '导出-常规写(发起/重试/下载/5安全参数/排程/新建模板)',  'API', '/analytics/export',       'WRITE', 0, 1, 0),
  ('bi_l5_task_approve',           '操作确认放行/执行门槛(高敏·不可逆·数据出境·超限拆分)', 'API', '/analytics/export',       'HIGH',  1, 1, 0),
  ('bi_l5_decrypt_export',         '解密导出(高敏·最高敏感档·强操作确认+强制事由·PII明文)','API', '/analytics/export',       'HIGH',  1, 1, 0),
  ('bi_l5_regulatory_generate',    '生成监管报告(高敏·合并4模板KYC/资金兑付/AML/辖区·风控→超管)', 'API', '/analytics/export',  'HIGH',  1, 1, 0),

  -- L6 用户行为热力图（1 点）
  ('bi_l6_read',                   '用户行为热力图-读(纯只读看板)',                       'API', '/analytics/behavior-heatmap', 'READ', 0, 1, 0),

  -- ===== M 域 · 客服中心（slug=service）5 页 10 点 =====

  -- M1 客服总览（2 点）
  ('service_m1_read',              '客服总览-读(4KPI/SLA监控/坐席负载)',                  'API', '/service/overview',       'READ',  0, 1, 0),
  ('service_m1_write',             '客服总览-负载调度(全局策略/坐席上限/接派单/手动均衡)', 'API', '/service/overview',       'WRITE', 0, 1, 0),

  -- M2 工单台（2 点）
  ('service_m2_read',              '工单台-读(队列/分类/优先级/状态/搜索/分页)',           'API', '/service/tickets',        'READ',  0, 1, 0),
  ('service_m2_write',             '工单台-常规写(回复/状态流转/关闭/重开/优先级/转交/升级会话)', 'API', '/service/tickets',  'WRITE', 0, 1, 0),

  -- M3 即时会话台（2 点）
  ('service_m3_read',              '即时会话台-读(收件箱/客户档案)',                      'API', '/service/sessions',       'READ',  0, 1, 0),
  ('service_m3_write',             '即时会话台-常规写(回复/转交/接收/退回/推送/归档/备注/标签/主动发起/转工单)', 'API', '/service/sessions', 'WRITE', 0, 1, 0),

  -- M4 知识库与 SLA（2 点）
  ('service_m4_read',              '知识库与SLA-读(FAQ内容池/SLA矩阵)',                  'API', '/service/kb-sla',         'READ',  0, 1, 0),
  ('service_m4_write',             '知识库与SLA-写(FAQ新增/发布/编辑/SLA编辑)',           'API', '/service/kb-sla',         'WRITE', 0, 1, 0),

  -- M5 话术与模板配置（2 点）
  ('service_m5_read',              '话术模板配置-读(会话类别/推送策略/话术/模板)',         'API', '/service/scripts',        'READ',  0, 1, 0),
  ('service_m5_write',             '话术模板配置-写(类别启停/推送开关/延迟/冷却/上限/受众/话术发布/新增/模板)', 'API', '/service/scripts', 'WRITE', 0, 1, 0)

ON DUPLICATE KEY UPDATE
  permission_name = VALUES(permission_name),
  resource_path   = VALUES(resource_path),
  perm_type       = VALUES(perm_type),
  amplifies       = VALUES(amplifies),
  status          = 1,
  is_deleted      = 0;
