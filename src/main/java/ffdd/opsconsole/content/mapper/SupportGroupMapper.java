package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

// Group, qualification and ownership histories use guarded cross-table statements, not generic entity CRUD.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportGroupMapper {
    String GROUP_COLUMNS="id,name,supervisor_admin_id supervisorAdminId,status,version";
    String QUALIFICATION_COLUMNS="id,admin_id adminId,qualification_kind qualificationKind,state,version,starts_at startsAt";
    String MEMBER_COLUMNS="id,agent_admin_id agentAdminId,group_id groupId,version,starts_at startsAt";
    // Fixed aliases let customer, group and directory readers share the same current facts.
    String UNIQUE_QUALIFICATION = """
        scope_q.starts_at &lt;= UTC_TIMESTAMP(6) AND scope_q.ends_at IS NULL
        AND NOT EXISTS (SELECT 1 FROM nx_support_account_qualification_history scope_q_other
          WHERE scope_q_other.admin_id=scope_q.admin_id AND scope_q_other.qualification_kind=scope_q.qualification_kind
            AND scope_q_other.id&lt;&gt;scope_q.id AND scope_q_other.starts_at &lt;= UTC_TIMESTAMP(6)
            AND (scope_q_other.ends_at IS NULL OR scope_q_other.ends_at&gt;UTC_TIMESTAMP(6)) FOR SHARE)
        """;
    String UNIQUE_QUALIFICATION_SNAPSHOT = """
        scope_q.starts_at &lt;= UTC_TIMESTAMP(6) AND scope_q.ends_at IS NULL
        AND NOT EXISTS(SELECT 1 FROM nx_support_account_qualification_history scope_q_other
          WHERE scope_q_other.admin_id=scope_q.admin_id AND scope_q_other.qualification_kind=scope_q.qualification_kind
            AND scope_q_other.id&lt;&gt;scope_q.id AND scope_q_other.starts_at &lt;= UTC_TIMESTAMP(6)
            AND (scope_q_other.ends_at IS NULL OR scope_q_other.ends_at&gt;UTC_TIMESTAMP(6)))
        """;
    String UNIQUE_MEMBER = """
        scope_member.starts_at &lt;= UTC_TIMESTAMP(6) AND scope_member.ends_at IS NULL
        AND NOT EXISTS (SELECT 1 FROM nx_support_group_member_history scope_member_other
          WHERE scope_member_other.agent_admin_id=scope_member.agent_admin_id AND scope_member_other.id&lt;&gt;scope_member.id
            AND scope_member_other.starts_at &lt;= UTC_TIMESTAMP(6)
            AND (scope_member_other.ends_at IS NULL OR scope_member_other.ends_at&gt;UTC_TIMESTAMP(6)) FOR SHARE)
        """;
    String SCOPE_SUPER_ADMIN = """
        EXISTS (SELECT 1 FROM nx_admin scope_actor
          JOIN nx_admin_role_relation scope_rr ON scope_rr.admin_id=scope_actor.id AND scope_rr.is_deleted=0
          JOIN nx_admin_role scope_role ON scope_role.id=scope_rr.role_id AND scope_role.is_deleted=0 AND scope_role.status=1
          WHERE scope_actor.id=#{scope.actorId} AND scope_actor.status=1 AND scope_actor.is_deleted=0
            AND scope_role.role_code IN ('SUPER','SUPERADMIN','SUPER_ADMIN') FOR SHARE)
        """;
    String SCOPE_QUALIFICATION = """
        EXISTS (SELECT 1 FROM nx_support_account_qualification_history scope_q
          JOIN nx_admin scope_actor ON scope_actor.id=scope_q.admin_id AND scope_actor.status=1 AND scope_actor.is_deleted=0
          WHERE scope_q.admin_id=#{scope.actorId} AND scope_q.state='ENABLED' AND
        """ + UNIQUE_QUALIFICATION + """
          AND EXISTS (SELECT 1 FROM nx_admin_role_relation scope_rr JOIN nx_admin_role scope_role ON scope_role.id=scope_rr.role_id
            WHERE scope_rr.admin_id=scope_actor.id AND scope_rr.is_deleted=0 AND scope_role.is_deleted=0 AND scope_role.status=1
              AND scope_role.role_code IN ('SUPPORT','SUPER_ADMIN') FOR SHARE)
        """;
    String SCOPE_SERVICE = SCOPE_QUALIFICATION + " AND scope_q.qualification_kind='SERVICE' AND EXISTS(SELECT 1 FROM nx_support_agent_profile scope_profile WHERE scope_profile.admin_id=scope_q.admin_id AND scope_profile.enabled=1 AND scope_profile.is_deleted=0 FOR SHARE) FOR SHARE) ";
    String SCOPE_SUPERVISOR = SCOPE_QUALIFICATION + " AND scope_q.qualification_kind='SUPERVISOR' FOR SHARE) ";
    String CURRENT_GROUP_OWNER = """
        EXISTS (SELECT 1 FROM nx_support_group_owner_history scope_owner
          WHERE scope_owner.group_id=scope_group.id AND scope_owner.supervisor_admin_id=scope_group.supervisor_admin_id
            AND scope_owner.starts_at &lt;= UTC_TIMESTAMP(6) AND scope_owner.ends_at IS NULL
            AND NOT EXISTS (SELECT 1 FROM nx_support_group_owner_history scope_owner_other
              WHERE scope_owner_other.group_id=scope_owner.group_id AND scope_owner_other.id&lt;&gt;scope_owner.id
                AND scope_owner_other.starts_at &lt;= UTC_TIMESTAMP(6)
                AND (scope_owner_other.ends_at IS NULL OR scope_owner_other.ends_at&gt;UTC_TIMESTAMP(6)) FOR SHARE)
          FOR SHARE)
        """;
    /** Expands to AND (...); callers provide scope_group and a WHERE, never a nullable authorization bypass. */
    String GROUP_SCOPE_PREDICATE = """
        AND (<choose>
          <when test="scope != null and scope.mode != null and scope.mode.name() == 'ALL'">
        """ + SCOPE_SUPER_ADMIN + """
          </when>
          <when test="scope != null and scope.mode != null and scope.mode.name() == 'MANAGED'">
        """ + SCOPE_SUPERVISOR + " AND scope_group.supervisor_admin_id=#{scope.actorId} AND " + CURRENT_GROUP_OWNER + """
          </when><otherwise>1=0</otherwise></choose>)
        <if test="scope != null and scope.requestedGroupId != null">AND scope_group.id=#{scope.requestedGroupId}</if>
        <if test="scope != null and scope.requestedAgentId != null">
          AND EXISTS (SELECT 1 FROM nx_support_group_member_history scope_member
            WHERE scope_member.agent_admin_id=#{scope.requestedAgentId} AND scope_member.group_id=scope_group.id AND
        """ + UNIQUE_MEMBER + " FOR SHARE)</if> ";

    @Select("<script>SELECT scope_q.id,scope_q.admin_id adminId,scope_q.qualification_kind qualificationKind,scope_q.state,scope_q.version,scope_q.starts_at startsAt FROM nx_support_account_qualification_history scope_q JOIN nx_admin scope_actor ON scope_actor.id=scope_q.admin_id WHERE scope_q.admin_id=#{id} AND scope_q.qualification_kind=#{kind} AND scope_q.state='ENABLED' AND scope_actor.status=1 AND scope_actor.is_deleted=0 AND " + UNIQUE_QUALIFICATION + " AND EXISTS (SELECT 1 FROM nx_admin_role_relation scope_rr JOIN nx_admin_role scope_role ON scope_role.id=scope_rr.role_id WHERE scope_rr.admin_id=scope_actor.id AND scope_rr.is_deleted=0 AND scope_role.status=1 AND scope_role.is_deleted=0 AND scope_role.role_code IN ('SUPPORT','SUPER_ADMIN') FOR SHARE) FOR SHARE</script>")
    Qualification qualificationCurrent(@Param("id") Long id,@Param("kind") String kind);
    @Select("<script>SELECT scope_q.id,scope_q.admin_id adminId,scope_q.qualification_kind qualificationKind,scope_q.state,scope_q.version,scope_q.starts_at startsAt FROM nx_support_account_qualification_history scope_q JOIN nx_admin a ON a.id=scope_q.admin_id WHERE scope_q.admin_id=#{id} AND scope_q.qualification_kind=#{kind} AND scope_q.state='ENABLED' AND scope_q.starts_at &lt;= UTC_TIMESTAMP(6) AND scope_q.ends_at IS NULL AND a.status=1 AND a.is_deleted=0 AND NOT EXISTS(SELECT 1 FROM nx_support_account_qualification_history other_q WHERE other_q.admin_id=scope_q.admin_id AND other_q.qualification_kind=scope_q.qualification_kind AND other_q.id&lt;&gt;scope_q.id AND other_q.starts_at &lt;= UTC_TIMESTAMP(6) AND (other_q.ends_at IS NULL OR other_q.ends_at&gt;UTC_TIMESTAMP(6))) AND EXISTS(SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id WHERE rr.admin_id=a.id AND rr.is_deleted=0 AND r.is_deleted=0 AND r.status=1 AND r.role_code IN ('SUPPORT','SUPER_ADMIN'))</script>")
    Qualification qualificationSnapshot(@Param("id") Long id,@Param("kind") String kind);
    @Select("<script>SELECT scope_member.id,scope_member.agent_admin_id agentAdminId,scope_member.group_id groupId,scope_member.version,scope_member.starts_at startsAt FROM nx_support_group_member_history scope_member WHERE scope_member.agent_admin_id=#{id} AND " + UNIQUE_MEMBER + " FOR SHARE</script>")
    Member memberCurrent(@Param("id") Long id);
    @Select("SELECT "+MEMBER_COLUMNS+" FROM nx_support_group_member_history WHERE agent_admin_id=#{id} AND ends_at IS NULL")
    Member memberSnapshot(Long id);
    @Select("<script>SELECT " + GROUP_COLUMNS + " FROM nx_support_group scope_group WHERE scope_group.id=#{id} " + GROUP_SCOPE_PREDICATE + " FOR SHARE</script>")
    Group readableGroup(@Param("scope") ReadScope scope,@Param("id") Long id);
    @Select("<script>SELECT " + GROUP_COLUMNS + " FROM nx_support_group scope_group WHERE 1=1 " + GROUP_SCOPE_PREDICATE + " ORDER BY scope_group.id FOR SHARE</script>")
    List<Group> scopedGroups(@Param("scope") ReadScope scope);
    /** AND fragment for the nx_admin scope_agent alias; unavailable members remain visible to their group. */
    String AGENT_SCOPE_PREDICATE = """
        AND scope_agent.is_deleted=0 AND (<choose>
          <when test="scope != null and scope.mode != null and scope.mode.name() == 'PERSONAL'">
        """ + SCOPE_SERVICE + " AND scope_agent.id=#{scope.actorId} " + """
          </when><when test="scope != null and scope.mode != null and scope.mode.name() == 'ALL'">
        """ + SCOPE_SUPER_ADMIN + """
            <if test="scope.requestedGroupId != null">
              AND EXISTS (SELECT 1 FROM nx_support_group_member_history scope_member
                WHERE scope_member.agent_admin_id=scope_agent.id AND scope_member.group_id=#{scope.requestedGroupId} AND
        """ + UNIQUE_MEMBER + " FOR SHARE)</if> " + """
          </when><when test="scope != null and scope.mode != null and scope.mode.name() == 'MANAGED'">
            EXISTS (SELECT 1 FROM nx_support_group_member_history scope_member
              JOIN nx_support_group scope_group ON scope_group.id=scope_member.group_id
              WHERE scope_member.agent_admin_id=scope_agent.id AND
        """ + UNIQUE_MEMBER + GROUP_SCOPE_PREDICATE + " FOR SHARE) " + """
          </when><otherwise>1=0</otherwise></choose>)
          <if test="scope != null and scope.requestedAgentId != null">AND scope_agent.id=#{scope.requestedAgentId}</if>
        """;
    @Select("<script>SELECT COUNT(*) FROM nx_admin scope_agent WHERE scope_agent.id=#{agent} " + AGENT_SCOPE_PREDICATE + " FOR SHARE</script>")
    int readableAgent(@Param("scope") ReadScope scope,@Param("agent") Long agent);
    @Select("""
        <script>SELECT scope_route.id,scope_route.customer_id customerId,scope_route.group_id groupId,
          scope_route.version,scope_route.starts_at startsAt FROM nx_support_customer_route_history scope_route
        WHERE scope_route.customer_id=#{id} AND scope_route.starts_at &lt;= UTC_TIMESTAMP(6) AND scope_route.ends_at IS NULL
          AND NOT EXISTS(SELECT 1 FROM nx_support_customer_route_history other_route
            WHERE other_route.customer_id=scope_route.customer_id AND other_route.id&lt;&gt;scope_route.id
              AND other_route.starts_at &lt;= UTC_TIMESTAMP(6)
              AND (other_route.ends_at IS NULL OR other_route.ends_at&gt;UTC_TIMESTAMP(6)) FOR SHARE) FOR SHARE</script>
        """)
    Route routeCurrent(Long id);
    // Preflight lock planning only; these snapshots never grant access or choose an allocation by themselves.
    @Select("SELECT id,customer_id customerId,group_id groupId,version,starts_at startsAt FROM nx_support_customer_route_history WHERE customer_id=#{id} AND ends_at IS NULL")
    Route routeSnapshot(Long id);
    @Select("SELECT agent_admin_id FROM nx_support_group_member_history WHERE group_id=#{id} AND ends_at IS NULL ORDER BY agent_admin_id")
    List<Long> candidateIdsSnapshot(Long id);
    @Select("SELECT COUNT(*) FROM nx_support_customer_route_history WHERE customer_id=#{id} AND ends_at IS NULL FOR SHARE")
    int openRouteCount(Long id);
    @Select("<script>SELECT scope_owner.id,scope_owner.group_id groupId,scope_owner.supervisor_admin_id supervisorAdminId,scope_owner.version,scope_owner.starts_at startsAt FROM nx_support_group_owner_history scope_owner JOIN nx_support_group scope_group ON scope_group.id=scope_owner.group_id WHERE scope_owner.group_id=#{id} AND scope_owner.supervisor_admin_id=scope_group.supervisor_admin_id AND scope_owner.starts_at &lt;= UTC_TIMESTAMP(6) AND scope_owner.ends_at IS NULL AND NOT EXISTS (SELECT 1 FROM nx_support_group_owner_history other_owner WHERE other_owner.group_id=scope_owner.group_id AND other_owner.id&lt;&gt;scope_owner.id AND other_owner.starts_at &lt;= UTC_TIMESTAMP(6) AND (other_owner.ends_at IS NULL OR other_owner.ends_at&gt;UTC_TIMESTAMP(6)) FOR SHARE) FOR SHARE</script>")
    Owner ownerCurrent(Long id);
    @Select("""
        <script>SELECT scope_member.agent_admin_id agentId,a.version accountVersion,p.version profileVersion,
          scope_member.id memberId,scope_member.version memberVersion,scope_q.id qualificationId,scope_q.version qualificationVersion
        FROM nx_support_group_member_history scope_member JOIN nx_admin a ON a.id=scope_member.agent_admin_id
          JOIN nx_support_agent_profile p ON p.admin_id=a.id
          JOIN nx_support_account_qualification_history scope_q ON scope_q.admin_id=a.id
        WHERE scope_member.group_id=#{group} AND a.status=1 AND a.is_deleted=0 AND p.enabled=1 AND p.is_deleted=0
          AND scope_q.qualification_kind='SERVICE' AND scope_q.state='ENABLED' AND
        """ + UNIQUE_MEMBER + " AND " + UNIQUE_QUALIFICATION + """
          AND EXISTS(SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id
            WHERE rr.admin_id=a.id AND rr.is_deleted=0 AND r.is_deleted=0 AND r.status=1 AND r.role_code IN ('SUPPORT','SUPER_ADMIN') FOR SHARE)
        ORDER BY a.id FOR SHARE</script>
        """)
    List<Candidate> candidates(Long group);
    @Select("SELECT version FROM nx_support_binding_pool WHERE customer_id=#{id} FOR SHARE") Long routePoolVersion(Long id);
    @Select("SELECT COUNT(*) FROM nx_support_agent_user_assignment WHERE user_id=#{id} AND status='ACTIVE' AND is_deleted=0 FOR SHARE") int currentBindingCount(Long id);
    @Update("UPDATE nx_support_customer_route_history SET ends_at=#{at} WHERE id=#{id} AND version=#{version} AND ends_at IS NULL")
    int closeRoute(@Param("id") Long id,@Param("version") Long version,@Param("at") LocalDateTime at);
    @Insert("INSERT INTO nx_support_customer_route_history(customer_id,group_id,starts_at,version,actor_admin_id,reason,operation_id) VALUES(#{customer},#{group},#{at},#{version},#{actor},#{reason},#{operation})")
    int insertRoute(@Param("customer") Long customer,@Param("group") Long group,@Param("at") LocalDateTime at,
            @Param("version") Long version,@Param("actor") Long actor,@Param("reason") String reason,@Param("operation") String operation);
    @Select("""
        SELECT COUNT(*) FROM nx_support_random_preview p
          JOIN JSON_TABLE(p.customers_json,'$[*]' COLUMNS(group_id BIGINT PATH '$.groupId' NULL ON ERROR)) frozen ON frozen.group_id=#{id}
        WHERE (p.expires_at>UTC_TIMESTAMP(6) AND NOT EXISTS (SELECT 1 FROM nx_support_random_operation done
                 WHERE done.preview_id=p.id AND done.status='COMPLETED' FOR SHARE))
          OR EXISTS (SELECT 1 FROM nx_support_random_operation running WHERE running.preview_id=p.id AND running.status='RUNNING' FOR SHARE)
        FOR SHARE OF p
        """)
    long pendingGroupOperations(Long id);
    @Select("SELECT UTC_TIMESTAMP(6)") LocalDateTime now();
    @Select("SELECT COUNT(*) FROM nx_support_migration WHERE id='support-groups-20261007'") int cutoverApplied();
    @Select("SELECT id,status,version FROM nx_admin WHERE id=#{id} AND is_deleted=0 FOR UPDATE") Account lockAccount(Long id);
    @Select("SELECT COUNT(*) FROM nx_admin a WHERE a.id=#{id} AND a.is_deleted=0 AND EXISTS(SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id WHERE rr.admin_id=a.id AND rr.is_deleted=0 AND r.status=1 AND r.is_deleted=0 AND r.role_code IN ('SUPPORT','SUPER_ADMIN'))")
    int compatibleAccount(Long id);
    @Select("SELECT COUNT(*) FROM nx_support_account_qualification_history q JOIN nx_admin a ON a.id=q.admin_id WHERE q.admin_id=#{id} AND q.qualification_kind=#{kind} AND q.state='ENABLED' AND q.ends_at IS NULL AND a.status=1 AND a.is_deleted=0 AND EXISTS(SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id WHERE rr.admin_id=a.id AND rr.is_deleted=0 AND r.status=1 AND r.is_deleted=0 AND r.role_code IN ('SUPPORT','SUPER_ADMIN'))")
    int qualified(@Param("id") Long id,@Param("kind") String kind);
    @Select("SELECT "+QUALIFICATION_COLUMNS+" FROM nx_support_account_qualification_history WHERE admin_id=#{id} AND qualification_kind=#{kind} AND ends_at IS NULL FOR SHARE")
    Qualification qualification(@Param("id") Long id,@Param("kind") String kind);
    @Select("SELECT "+QUALIFICATION_COLUMNS+" FROM nx_support_account_qualification_history WHERE admin_id=#{id} AND ends_at IS NULL ORDER BY qualification_kind FOR SHARE")
    List<Qualification> qualifications(Long id);
    @Select("SELECT "+MEMBER_COLUMNS+" FROM nx_support_group_member_history WHERE agent_admin_id=#{id} AND ends_at IS NULL") Member member(Long id);
    @Select("SELECT "+GROUP_COLUMNS+" FROM nx_support_group WHERE id=#{id}") Group group(Long id);
    @Select("SELECT "+GROUP_COLUMNS+" FROM nx_support_group WHERE id=#{id} FOR UPDATE") Group lockGroup(Long id);
    @Select("<script>SELECT "+GROUP_COLUMNS+" FROM nx_support_group <if test='owner != null'>WHERE supervisor_admin_id=#{owner}</if> ORDER BY id</script>") List<Group> groups(@Param("owner") Long owner);
    @Select("SELECT COUNT(*) FROM nx_support_group WHERE supervisor_admin_id=#{id} FOR SHARE") long ownedGroupCount(Long id);
    @Select("SELECT COUNT(*) FROM nx_support_group_member_history WHERE group_id=#{id} AND ends_at IS NULL FOR SHARE") long memberCount(Long id);
    @Select("SELECT COUNT(*) FROM nx_support_customer_route_history WHERE group_id=#{id} AND ends_at IS NULL FOR SHARE") long routeCount(Long id);
    @Select("SELECT COUNT(*) FROM nx_support_agent_user_assignment WHERE agent_admin_id=#{id} AND status='ACTIVE' AND is_deleted=0 FOR SHARE") long boundCount(Long id);
    @Select("SELECT m.agent_admin_id adminId,m.version,m.group_id groupId,a.status accountStatus,IF(a.status=1,0,1) handoverRequired,(SELECT COUNT(*) FROM nx_support_agent_user_assignment x WHERE x.agent_admin_id=m.agent_admin_id AND x.status='ACTIVE' AND x.is_deleted=0) boundCustomers FROM nx_support_group_member_history m JOIN nx_admin a ON a.id=m.agent_admin_id WHERE m.group_id=#{id} AND m.ends_at IS NULL ORDER BY m.agent_admin_id")
    List<Map<String,Object>> members(Long id);
    @Select("SELECT q.admin_id adminId,q.state,q.version,a.status accountStatus,(SELECT COUNT(*) FROM nx_support_group g WHERE g.supervisor_admin_id=q.admin_id) groupCount FROM nx_support_account_qualification_history q JOIN nx_admin a ON a.id=q.admin_id WHERE q.qualification_kind='SUPERVISOR' AND q.ends_at IS NULL AND q.state<>'REMOVED' ORDER BY q.admin_id")
    List<Map<String,Object>> supervisors();
    @Select("SELECT DISTINCT agent_admin_id FROM nx_support_group_member_history WHERE ends_at IS NULL AND group_id IS NOT NULL ORDER BY agent_admin_id") List<Long> memberIds();
    @Insert("INSERT INTO nx_support_group(name,supervisor_admin_id,status,version,created_at,updated_at) VALUES(#{name},#{owner},'ENABLED',1,#{at},#{at})")
    @Options(useGeneratedKeys=true,keyProperty="id") int insertGroup(Map<String,Object> values);
    @Update("UPDATE nx_support_group SET name=#{name},version=version+1,updated_at=#{at} WHERE id=#{id} AND version=#{version}")
    int rename(@Param("id") Long id,@Param("name") String name,@Param("version") Long version,@Param("at") LocalDateTime at);
    @Update("UPDATE nx_support_group SET status=#{status},version=version+1,updated_at=#{at} WHERE id=#{id} AND version=#{version}")
    int status(@Param("id") Long id,@Param("status") String status,@Param("version") Long version,@Param("at") LocalDateTime at);
    @Update("UPDATE nx_support_group SET supervisor_admin_id=#{owner},version=version+1,updated_at=#{at} WHERE id=#{id} AND version=#{version}")
    int owner(@Param("id") Long id,@Param("owner") Long owner,@Param("version") Long version,@Param("at") LocalDateTime at);
    @Update("UPDATE nx_support_group SET version=version+1,updated_at=#{at} WHERE id=#{id} AND version=#{version}")
    int touch(@Param("id") Long id,@Param("version") Long version,@Param("at") LocalDateTime at);
    @Insert("INSERT INTO nx_support_group_owner_history(group_id,supervisor_admin_id,starts_at,version,actor_admin_id,reason,operation_id) VALUES(#{group},#{owner},#{at},#{version},#{actor},#{reason},#{operation})")
    int insertOwner(@Param("group") Long group,@Param("owner") Long owner,@Param("at") LocalDateTime at,@Param("version") Long version,@Param("actor") Long actor,@Param("reason") String reason,@Param("operation") String operation);
    @Update("UPDATE nx_support_group_owner_history SET ends_at=#{at} WHERE group_id=#{group} AND supervisor_admin_id=#{owner} AND ends_at IS NULL")
    int closeOwner(@Param("group") Long group,@Param("owner") Long owner,@Param("at") LocalDateTime at);
    @Update("UPDATE nx_support_group_member_history SET ends_at=#{at} WHERE id=#{id} AND version=#{version} AND ends_at IS NULL")
    int closeMember(@Param("id") Long id,@Param("version") Long version,@Param("at") LocalDateTime at);
    @Insert("INSERT INTO nx_support_group_member_history(agent_admin_id,group_id,starts_at,version,actor_admin_id,reason,operation_id) VALUES(#{agent},#{group},#{at},#{version},#{actor},#{reason},#{operation})")
    int insertMember(@Param("agent") Long agent,@Param("group") Long group,@Param("at") LocalDateTime at,@Param("version") Long version,@Param("actor") Long actor,@Param("reason") String reason,@Param("operation") String operation);
    @Update("UPDATE nx_support_account_qualification_history SET ends_at=#{at} WHERE id=#{id} AND version=#{version} AND ends_at IS NULL")
    int closeQualification(@Param("id") Long id,@Param("version") Long version,@Param("at") LocalDateTime at);
    @Insert("INSERT INTO nx_support_account_qualification_history(admin_id,qualification_kind,state,starts_at,version,actor_admin_id,reason,operation_id) VALUES(#{admin},#{kind},#{state},#{at},#{version},#{actor},#{reason},#{operation})")
    int insertQualification(@Param("admin") Long admin,@Param("kind") String kind,@Param("state") String state,@Param("at") LocalDateTime at,@Param("version") Long version,@Param("actor") Long actor,@Param("reason") String reason,@Param("operation") String operation);
}
