package ffdd.opsconsole.team.mapper;

/** Distinguishes the placement algorithm from the administrator who triggered it. */
public final class BinaryPlacementSourceSql {
    private BinaryPlacementSourceSql() {}

    public static final String AUTOMATIC = """
            (a.assigned_by='SYSTEM_AUTO_PLACEMENT' OR EXISTS (
              SELECT 1 FROM nx_audit_log placement_audit
               WHERE placement_audit.action='F3_BINARY_AUTO_PLACED'
                 AND placement_audit.resource_type='BINARY_LEG_ASSIGNMENT'
                 AND placement_audit.resource_id=CONCAT(a.owner_user_id,':',a.member_user_id)
                 AND placement_audit.biz_no=CONCAT(a.owner_user_id,':',a.member_user_id)
                 AND placement_audit.user_id=a.owner_user_id
                 AND placement_audit.actor_type='ADMIN'
                 AND placement_audit.actor_id=a.assigned_by_admin_id
                 AND placement_audit.actor_username=a.assigned_by
                 AND placement_audit.result='SUCCESS' AND placement_audit.is_deleted=0
                 AND placement_audit.created_at>=a.assigned_at))
            """;
}
