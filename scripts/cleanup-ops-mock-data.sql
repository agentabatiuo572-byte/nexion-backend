USE nexion;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET SQL_SAFE_UPDATES = 0;

-- Run after scripts/schema.sql. The script is idempotent and intentionally
-- preserves the real superadmin account.
SET @cleanup_regex =
  '(e2e|mock|demo|SOP-DRAFT|PROOF-SEED|IMP-SEED|TASK-SEED|TASK-20260522|POC-20260522|EARN-20260522|KYC-10001|UD-10001|smoke|fixture|Seed User|Nexion Demo|seed:|20260701_173441|173441|175043|RISK[0-9]_20260701173441|SUPPORT[0-9]_20260701173441|WD-FIN2R4-LARGE-173441|super1[-_]|super2[-_]|cross_permission|agent_a|Agent-GROWTH|Growth Share Test|GROWTH-DEV)';
SET @config_cleanup_regex =
  '(^|[._:-])(e2e|sop-draft|seed)([._:-]|$)|SOP-DRAFT|PROOF-SEED|smoke|fixture|seeded|seed config|20260701_173441|173441|175043|RISK[0-9]_20260701173441|SUPPORT[0-9]_20260701173441|WD-FIN2R4-LARGE-173441|super1[-_]|super2[-_]|cross_permission|agent_a|Agent-GROWTH|Growth Share Test|GROWTH-DEV';
SET @device_cleanup_regex =
  '(e2e|mock|demo|SOP-DRAFT|PROOF-SEED|IMP-SEED|TASK-SEED|TASK-20260522|POC-20260522|EARN-20260522|KYC-10001|UD-10001|smoke|fixture|Seed User|Nexion Demo|seed:|20260701_173441|173441|175043|RISK[0-9]_20260701173441|SUPPORT[0-9]_20260701173441|WD-FIN2R4-LARGE-173441|super1[-_]|super2[-_]|cross_permission|agent_a|Agent-GROWTH|Growth Share Test|GROWTH-DEV|(^|[^[:alnum:]])TEST([-_]|[^[:alnum:]]|$))';

DROP TEMPORARY TABLE IF EXISTS tmp_ops_mock_admin;
DROP TEMPORARY TABLE IF EXISTS tmp_ops_mock_role;
DROP TEMPORARY TABLE IF EXISTS tmp_ops_mock_user;
DROP TEMPORARY TABLE IF EXISTS tmp_ops_mock_ticket;
DROP TEMPORARY TABLE IF EXISTS tmp_ops_mock_conversation;
DROP TEMPORARY TABLE IF EXISTS tmp_ops_mock_device;
DROP TEMPORARY TABLE IF EXISTS tmp_ops_mock_task;
DROP TEMPORARY TABLE IF EXISTS tmp_ops_mock_sop;

CREATE TEMPORARY TABLE tmp_ops_mock_admin (admin_id BIGINT PRIMARY KEY);
CREATE TEMPORARY TABLE tmp_ops_mock_role (role_id BIGINT PRIMARY KEY);
CREATE TEMPORARY TABLE tmp_ops_mock_user (user_id BIGINT PRIMARY KEY);
CREATE TEMPORARY TABLE tmp_ops_mock_ticket (ticket_id BIGINT PRIMARY KEY, ticket_no VARCHAR(40) NOT NULL, UNIQUE KEY uk_tmp_ticket_no (ticket_no));
CREATE TEMPORARY TABLE tmp_ops_mock_conversation (conversation_id BIGINT PRIMARY KEY, conversation_no VARCHAR(40) NOT NULL, UNIQUE KEY uk_tmp_conversation_no (conversation_no));
CREATE TEMPORARY TABLE tmp_ops_mock_device (device_id BIGINT PRIMARY KEY, instance_no VARCHAR(64) NOT NULL, UNIQUE KEY uk_tmp_instance_no (instance_no));
CREATE TEMPORARY TABLE tmp_ops_mock_task (task_no VARCHAR(96) PRIMARY KEY);
CREATE TEMPORARY TABLE tmp_ops_mock_sop (code VARCHAR(64) PRIMARY KEY);

START TRANSACTION;

INSERT IGNORE INTO tmp_ops_mock_admin (admin_id)
SELECT id
  FROM nx_admin
 WHERE id <> 1
   AND username <> 'superadmin'
   AND (
     REGEXP_LIKE(CONCAT_WS(' ', username, COALESCE(email, ''), COALESCE(nickname, '')), @cleanup_regex, 'i')
     OR COALESCE(email, '') LIKE 'e2e\_%@nexion.io'
   );

INSERT IGNORE INTO tmp_ops_mock_role (role_id)
SELECT id
  FROM nx_admin_role
 WHERE role_code NOT IN ('SUPER_ADMIN', 'OPS_ADMIN')
   AND REGEXP_LIKE(CONCAT_WS(' ', role_code, role_name, COALESCE(remark, '')), @cleanup_regex, 'i');

UPDATE nx_admin_role_relation rr
  LEFT JOIN tmp_ops_mock_admin a ON a.admin_id = rr.admin_id
  LEFT JOIN tmp_ops_mock_role r ON r.role_id = rr.role_id
   SET rr.is_deleted = 1,
       rr.updated_at = NOW()
 WHERE a.admin_id IS NOT NULL OR r.role_id IS NOT NULL;

UPDATE nx_admin_role_permission rp
  JOIN tmp_ops_mock_role r ON r.role_id = rp.role_id
   SET rp.is_deleted = 1,
       rp.updated_at = NOW();

UPDATE nx_admin_role_menu rm
  JOIN tmp_ops_mock_role r ON r.role_id = rm.role_id
   SET rm.is_deleted = 1,
       rm.updated_at = NOW();

UPDATE nx_admin_role role
  JOIN tmp_ops_mock_role r ON r.role_id = role.id
   SET role.status = 0,
       role.is_deleted = 1,
       role.updated_at = NOW()
 WHERE role.role_code NOT IN ('SUPER_ADMIN', 'OPS_ADMIN');

UPDATE nx_admin admin
  JOIN tmp_ops_mock_admin target ON target.admin_id = admin.id
   SET admin.status = 0,
       admin.is_deleted = 1,
       admin.updated_at = NOW()
 WHERE admin.id <> 1
   AND admin.username <> 'superadmin';

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_account_state') = 1,
  'UPDATE nx_admin_account_state s JOIN tmp_ops_mock_admin a ON a.admin_id = s.admin_id SET s.is_deleted = 1, s.updated_at = NOW()',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_rbac_action') = 1,
  'UPDATE nx_admin_rbac_action a SET a.status = 0, a.is_deleted = 1, a.updated_at = NOW() WHERE REGEXP_LIKE(CONCAT_WS('' '', a.action_id, a.action_name, a.domain_group), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_rbac_grant') = 1
  AND (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_rbac_action') = 1,
  'UPDATE nx_admin_rbac_grant g LEFT JOIN nx_admin_rbac_action a ON a.action_id = g.action_id SET g.status = 0, g.is_deleted = 1, g.updated_at = NOW() WHERE COALESCE(a.is_deleted, 0) = 1 OR REGEXP_LIKE(CONCAT_WS('' '', g.action_id, g.role_key, g.grant_value), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_security_baseline') = 1,
  'UPDATE nx_admin_security_baseline b SET b.status = 0, b.is_deleted = 1, b.updated_at = NOW() WHERE REGEXP_LIKE(CONCAT_WS('' '', b.baseline_key, b.label, b.description, b.baseline_value), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

DELETE FROM nx_config_item
 WHERE config_key <> 'commerce.payment.default_provider'
   AND (
     REGEXP_LIKE(config_key, @config_cleanup_regex, 'i')
     OR REGEXP_LIKE(config_group, @config_cleanup_regex, 'i')
     OR REGEXP_LIKE(COALESCE(remark, ''), @config_cleanup_regex, 'i')
     OR REGEXP_LIKE(config_value, '^(SOP-DRAFT|PROOF-SEED|seed:|e2e[-_]|mock[-_]|demo[-_])', 'i')
     OR REGEXP_LIKE(config_key, '^(a1\\.account\\.|a1\\.rbac\\.|a1\\.role\\.|a1\\.security\\.|treasury\\.b\\.|team\\.ui\\.F3\\.binary\\.rows|team\\.ui\\.F3\\.binary\\.summary|killswitch\\.|emergency\\.killswitch\\.|ops\\.J\\.emergency\\.|emergency\\.autorule\\.|emergency\\.(sop|geo|tamper))', 'i')
   );

UPDATE nx_config_item
   SET status = 0,
       is_deleted = 1,
       updated_at = NOW()
 WHERE config_key <> 'commerce.payment.default_provider'
   AND is_deleted = 0
   AND (
     REGEXP_LIKE(config_key, @config_cleanup_regex, 'i')
     OR REGEXP_LIKE(config_group, @config_cleanup_regex, 'i')
     OR REGEXP_LIKE(COALESCE(remark, ''), @config_cleanup_regex, 'i')
     OR REGEXP_LIKE(config_value, '^(SOP-DRAFT|PROOF-SEED|seed:|e2e[-_]|mock[-_]|demo[-_])', 'i')
     OR config_group IN ('admin_a1_account', 'admin_a1_rbac', 'admin_a1_role', 'admin_a1_security')
     OR REGEXP_LIKE(config_key, '^(a1\\.account\\.|a1\\.rbac\\.|a1\\.role\\.|a1\\.security\\.|treasury\\.b\\.|team\\.ui\\.F3\\.binary\\.rows|team\\.ui\\.F3\\.binary\\.summary|killswitch\\.|emergency\\.killswitch\\.|ops\\.J\\.emergency\\.|emergency\\.autorule\\.|emergency\\.(sop|geo|tamper))', 'i')
   );

UPDATE nx_config_item
   SET config_value = 'USDT',
       updated_at = NOW(),
       status = 1,
       is_deleted = 0
 WHERE config_key = 'commerce.payment.default_provider'
   AND UPPER(config_value) = 'MOCK';

UPDATE nx_config_item
   SET config_value = 'partner-example',
       updated_at = NOW()
 WHERE config_key = 'openapi.developer.docs_example_metadata'
   AND config_value = 'partner-demo';

INSERT IGNORE INTO tmp_ops_mock_user (user_id)
SELECT id
  FROM nx_user
 WHERE (
     id = 10001
     AND (phone = '4150004892' OR referral_code = 'NX4892' OR nickname = 'Stella Miner')
   )
   OR REGEXP_LIKE(CONCAT_WS(' ', country_code, phone, nickname, referral_code, COALESCE(sponsor_code, ''), COALESCE(region, ''), COALESCE(bio, ''), COALESCE(timezone, '')), @cleanup_regex, 'i');

INSERT IGNORE INTO tmp_ops_mock_user (user_id)
SELECT user_id
  FROM nx_user_profile
 WHERE REGEXP_LIKE(CONCAT_WS(' ', COALESCE(display_name, ''), COALESCE(email, ''), COALESCE(wallet_address, ''), COALESCE(bio, '')), @cleanup_regex, 'i');

UPDATE nx_user_session s
  LEFT JOIN tmp_ops_mock_user u ON u.user_id = s.user_id
   SET s.revoked_at = COALESCE(s.revoked_at, NOW()),
       s.is_deleted = 1,
       s.updated_at = NOW()
 WHERE u.user_id IS NOT NULL
    OR REGEXP_LIKE(CONCAT_WS(' ', s.refresh_token_id, COALESCE(s.device_name, ''), COALESCE(s.client_ip, '')), @cleanup_regex, 'i');

UPDATE nx_user_impersonation_session s
  LEFT JOIN tmp_ops_mock_user u ON u.user_id = s.user_id
   SET s.status = 'TERMINATED',
       s.terminated_by = COALESCE(s.terminated_by, 'cleanup'),
       s.terminate_reason = COALESCE(s.terminate_reason, 'cleanup mock/e2e/seed data'),
       s.terminated_at = COALESCE(s.terminated_at, NOW()),
       s.is_deleted = 1,
       s.updated_at = NOW()
 WHERE u.user_id IS NOT NULL
    OR REGEXP_LIKE(CONCAT_WS(' ', s.session_no, s.operator, s.reason), @cleanup_regex, 'i');

UPDATE nx_account_list l
  LEFT JOIN tmp_ops_mock_user u ON u.user_id = l.user_id
   SET l.status = 'RELEASED',
       l.released_by = COALESCE(l.released_by, 'cleanup'),
       l.release_reason = COALESCE(l.release_reason, 'cleanup mock/e2e/seed data'),
       l.released_at = COALESCE(l.released_at, NOW()),
       l.is_deleted = 1,
       l.updated_at = NOW()
 WHERE u.user_id IS NOT NULL
    OR REGEXP_LIKE(CONCAT_WS(' ', l.kind, l.reason, l.created_by, COALESCE(l.released_by, ''), COALESCE(l.release_reason, '')), @cleanup_regex, 'i');

UPDATE nx_notification n
  LEFT JOIN tmp_ops_mock_user u ON u.user_id = n.user_id
   SET n.is_deleted = 1,
       n.updated_at = NOW()
 WHERE u.user_id IS NOT NULL
    OR REGEXP_LIKE(CONCAT_WS(' ', COALESCE(n.biz_no, ''), n.type, n.title, n.body, n.push_status, COALESCE(n.last_push_error, '')), @cleanup_regex, 'i');

UPDATE nx_notification_campaign c
   SET c.status = 'ARCHIVED',
       c.is_deleted = 1,
       c.updated_at = NOW()
 WHERE REGEXP_LIKE(CONCAT_WS(' ', c.campaign_no, c.name, c.kind, c.tier, c.audience, c.status, c.body_en, c.body_zh, c.swipe_to, COALESCE(c.created_by, ''), COALESCE(c.last_operator, '')), @cleanup_regex, 'i');

INSERT IGNORE INTO tmp_ops_mock_ticket (ticket_id, ticket_no)
SELECT id, ticket_no
  FROM nx_support_ticket
 WHERE REGEXP_LIKE(CONCAT_WS(' ', ticket_no, category, priority, status, title, COALESCE(last_message, ''), COALESCE(assigned_admin_name, '')), @cleanup_regex, 'i')
    OR user_id IN (SELECT user_id FROM tmp_ops_mock_user)
    OR assigned_admin_id IN (SELECT admin_id FROM tmp_ops_mock_admin);

UPDATE nx_support_ticket_attachment a
  JOIN nx_support_ticket_message m ON m.id = a.message_id
  JOIN tmp_ops_mock_ticket t ON t.ticket_no = m.ticket_no
   SET a.is_deleted = 1,
       a.updated_at = NOW();

UPDATE nx_support_ticket_message m
  JOIN tmp_ops_mock_ticket t ON t.ticket_no = m.ticket_no
   SET m.is_deleted = 1,
       m.updated_at = NOW();

UPDATE nx_support_ticket t
  JOIN tmp_ops_mock_ticket target ON target.ticket_id = t.id
   SET t.status = 'CLOSED',
       t.is_deleted = 1,
       t.closed_at = COALESCE(t.closed_at, NOW()),
       t.updated_at = NOW();

INSERT IGNORE INTO tmp_ops_mock_conversation (conversation_id, conversation_no)
SELECT id, conversation_no
  FROM nx_conversation
 WHERE REGEXP_LIKE(CONCAT_WS(' ', conversation_no, conversation_type, status, COALESCE(owner_agent_id, ''), COALESCE(owner_agent_name, ''), COALESCE(last_message, '')), @cleanup_regex, 'i')
    OR user_id IN (SELECT user_id FROM tmp_ops_mock_user);

UPDATE nx_conversation_message m
  JOIN tmp_ops_mock_conversation c ON c.conversation_no = m.conversation_no
   SET m.is_deleted = 1,
       m.updated_at = NOW();

UPDATE nx_conversation_transfer tr
  JOIN tmp_ops_mock_conversation c ON c.conversation_no = tr.conversation_no
   SET tr.status = 'CANCELLED',
       tr.is_deleted = 1,
       tr.updated_at = NOW();

UPDATE nx_conversation c
  JOIN tmp_ops_mock_conversation target ON target.conversation_id = c.id
   SET c.status = 'CLOSED',
       c.is_deleted = 1,
       c.updated_at = NOW();

INSERT IGNORE INTO tmp_ops_mock_device (device_id, instance_no)
SELECT id, instance_no
  FROM nx_user_device
 WHERE REGEXP_LIKE(CONCAT_WS(' ', COALESCE(source_order_no, ''), COALESCE(product_code, ''), COALESCE(product_tier, ''), instance_no, name, device_type, status, COALESCE(gpu_model, ''), COALESCE(dc_location, ''), source_channel), @device_cleanup_regex, 'i')
    OR user_id IN (SELECT user_id FROM tmp_ops_mock_user);

INSERT IGNORE INTO tmp_ops_mock_device (device_id, instance_no)
SELECT d.id, d.instance_no
  FROM nx_user_device_runtime r
  JOIN nx_user_device d ON d.id = r.user_device_id
 WHERE REGEXP_LIKE(CONCAT_WS(' ', r.online_status, COALESCE(r.region, ''), COALESCE(r.country, ''), COALESCE(r.city, ''), COALESCE(r.paused_reason, ''), COALESCE(r.active_task_no, ''), COALESCE(r.client_name, ''), COALESCE(r.agent_version, '')), @device_cleanup_regex, 'i');

UPDATE nx_user_device_runtime r
  JOIN tmp_ops_mock_device d ON d.device_id = r.user_device_id
   SET r.is_deleted = 1,
       r.updated_at = NOW();

UPDATE nx_user_device d
  JOIN tmp_ops_mock_device target ON target.device_id = d.id
   SET d.status = 'DELETED',
       d.is_deleted = 1,
       d.updated_at = NOW();

INSERT IGNORE INTO tmp_ops_mock_task (task_no)
SELECT task_no
  FROM nx_compute_task
 WHERE REGEXP_LIKE(CONCAT_WS(' ', task_no, task_type, client_name, status, COALESCE(last_error, '')), @cleanup_regex, 'i')
    OR user_id IN (SELECT user_id FROM tmp_ops_mock_user)
    OR user_device_id IN (SELECT device_id FROM tmp_ops_mock_device);

INSERT IGNORE INTO tmp_ops_mock_task (task_no)
SELECT task_no
  FROM nx_compute_receipt
 WHERE task_no IS NOT NULL
   AND REGEXP_LIKE(CONCAT_WS(' ', COALESCE(task_no, ''), receipt_no, task_type, client_name, earning_status, proof_hash), @cleanup_regex, 'i');

UPDATE nx_compute_receipt r
  LEFT JOIN tmp_ops_mock_task t ON t.task_no = r.task_no
   SET r.is_deleted = 1,
       r.updated_at = NOW()
 WHERE t.task_no IS NOT NULL
    OR user_id IN (SELECT user_id FROM tmp_ops_mock_user)
    OR user_device_id IN (SELECT device_id FROM tmp_ops_mock_device)
    OR REGEXP_LIKE(CONCAT_WS(' ', COALESCE(r.task_no, ''), r.receipt_no, r.task_type, r.client_name, r.earning_status, r.proof_hash), @cleanup_regex, 'i');

UPDATE nx_compute_task task
  JOIN tmp_ops_mock_task target ON target.task_no = task.task_no
   SET task.is_deleted = 1,
       task.updated_at = NOW();

UPDATE nx_earning_event e
   SET e.is_deleted = 1,
       e.updated_at = NOW()
 WHERE e.user_id IN (SELECT user_id FROM tmp_ops_mock_user)
    OR e.user_device_id IN (SELECT device_id FROM tmp_ops_mock_device)
    OR REGEXP_LIKE(CONCAT_WS(' ', e.event_no, COALESCE(e.receipt_no, ''), e.asset, e.status), @cleanup_regex, 'i');

UPDATE nx_earning_summary s
   SET s.is_deleted = 1,
       s.updated_at = NOW()
 WHERE s.user_id IN (SELECT user_id FROM tmp_ops_mock_user);

UPDATE nx_user_wallet w
   SET w.usdt_available = 0,
       w.nex_available = 0,
       w.pending_withdraw = 0,
       w.is_deleted = 1,
       w.updated_at = NOW()
 WHERE w.user_id IN (SELECT user_id FROM tmp_ops_mock_user);

UPDATE nx_wallet_ledger l
   SET l.is_deleted = 1,
       l.updated_at = NOW()
 WHERE l.user_id IN (SELECT user_id FROM tmp_ops_mock_user)
    OR REGEXP_LIKE(CONCAT_WS(' ', l.biz_no, l.biz_type, l.asset, l.direction, l.status, COALESCE(l.remark, '')), @cleanup_regex, 'i');

UPDATE nx_exchange_order o
   SET o.is_deleted = 1,
       o.updated_at = NOW()
 WHERE o.user_id IN (SELECT user_id FROM tmp_ops_mock_user)
    OR REGEXP_LIKE(CONCAT_WS(' ', o.exchange_no, o.from_asset, o.to_asset, o.status), @cleanup_regex, 'i');

UPDATE nx_withdrawal_order o
   SET o.is_deleted = 1,
       o.updated_at = NOW()
 WHERE o.user_id IN (SELECT user_id FROM tmp_ops_mock_user)
    OR REGEXP_LIKE(CONCAT_WS(' ', o.withdrawal_no, o.asset, o.chain, o.target_address, COALESCE(o.chain_tx_hash, ''), o.status, COALESCE(o.failure_reason, ''), COALESCE(o.last_broadcast_error, '')), @cleanup_regex, 'i');

UPDATE nx_admin_risk_withdraw_hit h
   SET h.is_deleted = 1
 WHERE REGEXP_LIKE(CONCAT_WS(' ', h.withdrawal_no, h.user_no, h.amount_text, h.rule_id, h.dimension, h.action, h.time_text), @cleanup_regex, 'i');

UPDATE nx_deposit_order o
   SET o.is_deleted = 1,
       o.updated_at = NOW()
 WHERE o.user_id IN (SELECT user_id FROM tmp_ops_mock_user)
    OR REGEXP_LIKE(CONCAT_WS(' ', o.deposit_no, o.chain_name, o.chain_tx_hash, o.asset, o.status, COALESCE(o.failure_reason, '')), @cleanup_regex, 'i');

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_commission_event') = 1,
  'UPDATE nx_commission_event e LEFT JOIN tmp_ops_mock_user u ON u.user_id IN (e.user_id, e.source_user_id) SET e.is_deleted = 1, e.updated_at = NOW() WHERE u.user_id IS NOT NULL OR REGEXP_LIKE(CONCAT_WS('' '', e.commission_type, COALESCE(e.source_user_name, ''''), COALESCE(e.order_no, ''''), e.currency, e.status, COALESCE(e.remark, '''')), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_binary_commission_settlement') = 1,
  'UPDATE nx_binary_commission_settlement s LEFT JOIN tmp_ops_mock_user u ON u.user_id IN (s.user_id, s.left_user_id, s.right_user_id) SET s.is_deleted = 1, s.updated_at = NOW() WHERE u.user_id IS NOT NULL OR REGEXP_LIKE(CONCAT_WS('' '', s.status, COALESCE(s.commission_event_id, '''')), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_kyc_profile k
   SET k.is_deleted = 1,
       k.updated_at = NOW()
 WHERE k.user_id IN (SELECT user_id FROM tmp_ops_mock_user)
    OR REGEXP_LIKE(CONCAT_WS(' ', k.kyc_no, k.status, COALESCE(k.country, ''), COALESCE(k.applicant_name, ''), COALESCE(k.document_type, ''), COALESCE(k.document_last4, ''), COALESCE(k.document_object_key, ''), COALESCE(k.reviewed_by, ''), COALESCE(k.reject_reason, ''), COALESCE(k.risk_notes, '')), @cleanup_regex, 'i');

UPDATE nx_proof_asset p
   SET p.is_deleted = 1,
       p.updated_at = NOW()
 WHERE p.user_id IN (SELECT user_id FROM tmp_ops_mock_user)
    OR REGEXP_LIKE(CONCAT_WS(' ', p.proof_no, p.proof_type, p.object_key, p.status, COALESCE(p.file_name, ''), COALESCE(p.checksum, ''), COALESCE(p.related_biz_type, ''), COALESCE(p.related_biz_no, ''), COALESCE(p.submitted_by, ''), COALESCE(p.reviewed_by, ''), COALESCE(p.metadata_json, '')), @cleanup_regex, 'i');

UPDATE nx_product_review r
   SET r.status = 'HIDDEN',
       r.is_deleted = 1,
       r.updated_at = NOW()
 WHERE r.user_id IN (SELECT user_id FROM tmp_ops_mock_user)
    OR REGEXP_LIKE(CONCAT_WS(' ', COALESCE(r.title, ''), COALESCE(r.content, ''), COALESCE(r.avatar_object_key, ''), COALESCE(CAST(r.media_object_keys AS CHAR), '')), @cleanup_regex, 'i');

UPDATE nx_product_waitlist w
   SET w.status = 'CANCELLED',
       w.is_deleted = 1,
       w.updated_at = NOW()
 WHERE w.user_id IN (SELECT user_id FROM tmp_ops_mock_user)
    OR REGEXP_LIKE(CONCAT_WS(' ', COALESCE(w.product_no, ''), COALESCE(w.product_name, ''), COALESCE(w.unlock_phase, ''), w.status), @cleanup_regex, 'i');

UPDATE nx_user_profile p
   SET p.is_deleted = 1,
       p.updated_at = NOW()
 WHERE p.user_id IN (SELECT user_id FROM tmp_ops_mock_user);

UPDATE nx_user_preference p
   SET p.is_deleted = 1,
       p.updated_at = NOW()
 WHERE p.user_id IN (SELECT user_id FROM tmp_ops_mock_user);

UPDATE nx_user_security s
   SET s.is_deleted = 1,
       s.updated_at = NOW()
 WHERE s.user_id IN (SELECT user_id FROM tmp_ops_mock_user);

UPDATE nx_user u
   SET u.status = 'DISABLED',
       u.is_deleted = 1,
       u.updated_at = NOW()
 WHERE u.id IN (SELECT user_id FROM tmp_ops_mock_user);

UPDATE nx_conversation_message m
   SET m.is_deleted = 1,
       m.updated_at = NOW()
 WHERE REGEXP_LIKE(CONCAT_WS(' ', m.conversation_no, COALESCE(m.sender_name, ''), m.content), @cleanup_regex, 'i');

UPDATE nx_support_agent_profile p
   SET p.enabled = 0,
       p.transferable = 0,
       p.busy = 0,
       p.is_deleted = 1,
       p.updated_at = NOW()
 WHERE p.admin_id IN (SELECT admin_id FROM tmp_ops_mock_admin)
    OR REGEXP_LIKE(CONCAT_WS(' ', p.position, p.service_types, p.tags), @cleanup_regex, 'i');

UPDATE nx_support_agent_user_assignment a
   SET a.status = 'ENDED',
       a.is_deleted = 1,
       a.updated_at = NOW()
 WHERE a.agent_admin_id IN (SELECT admin_id FROM tmp_ops_mock_admin)
    OR a.user_id IN (SELECT user_id FROM tmp_ops_mock_user)
    OR REGEXP_LIKE(CONCAT_WS(' ', a.status, a.operator, a.reason), @cleanup_regex, 'i');

UPDATE nx_admin_risk_arbitrage_row r
   SET r.is_deleted = 1,
       r.updated_at = NOW()
 WHERE REGEXP_LIKE(CONCAT_WS(' ', r.row_id, r.view_key, r.cluster_id, r.cell1, r.cell2, r.cell3, r.cell4, r.cell5, r.cell6, r.level_value, r.actions_csv, r.disposition), @cleanup_regex, 'i');

UPDATE nx_admin_risk_kyc_review_ticket t
   SET t.status = 'closed',
       t.is_deleted = 1,
       t.updated_at = NOW()
 WHERE REGEXP_LIKE(CONCAT_WS(' ', t.ticket_id, t.ticket_type, t.user_no, t.amount_text, t.kyc_text, t.status, COALESCE(t.info_json, ''), COALESCE(t.history_json, ''), COALESCE(t.decision_reason, ''), COALESCE(t.reviewed_by, '')), @cleanup_regex, 'i');

UPDATE nx_growth_voucher v
   SET v.status = 'archived',
       v.is_deleted = 1,
       v.updated_at = NOW()
 WHERE REGEXP_LIKE(CONCAT_WS(' ', v.voucher_id, v.voucher_name, v.voucher_type, COALESCE(v.applicable_skus, ''), v.audience, v.claim_surfaces, v.status, COALESCE(v.created_by, ''), COALESCE(v.updated_by, '')), @cleanup_regex, 'i');

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_event_quest') = 1,
  'UPDATE nx_event_quest q SET q.status = 0, q.is_deleted = 1, q.updated_at = NOW() WHERE REGEXP_LIKE(CONCAT_WS('' '', q.quest_code, q.quest_name, COALESCE(q.description, ''''), q.geo_scope, q.target_type, q.reward_type, q.reward_name, COALESCE(q.badge_achievement_code, '''')), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_user_event_quest') = 1
  AND (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_event_quest') = 1,
  'UPDATE nx_user_event_quest u LEFT JOIN nx_event_quest q ON q.quest_code = u.quest_code SET u.is_deleted = 1, u.updated_at = NOW() WHERE u.user_id IN (SELECT user_id FROM tmp_ops_mock_user) OR COALESCE(q.is_deleted, 0) = 1 OR REGEXP_LIKE(CONCAT_WS('' '', u.quest_code, u.claim_status, u.reward_type), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_risk_signal s
   SET s.is_deleted = 1,
       s.updated_at = NOW()
 WHERE s.user_id IN (SELECT user_id FROM tmp_ops_mock_user)
    OR REGEXP_LIKE(CONCAT_WS(' ', s.signal_no, s.signal_type, s.severity, s.evidence, s.created_by), @cleanup_regex, 'i');

UPDATE nx_admin_idempotency_record r
   SET r.status = 'EXPIRED',
       r.is_deleted = 1,
       r.updated_at = NOW()
 WHERE REGEXP_LIKE(CONCAT_WS(' ', r.scope, r.idempotency_key, r.request_hash, r.status, COALESCE(r.error_message, ''), COALESCE(CAST(r.response_json AS CHAR), '')), @cleanup_regex, 'i');

UPDATE nx_admin_fourth_batch_report r
   SET r.status = 'ARCHIVED',
       r.is_deleted = 1,
       r.updated_at = NOW()
 WHERE REGEXP_LIKE(CONCAT_WS(' ', r.module_code, r.report_id, r.report_name, r.report_type, r.cycle, r.file_format, r.scope_text, r.field_text, r.status, COALESCE(r.note, ''), COALESCE(r.reason, '')), @cleanup_regex, 'i');

UPDATE nx_admin_device_task t
   SET t.status = 'inactive',
       t.is_deleted = 1,
       t.updated_at = NOW()
 WHERE REGEXP_LIKE(CONCAT_WS(' ', t.task_id, t.name, t.unit_text, t.requirement, t.status, t.task_class, t.model_name, t.min_vram, t.kill_init), @device_cleanup_regex, 'i');

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_admin_risk_multi_account_cluster') = 1,
  'UPDATE nx_admin_risk_multi_account_cluster c SET c.status = ''CLOSED'', c.is_deleted = 1, c.updated_at = NOW() WHERE REGEXP_LIKE(CONCAT_WS('' '', c.cluster_id, c.dedupe_key, c.layer_key, c.layer_label, c.status, c.note_text, COALESCE(c.gifts_json, ''''), COALESCE(c.nodes_json, ''''), COALESCE(c.review_note, ''''), COALESCE(c.updated_by, '''')), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE nx_audit_log l
   SET l.is_deleted = 1
 WHERE l.actor_id IN (SELECT admin_id FROM tmp_ops_mock_admin)
    OR l.user_id IN (SELECT user_id FROM tmp_ops_mock_user)
    OR REGEXP_LIKE(CONCAT_WS(' ', COALESCE(l.trace_id, ''), l.service_name, l.action, l.resource_type, COALESCE(l.resource_id, ''), COALESCE(l.biz_no, ''), COALESCE(l.actor_username, ''), COALESCE(l.client_ip, ''), COALESCE(l.method, ''), COALESCE(l.path, ''), COALESCE(CAST(l.detail_json AS CHAR), '')), @cleanup_regex, 'i')
    OR (
      l.resource_type IN ('SOP_PLAYBOOK', 'SOP_PLAYBOOK_EXECUTION', 'WITHDRAWAL')
      AND REGEXP_LIKE(CONCAT_WS(' ', COALESCE(l.resource_id, ''), COALESCE(l.biz_no, ''), COALESCE(l.path, ''), COALESCE(CAST(l.detail_json AS CHAR), '')), '(SOP-DRAFT|e2e|mock|demo|seed:|smoke|fixture)', 'i')
    );

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_emergency_sop_step') = 1,
  'UPDATE nx_emergency_sop_step s SET s.status = ''CANCELLED'', s.is_deleted = 1, s.updated_at = NOW() WHERE REGEXP_LIKE(CONCAT_WS('' '', s.sop_id, s.step_title, s.status, COALESCE(s.status_reason, ''''), COALESCE(s.operator, '''')), @cleanup_regex, ''i'') OR s.sop_id LIKE ''SOP-DRAFT%''',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_emergency_sop_playbook') = 1,
  'INSERT IGNORE INTO tmp_ops_mock_sop (code) SELECT code FROM nx_emergency_sop_playbook WHERE code LIKE ''SOP-DRAFT%'' OR REGEXP_LIKE(CONCAT_WS('' '', code, name, scene, state, owner, COALESCE(notify_campaign_no, ''''), COALESCE(notify_template, ''''), COALESCE(rollback_plan, ''''), COALESCE(summary, ''''), COALESCE(created_by, ''''), COALESCE(updated_by, '''')), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_emergency_sop_action') = 1,
  'UPDATE nx_emergency_sop_action a JOIN tmp_ops_mock_sop s ON s.code = a.playbook_code SET a.is_deleted = 1, a.updated_at = NOW()',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_emergency_sop_execution') = 1,
  'UPDATE nx_emergency_sop_execution e LEFT JOIN tmp_ops_mock_sop s ON s.code = e.playbook_code SET e.is_deleted = 1, e.updated_at = NOW() WHERE s.code IS NOT NULL OR REGEXP_LIKE(CONCAT_WS('' '', e.execution_id, e.playbook_code, e.playbook_name, e.trigger_reason, e.execution_mode, COALESCE(e.operator, ''''), COALESCE(e.role_gate, ''''), COALESCE(e.idempotency_key, ''''), COALESCE(CAST(e.notification_json AS CHAR), ''''), COALESCE(CAST(e.domain_action_json AS CHAR), ''''), COALESCE(e.rollback_plan, ''''), COALESCE(e.rollback_status, ''''), COALESCE(e.rollback_reason, ''''), COALESCE(CAST(e.rollback_action_json AS CHAR), '''')), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_emergency_sop_playbook') = 1,
  'UPDATE nx_emergency_sop_playbook p JOIN tmp_ops_mock_sop s ON s.code = p.code SET p.state = ''archived'', p.draft = 1, p.is_deleted = 1, p.updated_by = ''cleanup'', p.updated_at = NOW()',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_emergency_control_setting') = 1,
  'UPDATE nx_emergency_control_setting s SET s.is_deleted = 1, s.updated_at = NOW() WHERE REGEXP_LIKE(CONCAT_WS('' '', s.setting_key, s.setting_value, s.value_type, s.group_code, COALESCE(s.remark, ''''), COALESCE(s.operator, '''')), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_emergency_geo_country_policy') = 1,
  'UPDATE nx_emergency_geo_country_policy p SET p.is_deleted = 1, p.updated_at = NOW() WHERE REGEXP_LIKE(CONCAT_WS('' '', p.country_code, COALESCE(p.country_name, ''''), p.policy_status, COALESCE(p.reason, ''''), COALESCE(p.operator, '''')), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_emergency_geo_endpoint_catalog') = 1,
  'UPDATE nx_emergency_geo_endpoint_catalog c SET c.is_deleted = 1, c.updated_at = NOW() WHERE REGEXP_LIKE(CONCAT_WS('' '', c.endpoint_key, c.endpoint_path, c.label, c.biz, c.domain_code, c.status), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_emergency_geo_endpoint_policy') = 1,
  'UPDATE nx_emergency_geo_endpoint_policy p SET p.is_deleted = 1, p.updated_at = NOW() WHERE REGEXP_LIKE(CONCAT_WS('' '', p.endpoint_key, p.endpoint_path, p.label, p.biz, p.domain_code, p.country_code, p.policy_source, COALESCE(p.reason, ''''), COALESCE(p.operator, '''')), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_emergency_geo_block_event') = 1,
  'UPDATE nx_emergency_geo_block_event e SET e.is_deleted = 1, e.updated_at = NOW() WHERE REGEXP_LIKE(CONCAT_WS('' '', e.country_code, COALESCE(e.country_name, ''''), COALESCE(e.endpoint_key, ''''), COALESCE(e.source, '''')), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_emergency_tamper_event') = 1,
  'UPDATE nx_emergency_tamper_event e SET e.is_deleted = 1, e.updated_at = NOW() WHERE REGEXP_LIKE(CONCAT_WS('' '', COALESCE(e.event_no, ''''), COALESCE(e.user_no, ''''), e.path_key, e.path_name, COALESCE(e.description, ''''), COALESCE(e.cluster_code, '''')), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_emergency_tamper_report') = 1,
  'UPDATE nx_emergency_tamper_report r SET r.is_deleted = 1, r.updated_at = NOW() WHERE REGEXP_LIKE(CONCAT_WS('' '', r.report_id, r.window_label, r.status, COALESCE(r.operator, ''''), COALESCE(r.reason, ''''), COALESCE(CAST(r.payload_json AS CHAR), '''')), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'nx_emergency_tamper_gate') = 1,
  'UPDATE nx_emergency_tamper_gate g SET g.status = 0, g.is_deleted = 1, g.updated_at = NOW() WHERE REGEXP_LIKE(CONCAT_WS('' '', g.gate_key, g.gate_name, COALESCE(g.verdict, ''''), COALESCE(g.review_reason, ''''), COALESCE(g.reviewed_by, '''')), @cleanup_regex, ''i'')',
  'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

COMMIT;
