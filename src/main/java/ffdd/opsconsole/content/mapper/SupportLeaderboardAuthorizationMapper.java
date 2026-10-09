package ffdd.opsconsole.content.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;

/** Current grants for public aggregate reads; never a private ReadScope bypass. */
@SuppressWarnings("MybatisPlusBaseMapper") // Fixed current-read SQL; generic CRUD would bypass these authorization predicates.
public interface SupportLeaderboardAuthorizationMapper {
    @Select("SELECT id,version FROM nx_admin WHERE id=#{actor} AND status=1 AND is_deleted=0 FOR SHARE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Account account(@Param("actor") long actor);

    @Select("""
        SELECT rr.id relationId,r.id roleId,r.role_code code FROM nx_admin_role_relation rr
        JOIN nx_admin_role r ON r.id=rr.role_id AND r.status=1 AND r.is_deleted=0
        WHERE rr.admin_id=#{actor} AND rr.is_deleted=0 ORDER BY rr.id FOR SHARE
        """)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Role> roles(@Param("actor") long actor);

    @Select("""
        SELECT rr.id relationId,r.id roleId,rp.id rolePermissionId,p.id permissionId,p.permission_code code
        FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id AND r.status=1 AND r.is_deleted=0
        JOIN nx_admin_role_permission rp ON rp.role_id=r.id AND rp.is_deleted=0
        JOIN nx_admin_permission p ON p.id=rp.permission_id AND p.status=1 AND p.is_deleted=0 AND p.resource_type='API'
        WHERE rr.admin_id=#{actor} AND rr.is_deleted=0 AND p.permission_code IN ('service_m1_read','service_m3_read')
        ORDER BY rr.id,rp.id,p.id FOR SHARE
        """)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Grant> grants(@Param("actor") long actor);

    @Select("""
        <script>SELECT scope_q.id,scope_q.qualification_kind kind,scope_q.version,p.version profileVersion
        FROM nx_support_account_qualification_history scope_q
        LEFT JOIN nx_support_agent_profile p ON p.admin_id=scope_q.admin_id AND p.enabled=1 AND p.is_deleted=0
        WHERE scope_q.admin_id=#{actor} AND scope_q.state='ENABLED'
        AND (scope_q.qualification_kind='SUPERVISOR' OR scope_q.qualification_kind='SERVICE' AND p.admin_id IS NOT NULL)
        AND
        """ + SupportGroupMapper.UNIQUE_QUALIFICATION + " ORDER BY scope_q.id FOR SHARE</script>")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Qualification> qualifications(@Param("actor") long actor);

    @Select("<script>SELECT scope_member.id,scope_member.group_id groupId,scope_member.version,"
        + "g.name,g.version groupVersion FROM nx_support_group_member_history scope_member "
        + "JOIN nx_support_group g ON g.id=scope_member.group_id AND g.status='ENABLED' "
        + "WHERE scope_member.agent_admin_id=#{actor} AND " + SupportGroupMapper.UNIQUE_MEMBER
        + " ORDER BY scope_member.id FOR SHARE</script>")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Member> ownGroups(@Param("actor") long actor);

    @Select("<script>SELECT scope_group.id,scope_group.name,scope_group.version,scope_group.supervisor_admin_id supervisorId "
        + "FROM nx_support_group scope_group WHERE scope_group.status='ENABLED' "
        + "AND (#{superAdmin}=TRUE OR scope_group.supervisor_admin_id=#{actor}) AND "
        + SupportGroupMapper.CURRENT_GROUP_OWNER + " ORDER BY scope_group.id FOR SHARE</script>")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Group> managedGroups(@Param("actor") long actor,@Param("superAdmin") boolean superAdmin);

    @Select("SELECT avatar_asset_id assetId,avatar_version version FROM nx_admin_account_state WHERE admin_id=#{admin} AND is_deleted=0 FOR SHARE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    AssetReference avatarReference(@Param("admin") long admin);

    record Account(Long id,Long version) { }
    record Role(Long relationId,Long roleId,String code) { }
    record Grant(Long relationId,Long roleId,Long rolePermissionId,Long permissionId,String code) { }
    record Qualification(Long id,String kind,Long version,Long profileVersion) { }
    record Member(Long id,Long groupId,Long version,String name,Long groupVersion) { }
    record Group(Long id,String name,Long version,Long supervisorId) { }
    record AssetReference(String assetId,Long version) { }
}
