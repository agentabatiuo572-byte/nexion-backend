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
          SELECT u.id customerId,CAST(u.id AS CHAR) customerNo,u.nickname,
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
          JOIN nx_admin ad ON ad.id=a.agent_admin_id
          LEFT JOIN nx_admin_account_state av ON av.admin_id=a.agent_admin_id AND av.is_deleted=0
          LEFT JOIN nx_support_maintenance_preference p ON p.customer_id=u.id
          WHERE u.is_deleted=0
          <if test='agentId != null'>AND a.agent_admin_id=#{agentId}</if>
          <if test='customerId != null'>AND u.id=#{customerId}</if>
        ), classified AS (
          SELECT facts.*,(pendingReplyCount&gt;0) waitingReply,
            CASE WHEN #{dormantCutoff} IS NOT NULL AND lastEffectiveAt &gt; #{dormantCutoff} THEN 'ACTIVE'
                 WHEN #{dormantCutoff} IS NOT NULL AND lastEffectiveAt IS NOT NULL
                   AND #{coverageStartAt} &lt;= #{dormantCutoff} THEN 'DORMANT' ELSE 'UNKNOWN' END activityStatus,
            CASE WHEN #{windowCutoff} IS NOT NULL AND lastEffectiveAt &gt;= #{windowCutoff} THEN 'ACTIVE'
                 WHEN #{windowCutoff} IS NOT NULL AND #{coverageStartAt} &lt;= #{windowCutoff}
                   THEN 'INACTIVE' ELSE 'UNKNOWN' END windowStatus,
            CASE WHEN #{maintenanceDays} IS NULL OR lastExecutionAt IS NULL THEN NULL
                 ELSE TIMESTAMPADD(DAY,#{maintenanceDays},GREATEST(lastExecutionAt,COALESCE(lastSucceededAt,lastExecutionAt))) END nextDueAt
          FROM facts
        ), customers AS (
          SELECT classified.*,
            (enabled=1 AND #{maintenanceDays} IS NOT NULL AND (lastExecutionAt IS NULL OR nextDueAt &lt;= #{evaluatedAt})) due
          FROM classified
        )
        """;
    String FILTER = """
        WHERE 1=1
        <if test='keyword != null'>AND (nickname LIKE CONCAT('%',#{keyword},'%') OR customerNo=#{keyword})</if>
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

    @Select("""
        <script>SELECT DATE(CONVERT_TZ(executed_at,'+00:00',#{businessOffset})) day,COUNT(*) executionCount
          FROM nx_support_maintenance_execution
          WHERE executed_at &gt;= #{from} AND executed_at &lt; #{to} AND executed_at &lt;= #{evaluatedAt}
          <if test='agentId != null'>AND agent_admin_id=#{agentId}</if>
          GROUP BY day ORDER BY day</script>
        """)
    List<Map<String,Object>> executionDays(Map<String,Object> query);

    @Select("""
        <script>SELECT DATE(CONVERT_TZ(closed_at,'+00:00',#{businessOffset})) day,COUNT(*) successfulCycleCount
          FROM nx_support_maintenance_cycle
          WHERE status='SUCCEEDED' AND closed_at &gt;= #{from} AND closed_at &lt; #{to} AND closed_at &lt;= #{evaluatedAt}
          <if test='agentId != null'>AND agent_admin_id=#{agentId}</if>
          GROUP BY day ORDER BY day</script>
        """)
    List<Map<String,Object>> successDays(Map<String,Object> query);

    @Select("""
        <script>SELECT COUNT(DISTINCT customer_id) FROM nx_support_maintenance_cycle
          WHERE status='SUCCEEDED' AND closed_at &gt;= #{from} AND closed_at &lt; #{to} AND closed_at &lt;= #{evaluatedAt}
          <if test='agentId != null'>AND agent_admin_id=#{agentId}</if></script>
        """)
    long successfulCustomers(Map<String,Object> query);
}
