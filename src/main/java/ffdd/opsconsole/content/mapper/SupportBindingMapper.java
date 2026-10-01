package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.SupportAssignment;
import ffdd.opsconsole.content.domain.SupportRules;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.SupportAgentAssignmentEntity;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** All mutable customer operations lock nx_user first, then read current assignment. */
public interface SupportBindingMapper extends BaseMapper<SupportAgentAssignmentEntity> {
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
        SELECT id,user_id customerId,agent_admin_id agentAdminId,version,source,
               segment_root_id segmentRootId,depth,parent_assignment_id parentAssignmentId,rule_version ruleVersion
          FROM nx_support_agent_user_assignment WHERE user_id=#{id} AND status='ACTIVE' AND is_deleted=0 FOR SHARE
        """)
    SupportAssignment current(Long id);

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

    String ELIGIBLE_AGENT_FROM = """
        FROM nx_admin a JOIN nx_support_agent_profile p ON p.admin_id=a.id
          JOIN nx_admin_role_relation rr ON rr.admin_id=a.id AND rr.is_deleted=0
          JOIN nx_admin_role r ON r.id=rr.role_id AND r.is_deleted=0 AND r.status=1 AND r.role_code='SUPPORT'
         WHERE a.status=1 AND a.is_deleted=0 AND p.is_deleted=0 AND p.enabled=1
           AND p.seat_type='DEDICATED' AND FIND_IN_SET('advisor',REPLACE(LOWER(p.service_types),' ',''))>0
        """;
    @Select("SELECT COUNT(DISTINCT a.id) " + ELIGIBLE_AGENT_FROM + " AND a.id=#{id} FOR SHARE")
    int eligibleAgent(Long id);
    @Select("SELECT COUNT(DISTINCT a.id) " + ELIGIBLE_AGENT_FROM + " AND a.id=#{id}")
    int eligibleAgentSnapshot(Long id);

    @Select("SELECT DISTINCT a.id " + ELIGIBLE_AGENT_FROM + " ORDER BY a.id")
    List<Long> eligibleAgents();

    @Select("""
        SELECT r.role_code FROM nx_admin a JOIN nx_admin_role_relation rr ON rr.admin_id=a.id
          JOIN nx_admin_role r ON r.id=rr.role_id
         WHERE a.id=#{id} AND a.status=1 AND a.is_deleted=0 AND rr.is_deleted=0 AND r.is_deleted=0 AND r.status=1 FOR SHARE
        """)
    List<String> roles(Long id);
    @Select("SELECT r.role_code FROM nx_admin a JOIN nx_admin_role_relation rr ON rr.admin_id=a.id JOIN nx_admin_role r ON r.id=rr.role_id WHERE a.id=#{id} AND a.status=1 AND a.is_deleted=0 AND rr.is_deleted=0 AND r.is_deleted=0 AND r.status=1")
    List<String> rolesSnapshot(Long id);
    @Select("SELECT COUNT(*) FROM nx_support_agent_profile WHERE admin_id=#{id} AND enabled=1 AND is_deleted=0 AND seat_type='MANAGER'")
    int supervisorProfileSnapshot(Long id);

    @Select("SELECT COUNT(*) FROM nx_support_agent_profile WHERE admin_id=#{id} AND enabled=1 AND is_deleted=0 AND seat_type='MANAGER' FOR SHARE")
    int supervisorProfile(Long id);

    @Select("SELECT agent_admin_id FROM nx_support_agent_user_assignment WHERE user_id=#{id} AND status='ACTIVE' AND is_deleted=0 FOR SHARE")
    Long currentAgent(Long id);

    @Select("SELECT user_id FROM nx_conversation WHERE conversation_no=#{no} AND is_deleted=0")
    Long conversationCustomer(String no);

    @Select("SELECT user_id FROM nx_support_ticket WHERE ticket_no=#{no} AND is_deleted=0")
    Long ticketCustomer(String no);

    @Update("UPDATE nx_support_agent_user_assignment SET status='INACTIVE',ends_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6),version=version+1 WHERE id=#{id} AND status='ACTIVE' AND version=#{version}")
    int endAssignment(@Param("id") Long id,@Param("version") Long version);

    @Insert("""
        INSERT INTO nx_support_agent_user_assignment(agent_admin_id,user_id,status,starts_at,operator,reason,
          source,segment_root_id,depth,parent_assignment_id,rule_version,operation_id,version)
        VALUES(#{agent},#{customer},'ACTIVE',UTC_TIMESTAMP(6),#{actor},#{reason},#{source},#{root},#{depth},#{parent},#{rule},#{operation},1)
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

    String HANDOVER_FILTER="FROM nx_support_agent_user_assignment x JOIN nx_user u ON u.id=x.user_id AND u.is_deleted=0 LEFT JOIN nx_admin a ON a.id=x.agent_admin_id LEFT JOIN nx_support_agent_profile p ON p.admin_id=a.id WHERE x.status='ACTIVE' AND x.is_deleted=0 <if test='agent != null'>AND x.agent_admin_id=#{agent}</if> <if test='unavailable'>AND (a.id IS NULL OR a.status&lt;&gt;1 OR a.is_deleted&lt;&gt;0 OR p.admin_id IS NULL OR p.enabled&lt;&gt;1 OR p.is_deleted&lt;&gt;0 OR p.seat_type&lt;&gt;'DEDICATED' OR FIND_IN_SET('advisor',REPLACE(LOWER(p.service_types),' ',''))=0 OR NOT EXISTS(SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id WHERE rr.admin_id=a.id AND rr.is_deleted=0 AND r.is_deleted=0 AND r.status=1 AND r.role_code='SUPPORT'))</if>";
    @Select("<script>SELECT COUNT(*) "+HANDOVER_FILTER+"</script>")
    long handoverCount(@Param("agent") Long agent,@Param("unavailable") boolean unavailable);
    @Select("<script>SELECT x.id assignmentId,x.user_id customerId,x.agent_admin_id agentAdminId,x.version,u.nickname "+HANDOVER_FILTER+" ORDER BY x.user_id LIMIT #{limit} OFFSET #{offset}</script>")
    List<Map<String,Object>> handover(@Param("agent") Long agent,@Param("unavailable") boolean unavailable,@Param("offset") long offset,@Param("limit") int limit);
}
