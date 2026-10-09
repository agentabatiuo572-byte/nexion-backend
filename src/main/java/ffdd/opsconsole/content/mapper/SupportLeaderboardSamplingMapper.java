package ffdd.opsconsole.content.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Internal system catalogue, not a caller's object grant; no generic writes are permitted. */
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportLeaderboardSamplingMapper {
    String ACTIVE_ROLE = "rr.admin_id=a.id AND rr.is_deleted=0 AND r.status=1 AND r.is_deleted=0";
    String SUPER = "EXISTS(SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id WHERE "
        + ACTIVE_ROLE + " AND r.role_code IN ('SUPER','SUPERADMIN','SUPER_ADMIN'))";
    String SUPPORT = "EXISTS(SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id WHERE "
        + ACTIVE_ROLE + " AND r.role_code='SUPPORT')";
    String UNIQUE_QUALIFICATION = "q.starts_at &lt;= #{cutoff} AND q.ends_at IS NULL AND NOT EXISTS("
        + "SELECT 1 FROM nx_support_account_qualification_history other_q WHERE other_q.admin_id=q.admin_id "
        + "AND other_q.qualification_kind=q.qualification_kind AND other_q.id&lt;&gt;q.id "
        + "AND other_q.starts_at &lt;= #{cutoff} AND (other_q.ends_at IS NULL OR other_q.ends_at&gt;#{cutoff}))";
    String SERVICE = "(" + SUPPORT + " AND EXISTS(SELECT 1 FROM nx_support_account_qualification_history q "
        + "JOIN nx_support_agent_profile p ON p.admin_id=q.admin_id AND p.enabled=1 AND p.is_deleted=0 "
        + "WHERE q.admin_id=a.id AND q.qualification_kind='SERVICE' AND q.state='ENABLED' AND " + UNIQUE_QUALIFICATION + "))";
    String SUPERVISOR = "(" + SUPPORT + " AND EXISTS(SELECT 1 FROM nx_support_account_qualification_history q "
        + "WHERE q.admin_id=a.id AND q.qualification_kind='SUPERVISOR' AND q.state='ENABLED' AND " + UNIQUE_QUALIFICATION + "))";

    @Select("<script>SELECT a.id adminId," + SUPER + " superAdmin," + SERVICE + " service," + SUPERVISOR + " supervisor "
        + "FROM nx_admin a WHERE a.status=1 AND a.is_deleted=0 AND EXISTS("
        + "SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id "
        + "JOIN nx_admin_role_permission rp ON rp.role_id=r.id AND rp.is_deleted=0 "
        + "JOIN nx_admin_permission p ON p.id=rp.permission_id AND p.status=1 AND p.is_deleted=0 AND p.resource_type='API' "
        + "WHERE " + ACTIVE_ROLE + " AND p.permission_code IN ('service_m1_read','service_m3_read')) "
        + "AND (" + SUPER + " OR " + SERVICE + " OR " + SUPERVISOR + ") ORDER BY a.id</script>")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Viewer> viewers(@Param("cutoff") LocalDateTime cutoff);

    @Select("""
        <script>SELECT g.id,g.supervisor_admin_id supervisorId FROM nx_support_group g WHERE g.status='ENABLED'
        AND EXISTS(SELECT 1 FROM nx_support_group_owner_history o WHERE o.group_id=g.id
          AND o.supervisor_admin_id=g.supervisor_admin_id AND o.starts_at &lt;= #{cutoff} AND o.ends_at IS NULL
          AND NOT EXISTS(SELECT 1 FROM nx_support_group_owner_history other_o WHERE other_o.group_id=o.group_id
            AND other_o.id&lt;&gt;o.id AND other_o.starts_at &lt;= #{cutoff}
            AND (other_o.ends_at IS NULL OR other_o.ends_at&gt;#{cutoff}))) ORDER BY g.id</script>
        """)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<SamplingGroup> groups(@Param("cutoff") LocalDateTime cutoff);

    @Select("""
        <script>SELECT m.agent_admin_id adminId,m.group_id groupId FROM nx_support_group_member_history m
        JOIN nx_support_group g ON g.id=m.group_id AND g.status='ENABLED'
        WHERE m.starts_at &lt;= #{cutoff} AND m.ends_at IS NULL
        AND NOT EXISTS(SELECT 1 FROM nx_support_group_member_history other_m
          WHERE other_m.agent_admin_id=m.agent_admin_id AND other_m.id&lt;&gt;m.id
          AND other_m.starts_at &lt;= #{cutoff} AND (other_m.ends_at IS NULL OR other_m.ends_at&gt;#{cutoff}))
        ORDER BY m.agent_admin_id</script>
        """)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<SamplingMember> members(@Param("cutoff") LocalDateTime cutoff);

    record Viewer(long adminId,boolean superAdmin,boolean service,boolean supervisor) { }
    record SamplingGroup(long id,long supervisorId) { }
    record SamplingMember(long adminId,long groupId) { }
}
