-- Read-only evidence. Save this result before starting an upgraded application.
SELECT DATABASE() AS database_name, @@server_uuid AS server_uuid;
SELECT * FROM nx_admin_role_relation ORDER BY admin_id,role_id,id;
SELECT * FROM nx_support_agent_profile ORDER BY admin_id;
SELECT * FROM nx_support_agent_user_assignment ORDER BY user_id,id;
SELECT * FROM nx_support_binding_pool ORDER BY customer_id;
SELECT * FROM nx_support_rules ORDER BY id;
SELECT COLUMN_NAME,COLUMN_TYPE FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='nx_support_agent_user_assignment' ORDER BY ORDINAL_POSITION;
SELECT 'DUPLICATE_CURRENT_BINDING' issue,user_id,COUNT(*) count FROM nx_support_agent_user_assignment
 WHERE status='ACTIVE' AND is_deleted=0 GROUP BY user_id HAVING COUNT(*)>1;
SELECT 'ORPHAN_BINDING' issue,x.id,x.user_id,x.agent_admin_id FROM nx_support_agent_user_assignment x
 LEFT JOIN nx_user u ON u.id=x.user_id LEFT JOIN nx_admin a ON a.id=x.agent_admin_id
 WHERE x.is_deleted=0 AND (u.id IS NULL OR a.id IS NULL);
SELECT 'BOUND_AND_POOLED' issue,x.id,x.user_id FROM nx_support_agent_user_assignment x
 JOIN nx_support_binding_pool p ON p.customer_id=x.user_id WHERE x.status='ACTIVE' AND x.is_deleted=0;
SELECT 'UNAVAILABLE_OR_UNPROVEN_SERVICE' issue,x.id,x.agent_admin_id,a.status,p.enabled,p.seat_type,p.service_types
 FROM nx_support_agent_user_assignment x LEFT JOIN nx_admin a ON a.id=x.agent_admin_id
 LEFT JOIN nx_support_agent_profile p ON p.admin_id=x.agent_admin_id
 WHERE x.status='ACTIVE' AND x.is_deleted=0 AND (a.status<>1 OR a.is_deleted<>0 OR p.admin_id IS NULL
 OR p.enabled<>1 OR p.is_deleted<>0 OR p.seat_type<>'DEDICATED'
 OR FIND_IN_SET('advisor',REPLACE(LOWER(p.service_types),' ',''))=0);
SELECT 'OVERLAPPING_BINDING_HISTORY' issue,a.id,b.id,a.user_id FROM nx_support_agent_user_assignment a
 JOIN nx_support_agent_user_assignment b ON a.user_id=b.user_id AND a.id<b.id AND a.is_deleted=0 AND b.is_deleted=0
 AND a.starts_at<COALESCE(b.ends_at,'9999-12-31') AND b.starts_at<COALESCE(a.ends_at,'9999-12-31');
SELECT 'QUALIFICATION_REVIEW' issue,p.* FROM nx_support_agent_profile p WHERE p.is_deleted=0
 AND (p.seat_type NOT IN ('MANAGER','DEDICATED') OR (p.seat_type='DEDICATED'
 AND FIND_IN_SET('advisor',REPLACE(LOWER(p.service_types),' ',''))=0));
-- New group history is N/A until the structural migration has been applied.
