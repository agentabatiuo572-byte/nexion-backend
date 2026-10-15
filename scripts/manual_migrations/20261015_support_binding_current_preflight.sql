-- Read-only current-contract review after the group/qualification schema is installed.
-- Historical S3 receipts stay immutable; seat/profile labels never grant SERVICE qualification.
SELECT user_id,COUNT(*) active_count FROM nx_support_agent_user_assignment
 WHERE status='ACTIVE' AND is_deleted=0 GROUP BY user_id HAVING COUNT(*)>1;
SELECT x.id,x.user_id,x.agent_admin_id FROM nx_support_agent_user_assignment x
 LEFT JOIN nx_user u ON u.id=x.user_id
 WHERE x.status='ACTIVE' AND x.is_deleted=0 AND (u.id IS NULL OR u.is_deleted<>0 OR NOT EXISTS(
 SELECT 1 FROM nx_admin a JOIN nx_support_agent_profile p ON p.admin_id=a.id
 JOIN nx_admin_role_relation rr ON rr.admin_id=a.id AND rr.is_deleted=0
 JOIN nx_admin_role r ON r.id=rr.role_id AND r.is_deleted=0 AND r.status=1 AND r.role_code IN ('SUPPORT','SUPER_ADMIN')
 JOIN nx_support_account_qualification_history scope_q ON scope_q.admin_id=a.id
 WHERE a.status=1 AND a.is_deleted=0 AND p.is_deleted=0 AND p.enabled=1
 AND scope_q.qualification_kind='SERVICE' AND scope_q.state='ENABLED'
 AND scope_q.starts_at<=UTC_TIMESTAMP(6) AND scope_q.ends_at IS NULL
 AND NOT EXISTS(SELECT 1 FROM nx_support_account_qualification_history scope_q_other
 WHERE scope_q_other.admin_id=scope_q.admin_id AND scope_q_other.qualification_kind=scope_q.qualification_kind
 AND scope_q_other.id<>scope_q.id AND scope_q_other.starts_at<=UTC_TIMESTAMP(6)
 AND (scope_q_other.ends_at IS NULL OR scope_q_other.ends_at>UTC_TIMESTAMP(6)))
 AND a.id=x.agent_admin_id));
SELECT DISTINCT c.user_id FROM nx_conversation c WHERE c.is_deleted=0
 AND NOT EXISTS(SELECT 1 FROM nx_support_agent_user_assignment x
 WHERE x.user_id=c.user_id AND x.status='ACTIVE' AND x.is_deleted=0);
SELECT id,user_id,agent_admin_id,status,starts_at,ends_at FROM nx_support_agent_user_assignment ORDER BY id;
