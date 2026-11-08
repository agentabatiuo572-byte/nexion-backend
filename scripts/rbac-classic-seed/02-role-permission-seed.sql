-- role_permission 绑定（角色×权限，域级规则）
-- 经典权限规则：超管=全部；审计=READ；各域主操作角色=该域权限；B/L 域全角色。
-- growth_promotion_：超管拥有全部；增长只默认授予 D10 的七个码。
-- 按 permission_code 前缀（域 slug）+ perm_type 匹配。幂等 INSERT IGNORE。
-- 依赖：00-alter + 01-menu + 各域权限 seed(AB/C/D/EF/GH/IJ/KLM.sql) 已执行。

INSERT INTO nx_admin_permission
  (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES ('user_c1_export', '导出脱敏用户清单', 'API', '/users/search', 'READ', 0, 1, 0)
ON DUPLICATE KEY UPDATE status = 1, is_deleted = 0;

-- 超管：全部权限
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p WHERE r.role_code='SUPER_ADMIN'
  AND LEFT(p.permission_code,17) <> 'growth_promotion_'
  AND (p.permission_code <> 'overview_b0_export' OR (r.status=1 AND r.is_deleted=0));

-- H4 新促销：补齐超管细项，并恢复此前被软删除的超管绑定。
INSERT INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM nx_admin_role r
JOIN nx_admin_permission p ON LEFT(p.permission_code, 17) = 'growth_promotion_'
WHERE r.role_code = 'SUPER_ADMIN' AND r.status = 1 AND r.is_deleted = 0
  AND p.resource_type = 'API' AND p.status = 1 AND p.is_deleted = 0
ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW();

-- 审计：所有 READ 类型（只读，全域）
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='AUDITOR' AND p.perm_type='READ' AND p.status=1 AND p.is_deleted=0
  AND LEFT(p.permission_code,17) <> 'growth_promotion_'
  AND p.permission_code <> 'overview_b0_export'
  AND p.permission_code NOT IN (
      'bi_l1_write', 'bi_l2_write', 'bi_l3_write',
      'bi_l4_export_tree', 'bi_l4_write', 'finance_d3_export',
      'overview_b3_export', 'overview_b4_export', 'user_c1_write'
  );

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
WHERE r.role_code IN ('FINANCE','RISK','CONTENT','GROWTH','CONFIG_ADMIN')
  AND p.permission_code NOT LIKE 'overview_b1_%'
  AND p.permission_code NOT LIKE 'overview_b2_%'
  AND p.permission_code <> 'overview_b0_export'
  AND p.permission_code NOT IN ('overview_b3_view_write','overview_b3_export',
                                'overview_b4_export','overview_b5_threshold_write')
  AND (r.role_code NOT IN ('CONFIG_ADMIN','CONTENT','GROWTH')
       OR p.permission_code NOT IN ('overview_b5_subscribe','overview_b5_triage'))
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
  AND p.permission_code NOT IN (
      'user_c5_phone_replacement_exception_grant', 'user_c5_phone_replacement_exception_revoke', 'user_c1_export',
      'user_c1hub_2fa_reset', 'user_c1hub_account_freeze', 'user_c1hub_account_unfreeze',
      'user_c1hub_compensation_grant', 'user_c1hub_device_recycle', 'user_c1hub_device_replace',
      'user_c1hub_earning_grant', 'user_c1hub_earning_reverse', 'user_c1hub_password_force_change',
      'user_c1hub_password_reset', 'user_c1hub_session_revoke_all', 'user_c1hub_session_revoke_one'
  )
  AND p.status=1 AND p.is_deleted=0;

-- C3 exact matrix: view is cross-functional; execution and reversal follow the amount boundary.
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE (r.role_code <> 'SUPER_ADMIN'
       AND p.permission_code IN ('user_c3_write','user_c3_adjust_create','user_c3_adjust_approve','user_c3_adjust_reverse'))
   OR (r.role_code='SUPPORT' AND LEFT(p.permission_code,8)='user_c3_');
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND p.permission_code='user_c3_read' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD')
  AND p.permission_code='platform_a4_read' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD')
  AND p.permission_code='user_c3_adjust_create' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE_LEAD'
  AND p.permission_code IN ('user_c3_adjust_approve','user_c3_adjust_reverse')
  AND p.status=1 AND p.is_deleted=0;

-- These documented FINANCE_LEAD grants used to arrive through FINANCE inheritance on repeat startup.
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE_LEAD' AND p.status=1 AND p.is_deleted=0
  AND p.permission_code IN (
      'finprod_g3_engine_pause_toggle', 'finprod_g3_override_price_write', 'finprod_g4_dividend_rate_write',
      'bi_l3_export_detail', 'finprod_g2_cap_per_tx_write', 'finprod_g2_cap_platform_write',
      'finprod_g2_cap_user_write', 'finprod_g2_fee_rate_write', 'finprod_g2_queue_cancel',
      'finprod_g2_swap_toggle', 'finprod_g3_curve_pump_prob_write', 'finprod_g3_curve_target_price_write',
      'finprod_g3_write', 'finprod_g7_apy_write', 'finprod_g7_nurture_write',
      'finprod_g7_write'
  );

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD') AND p.permission_code='platform_a2_proposal_create'
  AND p.status=1 AND p.is_deleted=0;
-- C1 跨职能最小授权：财务看资金，增长看分层并可导出脱敏名单，审计可导出脱敏取证名单。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE'
  AND p.permission_code IN ('user_c1_read','user_c1hub_read')
  AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='GROWTH'
  AND p.permission_code IN ('user_c1_read','user_c1_export','user_c1hub_read')
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
  AND p.permission_code='user_c1_export'
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
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK')
  AND p.permission_code='finance_d3_export' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE_LEAD')
  AND p.permission_code IN ('finance_d3_write','finance_d3_injection_create')
  AND p.status=1 AND p.is_deleted=0;
-- D4：全平台查询与脱敏导出分权；任何角色都没有 D4 写权限。
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
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND p.permission_code='finance_d4_user_read' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE_LEAD','AUDITOR')
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
-- D7：参数读取覆盖财务/风控/审计；参数写仅财务主管；通道与倒挂强制仅超管。
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code LIKE 'finance_d7_%';
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND p.permission_code='finance_d7_read' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE_LEAD')
  AND p.permission_code='finance_d7_manage' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPER_ADMIN'
  AND p.permission_code IN ('finance_d7_channel_toggle','finance_d7_force_inverted')
  AND p.status=1 AND p.is_deleted=0;
-- E 域(device_)：增长
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'device_%'
WHERE r.role_code='GROWTH' AND p.status=1 AND p.is_deleted=0
  AND p.permission_code NOT IN (
      'device_e4_order_refund', 'device_e1_generation_gate_force_lock', 'device_e1_generation_gate_force_unlock',
      'device_e2_phone_tier_nex', 'device_e2_phone_tier_usdt', 'device_e2_write',
      'device_e6_flag_toggle'
  );
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE_LEAD' AND p.status=1 AND p.is_deleted=0
  AND p.permission_code IN ('device_e4_read','device_e4_order_refund');
-- F 域(network_)：增长；F5 佣金资金动作不归增长，见下方 F5 职责矩阵。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'network_%'
WHERE r.role_code='GROWTH' AND LEFT(p.permission_code,22) <> 'network_f5_commission_'
  AND p.permission_code NOT IN (
      'network_f1_promote_user', 'network_f1_reward_reissue', 'network_f1_reward_reverse',
      'network_f3_engine_pause', 'network_f4_ambassador_approve', 'network_f4_pool_fund',
      'network_f4_ambassador_material_review', 'network_f4_ambassador_finance_review',
      'network_f1_permanent_protection', 'network_f2_policy_amplify', 'network_f2_royalty_rate',
      'network_f2_write', 'network_f3_match_rate', 'network_f3_write'
  )
  AND p.status=1 AND p.is_deleted=0;
-- F5 职责矩阵：财务/风控/审计可读；冻结解冻归风控主管与财务主管，暂停归风控主管，
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id,p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE_LEAD' AND p.permission_code IN ('network_f4_read','network_f4_ambassador_finance_review')
  AND p.status=1 AND p.is_deleted=0;
-- 放钱（解锁/支付、补发）与冲正归财务主管；异常阈值归增长与风控主管。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK','RISK_LEAD')
  AND p.permission_code='network_f5_read' AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='RISK_LEAD'
  AND p.permission_code IN ('network_f5_write','network_f5_commission_freeze','network_f5_commission_suspend')
  AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE_LEAD'
  AND p.permission_code IN ('network_f5_commission_freeze','network_f5_commission_release',
                            'network_f5_commission_reverse','network_f5_commission_reissue')
  AND p.status=1 AND p.is_deleted=0;

-- F1 手动晋升及资金补发/撤销保留超管权限。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPER_ADMIN'
  AND p.permission_code='network_f1_promote_user'
  AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPER_ADMIN'
  AND p.permission_code IN ('network_f1_reward_reissue','network_f1_reward_reverse')
  AND p.status=1 AND p.is_deleted=0;
-- G 域(finprod_)：财务+增长
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'finprod_%'
WHERE r.role_code IN ('FINANCE','GROWTH') AND p.status=1 AND p.is_deleted=0
  AND (r.role_code <> 'FINANCE' OR p.permission_code NOT IN (
      'finprod_g3_engine_pause_toggle', 'finprod_g3_override_price_write', 'finprod_g4_dividend_rate_write',
      'finprod_g1_apy_write', 'finprod_g1_kill_toggle', 'finprod_g1_min_write',
      'finprod_g1_penalty_write', 'finprod_g1_write', 'finprod_g2_cap_per_tx_write',
      'finprod_g2_cap_platform_write', 'finprod_g2_cap_user_write', 'finprod_g2_fee_rate_write',
      'finprod_g2_queue_cancel', 'finprod_g2_swap_toggle', 'finprod_g3_curve_pump_prob_write',
      'finprod_g3_curve_target_price_write', 'finprod_g3_write', 'finprod_g4_airdrop_lock_days_write',
      'finprod_g4_airdrop_pct_write', 'finprod_g4_emission_curve_write', 'finprod_g4_market_toggle',
      'finprod_g4_price_write', 'finprod_g4_royalty_write', 'finprod_g4_write',
      'finprod_g7_apy_write', 'finprod_g7_nurture_write', 'finprod_g7_write'
  ))
  AND (r.role_code <> 'GROWTH' OR p.permission_code NOT IN (
      'finprod_g1_apy_write', 'finprod_g1_kill_toggle', 'finprod_g1_min_write',
      'finprod_g1_penalty_write', 'finprod_g1_write', 'finprod_g2_cap_per_tx_write',
      'finprod_g2_cap_platform_write', 'finprod_g2_cap_user_write', 'finprod_g2_fee_rate_write',
      'finprod_g2_queue_cancel', 'finprod_g2_swap_toggle', 'finprod_g2_write',
      'finprod_g3_curve_pump_prob_write', 'finprod_g3_curve_target_price_write', 'finprod_g3_engine_pause_toggle',
      'finprod_g3_override_price_write', 'finprod_g3_write', 'finprod_g4_airdrop_lock_days_write',
      'finprod_g4_airdrop_pct_write', 'finprod_g4_dividend_rate_write', 'finprod_g4_emission_curve_write',
      'finprod_g4_market_toggle', 'finprod_g4_price_write', 'finprod_g4_royalty_write',
      'finprod_g4_write', 'finprod_g7_apy_write', 'finprod_g7_nurture_write',
      'finprod_g7_write'
  ));
-- H 域(growth_)：增长
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'growth_%'
WHERE r.role_code='GROWTH' AND p.permission_code <> 'growth_h8_settle'
  AND LEFT(p.permission_code,17) <> 'growth_promotion_'
  AND p.permission_code NOT IN (
      'growth_h1_control_pin_write', 'growth_h1_override_revoke', 'growth_h1_write',
      'growth_h5_tick_write', 'growth_h8_write'
  )
  AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='GROWTH' AND p.status=1 AND p.is_deleted=0
  AND p.permission_code IN (
      'growth_promotion_edit', 'growth_promotion_metrics_read', 'growth_promotion_policy_read',
      'growth_promotion_read', 'growth_promotion_reward_read', 'growth_promotion_simulate',
      'growth_promotion_submit'
  );
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id AND r.role_code='GROWTH'
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='growth_h8_settle';
-- I 域(content_)：内容
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'content_%'
WHERE r.role_code='CONTENT' AND p.status=1 AND p.is_deleted=0
  AND p.permission_code NOT IN (
      'content_i3_critical_send', 'content_i5_disclosure_publish', 'content_i5_gate_adjust',
      'content_legal_terms_publish', 'content_i1_experiment_manage', 'content_i4_publish_standard',
      'content_i4_trust_section_manage'
  );
-- I3 critical 合规/监管通知同时授予风控执行权；CAP 调整权限不再代替发送权限。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code='content_i3_critical_send'
WHERE r.role_code='RISK' AND p.status=1 AND p.is_deleted=0;
-- J 域(emergency_)：风控负责止血，但 J1 恢复/参数配置、J2 边缘源、J3 告警配置仅超管。
-- 财务只保留 J1 查看。边界以 PRD v4 §J1⑥ 为准。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'emergency_%'
WHERE r.role_code='RISK'
  AND p.permission_code NOT IN ('emergency_j1_write', 'emergency_j1_gate_resume', 'emergency_j2_edge_source_manage', 'emergency_j3_alert_config')
  AND p.status=1 AND p.is_deleted=0;
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE'
  AND p.permission_code='emergency_j1_read'
  AND p.status=1 AND p.is_deleted=0;
-- K 域(risk_)：风控。K4 尚无可持久识别的 lead/member 层级，模型起草和人工覆盖先仅超管。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'risk_%'
WHERE r.role_code='RISK'
  AND p.permission_code NOT IN ('risk_k4_write','risk_k4_user_override','risk_k6_target_manage')
  AND p.status=1 AND p.is_deleted=0;
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

-- 财务：L3 聚合与 L5 只读；L3 明细发起按上方财务主管授权。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='FINANCE'
  AND p.permission_code IN ('bi_l3_read','bi_l3_write','bi_l5_read')
  AND p.status=1 AND p.is_deleted=0;

-- 增长：经营、漏斗、运营聚合、行为热力只读与团队树发起；不授予财务明细或监管高敏权限。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='GROWTH'
  AND p.permission_code IN ('bi_l1_read','bi_l1_write','bi_l2_read','bi_l2_write','bi_l4_read','bi_l4_write','bi_l5_read','bi_l6_read')
  AND p.status=1 AND p.is_deleted=0;

-- 风控：只读查看 L4/L6 运营与行为事实及财务/监管报表，并执行敏感任务放行/监管生成；不发起 L4 或财务明细导出。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='RISK'
  AND p.permission_code IN ('bi_l3_read','bi_l4_read','bi_l5_read','bi_l5_task_approve','bi_l5_regulatory_generate','bi_l6_read')
  AND p.status=1 AND p.is_deleted=0;

-- 只读审计：查看聚合与热力，按独立授权读取合规导出。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='AUDITOR'
  AND p.permission_code IN ('bi_l1_read','bi_l2_read','bi_l3_read','bi_l4_read','bi_l5_read','bi_l6_read')
  AND p.status=1 AND p.is_deleted=0;
-- M 域(service_)：风控保持既有集合；CONTENT only receives the exact M5 read/write pair.
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p ON p.permission_code LIKE 'service_%'
WHERE r.role_code='RISK'
  AND (r.role_code <> 'RISK' OR p.permission_code <> 'service_m5_write')
  AND p.permission_code <> 'service_m3_timeout_manage'
  AND p.status=1 AND p.is_deleted=0;
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id AND r.role_code='RISK'
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='service_m5_write';

-- Current RBAC matrix: CONTENT can read and maintain M5 scripts/templates, but none of M1-M4.
-- Remove every historical M-domain grant first: a prior seed may have left M1-M4 rows behind.
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id AND r.role_code='CONTENT'
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code LIKE 'service_m%';
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='CONTENT'
  AND p.permission_code IN ('service_m5_read','service_m5_write')
  AND p.status=1 AND p.is_deleted=0;

-- SUPPORT retains only service permissions plus the scoped single-user ledger read.
-- 重跑 seed 收紧旧默认关系；自定义角色与其它角色的显式授权保持原集合。
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id AND r.role_code='SUPPORT';
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPPORT'
  AND p.permission_code IN ('service_m1_read','service_m1_write','service_m2_read','service_m2_write',
                            'service_m3_read','service_m3_write','service_m4_read','service_m5_read',
                            'finance_d4_user_read')
  AND p.status=1 AND p.is_deleted=0;

-- 2026-07-25 高保真真实接口同步：新增高敏权限始终显式授权，避免域前缀通配扩大权限。
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE (p.permission_code IN (
         'finance_d1_bank_reconcile',
         'finance_d1_bank_account_manage',
         'finance_d1_bank_config_manage',
         'finance_d6_manage'
       ) AND r.role_code NOT IN ('SUPER_ADMIN','FINANCE_LEAD'))
   OR (p.permission_code IN ('service_m3_timeout_manage','risk_k6_target_manage')
       AND r.role_code <> 'SUPER_ADMIN')
   OR (p.permission_code='finance_d6_read'
       AND r.role_code NOT IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR'));

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR')
  AND p.permission_code='finance_d6_read'
  AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','FINANCE_LEAD')
  AND p.permission_code IN (
    'finance_d1_bank_reconcile',
    'finance_d1_bank_account_manage',
    'finance_d1_bank_config_manage',
    'finance_d6_manage'
  )
  AND p.status=1 AND p.is_deleted=0;

INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPER_ADMIN'
  AND p.permission_code IN ('service_m3_timeout_manage','risk_k6_target_manage')
  AND p.status=1 AND p.is_deleted=0;

-- 财务主管在财务默认绑定写完后继承，避免新库第一次启动缺少 C1、G 域和 BI 等保留权限。
INSERT IGNORE INTO nx_admin_role_permission (role_id, permission_id)
SELECT lead_role.id, rp.permission_id
FROM nx_admin_role lead_role
JOIN nx_admin_role finance_role ON finance_role.role_code='FINANCE' AND finance_role.is_deleted=0
JOIN nx_admin_role_permission rp ON rp.role_id=finance_role.id AND rp.is_deleted=0
JOIN nx_admin_permission inherited ON inherited.id=rp.permission_id
  AND LEFT(inherited.permission_code,17) <> 'growth_promotion_'
  AND inherited.permission_code NOT IN (
      'overview_b5_threshold_write', 'platform_a2_export', 'platform_a2_read',
      'platform_a3_read', 'user_c2_account_freeze', 'user_c2_account_unfreeze',
      'user_c2_read', 'user_c2_session_revoke_all', 'finprod_g1_apy_write',
      'finprod_g1_kill_toggle', 'finprod_g1_min_write', 'finprod_g1_penalty_write',
      'finprod_g1_write', 'emergency_j1_gate_kill', 'finprod_g4_airdrop_lock_days_write',
      'finprod_g4_airdrop_pct_write', 'finprod_g4_emission_curve_write', 'finprod_g4_market_toggle',
      'finprod_g4_price_write', 'finprod_g4_royalty_write', 'finprod_g4_write',
      'overview_b4_export'
  )
WHERE lead_role.role_code='FINANCE_LEAD' AND lead_role.is_deleted=0;

-- B38 exact deltas; restore soft-deleted bindings on every seed run.
UPDATE nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
SET rp.is_deleted=1
WHERE (r.role_code='FINANCE_LEAD' AND p.permission_code='platform_a2_operation_approve')
   OR (r.role_code='GROWTH' AND p.permission_code IN ('device_e4_order_refund','finance_d6_read'))
   OR (r.role_code='RISK' AND p.permission_code IN ('content_i5_write','content_i5_disclosure_publish','content_i5_gate_adjust'));

INSERT INTO nx_admin_role_permission (role_id,permission_id,is_deleted)
SELECT r.id,p.id,0 FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.status=1 AND r.is_deleted=0 AND p.status=1 AND p.is_deleted=0
  AND ((r.role_code='FINANCE_LEAD' AND p.permission_code='platform_a2_read')
    OR (r.role_code IN ('SUPER_ADMIN','FINANCE_LEAD') AND p.permission_code IN ('device_e4_read','device_e4_order_refund'))
    OR (r.role_code='SUPPORT' AND p.permission_code='finance_d4_user_read')
    OR (r.role_code='RISK' AND p.permission_code='content_i5_read')
    OR (r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR') AND p.permission_code='finance_d6_read'))
ON DUPLICATE KEY UPDATE is_deleted=0, updated_at=NOW();

-- B38-BE-03 user decision: only active SUPER_ADMIN may investigate unknown results.
UPDATE nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
SET rp.is_deleted=1,rp.updated_at=NOW()
WHERE p.permission_code='platform_a2_unknown_investigate' AND r.role_code<>'SUPER_ADMIN';

INSERT INTO nx_admin_role_permission (role_id,permission_id,is_deleted)
SELECT r.id,p.id,0 FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code='SUPER_ADMIN' AND r.status=1 AND r.is_deleted=0
  AND p.permission_code='platform_a2_unknown_investigate' AND p.status=1 AND p.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();

-- B36: the overview prefix and FINANCE inheritance must not widen this sales-only exception.
DELETE rp FROM nx_admin_role_permission rp
JOIN nx_admin_role r ON r.id=rp.role_id
JOIN nx_admin_permission p ON p.id=rp.permission_id
WHERE p.permission_code='overview_b0_sales_read' AND r.role_code NOT IN ('SUPER_ADMIN','GROWTH');
INSERT INTO nx_admin_role_permission(role_id,permission_id,is_deleted)
SELECT r.id,p.id,0 FROM nx_admin_role r JOIN nx_admin_permission p
WHERE r.role_code IN ('SUPER_ADMIN','GROWTH') AND r.status=1 AND r.is_deleted=0
  AND p.permission_code='overview_b0_sales_read' AND p.status=1 AND p.is_deleted=0
ON DUPLICATE KEY UPDATE is_deleted=0,updated_at=NOW();
