package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.SupportAssignment;
import ffdd.opsconsole.content.domain.SupportRules;
import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.SupportAgentAssignmentEntity;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** All mutable customer operations lock nx_user first, then read current assignment. */
public interface SupportBindingMapper extends BaseMapper<SupportAgentAssignmentEntity> {
    String UNIQUE_SCOPE_BINDING = """
        scope_binding.status='ACTIVE' AND scope_binding.is_deleted=0
        AND scope_binding.starts_at &lt;= UTC_TIMESTAMP(6) AND scope_binding.ends_at IS NULL
        AND NOT EXISTS (SELECT 1 FROM nx_support_agent_user_assignment scope_binding_other
          WHERE scope_binding_other.user_id=scope_binding.user_id AND scope_binding_other.id&lt;&gt;scope_binding.id
            AND scope_binding_other.is_deleted=0 AND scope_binding_other.starts_at &lt;= UTC_TIMESTAMP(6)
            AND (scope_binding_other.ends_at IS NULL OR scope_binding_other.ends_at&gt;UTC_TIMESTAMP(6)) FOR SHARE)
        """;
    String SCOPE_GROUP_BINDING = """
        EXISTS (SELECT 1 FROM nx_support_agent_user_assignment scope_binding
          JOIN nx_support_group_member_history scope_member ON scope_member.agent_admin_id=scope_binding.agent_admin_id
          JOIN nx_support_group scope_group ON scope_group.id=scope_member.group_id
          WHERE scope_binding.user_id=scope_customer.id AND
        """ + UNIQUE_SCOPE_BINDING + " AND " + SupportGroupMapper.UNIQUE_MEMBER + SupportGroupMapper.GROUP_SCOPE_PREDICATE + """
          <if test="scope.requestedAgentId != null">AND scope_binding.agent_admin_id=#{scope.requestedAgentId}</if>
          FOR SHARE)
        """;
    String SCOPE_GROUP_QUEUE = """
        (<choose><when test="scope.requestedAgentId == null">
          NOT EXISTS (SELECT 1 FROM nx_support_agent_user_assignment scope_active_binding
            WHERE scope_active_binding.user_id=scope_customer.id AND scope_active_binding.status='ACTIVE'
              AND scope_active_binding.is_deleted=0 FOR SHARE)
          AND EXISTS (SELECT 1 FROM nx_support_customer_route_history scope_route
            JOIN nx_support_group scope_group ON scope_group.id=scope_route.group_id
            WHERE scope_route.customer_id=scope_customer.id AND scope_route.group_id IS NOT NULL
              AND scope_route.starts_at &lt;= UTC_TIMESTAMP(6) AND scope_route.ends_at IS NULL
              AND NOT EXISTS (SELECT 1 FROM nx_support_customer_route_history scope_route_other
                WHERE scope_route_other.customer_id=scope_route.customer_id AND scope_route_other.id&lt;&gt;scope_route.id
                  AND scope_route_other.starts_at &lt;= UTC_TIMESTAMP(6)
                  AND (scope_route_other.ends_at IS NULL OR scope_route_other.ends_at&gt;UTC_TIMESTAMP(6)) FOR SHARE)
        """ + SupportGroupMapper.GROUP_SCOPE_PREDICATE + " FOR SHARE) </when><otherwise>1=0</otherwise></choose>) ";
    /** AND fragment for a one-to-one nx_user scope_customer join; null scope always denies. */
    String CUSTOMER_SCOPE_PREDICATE = """
        AND scope_customer.is_deleted=0 AND (<choose>
          <when test="scope != null and scope.mode != null and scope.mode.name() == 'PERSONAL'">
        """ + SupportGroupMapper.SCOPE_SERVICE + """
            AND EXISTS (SELECT 1 FROM nx_support_agent_user_assignment scope_binding
              WHERE scope_binding.user_id=scope_customer.id AND scope_binding.agent_admin_id=#{scope.actorId} AND
        """ + UNIQUE_SCOPE_BINDING + " FOR SHARE) " + """
          </when><when test="scope != null and scope.mode != null and scope.mode.name() == 'MANAGED'">
        """ + "(" + SCOPE_GROUP_BINDING + " OR " + SCOPE_GROUP_QUEUE + ") " + """
          </when><when test="scope != null and scope.mode != null and scope.mode.name() == 'ALL'">
        """ + SupportGroupMapper.SCOPE_SUPER_ADMIN + """
            <choose><when test="scope.requestedGroupId != null">
        """ + " AND (" + SCOPE_GROUP_BINDING + " OR " + SCOPE_GROUP_QUEUE + ") " + """
            </when><when test="scope.requestedAgentId != null">
              AND EXISTS (SELECT 1 FROM nx_support_agent_user_assignment scope_binding
                WHERE scope_binding.user_id=scope_customer.id AND scope_binding.agent_admin_id=#{scope.requestedAgentId} AND
        """ + UNIQUE_SCOPE_BINDING + " FOR SHARE) " + """
            </when></choose>
          </when><otherwise>1=0</otherwise></choose>)
        """;
    @Select("<script>SELECT COUNT(*) FROM nx_user scope_customer WHERE scope_customer.id=#{id} " + CUSTOMER_SCOPE_PREDICATE + " FOR SHARE</script>")
    int readableCustomer(@Param("scope") ReadScope scope,@Param("id") Long id);
    String SCOPED_POOL_FILTER="FROM nx_support_binding_pool p JOIN nx_user scope_customer ON scope_customer.id=p.customer_id WHERE 1=1 "
            + CUSTOMER_SCOPE_PREDICATE + " AND NOT EXISTS(SELECT 1 FROM nx_support_agent_user_assignment active_pool_binding WHERE active_pool_binding.user_id=p.customer_id AND active_pool_binding.status='ACTIVE' AND active_pool_binding.is_deleted=0 FOR SHARE) <if test='reason != null'>AND p.reason=#{reason}</if> <if test='keyword != null'>AND (scope_customer.nickname LIKE CONCAT('%',#{keyword},'%') OR CAST(scope_customer.id AS CHAR)=#{keyword})</if> ";
    @Select("<script>SELECT COUNT(*) "+SCOPED_POOL_FILTER+" FOR SHARE</script>")
    long scopedPoolCount(@Param("scope") ReadScope scope,@Param("reason") String reason,@Param("keyword") String keyword);
    @Select("<script>SELECT p.customer_id customerId,p.reason,p.version,p.entered_at enteredAt,p.auto_eligible autoEligible,p.auto_rule_version autoRuleVersion,p.auto_attempt_state autoAttemptState,p.attempts,p.last_attempt_at lastAttemptAt,p.last_outcome lastOutcome,p.operation_id operationId,scope_customer.sponsor_user_id inviterId,scope_customer.nickname "+SCOPED_POOL_FILTER+" ORDER BY p.customer_id LIMIT #{limit} OFFSET #{offset} FOR SHARE</script>")
    List<Map<String,Object>> scopedPool(@Param("scope") ReadScope scope,@Param("reason") String reason,@Param("keyword") String keyword,@Param("offset") long offset,@Param("limit") int limit);
    String SCOPED_HANDOVER_FILTER="FROM nx_support_agent_user_assignment x JOIN nx_user scope_customer ON scope_customer.id=x.user_id LEFT JOIN nx_admin a ON a.id=x.agent_admin_id LEFT JOIN nx_support_agent_profile p ON p.admin_id=a.id WHERE x.status='ACTIVE' AND x.is_deleted=0 " + CUSTOMER_SCOPE_PREDICATE
            + " <if test='unavailable'>AND NOT EXISTS(SELECT 1 " + SupportBindingMapper.ELIGIBLE_AGENT_FROM + " AND a.id=x.agent_admin_id FOR SHARE)</if> ";
    @Select("<script>SELECT COUNT(*) "+SCOPED_HANDOVER_FILTER+" FOR SHARE</script>")
    long scopedHandoverCount(@Param("scope") ReadScope scope,@Param("unavailable") boolean unavailable);
    @Select("<script>SELECT x.id assignmentId,x.user_id customerId,x.agent_admin_id agentAdminId,x.version,scope_customer.nickname "+SCOPED_HANDOVER_FILTER+" ORDER BY x.user_id LIMIT #{limit} OFFSET #{offset} FOR SHARE</script>")
    List<Map<String,Object>> scopedHandover(@Param("scope") ReadScope scope,@Param("unavailable") boolean unavailable,@Param("offset") long offset,@Param("limit") int limit);
    String CUSTOMER_FILTER="FROM nx_user u JOIN nx_support_agent_user_assignment a ON a.user_id=u.id AND a.status='ACTIVE' AND a.is_deleted=0 WHERE u.is_deleted=0 AND a.agent_admin_id=#{actor} <if test='keyword != null'>AND (u.nickname LIKE CONCAT('%',#{keyword},'%') OR CAST(u.id AS CHAR)=#{keyword})</if> <if test='status != null'>AND u.status=#{status}</if> <if test='id != null'>AND u.id=#{id}</if>";
    @Select("<script>SELECT COUNT(*) " + CUSTOMER_FILTER + "</script>")
    long ownedCustomerCount(@Param("actor") Long actor,@Param("keyword") String keyword,@Param("status") String status,@Param("id") Long id);
    @Select("<script>SELECT u.id " + CUSTOMER_FILTER + " ORDER BY u.id LIMIT #{limit} OFFSET #{offset}</script>")
    List<Long> ownedCustomers(@Param("actor") Long actor,@Param("keyword") String keyword,@Param("status") String status,@Param("id") Long id,@Param("limit") int limit,@Param("offset") long offset);
    @Select("SELECT COUNT(*) FROM nx_conversation_message m LEFT JOIN nx_support_reply_cursor r ON r.conversation_no=m.conversation_no WHERE m.conversation_no=#{no} AND m.is_deleted=0 AND m.sender_type='user' AND m.id>COALESCE(r.through_message_id,0)")
    long pendingReplies(String no);
    @Select("SELECT COUNT(*) FROM nx_conversation_message WHERE conversation_no=#{no} AND id=#{id} AND sender_type='user' AND is_deleted=0")
    int customerMessage(@Param("no") String no,@Param("id") Long id);
    @Insert("INSERT INTO nx_support_reply_cursor(conversation_no,through_message_id,reply_message_id) VALUES(#{no},#{through},#{reply}) ON DUPLICATE KEY UPDATE through_message_id=GREATEST(through_message_id,VALUES(through_message_id)),reply_message_id=VALUES(reply_message_id)")
    int handled(@Param("no") String no,@Param("through") Long through,@Param("reply") Long reply);
    @Select("SELECT id FROM nx_user WHERE id=#{id} AND is_deleted=0 FOR UPDATE")
    Long lockCustomer(Long id);
    @Select("SELECT COUNT(*) FROM nx_user WHERE id=#{id} AND is_deleted=0 AND sandbox=0")
    int canonicalCustomer(Long id);

    @Select("SELECT id FROM nx_admin WHERE id=#{id} AND status=1 AND is_deleted=0 FOR UPDATE")
    Long lockAgent(Long id);

    @Select("""
        SELECT p.id FROM nx_admin a
          JOIN nx_admin_role_relation rr ON rr.admin_id=a.id AND rr.is_deleted=0
          JOIN nx_admin_role r ON r.id=rr.role_id AND r.status=1 AND r.is_deleted=0
          JOIN nx_admin_role_permission rp ON rp.role_id=r.id AND rp.is_deleted=0
          JOIN nx_admin_permission p ON p.id=rp.permission_id AND p.status=1 AND p.is_deleted=0
         WHERE a.id=#{id} AND a.status=1 AND a.is_deleted=0
           AND p.resource_type='API' AND p.permission_code='service_m3_write'
         ORDER BY r.id,rp.id FOR SHARE
        """)
    List<Long> writerGrant(Long id);

    @Select("""
        <script>SELECT scope_binding.id,scope_binding.user_id customerId,scope_binding.agent_admin_id agentAdminId,scope_binding.version,scope_binding.source,
               scope_binding.segment_root_id segmentRootId,scope_binding.depth,scope_binding.parent_assignment_id parentAssignmentId,scope_binding.rule_version ruleVersion
          FROM nx_support_agent_user_assignment scope_binding WHERE scope_binding.user_id=#{id} AND
        """ + UNIQUE_SCOPE_BINDING + " FOR SHARE</script>")
    SupportAssignment current(Long id);

    @Select("SELECT id,sponsor_user_id sponsorUserId FROM nx_user WHERE id=#{id} AND is_deleted=0")
    Map<String,Object> invitationSnapshot(Long id);
    @Select("SELECT id,user_id customerId,agent_admin_id agentAdminId,version,source,segment_root_id segmentRootId,depth,parent_assignment_id parentAssignmentId,rule_version ruleVersion FROM nx_support_agent_user_assignment WHERE id=#{id} AND is_deleted=0")
    SupportAssignment inheritanceSnapshot(Long id);

    @Select("SELECT version,dormant_days dormantDays,maintenance_days maintenanceDays,activity_window_days activityWindowDays,inheritance_mode inheritanceMode,max_inheritance_depth maxInheritanceDepth,unbound_assignment_mode unboundAssignmentMode,mode_effective_at modeEffectiveAt FROM nx_support_rules WHERE id=1 FOR SHARE")
    SupportRules rules();
    @Select("SELECT version FROM nx_support_rules WHERE id=1 FOR UPDATE")
    Long lockRules();

    @Update("""
        UPDATE nx_support_rules SET dormant_days=#{d},maintenance_days=#{m},activity_window_days=#{w},
          inheritance_mode=#{mode},max_inheritance_depth=#{depth},
          mode_effective_at=IF(#{unboundMode} IS NOT NULL AND unbound_assignment_mode<>#{unboundMode},UTC_TIMESTAMP(6),mode_effective_at),
          unbound_assignment_mode=COALESCE(#{unboundMode},unbound_assignment_mode),version=version+1,updated_by=#{actor},
          reason=#{reason},updated_at=UTC_TIMESTAMP(6) WHERE id=1 AND version=#{version}
        """)
    int updateRules(@Param("d") Integer d,@Param("m") Integer m,@Param("w") Integer w,
        @Param("mode") String mode,@Param("depth") Integer depth,@Param("version") Long version,
        @Param("actor") Long actor,@Param("reason") String reason,@Param("unboundMode") String unboundMode);

    String ELIGIBLE_AGENT_BASE_FROM = """
        FROM nx_admin a JOIN nx_support_agent_profile p ON p.admin_id=a.id
          JOIN nx_admin_role_relation rr ON rr.admin_id=a.id AND rr.is_deleted=0
          JOIN nx_admin_role r ON r.id=rr.role_id AND r.is_deleted=0 AND r.status=1 AND r.role_code IN ('SUPPORT','SUPER_ADMIN')
          JOIN nx_support_account_qualification_history scope_q ON scope_q.admin_id=a.id
         WHERE a.status=1 AND a.is_deleted=0 AND p.is_deleted=0 AND p.enabled=1
           AND scope_q.qualification_kind='SERVICE' AND scope_q.state='ENABLED' AND
        """;
    String ELIGIBLE_AGENT_FROM=ELIGIBLE_AGENT_BASE_FROM+SupportGroupMapper.UNIQUE_QUALIFICATION;
    @Select("<script>SELECT COUNT(DISTINCT a.id) " + ELIGIBLE_AGENT_FROM + " AND a.id=#{id} FOR SHARE</script>")
    int eligibleAgent(Long id);
    @Select("<script>SELECT COUNT(DISTINCT a.id) " + ELIGIBLE_AGENT_BASE_FROM + SupportGroupMapper.UNIQUE_QUALIFICATION_SNAPSHOT + " AND a.id=#{id}</script>")
    int eligibleAgentSnapshot(Long id);

    @Select("<script>SELECT DISTINCT a.id " + ELIGIBLE_AGENT_FROM + " ORDER BY a.id FOR SHARE</script>")
    List<Long> eligibleAgents();

    @Select("""
        SELECT r.role_code FROM nx_admin a JOIN nx_admin_role_relation rr ON rr.admin_id=a.id
          JOIN nx_admin_role r ON r.id=rr.role_id
         WHERE a.id=#{id} AND a.status=1 AND a.is_deleted=0 AND rr.is_deleted=0 AND r.is_deleted=0 AND r.status=1 FOR SHARE
        """)
    List<String> roles(Long id);
    @Select("SELECT r.role_code FROM nx_admin a JOIN nx_admin_role_relation rr ON rr.admin_id=a.id JOIN nx_admin_role r ON r.id=rr.role_id WHERE a.id=#{id} AND a.status=1 AND a.is_deleted=0 AND rr.is_deleted=0 AND r.is_deleted=0 AND r.status=1")
    List<String> rolesSnapshot(Long id);
    // Compatibility name only: position text and migration presence cannot grant management authority.
    String SUPERVISOR_PROFILE="SELECT COUNT(DISTINCT a.id) FROM nx_admin a JOIN nx_support_account_qualification_history scope_q ON scope_q.admin_id=a.id JOIN nx_admin_role_relation rr ON rr.admin_id=a.id AND rr.is_deleted=0 JOIN nx_admin_role r ON r.id=rr.role_id AND r.is_deleted=0 AND r.status=1 AND r.role_code IN ('SUPPORT','SUPER_ADMIN') WHERE a.id=#{id} AND a.status=1 AND a.is_deleted=0 AND scope_q.qualification_kind='SUPERVISOR' AND scope_q.state='ENABLED' AND " + SupportGroupMapper.UNIQUE_QUALIFICATION;
    @Select("<script>"+SUPERVISOR_PROFILE+"</script>")
    int supervisorProfileSnapshot(Long id);

    @Select("<script>"+SUPERVISOR_PROFILE+" FOR SHARE</script>")
    int supervisorProfile(Long id);

    @Select("<script>SELECT scope_binding.agent_admin_id FROM nx_support_agent_user_assignment scope_binding WHERE scope_binding.user_id=#{id} AND "+UNIQUE_SCOPE_BINDING+" FOR SHARE</script>")
    Long currentAgent(Long id);

    @Select("SELECT user_id FROM nx_conversation WHERE conversation_no=#{no} AND is_deleted=0")
    Long conversationCustomer(String no);

    @Select("SELECT user_id FROM nx_support_ticket WHERE ticket_no=#{no} AND is_deleted=0")
    Long ticketCustomer(String no);

    // Legacy interval columns have second precision; fractional writes round into the future in MySQL.
    @Update("UPDATE nx_support_agent_user_assignment SET status='INACTIVE',ends_at=UTC_TIMESTAMP(),updated_at=UTC_TIMESTAMP(6),version=version+1 WHERE id=#{id} AND status='ACTIVE' AND version=#{version}")
    int endAssignment(@Param("id") Long id,@Param("version") Long version);

    @Insert("""
        INSERT INTO nx_support_agent_user_assignment(agent_admin_id,user_id,status,starts_at,operator,reason,
          source,segment_root_id,depth,parent_assignment_id,rule_version,operation_id,version)
        VALUES(#{agent},#{customer},'ACTIVE',UTC_TIMESTAMP(),#{actor},#{reason},#{source},#{root},#{depth},#{parent},#{rule},#{operation},1)
        """)
    int insertAssignment(@Param("agent") Long agent,@Param("customer") Long customer,@Param("actor") String actor,
        @Param("reason") String reason,@Param("source") String source,@Param("root") Long root,@Param("depth") int depth,
        @Param("parent") Long parent,@Param("rule") Long rule,@Param("operation") String operation);

    @Insert("INSERT INTO nx_support_binding_pool(customer_id,reason,version,entered_at) VALUES(#{id},#{reason},1,UTC_TIMESTAMP(6))")
    int enterPool(@Param("id") Long id,@Param("reason") String reason);
    @Update("UPDATE nx_support_binding_pool SET auto_eligible=1,auto_rule_version=#{version},auto_attempt_state='WAITING_CANDIDATE',operation_id=#{operation} WHERE customer_id=#{id}")
    int eligiblePool(@Param("id") Long id,@Param("version") Long version,@Param("operation") String operation);
    @Update("UPDATE nx_support_binding_pool SET auto_attempt_state=#{state},attempts=attempts+1,last_attempt_at=UTC_TIMESTAMP(6),last_outcome=#{outcome},version=version+1 WHERE customer_id=#{id}")
    int attemptPool(@Param("id") Long id,@Param("state") String state,@Param("outcome") String outcome);
    @Select("SELECT p.customer_id FROM nx_support_binding_pool p JOIN nx_user u ON u.id=p.customer_id AND u.is_deleted=0 AND u.sandbox=0 WHERE p.auto_eligible=1 ORDER BY COALESCE(p.last_attempt_at,p.entered_at),p.customer_id LIMIT 100")
    List<Long> autoPending();
    @Select("SELECT auto_eligible FROM nx_support_binding_pool WHERE customer_id=#{id} FOR SHARE")
    Boolean autoEligible(Long id);
    @Select("SELECT version FROM nx_support_binding_pool WHERE customer_id=#{id} FOR SHARE")
    Long poolVersion(Long id);
    @Select("SELECT reason FROM nx_support_binding_pool WHERE customer_id=#{id} FOR SHARE")
    String poolReason(Long id);
    @Select("SELECT customer_id customerId,reason,auto_eligible autoEligible,auto_rule_version autoRuleVersion,auto_attempt_state autoAttemptState,attempts,last_attempt_at lastAttemptAt,last_outcome lastOutcome,operation_id operationId FROM nx_support_binding_pool WHERE customer_id=#{id}")
    Map<String,Object> poolAttempt(Long id);
    @Delete("DELETE FROM nx_support_binding_pool WHERE customer_id=#{id}")
    int leavePool(Long id);
    @Select("""
        <script>SELECT p.customer_id customerId,p.reason,p.version,p.entered_at enteredAt,p.auto_eligible autoEligible,
          p.auto_rule_version autoRuleVersion,p.auto_attempt_state autoAttemptState,p.attempts,p.last_attempt_at lastAttemptAt,
          p.last_outcome lastOutcome,p.operation_id operationId,u.sponsor_user_id inviterId,
          u.nickname FROM nx_support_binding_pool p JOIN nx_user u ON u.id=p.customer_id AND u.is_deleted=0
          WHERE 1=1 <if test='reason != null'>AND p.reason=#{reason}</if>
          <if test='keyword != null'>AND (u.nickname LIKE CONCAT('%',#{keyword},'%') OR CAST(u.id AS CHAR)=#{keyword})</if>
          ORDER BY p.customer_id LIMIT #{limit} OFFSET #{offset}</script>
        """)
    List<Map<String,Object>> pool(@Param("reason") String reason,@Param("keyword") String keyword,@Param("offset") long offset,@Param("limit") int limit);
    @Select("<script>SELECT COUNT(*) FROM nx_support_binding_pool p JOIN nx_user u ON u.id=p.customer_id AND u.is_deleted=0 WHERE 1=1 <if test='reason != null'>AND p.reason=#{reason}</if> <if test='keyword != null'>AND (u.nickname LIKE CONCAT('%',#{keyword},'%') OR CAST(u.id AS CHAR)=#{keyword})</if></script>")
    long poolCount(@Param("reason") String reason,@Param("keyword") String keyword);

    String HANDOVER_FILTER="FROM nx_support_agent_user_assignment x JOIN nx_user u ON u.id=x.user_id AND u.is_deleted=0 LEFT JOIN nx_admin a ON a.id=x.agent_admin_id LEFT JOIN nx_support_agent_profile p ON p.admin_id=a.id WHERE x.status='ACTIVE' AND x.is_deleted=0 <if test='agent != null'>AND x.agent_admin_id=#{agent}</if> <if test='unavailable'>AND NOT EXISTS(SELECT 1 "
        + ELIGIBLE_AGENT_FROM + " AND a.id=x.agent_admin_id)</if>";
    @Select("<script>SELECT COUNT(*) "+HANDOVER_FILTER+"</script>")
    long handoverCount(@Param("agent") Long agent,@Param("unavailable") boolean unavailable);
    @Select("<script>SELECT x.id assignmentId,x.user_id customerId,x.agent_admin_id agentAdminId,x.version,u.nickname "+HANDOVER_FILTER+" ORDER BY x.user_id LIMIT #{limit} OFFSET #{offset}</script>")
    List<Map<String,Object>> handover(@Param("agent") Long agent,@Param("unavailable") boolean unavailable,@Param("offset") long offset,@Param("limit") int limit);
}
