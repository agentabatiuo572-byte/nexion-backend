-- role_permission 绑定（角色×权限，域级规则）
-- 规则：超管=全部；审计=所有 READ 类型；各域主操作角色=该域全部(READ/WRITE/HIGH)；B/L 域全角色。
-- 按 permission_code 前缀（域 slug）+ perm_type 匹配。幂等 INSERT IGNORE。
-- 依赖：00-alter + 01-menu + 各域权限 seed(AB/C/D/EF/GH/IJ/KLM.sql) 已执行。

-- 超管：全部权限
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p WHERE r.role_code='SUPER_ADMIN';

-- 审计：所有 READ 类型（只读，全域）
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='AUDITOR' AND p.perm_type='READ' AND p.status=1 AND p.is_deleted=0;

-- A 域(platform_)：config 延伸→仅超管(上面已给)+审计读(上面已给)。运营账号治理高敏不放开。
-- 风控仅补 A2 读和“按自身业务权限创建提案”；业务指令仍由 AuditReplayBusinessPermissionGuard 二次校验。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='RISK'
  AND p.permission_code IN ('platform_a2_read', 'platform_a2_proposal_create')
  AND p.status=1 AND p.is_deleted=0;
-- B 域通用看板（B1/B2 由下方精确矩阵接管）
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'overview_%'
WHERE r.role_code IN ('FINANCE','RISK','CONTENT','GROWTH','SUPPORT','CONFIG_ADMIN')
  AND p.permission_code NOT LIKE 'overview_b1_%'
  AND p.permission_code NOT LIKE 'overview_b2_%'
  AND p.permission_code NOT IN ('overview_b3_view_write','overview_b3_export')
  AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('GROWTH','FINANCE')
  AND p.permission_code='overview_b3_view_write'
  AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('GROWTH','FINANCE')
  AND p.permission_code='overview_b3_export'
  AND p.status=1 AND p.is_deleted=0;
-- B1 精确职责矩阵：财务/风控/增长/审计可读；告警确认与红黄线仅财务主管/超管。
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE r.role_code <> 'SUPER_ADMIN' AND p.permission_code LIKE 'overview_b1_%';
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK','GROWTH','AUDITOR')
  AND p.permission_code='overview_b1_read' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE_LEAD'
  AND p.permission_code IN ('overview_b1_write','overview_b1_redline_write')
  AND p.status=1 AND p.is_deleted=0;
-- B2 精确职责矩阵：查看覆盖财务/风控/增长/审计；导出排除增长；口径写入仅财务主管/超管。
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE r.role_code <> 'SUPER_ADMIN' AND p.permission_code LIKE 'overview_b2_%';
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK','GROWTH','AUDITOR')
  AND p.permission_code='overview_b2_read' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND p.permission_code='overview_b2_export' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE_LEAD'
  AND p.permission_code='overview_b2_write' AND p.status=1 AND p.is_deleted=0;
-- C 域(user_)：风控
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'user_%'
WHERE r.role_code='RISK'
  AND (p.permission_code NOT LIKE 'user_c3_%' OR p.permission_code='user_c3_read')
  AND p.status=1 AND p.is_deleted=0;

-- C3 exact matrix: view is cross-functional; execution and reversal follow the amount boundary.
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE r.role_code <> 'SUPER_ADMIN'
  AND p.permission_code IN ('user_c3_write','user_c3_adjust_create','user_c3_adjust_approve','user_c3_adjust_reverse');
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK','SUPPORT','AUDITOR')
  AND p.permission_code='user_c3_read' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD')
  AND p.permission_code='platform_a4_read' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','SUPPORT')
  AND p.permission_code='user_c3_adjust_create' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE_LEAD'
  AND p.permission_code IN ('user_c3_adjust_approve','user_c3_adjust_reverse')
  AND p.status=1 AND p.is_deleted=0;

-- 财务主管继承财务权限；大额边界仍由服务端角色码二次校验。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT lead_role.id, rp.permission_id
FROM nx_admin_role lead_role
JOIN nx_admin_role finance_role ON finance_role.role_code='FINANCE' AND finance_role.is_deleted=0
JOIN nx_admin_role_permission rp ON rp.role_id=finance_role.id AND rp.is_deleted=0
WHERE lead_role.role_code='FINANCE_LEAD' AND lead_role.is_deleted=0;
-- C1 跨职能最小授权：财务看资金/KYC，增长看分层并可导出脱敏名单，审计可导出脱敏取证名单。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE'
  AND p.permission_code IN ('user_c1_read','user_c1hub_read')
  AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='GROWTH'
  AND p.permission_code IN ('user_c1_read','user_c1_write','user_c1hub_read')
  AND p.status=1 AND p.is_deleted=0;
-- C6 增长只读：只授页面读，不复用 C6 常规写权限。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='GROWTH'
  AND p.permission_code='user_c6_read'
  AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='AUDITOR'
  AND p.permission_code='user_c1_write'
  AND p.status=1 AND p.is_deleted=0;
-- D 域(finance_)：财务+风控
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'finance_%'
WHERE r.role_code IN ('FINANCE','RISK') AND p.status=1 AND p.is_deleted=0;
-- D1 精确职责矩阵：风险只能管风险锁；财务主管负责渠道、参数、核销和拒付；PSP 切换仅超管。
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND p.permission_code LIKE 'finance_d1_%';
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPER_ADMIN'
  AND p.permission_code LIKE 'finance_d1_%' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND p.permission_code='finance_d1_read' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE_LEAD'
  AND p.permission_code IN ('finance_d1_channel_manage','finance_d1_config_manage','finance_d1_reconcile',
                            'finance_d1_chargeback_refund','finance_d1_bin_manual_lock','finance_d1_bin_lock','finance_d1_bin_unlock')
  AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='RISK'
  AND p.permission_code IN ('finance_d1_bin_manual_lock','finance_d1_bin_lock','finance_d1_bin_unlock')
  AND p.status=1 AND p.is_deleted=0;
-- D2 精确职责矩阵：审计只读；普通财务不可冻结/退款；普通风控不可放行/解冻。
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND p.permission_code LIKE 'finance_d2_%';
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPER_ADMIN' AND p.permission_code LIKE 'finance_d2_%'
  AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND p.permission_code='finance_d2_read' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD')
  AND p.permission_code='finance_d2_withdrawal_approve' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK')
  AND p.permission_code IN ('finance_d2_withdrawal_delay','finance_d2_withdrawal_reject','finance_d2_withdrawal_batch')
  AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE_LEAD'
  AND p.permission_code IN ('finance_d2_withdrawal_freeze','finance_d2_withdrawal_unfreeze','finance_d2_withdrawal_refund')
  AND p.status=1 AND p.is_deleted=0;
-- D3：查看覆盖相关岗位；导出不授予增长；结构化口径与储备注入仅财务主管/超管。
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code LIKE 'finance_d3_%';
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','GROWTH','AUDITOR')
  AND p.permission_code='finance_d3_read' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND p.permission_code='finance_d3_export' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE_LEAD')
  AND p.permission_code IN ('finance_d3_write','finance_d3_injection_create')
  AND p.status=1 AND p.is_deleted=0;
-- D4：全平台查询与脱敏导出分权；客服只能按用户核对；任何角色都没有 D4 写权限。
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code LIKE 'finance_d4_%';
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND p.permission_code='finance_d4_read' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR','SUPPORT')
  AND p.permission_code='finance_d4_user_read' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','AUDITOR')
  AND p.permission_code='finance_d4_export' AND p.status=1 AND p.is_deleted=0;
-- D5：查看覆盖财务/风控/增长/审计；写入仅财务主管/超管。
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code LIKE 'finance_d5_%';
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','GROWTH','AUDITOR')
  AND p.permission_code='finance_d5_read' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE_LEAD')
  AND p.permission_code IN ('finance_d5_daily_limit_write','finance_d5_balance_max_write','finance_d5_fee_write')
  AND p.status=1 AND p.is_deleted=0;
-- D5 到 H1 的只读跳转必须可达。
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK') AND p.permission_code='growth_h1_read'
  AND p.status=1 AND p.is_deleted=0;
-- E 域(device_)：增长
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'device_%'
WHERE r.role_code='GROWTH' AND p.status=1 AND p.is_deleted=0;
-- F 域(network_)：增长
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'network_%'
WHERE r.role_code='GROWTH' AND p.status=1 AND p.is_deleted=0;

-- Sprint5 F1 V-Rank 评估/手动晋升回滚(network_f1_promote_user,HIGH)显式授权 SUPER_ADMIN/GROWTH,
-- 即使上面 LIKE 'network_%' 通配已覆盖,这里显式补一行表达意图(防 LIKE 通配被改时静默丢失)。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','GROWTH')
  AND p.permission_code='network_f1_promote_user'
  AND p.status=1 AND p.is_deleted=0;

-- Sprint6 F1 V-Rank 派发补发/撤销(network_f1_reward_reissue/reverse,HIGH)显式授权 SUPER_ADMIN/GROWTH,
-- 防范 LIKE 'network_%' 通配被改时静默丢失。补发/撤销为 F1-MD4 资金类高风险动作,显式记录意图。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','GROWTH')
  AND p.permission_code IN ('network_f1_reward_reissue','network_f1_reward_reverse')
  AND p.status=1 AND p.is_deleted=0;
-- G 域(finprod_)：财务+增长
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'finprod_%'
WHERE r.role_code IN ('FINANCE','GROWTH') AND p.status=1 AND p.is_deleted=0;
-- H 域(growth_)：增长
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'growth_%'
WHERE r.role_code='GROWTH' AND p.permission_code <> 'growth_h8_settle'
  AND p.status=1 AND p.is_deleted=0;
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id AND r.role_code='GROWTH'
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='growth_h8_settle';
-- I 域(content_)：内容
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'content_%'
WHERE r.role_code='CONTENT' AND p.status=1 AND p.is_deleted=0;
-- I3 critical 合规/监管通知同时授予风控执行权；CAP 调整权限不再代替发送权限。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code='content_i3_critical_send'
WHERE r.role_code='RISK' AND p.status=1 AND p.is_deleted=0;
-- J 域(emergency_)：风控负责止血，但 J1 恢复/参数配置、J2 边缘源、J3 告警配置仅超管。
-- J1 单闸熔断另授财务；查看矩阵授财务。边界以 PRD v4 §J1⑥ 为准。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'emergency_%'
WHERE r.role_code='RISK'
  AND p.permission_code NOT IN ('emergency_j1_write', 'emergency_j1_gate_resume', 'emergency_j2_edge_source_manage', 'emergency_j3_alert_config')
  AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE'
  AND p.permission_code IN ('emergency_j1_read', 'emergency_j1_gate_kill')
  AND p.status=1 AND p.is_deleted=0;
-- K 域(risk_)：风控。K4 尚无可持久识别的 lead/member 层级，模型起草和人工覆盖先仅超管。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'risk_%'
WHERE r.role_code='RISK'
  AND p.permission_code NOT IN ('risk_k4_write','risk_k4_user_override')
  AND p.status=1 AND p.is_deleted=0;
-- SUPPORT 仅可查看 K5 工单、统计与告警，不授予任何 K5 写权限。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code='risk_k5_read'
WHERE r.role_code='SUPPORT' AND p.status=1 AND p.is_deleted=0;
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id AND r.role_code='RISK'
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code IN ('risk_k4_write','risk_k4_user_override');
-- L 域(bi_)：按职能最小授权；先清理旧版“所有业务角色拥有全部 BI 高敏权限”的宽授权。
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE r.role_code IN ('AUDITOR','FINANCE','RISK','CONTENT','GROWTH','SUPPORT','CONFIG_ADMIN')
  AND p.permission_code LIKE 'bi_%';

-- 财务：L3 聚合与敏感明细发起；L5 只读查看任务。高敏放行仍由超管/风控确认门执行。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE'
  AND p.permission_code IN ('bi_l3_read','bi_l3_write','bi_l3_export_detail','bi_l5_read')
  AND p.status=1 AND p.is_deleted=0;

-- 增长：经营、漏斗、运营聚合、行为热力只读与团队树发起；不授予财务明细或监管高敏权限。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='GROWTH'
  AND p.permission_code IN ('bi_l1_read','bi_l1_write','bi_l2_read','bi_l2_write','bi_l4_read','bi_l4_write','bi_l4_export_tree','bi_l5_read','bi_l6_read')
  AND p.status=1 AND p.is_deleted=0;

-- 风控：只读查看 L4/L6 运营与行为事实及财务/监管报表，并执行敏感任务放行/监管生成；不发起 L4 或财务明细导出。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='RISK'
  AND p.permission_code IN ('bi_l3_read','bi_l4_read','bi_l5_read','bi_l5_task_approve','bi_l5_regulatory_generate','bi_l6_read')
  AND p.status=1 AND p.is_deleted=0;

-- 只读审计：全域只读，可生成聚合报表并按 L4 合同发起团队树；不授予放行或解密权限。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='AUDITOR'
  AND p.permission_code IN ('bi_l1_read','bi_l1_write','bi_l2_read','bi_l2_write','bi_l3_read','bi_l3_write',
                            'bi_l4_read','bi_l4_write','bi_l4_export_tree','bi_l5_read','bi_l6_read')
  AND p.status=1 AND p.is_deleted=0;
-- M 域(service_)：客服+风控。M5 配置写仅授客服角色，并由服务层按客服主管二次校验；风控只读。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'service_%'
WHERE r.role_code IN ('SUPPORT','RISK')
  AND (r.role_code <> 'RISK' OR p.permission_code <> 'service_m5_write')
  AND p.status=1 AND p.is_deleted=0;
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id AND r.role_code='RISK'
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='service_m5_write';

-- SUPPORT 补充：C/E/I 域只读（客服查用户画像/设备状态/内容，辅助工单；不授予 write/high）
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPPORT'
  AND p.perm_type='READ' AND p.status=1 AND p.is_deleted=0
  AND (p.permission_code LIKE 'user_%' OR p.permission_code LIKE 'device_%' OR p.permission_code LIKE 'content_%');

-- C5 精确客服授权：可强制下线指定/全部会话并解除短锁；不授常规写、重置密码、关闭 2FA 或长锁权限。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPPORT'
  AND p.permission_code IN ('user_c5_session_revoke_one','user_c5_session_revoke_all')
  AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPPORT'
  AND p.permission_code='user_c5_unlock_short'
  AND p.status=1 AND p.is_deleted=0;
