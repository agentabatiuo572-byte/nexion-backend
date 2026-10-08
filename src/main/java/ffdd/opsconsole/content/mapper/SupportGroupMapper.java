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
    @Select("SELECT UTC_TIMESTAMP(6)") LocalDateTime now();
    @Select("SELECT COUNT(*) FROM nx_support_migration WHERE id='support-groups-20261007'") int cutoverApplied();
    @Select("SELECT id,status,version FROM nx_admin WHERE id=#{id} AND is_deleted=0 FOR UPDATE") Account lockAccount(Long id);
    @Select("SELECT COUNT(*) FROM nx_admin a WHERE a.id=#{id} AND a.is_deleted=0 AND EXISTS(SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id WHERE rr.admin_id=a.id AND rr.is_deleted=0 AND r.status=1 AND r.is_deleted=0 AND r.role_code IN ('SUPPORT','SUPER_ADMIN'))")
    int compatibleAccount(Long id);
    @Select("SELECT COUNT(*) FROM nx_support_account_qualification_history q JOIN nx_admin a ON a.id=q.admin_id WHERE q.admin_id=#{id} AND q.qualification_kind=#{kind} AND q.state='ENABLED' AND q.ends_at IS NULL AND a.status=1 AND a.is_deleted=0 AND EXISTS(SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id WHERE rr.admin_id=a.id AND rr.is_deleted=0 AND r.status=1 AND r.is_deleted=0 AND r.role_code IN ('SUPPORT','SUPER_ADMIN'))")
    int qualified(@Param("id") Long id,@Param("kind") String kind);
    @Select("SELECT "+QUALIFICATION_COLUMNS+" FROM nx_support_account_qualification_history WHERE admin_id=#{id} AND qualification_kind=#{kind} AND ends_at IS NULL")
    Qualification qualification(@Param("id") Long id,@Param("kind") String kind);
    @Select("SELECT "+QUALIFICATION_COLUMNS+" FROM nx_support_account_qualification_history WHERE admin_id=#{id} AND ends_at IS NULL ORDER BY qualification_kind")
    List<Qualification> qualifications(Long id);
    @Select("SELECT "+MEMBER_COLUMNS+" FROM nx_support_group_member_history WHERE agent_admin_id=#{id} AND ends_at IS NULL") Member member(Long id);
    @Select("SELECT "+GROUP_COLUMNS+" FROM nx_support_group WHERE id=#{id}") Group group(Long id);
    @Select("SELECT "+GROUP_COLUMNS+" FROM nx_support_group WHERE id=#{id} FOR UPDATE") Group lockGroup(Long id);
    @Select("<script>SELECT "+GROUP_COLUMNS+" FROM nx_support_group <if test='owner != null'>WHERE supervisor_admin_id=#{owner}</if> ORDER BY id</script>") List<Group> groups(@Param("owner") Long owner);
    @Select("SELECT COUNT(*) FROM nx_support_group WHERE supervisor_admin_id=#{id}") long ownedGroupCount(Long id);
    @Select("SELECT COUNT(*) FROM nx_support_group_member_history WHERE group_id=#{id} AND ends_at IS NULL") long memberCount(Long id);
    @Select("SELECT COUNT(*) FROM nx_support_customer_route_history WHERE group_id=#{id} AND ends_at IS NULL") long routeCount(Long id);
    @Select("SELECT COUNT(*) FROM nx_support_agent_user_assignment WHERE agent_admin_id=#{id} AND status='ACTIVE' AND is_deleted=0") long boundCount(Long id);
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
