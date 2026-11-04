package ffdd.opsconsole.content.mapper;

import java.util.List;
import java.util.Map;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.SupportAgentAssignmentEntity;
import org.apache.ibatis.annotations.Select;

/** One SQL projection defines counts and filters; LIMIT is applied after authorization. */
public interface SupportWorkbenchMapper extends BaseMapper<SupportAgentAssignmentEntity> {
    String PENDING = """
        FROM nx_conversation c JOIN nx_conversation_message m ON m.conversation_no=c.conversation_no
        LEFT JOIN nx_support_reply_cursor r ON r.conversation_no=c.conversation_no
        WHERE c.user_id=u.id AND c.is_deleted=0 AND m.is_deleted=0 AND m.sender_type='user'
          AND m.id&gt;COALESCE(r.through_message_id,0)
        """;
    String PROJECTION = """
        WITH facts AS (
          SELECT u.id customerId,CAST(u.id AS CHAR) customerNo,u.nickname,u.avatar_url customerAvatarKey,
            a.id assignmentId,a.version assignmentVersion,a.agent_admin_id agentAdminId,
            COALESCE(ad.nickname,ad.username) agentName,
            av.avatar_asset_id advisorAvatarAssetId,av.avatar_version advisorAvatarVersion,
            COALESCE(p.enabled,1) enabled,COALESCE(p.version,1) preferenceVersion,p.reason stoppedReason,
            (SELECT MAX(e.occurred_at) FROM nx_support_activity_event e
              WHERE e.customer_id=u.id AND e.occurred_at &lt;= #{evaluatedAt}) lastEffectiveAt,
            (SELECT MAX(e.executed_at) FROM nx_support_maintenance_execution e
              WHERE e.assignment_id=a.id) lastExecutionAt,
            (SELECT MAX(c.closed_at) FROM nx_support_maintenance_cycle c
              WHERE c.assignment_id=a.id AND c.status='SUCCEEDED') lastSucceededAt,
            (SELECT c.id FROM nx_support_maintenance_cycle c WHERE c.assignment_id=a.id AND c.status='OPEN') openCycleId,
            (SELECT COUNT(*)
        """ + PENDING + ") pendingReplyCount,(SELECT c.conversation_no " + PENDING
            + " ORDER BY m.id DESC LIMIT 1) pendingConversationNo,(SELECT m.id " + PENDING
            + " ORDER BY m.id DESC LIMIT 1) pendingThroughMessageId," + """
            NOT EXISTS(SELECT 1 FROM nx_support_human_message h
              WHERE h.assignment_id=a.id AND h.customer_id=u.id AND h.actor_type='ADMIN'
                AND h.actor_id=a.agent_admin_id) firstContact
          FROM nx_user u JOIN nx_support_agent_user_assignment a
            ON a.user_id=u.id AND a.status='ACTIVE' AND a.is_deleted=0
              AND a.ends_at IS NULL AND a.starts_at &lt;= UTC_TIMESTAMP(6)
          JOIN nx_user scope_customer ON scope_customer.id=u.id
          JOIN nx_admin ad ON ad.id=a.agent_admin_id
          LEFT JOIN nx_admin_account_state av ON av.admin_id=a.agent_admin_id AND av.is_deleted=0
          LEFT JOIN nx_support_maintenance_preference p ON p.customer_id=u.id
          WHERE u.is_deleted=0
        """ + SupportBindingMapper.CUSTOMER_SCOPE_PREDICATE + """
          <if test='customerId != null'>AND u.id=#{customerId}</if>
        ), classified AS (
          SELECT facts.*,(pendingReplyCount&gt;0) waitingReply,
            CASE WHEN #{dormantCutoff} IS NOT NULL AND lastEffectiveAt &gt; #{dormantCutoff} THEN 'ACTIVE'
                 WHEN #{dormantCutoff} IS NOT NULL AND lastEffectiveAt IS NOT NULL
                   AND #{coverageStartAt} &lt;= #{dormantCutoff} THEN 'DORMANT' ELSE 'UNKNOWN' END activityStatus,
            CASE WHEN #{windowCutoff} IS NOT NULL AND lastEffectiveAt &gt;= #{windowCutoff} THEN 'ACTIVE'
                 WHEN #{windowCutoff} IS NOT NULL AND #{coverageStartAt} &lt;= #{windowCutoff}
                   THEN 'INACTIVE' ELSE 'UNKNOWN' END windowStatus,
        """ + SupportMaintenanceMapper.NEXT_DUE_AT + """
            nextDueAt
          FROM facts
        ), customers AS (
          SELECT classified.*,
            (enabled=1 AND #{maintenanceDays} IS NOT NULL AND (lastExecutionAt IS NULL OR nextDueAt &lt;= #{evaluatedAt})) due
          FROM classified
        )
        """;
    String FILTER = """
        WHERE 1=1
        <if test='keyword != null'>AND (nickname LIKE CONCAT('%',#{keyword},'%')
          OR customerNo LIKE CONCAT('%',#{keyword},'%')
          OR CONCAT('U',LPAD(customerNo,GREATEST(8,LENGTH(customerNo)),'0')) LIKE CONCAT('%',#{keyword},'%')
          OR EXISTS (SELECT 1 FROM nx_user keyword_user WHERE keyword_user.id=customers.customerId
            AND keyword_user.phone LIKE CONCAT('%',#{keyword},'%')))</if>
        <choose>
          <when test='filter == "WINDOW_ACTIVE"'>AND windowStatus='ACTIVE'</when>
          <when test='filter == "ACTIVE"'>AND activityStatus='ACTIVE'</when>
          <when test='filter == "DORMANT"'>AND activityStatus='DORMANT'</when>
          <when test='filter == "UNKNOWN"'>AND activityStatus='UNKNOWN'</when>
          <when test='filter == "DUE"'>AND due=1</when>
          <when test='filter == "WAITING_REPLY"'>AND waitingReply=1</when>
          <when test='filter == "FIRST_CONTACT"'>AND firstContact=1 AND enabled=1</when>
          <when test='filter == "STOPPED"'>AND enabled=0</when>
          <when test='filter == "TODO"'>AND (due=1 OR waitingReply=1 OR (firstContact=1 AND enabled=1))</when>
        </choose>
        """;

    @Select("<script>" + PROJECTION + """
        SELECT COUNT(*) boundTotal,COALESCE(SUM(windowStatus='ACTIVE'),0) knownActiveCount,
          COALESCE(SUM(windowStatus='UNKNOWN'),0) unknownWindowCount,
          COALESCE(SUM(activityStatus='DORMANT'),0) dormantTotal,
          COALESCE(SUM(activityStatus='UNKNOWN'),0) unknownCount,
          COALESCE(SUM(due),0) dueTotal,COALESCE(SUM(waitingReply),0) waitingReplyTotal,
          COALESCE(SUM(firstContact=1 AND enabled=1),0) firstContactTotal,
          COALESCE(SUM(enabled=0),0) stoppedTotal,
          COALESCE(SUM(due=1 OR waitingReply=1 OR (firstContact=1 AND enabled=1)),0) todoTotal
        FROM customers</script>
        """)
    Map<String,Object> overview(Map<String,Object> query);

    @Select("<script>" + PROJECTION + "SELECT COUNT(*) FROM customers " + FILTER + "</script>")
    long count(Map<String,Object> query);

    @Select("<script>" + PROJECTION + "SELECT * FROM customers " + FILTER
        + " ORDER BY waitingReply DESC,nextDueAt IS NOT NULL,nextDueAt,customerId LIMIT #{limit} OFFSET #{offset}</script>")
    List<Map<String,Object>> customers(Map<String,Object> query);

    String EXECUTION_WINDOW = """
          FROM nx_support_maintenance_execution execution
          JOIN nx_admin scope_agent ON scope_agent.id=execution.agent_admin_id
          WHERE executed_at &gt;= #{from} AND executed_at &lt; #{to} AND executed_at &lt;= #{evaluatedAt}
        """ + SupportGroupMapper.AGENT_SCOPE_PREDICATE;

    String SUCCESS_WINDOW = """
          FROM nx_support_maintenance_cycle cycle
          JOIN nx_admin scope_agent ON scope_agent.id=cycle.agent_admin_id
          WHERE cycle.status='SUCCEEDED' AND closed_at &gt;= #{from} AND closed_at &lt; #{to} AND closed_at &lt;= #{evaluatedAt}
        """ + SupportGroupMapper.AGENT_SCOPE_PREDICATE;

    // A westward switch lengthens the final business date in the old-offset segment.
    // Its extra hours still belong to that date until the next version's actual midnight.
    @Select("<script>SELECT LEAST(#{lastDay},DATE(ADDTIME(executed_at,REPLACE(#{businessOffset},'+','')))) day,"
        + "COUNT(*) executionCount " + EXECUTION_WINDOW + " GROUP BY day ORDER BY day</script>")
    List<Map<String,Object>> executionDays(Map<String,Object> query);

    @Select("<script>SELECT LEAST(#{lastDay},DATE(ADDTIME(closed_at,REPLACE(#{businessOffset},'+','')))) day,"
        + "COUNT(*) successfulCycleCount " + SUCCESS_WINDOW + " GROUP BY day ORDER BY day</script>")
    List<Map<String,Object>> successDays(Map<String,Object> query);

    @Select("<script>SELECT COUNT(DISTINCT customer_id) " + SUCCESS_WINDOW + "</script>")
    long successfulCustomers(Map<String,Object> query);

    // Bind the workbench receipt to its current agent/group sets, original bounds and observed watermark.
    @Select("""
        <script>SELECT SHA2(CONCAT_WS('|','maintenance-scope-v1',#{scope.actorId},#{scope.mode},
          COALESCE(#{scope.requestedGroupId},'-'),COALESCE(#{scope.requestedAgentId},'-'),
          #{windowFrom},#{windowTo},#{evaluatedAt},#{calendarKey},
          (SELECT HEX(BIT_XOR(UNHEX(SHA2(scope_agent.id,256)))) FROM nx_admin scope_agent WHERE 1=1
        """ + SupportGroupMapper.AGENT_SCOPE_PREDICATE + """
          ),(SELECT HEX(BIT_XOR(UNHEX(SHA2(scope_group.id,256)))) FROM nx_support_group scope_group WHERE 1=1
        """ + SupportGroupMapper.GROUP_SCOPE_PREDICATE + """
          )),256) FROM nx_support_activity_coverage WHERE id=1 AND #{evaluatedAt} &lt;= observed_through_at</script>
        """)
    String performanceScopeVersion(Map<String,Object> query);

    // Historical totals belong to the executing agent. Only currently readable customers have detail rows.
    String DETAIL_FACTS = """
        WITH period_facts AS (<choose><when test='metric == "EXECUTION"'>
          SELECT execution.id,execution.customer_id customerId,execution.agent_admin_id agentAdminId,
            execution.assignment_id assignmentId,execution.cycle_id cycleId,execution.message_id sourceId,
            execution.executed_at factAt
        """ + EXECUTION_WINDOW + """
          </when><otherwise>
          SELECT cycle.id,cycle.customer_id customerId,cycle.agent_admin_id agentAdminId,
            cycle.assignment_id assignmentId,cycle.id cycleId,cycle.success_event_id sourceId,cycle.closed_at factAt
        """ + SUCCESS_WINDOW + """
          </otherwise></choose>), classified AS (
            SELECT f.*,EXISTS(SELECT 1 FROM nx_user scope_customer WHERE scope_customer.id=f.customerId
        """ + SupportBindingMapper.CUSTOMER_SCOPE_PREDICATE + """
            ) detailVisible FROM period_facts f)
        """;

    // Digest every visible record before pagination. Restricted records contribute only their count.
    @Select("<script>" + DETAIL_FACTS + """
        SELECT COUNT(*) total,COUNT(IF(detailVisible,1,NULL)) visibleCount,
          SHA2(CONCAT_WS('|','maintenance-details-v1',#{scopeVersion},#{metric},COALESCE(#{day},'-'),
            #{from},#{to},#{limit},COUNT(*),COUNT(IF(detailVisible,1,NULL)),
            HEX(BIT_XOR(IF(detailVisible,UNHEX(SHA2(CONCAT_WS('|',id,customerId,agentAdminId,assignmentId,
              cycleId,COALESCE(sourceId,'-'),factAt),256)),NULL)))),256) queryVersion
        FROM classified</script>
        """)
    Map<String,Object> performanceDetailCounts(Map<String,Object> query);

    @Select("<script>" + DETAIL_FACTS + """
        SELECT id,customerId,agentAdminId,assignmentId,cycleId,
          <choose><when test='metric == "EXECUTION"'>sourceId messageId,factAt executedAt</when>
            <otherwise>sourceId successEventId,factAt closedAt</otherwise></choose>
        FROM classified WHERE detailVisible=1 ORDER BY factAt DESC,id DESC LIMIT #{limit} OFFSET #{offset}</script>
        """)
    List<Map<String,Object>> performanceDetails(Map<String,Object> query);
}
