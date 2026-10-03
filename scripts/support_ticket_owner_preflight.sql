-- Read-only structural prerequisite for the owner migration and formal binding service.
-- Equivalent already-migrated schemas are accepted without requiring historical migration markers.
SELECT CASE WHEN COUNT(c.COLUMN_NAME)=COUNT(*) THEN 'READY'
       ELSE CONCAT('MISSING: ',GROUP_CONCAT(
           CASE WHEN c.COLUMN_NAME IS NULL THEN CONCAT(r.table_name,'.',r.column_name) END
           ORDER BY r.table_name,r.column_name SEPARATOR ', ')) END AS support_binding_schema
FROM (
    SELECT 'nx_support_agent_user_assignment' table_name,'id' column_name
    UNION ALL SELECT 'nx_support_agent_user_assignment','user_id'
    UNION ALL SELECT 'nx_support_agent_user_assignment','agent_admin_id'
    UNION ALL SELECT 'nx_support_agent_user_assignment','status'
    UNION ALL SELECT 'nx_support_agent_user_assignment','is_deleted'
    UNION ALL SELECT 'nx_support_agent_user_assignment','version'
    UNION ALL SELECT 'nx_support_agent_user_assignment','source'
    UNION ALL SELECT 'nx_support_agent_user_assignment','segment_root_id'
    UNION ALL SELECT 'nx_support_agent_user_assignment','depth'
    UNION ALL SELECT 'nx_support_agent_user_assignment','parent_assignment_id'
    UNION ALL SELECT 'nx_support_agent_user_assignment','rule_version'
    UNION ALL SELECT 'nx_support_agent_user_assignment','operation_id'
    UNION ALL SELECT 'nx_support_binding_pool','customer_id'
    UNION ALL SELECT 'nx_support_binding_pool','reason'
    UNION ALL SELECT 'nx_support_binding_pool','version'
    UNION ALL SELECT 'nx_support_binding_pool','entered_at'
    UNION ALL SELECT 'nx_support_rules','id'
    UNION ALL SELECT 'nx_support_rules','version'
    UNION ALL SELECT 'nx_support_rules','dormant_days'
    UNION ALL SELECT 'nx_support_rules','maintenance_days'
    UNION ALL SELECT 'nx_support_rules','activity_window_days'
    UNION ALL SELECT 'nx_support_rules','inheritance_mode'
    UNION ALL SELECT 'nx_support_rules','max_inheritance_depth'
    UNION ALL SELECT 'nx_support_rules','updated_by'
    UNION ALL SELECT 'nx_support_rules','reason'
    UNION ALL SELECT 'nx_support_rules','updated_at'
    UNION ALL SELECT 'nx_support_reply_cursor','conversation_no'
    UNION ALL SELECT 'nx_support_reply_cursor','through_message_id'
    UNION ALL SELECT 'nx_support_reply_cursor','reply_message_id'
) r
LEFT JOIN information_schema.COLUMNS c
  ON c.TABLE_SCHEMA=DATABASE() AND c.TABLE_NAME=r.table_name AND c.COLUMN_NAME=r.column_name;
