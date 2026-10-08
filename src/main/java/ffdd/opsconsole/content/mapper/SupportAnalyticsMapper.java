package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Fixed scoped projections only; no entity CRUD or source-success adapter. */
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportAnalyticsMapper {
    String EVENT_SCOPE = """
        (<choose>
        <when test="scope != null and scope.mode.name() == 'PERSONAL'">
        """ + SupportGroupMapper.SCOPE_SERVICE + """
          AND e.agent_status='KNOWN' AND e.agent_admin_id=#{scope.actorId}
        </when>
        <when test="scope != null and scope.mode.name() == 'MANAGED'">
        """ + SupportGroupMapper.SCOPE_SUPERVISOR + """
          AND e.group_status='KNOWN' AND EXISTS(SELECT 1 FROM nx_support_group scope_group
            WHERE scope_group.id=e.group_id AND scope_group.supervisor_admin_id=#{scope.actorId} AND
        """ + SupportGroupMapper.CURRENT_GROUP_OWNER + """
            <if test="scope.requestedGroupId != null">AND scope_group.id=#{scope.requestedGroupId}</if>)
        </when>
        <when test="scope != null and scope.mode.name() == 'ALL'">
        """ + SupportGroupMapper.SCOPE_SUPER_ADMIN + """
          <if test="scope.requestedGroupId != null">AND e.group_status='KNOWN' AND e.group_id=#{scope.requestedGroupId}</if>
        </when><otherwise>1=0</otherwise></choose>
        <if test="scope != null and scope.requestedAgentId != null">AND e.agent_status='KNOWN' AND e.agent_admin_id=#{scope.requestedAgentId}</if>)
        """;
    String CURRENT_PROJECTION = """
        WITH assignments AS (
          SELECT user_id,COUNT(*) activeRows,
            SUM(starts_at&lt;=UTC_TIMESTAMP(6) AND ends_at IS NULL) openRows,
            MIN(agent_admin_id) agentId
          FROM nx_support_agent_user_assignment WHERE status='ACTIVE' AND is_deleted=0 GROUP BY user_id
        ), binding_intervals AS (
          SELECT user_id,COUNT(*) intervalRows FROM nx_support_agent_user_assignment
          WHERE is_deleted=0 AND starts_at&lt;=UTC_TIMESTAMP(6)
            AND (ends_at IS NULL OR ends_at&gt;UTC_TIMESTAMP(6)) GROUP BY user_id
        ), members AS (
          SELECT agent_admin_id,COUNT(*) intervalRows,SUM(ends_at IS NULL) openRows,MIN(group_id) groupId
          FROM nx_support_group_member_history WHERE starts_at&lt;=UTC_TIMESTAMP(6)
            AND (ends_at IS NULL OR ends_at&gt;UTC_TIMESTAMP(6)) GROUP BY agent_admin_id
        ), routes AS (
          SELECT customer_id,COUNT(*) intervalRows,SUM(ends_at IS NULL) openRows,MIN(group_id) groupId
          FROM nx_support_customer_route_history WHERE starts_at&lt;=UTC_TIMESTAMP(6)
            AND (ends_at IS NULL OR ends_at&gt;UTC_TIMESTAMP(6)) GROUP BY customer_id
        ), owners AS (
          SELECT group_id,COUNT(*) intervalRows,SUM(ends_at IS NULL) openRows,MIN(supervisor_admin_id) supervisorId
          FROM nx_support_group_owner_history WHERE starts_at&lt;=UTC_TIMESTAMP(6)
            AND (ends_at IS NULL OR ends_at&gt;UTC_TIMESTAMP(6)) GROUP BY group_id
        ), classified AS (
          SELECT scope_customer.id customerId,
            CASE WHEN a.activeRows=1 AND a.openRows=1 AND bi.intervalRows=1 AND ad.id IS NOT NULL THEN 'BOUND'
              WHEN COALESCE(a.activeRows,0)=0 AND COALESCE(bi.intervalRows,0)=0
                AND (r.customer_id IS NULL OR (r.intervalRows=1 AND r.openRows=1)) THEN 'PENDING'
              ELSE 'ANOMALY' END category,
            CASE WHEN a.activeRows=1 AND a.openRows=1 AND bi.intervalRows=1 AND ad.id IS NOT NULL THEN
              CASE WHEN m.intervalRows=1 AND m.openRows=1 AND m.groupId IS NULL THEN 'UNGROUPED'
                WHEN m.intervalRows=1 AND m.openRows=1 AND g.id IS NOT NULL AND o.intervalRows=1 AND o.openRows=1
                  AND o.supervisorId=g.supervisor_admin_id THEN 'GROUPED' ELSE 'UNKNOWN' END
              WHEN COALESCE(a.activeRows,0)=0 AND COALESCE(bi.intervalRows,0)=0 THEN
              CASE WHEN r.customer_id IS NULL OR (r.intervalRows=1 AND r.openRows=1 AND r.groupId IS NULL) THEN 'GLOBAL_QUEUE'
                WHEN r.intervalRows=1 AND r.openRows=1 AND g.id IS NOT NULL AND o.intervalRows=1 AND o.openRows=1
                  AND o.supervisorId=g.supervisor_admin_id THEN 'GROUP_QUEUE' ELSE 'UNKNOWN' END
              ELSE 'UNKNOWN' END placement,
            CASE WHEN a.agentId IS NOT NULL AND (ad.id IS NULL OR ad.status&lt;&gt;1 OR ad.is_deleted&lt;&gt;0
              OR NOT EXISTS(SELECT 1 FROM nx_support_agent_profile p WHERE p.admin_id=a.agentId AND p.enabled=1 AND p.is_deleted=0)
              OR NOT EXISTS(SELECT 1 FROM nx_support_account_qualification_history q WHERE q.admin_id=a.agentId
                AND q.qualification_kind='SERVICE' AND q.state='ENABLED' AND q.starts_at&lt;=UTC_TIMESTAMP(6) AND q.ends_at IS NULL))
              THEN 1 ELSE 0 END handoverRequired
          FROM nx_user scope_customer
          LEFT JOIN assignments a ON a.user_id=scope_customer.id
          LEFT JOIN binding_intervals bi ON bi.user_id=scope_customer.id
          LEFT JOIN nx_admin ad ON ad.id=a.agentId
          LEFT JOIN members m ON m.agent_admin_id=a.agentId
          LEFT JOIN routes r ON r.customer_id=scope_customer.id
          LEFT JOIN nx_support_group g ON g.id=CASE WHEN a.activeRows=1 THEN m.groupId ELSE r.groupId END
          LEFT JOIN owners o ON o.group_id=g.id
          WHERE scope_customer.sandbox=0
        """ + SupportBindingMapper.CUSTOMER_SCOPE_PREDICATE + """
        ) SELECT customerId,category,placement,handoverRequired FROM classified ORDER BY customerId
        """;
    @Select("<script>"+CURRENT_PROJECTION+"</script>")
    List<CurrentCustomer> currentCustomers(@Param("scope") ReadScope scope);

    // All-history event candidates are independent of present customer membership and query dates/currency.
    @Select("<script>SELECT e.fact_id factId,e.customer_id customerId FROM nx_support_payment_attribution e WHERE "+EVENT_SCOPE+" ORDER BY e.fact_id</script>")
    List<EventCandidate> eventCandidates(@Param("scope") ReadScope scope);

    @Select("""
        <script>SELECT u.id FROM nx_user u WHERE u.sandbox=0 AND
        <choose><when test="scope != null and scope.mode.name() == 'ALL' and scope.requestedGroupId == null and scope.requestedAgentId == null">
        """+SupportGroupMapper.SCOPE_SUPER_ADMIN+"""
        </when><otherwise>1=0</otherwise></choose> ORDER BY u.id</script>
        """)
    List<Long> legacyProductionCustomers(@Param("scope") ReadScope scope);

    @Select("""
        <script>SELECT e.fact_id factId,e.customer_id customerId,e.kind,e.source,e.ledger_id ledgerId,
          e.source_business_id sourceBusinessId,e.order_no orderNo,e.order_type orderType,e.original_fact_id originalFactId,
          e.currency,e.amount,e.succeeded_at succeededAt,e.source_business_zone sourceBusinessZone,
          e.success_time_field successTimeField,e.fractional_second_digits fractionalSecondDigits,
          e.capture_mode captureMode,e.capture_schema_version captureSchemaVersion,
          e.agent_admin_id agentAdminId,e.group_id groupId,e.owner_admin_id ownerAdminId,
          e.agent_status agentStatus,e.group_status groupStatus,e.owner_status ownerStatus
        FROM nx_support_payment_attribution e WHERE e.fact_id IN
        <foreach collection="factIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        AND (
        """+EVENT_SCOPE+"""
          OR EXISTS(SELECT 1 FROM nx_user scope_customer WHERE scope_customer.id=e.customer_id AND scope_customer.sandbox=0
        """+SupportBindingMapper.CUSTOMER_SCOPE_PREDICATE+"""
          )) ORDER BY e.fact_id</script>
        """)
    List<AttributionRow> attributions(@Param("scope") ReadScope scope,@Param("factIds") Collection<String> factIds);

    record CurrentCustomer(Long customerId,String category,String placement,Integer handoverRequired) { }
    record EventCandidate(String factId,Long customerId) { }
    record AttributionRow(String factId,Long customerId,String kind,String source,Long ledgerId,String sourceBusinessId,
                          String orderNo,String orderType,String originalFactId,String currency,BigDecimal amount,
                          LocalDateTime succeededAt,String sourceBusinessZone,String successTimeField,Integer fractionalSecondDigits,
                          String captureMode,String captureSchemaVersion,Long agentAdminId,Long groupId,Long ownerAdminId,
                          String agentStatus,String groupStatus,String ownerStatus) { }
}
