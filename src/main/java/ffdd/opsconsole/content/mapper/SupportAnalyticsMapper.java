package ffdd.opsconsole.content.mapper;

import ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import ffdd.opsconsole.content.domain.SupportRules;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;

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
            CASE WHEN a.activeRows=1 AND a.openRows=1 AND bi.intervalRows=1 AND ad.id IS NOT NULL
              THEN a.agentId ELSE NULL END ownerAgentId,
            CASE WHEN a.activeRows=1 AND a.openRows=1 AND bi.intervalRows=1 AND ad.id IS NOT NULL
                AND m.intervalRows=1 AND m.openRows=1 AND g.id IS NOT NULL
                AND o.intervalRows=1 AND o.openRows=1 AND o.supervisorId=g.supervisor_admin_id THEN g.id
              WHEN COALESCE(a.activeRows,0)=0 AND COALESCE(bi.intervalRows,0)=0
                AND r.intervalRows=1 AND r.openRows=1 AND g.id IS NOT NULL
                AND o.intervalRows=1 AND o.openRows=1 AND o.supervisorId=g.supervisor_admin_id THEN g.id
              ELSE NULL END currentGroupId,
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
        ) SELECT customerId,category,placement,handoverRequired,ownerAgentId,currentGroupId FROM classified ORDER BY customerId
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

    String READ_SCOPE="<choose><when test=\"scope != null and scope.mode.name() == 'PERSONAL'\">"+SupportGroupMapper.SCOPE_SERVICE
        +"</when><when test=\"scope != null and scope.mode.name() == 'MANAGED'\">"+SupportGroupMapper.SCOPE_SUPERVISOR
        +"</when><when test=\"scope != null and scope.mode.name() == 'ALL'\">"+SupportGroupMapper.SCOPE_SUPER_ADMIN+"</when><otherwise>1=0</otherwise></choose>";
    @Select("<script>SELECT coverage_start_at coverageStartAt,observed_through_at observedThroughAt,UTC_TIMESTAMP(6) evaluatedDbAt FROM nx_support_activity_coverage WHERE id=1 AND "+READ_SCOPE+"</script>")
    ActivityCoverage activityCoverage(@Param("scope") ReadScope scope);
    @Select("<script>SELECT version,activity_window_days activityWindowDays FROM nx_support_rules WHERE id=1 AND "+READ_SCOPE+"</script>")
    ActivityRules activityRules(@Param("scope") ReadScope scope);
    @Select("""
        <script>SELECT e.customer_id customerId,MAX(e.occurred_at) lastEffectiveAt
        FROM nx_support_activity_event e JOIN nx_user scope_customer ON scope_customer.id=e.customer_id
        WHERE scope_customer.sandbox=0
        """+SupportBindingMapper.CUSTOMER_SCOPE_PREDICATE+"""
        AND <choose><when test="customerIds != null and !customerIds.isEmpty()">e.customer_id IN
          <foreach collection="customerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </when><otherwise>1=0</otherwise></choose>
        AND e.occurred_at &lt;= #{observedThroughAt}
        GROUP BY e.customer_id ORDER BY e.customer_id</script>
        """)
    List<ActivityRow> activityEvents(@Param("scope") ReadScope scope,@Param("customerIds") Collection<Long> customerIds,
            @Param("observedThroughAt") LocalDateTime observedThroughAt);

    @Select("""
        <script>SELECT scope_group.id,scope_group.supervisor_admin_id supervisorAdminId,scope_group.status,
          EXISTS(SELECT 1 FROM nx_support_group_owner_history o WHERE o.group_id=scope_group.id
            AND o.supervisor_admin_id=scope_group.supervisor_admin_id AND o.starts_at &lt;= UTC_TIMESTAMP(6) AND o.ends_at IS NULL
            AND NOT EXISTS(SELECT 1 FROM nx_support_group_owner_history other_o WHERE other_o.group_id=o.group_id
              AND other_o.id&lt;&gt;o.id AND other_o.starts_at &lt;= UTC_TIMESTAMP(6)
              AND (other_o.ends_at IS NULL OR other_o.ends_at&gt;UTC_TIMESTAMP(6)))) ownerVerified
        FROM nx_support_group scope_group WHERE 1=1
        """+SupportGroupMapper.GROUP_SCOPE_PREDICATE+" ORDER BY scope_group.id</script>")
    List<GroupRow> scopedGroupRows(@Param("scope") ReadScope scope);

    String ROSTER_SELECT="""
        SELECT scope_agent.id accountId,scope_agent.status accountStatus,p.enabled profileEnabled,p.is_deleted profileDeleted,
          p.seat_type profileSeatType,q.id qualificationId,q.qualification_kind qualificationKind,q.state qualificationState,
          q.starts_at qualificationStartsAt,q.ends_at qualificationEndsAt,m.id memberId,m.group_id groupId,
          m.starts_at memberStartsAt,m.ends_at memberEndsAt,UTC_TIMESTAMP(6) evaluatedDbAt,
          EXISTS(SELECT 1 FROM nx_admin_role_relation rr JOIN nx_admin_role compatible_role ON compatible_role.id=rr.role_id
            WHERE rr.admin_id=scope_agent.id AND rr.is_deleted=0 AND compatible_role.is_deleted=0 AND compatible_role.status=1
              AND compatible_role.role_code IN ('SUPPORT','SUPER_ADMIN')) compatibleRole
        FROM nx_admin scope_agent LEFT JOIN nx_support_agent_profile p ON p.admin_id=scope_agent.id
        LEFT JOIN nx_support_account_qualification_history q ON q.admin_id=scope_agent.id
          AND q.qualification_kind=
        """;
    String ROSTER_JOIN="""
          AND q.starts_at &lt;= UTC_TIMESTAMP(6) AND (q.ends_at IS NULL OR q.ends_at&gt;UTC_TIMESTAMP(6))
        LEFT JOIN nx_support_group_member_history m ON m.agent_admin_id=scope_agent.id
          AND m.starts_at &lt;= UTC_TIMESTAMP(6) AND (m.ends_at IS NULL OR m.ends_at&gt;UTC_TIMESTAMP(6))
        WHERE scope_agent.is_deleted=0
        """;
    // GENERAL and DEDICATED are old storage categories of the same current service account category.
    @Select("<script>"+ROSTER_SELECT+"'SERVICE' "+ROSTER_JOIN+"""
        AND (q.id IS NOT NULL OR (p.is_deleted=0 AND p.seat_type IN ('GENERAL','DEDICATED')) OR m.id IS NOT NULL
          OR EXISTS(SELECT 1 FROM nx_support_agent_user_assignment a WHERE a.agent_admin_id=scope_agent.id
            AND a.status='ACTIVE' AND a.is_deleted=0))
        """+SupportGroupMapper.AGENT_SCOPE_PREDICATE+" ORDER BY scope_agent.id,q.id,m.id</script>")
    List<RosterRow> serviceAccountRows(@Param("scope") ReadScope scope);
    @Select("<script>"+ROSTER_SELECT+"'SUPERVISOR' "+ROSTER_JOIN+"""
        AND (q.id IS NOT NULL OR (p.is_deleted=0 AND p.seat_type='MANAGER')) AND
        <choose><when test="scope != null and scope.mode.name() == 'ALL'">
        """+SupportGroupMapper.SCOPE_SUPER_ADMIN+"""
        </when><when test="scope != null and scope.mode.name() == 'MANAGED'">
        """+SupportGroupMapper.SCOPE_SUPERVISOR+"""
          AND scope_agent.id=#{scope.actorId}
        </when><otherwise>1=0</otherwise></choose> ORDER BY scope_agent.id,q.id,m.id</script>
        """)
    List<RosterRow> supervisorAccountRows(@Param("scope") ReadScope scope);

    record CurrentCustomer(Long customerId,String category,String placement,Integer handoverRequired,Long ownerAgentId,Long currentGroupId) {
        @org.apache.ibatis.annotations.AutomapConstructor
        public CurrentCustomer { }
        public CurrentCustomer(Long customerId,String category,String placement,Integer handoverRequired) {
            this(customerId,category,placement,handoverRequired,null,null);
        }
    }
    record ActivityCoverage(LocalDateTime coverageStartAt,LocalDateTime observedThroughAt,LocalDateTime evaluatedDbAt) { }
    record ActivityRules(Long version,Integer activityWindowDays) { }
    record ActivityRow(Long customerId,LocalDateTime lastEffectiveAt) { }
    record GroupRow(Long id,Long supervisorAdminId,String status,Integer ownerVerified) { }
    record RosterRow(Long accountId,Integer accountStatus,Integer profileEnabled,Integer profileDeleted,String profileSeatType,
            Long qualificationId,String qualificationKind,String qualificationState,LocalDateTime qualificationStartsAt,
            LocalDateTime qualificationEndsAt,Long memberId,Long groupId,LocalDateTime memberStartsAt,
            LocalDateTime memberEndsAt,LocalDateTime evaluatedDbAt,Integer compatibleRole) { }
    record EventCandidate(String factId,Long customerId) { }
    record AttributionRow(String factId,Long customerId,String kind,String source,Long ledgerId,String sourceBusinessId,
                          String orderNo,String orderType,String originalFactId,String currency,BigDecimal amount,
                          LocalDateTime succeededAt,String sourceBusinessZone,String successTimeField,Integer fractionalSecondDigits,
                          String captureMode,String captureSchemaVersion,Long agentAdminId,Long groupId,Long ownerAdminId,
                          String agentStatus,String groupStatus,String ownerStatus) { }

    /** Current locking API-grant read; ordinary RR permission codes or the JWT cache are not this proof. */
    @Select("""
        SELECT rr.id roleRelationId,r.id roleId,rp.id rolePermissionId,p.id permissionId,p.permission_code permissionCode,
          r.status roleStatus,p.status permissionStatus
        FROM nx_admin a JOIN nx_admin_role_relation rr ON rr.admin_id=a.id AND rr.is_deleted=0
        JOIN nx_admin_role r ON r.id=rr.role_id AND r.status=1 AND r.is_deleted=0
        JOIN nx_admin_role_permission rp ON rp.role_id=r.id AND rp.is_deleted=0
        JOIN nx_admin_permission p ON p.id=rp.permission_id AND p.status=1 AND p.is_deleted=0 AND p.resource_type='API'
        WHERE a.id=#{actorId} AND a.status=1 AND a.is_deleted=0
          AND p.permission_code IN ('service_m1_read','service_m3_read','platform_a1_read')
        ORDER BY rr.id,rp.id,p.id FOR SHARE
        """)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<PermissionStamp> currentReadGrants(@Param("actorId") Long actorId);

    @Select("<script>SELECT scope_group.id FROM nx_support_group scope_group WHERE 1=1 "+SupportGroupMapper.GROUP_SCOPE_PREDICATE+" ORDER BY scope_group.id FOR SHARE</script>")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Long> currentGrantedGroupIds(@Param("scope") ReadScope scope);

    String IDS="<choose><when test=\"ids != null and !ids.isEmpty()\"><foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach></when><otherwise>(NULL)</otherwise></choose>";
    // ids below are internal sets already returned by the authorized current-root/roster readers, never HTTP IDs.
    @Select("<script>SELECT a.id,a.status,a.version,p.enabled profileEnabled,p.is_deleted profileDeleted,p.version profileVersion,p.seat_type profileSeatType,a.nickname,a.username,av.avatar_asset_id avatarAssetId,av.avatar_version avatarVersion FROM nx_admin a LEFT JOIN nx_support_agent_profile p ON p.admin_id=a.id LEFT JOIN nx_admin_account_state av ON av.admin_id=a.id AND av.is_deleted=0 WHERE a.id IN "+IDS+" AND "+READ_SCOPE+" ORDER BY a.id</script>")
    List<PersonStamp> personnelStamps(@Param("scope") ReadScope scope,@Param("ids") Collection<Long> ids);
    @Select("<script>SELECT rr.id relationId,r.id roleId,rr.admin_id adminId,r.role_code roleCode,r.status FROM nx_admin_role_relation rr JOIN nx_admin_role r ON r.id=rr.role_id AND r.is_deleted=0 AND r.status=1 WHERE rr.is_deleted=0 AND rr.admin_id IN "+IDS+" AND "+READ_SCOPE+" ORDER BY rr.id</script>")
    List<RoleStamp> roleStamps(@Param("scope") ReadScope scope,@Param("ids") Collection<Long> ids);
    @Select("<script>SELECT q.id,q.admin_id adminId,q.qualification_kind kind,q.state,q.version,q.starts_at startsAt,q.ends_at endsAt FROM nx_support_account_qualification_history q WHERE q.admin_id IN "+IDS+" AND q.starts_at &lt;= UTC_TIMESTAMP(6) AND (q.ends_at IS NULL OR q.ends_at&gt;UTC_TIMESTAMP(6)) AND "+READ_SCOPE+" ORDER BY q.id</script>")
    List<QualificationStamp> qualificationStamps(@Param("scope") ReadScope scope,@Param("ids") Collection<Long> ids);
    @Select("<script>SELECT scope_group.id,scope_group.name,scope_group.supervisor_admin_id supervisorAdminId,scope_group.status,scope_group.version FROM nx_support_group scope_group WHERE scope_group.id IN "+IDS+" AND "+READ_SCOPE+" ORDER BY scope_group.id</script>")
    List<GroupStamp> groupStamps(@Param("scope") ReadScope scope,@Param("ids") Collection<Long> ids);
    @Select("<script>SELECT 'ASSIGNMENT' kind,a.id,a.user_id customerId,a.agent_admin_id agentId,NULL groupId,NULL ownerId,a.status state,a.is_deleted deleted,a.version,a.starts_at startsAt,a.ends_at endsAt FROM nx_support_agent_user_assignment a WHERE a.user_id IN "+IDS+" AND a.is_deleted=0 AND (a.status='ACTIVE' OR (a.starts_at&lt;=UTC_TIMESTAMP(6) AND (a.ends_at IS NULL OR a.ends_at&gt;UTC_TIMESTAMP(6)))) AND "+READ_SCOPE+" ORDER BY a.id</script>")
    List<RelationshipStamp> assignmentStamps(@Param("scope") ReadScope scope,@Param("ids") Collection<Long> ids);
    @Select("<script>SELECT 'ROUTE' kind,r.id,r.customer_id customerId,NULL agentId,r.group_id groupId,NULL ownerId,'PRESENT' state,0 deleted,r.version,r.starts_at startsAt,r.ends_at endsAt FROM nx_support_customer_route_history r WHERE r.customer_id IN "+IDS+" AND r.starts_at&lt;=UTC_TIMESTAMP(6) AND (r.ends_at IS NULL OR r.ends_at&gt;UTC_TIMESTAMP(6)) AND "+READ_SCOPE+" ORDER BY r.id</script>")
    List<RelationshipStamp> routeStamps(@Param("scope") ReadScope scope,@Param("ids") Collection<Long> ids);
    @Select("<script>SELECT 'MEMBERSHIP' kind,m.id,NULL customerId,m.agent_admin_id agentId,m.group_id groupId,NULL ownerId,'PRESENT' state,0 deleted,m.version,m.starts_at startsAt,m.ends_at endsAt FROM nx_support_group_member_history m WHERE m.agent_admin_id IN "+IDS+" AND m.starts_at&lt;=UTC_TIMESTAMP(6) AND (m.ends_at IS NULL OR m.ends_at&gt;UTC_TIMESTAMP(6)) AND "+READ_SCOPE+" ORDER BY m.id</script>")
    List<RelationshipStamp> memberStamps(@Param("scope") ReadScope scope,@Param("ids") Collection<Long> ids);
    @Select("<script>SELECT 'GROUP_OWNER' kind,o.id,NULL customerId,NULL agentId,o.group_id groupId,o.supervisor_admin_id ownerId,'PRESENT' state,0 deleted,o.version,o.starts_at startsAt,o.ends_at endsAt FROM nx_support_group_owner_history o WHERE o.group_id IN "+IDS+" AND o.starts_at&lt;=UTC_TIMESTAMP(6) AND (o.ends_at IS NULL OR o.ends_at&gt;UTC_TIMESTAMP(6)) AND "+READ_SCOPE+" ORDER BY o.id</script>")
    List<RelationshipStamp> ownerStamps(@Param("scope") ReadScope scope,@Param("ids") Collection<Long> ids);

    @Select("""
        <script>SELECT scope_customer.id customerId,scope_customer.nickname,CAST(scope_customer.id AS CHAR) customerNo,
          scope_customer.status,scope_customer.created_at registeredAt,scope_customer.updated_at sourceUpdatedAt,
          (SELECT MAX(a.starts_at) FROM nx_support_agent_user_assignment a WHERE a.user_id=scope_customer.id
            AND a.status='ACTIVE' AND a.is_deleted=0 AND a.starts_at&lt;=UTC_TIMESTAMP(6) AND a.ends_at IS NULL) assignedAt,
          pool.entered_at poolEnteredAt,pool.version poolVersion,scope_customer.avatar_url avatarObjectKey,
          scope_customer.v_rank vRank,scope_customer.user_level userLevel
        FROM nx_user scope_customer LEFT JOIN nx_support_binding_pool pool ON pool.customer_id=scope_customer.id
        WHERE scope_customer.sandbox=0 AND scope_customer.id IN
        """+IDS+SupportBindingMapper.CUSTOMER_SCOPE_PREDICATE+" ORDER BY scope_customer.id</script>")
    List<RootDisplayRow> rootDisplayRows(@Param("scope") ReadScope scope,@Param("ids") Collection<Long> ids);

    @Select("""
        <script>SELECT t.id,t.user_id customerId,t.tag,t.created_at createdAt,t.updated_at updatedAt
        FROM nx_customer_tag t JOIN nx_user scope_customer ON scope_customer.id=t.user_id
        WHERE t.is_deleted=0 AND scope_customer.sandbox=0 AND scope_customer.id IN
        """+IDS+SupportBindingMapper.CUSTOMER_SCOPE_PREDICATE+" ORDER BY t.id</script>")
    List<CustomerTagRow> customerTagRows(@Param("scope") ReadScope scope,@Param("ids") Collection<Long> ids);

    @Select("<script>SELECT version,dormant_days dormantDays,maintenance_days maintenanceDays,activity_window_days activityWindowDays,inheritance_mode inheritanceMode,max_inheritance_depth maxInheritanceDepth,unbound_assignment_mode unboundAssignmentMode,mode_effective_at modeEffectiveAt FROM nx_support_rules WHERE id=1 AND "+READ_SCOPE+"</script>")
    SupportRules queryRules(@Param("scope") ReadScope scope);
    @Select("""
        <script>WITH observed AS (SELECT e.customer_id customerId,e.id eventId,e.seq,e.source_ref sourceRef,e.occurred_at occurredAt,
          ROW_NUMBER() OVER(PARTITION BY e.customer_id ORDER BY e.occurred_at DESC,e.id DESC) position
        FROM nx_support_activity_event e JOIN nx_user scope_customer ON scope_customer.id=e.customer_id
        WHERE scope_customer.sandbox=0 AND e.customer_id IN
        """+IDS+SupportBindingMapper.CUSTOMER_SCOPE_PREDICATE+"""
          AND e.occurred_at&lt;=#{observedThroughAt})
        SELECT customerId,eventId,seq,sourceRef,occurredAt FROM observed WHERE position=1 ORDER BY customerId</script>
        """)
    List<ActivityIdentityRow> activityIdentityRows(@Param("scope") ReadScope scope,@Param("ids") Collection<Long> ids,
        @Param("observedThroughAt") LocalDateTime observedThroughAt);

    /** All rows of the existing workbench task projection, plus the actual oldest pending time and source identities. */
    @Select("<script>"+SupportWorkbenchMapper.PROJECTION+"""
        SELECT c.customerId,c.enabled,c.preferenceVersion,c.openCycleId,c.lastExecutionAt,c.lastSucceededAt,c.nextDueAt,
          c.firstContact,c.pendingConversationNo,c.pendingThroughMessageId,c.pendingReplyCount,c.waitingReply,
          (c.enabled=1 AND #{maintenanceDays} IS NOT NULL AND (c.lastExecutionAt IS NULL OR c.nextDueAt&lt;=#{evaluatedDbAt})) due,c.stoppedReason,
          c.activityStatus,c.windowStatus,p.updated_at preferenceUpdatedAt,(p.customer_id IS NOT NULL) preferencePresent,
          (SELECT MAX(e.id) FROM nx_support_maintenance_execution e WHERE e.assignment_id=c.assignmentId
            AND e.executed_at=c.lastExecutionAt) executionId,
          (SELECT MAX(h.message_id) FROM nx_support_human_message h WHERE h.assignment_id=c.assignmentId
            AND h.customer_id=c.customerId AND h.actor_type='ADMIN' AND h.actor_id=c.agentAdminId) contactFactId,
          (SELECT MIN(m.created_at) FROM nx_conversation cv JOIN nx_conversation_message m ON m.conversation_no=cv.conversation_no
            LEFT JOIN nx_support_reply_cursor rc ON rc.conversation_no=cv.conversation_no
            WHERE cv.user_id=c.customerId AND cv.is_deleted=0 AND m.is_deleted=0 AND m.sender_type='user'
              AND m.id&gt;COALESCE(rc.through_message_id,0)) waitingSinceAt
        FROM customers c LEFT JOIN nx_support_maintenance_preference p ON p.customer_id=c.customerId
        WHERE c.customerId IN
        """+IDS+" ORDER BY c.customerId</script>")
    List<TaskRow> taskRows(@Param("scope") ReadScope scope,@Param("ids") Collection<Long> ids,
        @Param("evaluatedAt") LocalDateTime evaluatedAt,@Param("coverageStartAt") LocalDateTime coverageStartAt,
        @Param("dormantCutoff") LocalDateTime dormantCutoff,@Param("windowCutoff") LocalDateTime windowCutoff,
        @Param("maintenanceDays") Integer maintenanceDays,@Param("customerId") Long customerId,@Param("evaluatedDbAt") LocalDateTime evaluatedDbAt);
    @Select("""
        <script>SELECT scope_customer.id customerId,m.id messageId,c.conversation_no conversationNo,
          COALESCE(r.through_message_id,0) throughMessageId,m.created_at createdAt
        FROM nx_user scope_customer JOIN nx_conversation c ON c.user_id=scope_customer.id AND c.is_deleted=0
        JOIN nx_conversation_message m ON m.conversation_no=c.conversation_no AND m.is_deleted=0 AND m.sender_type='user'
        LEFT JOIN nx_support_reply_cursor r ON r.conversation_no=c.conversation_no
        WHERE scope_customer.sandbox=0 AND scope_customer.id IN
        """+IDS+SupportBindingMapper.CUSTOMER_SCOPE_PREDICATE+" AND m.id&gt;COALESCE(r.through_message_id,0) ORDER BY m.id</script>")
    List<PendingReplyRow> pendingReplyRows(@Param("scope") ReadScope scope,@Param("ids") Collection<Long> ids);

    record PermissionStamp(Long roleRelationId,Long roleId,Long rolePermissionId,Long permissionId,String permissionCode,Integer roleStatus,Integer permissionStatus) { }
    record PersonStamp(Long id,Integer status,Long version,Integer profileEnabled,Integer profileDeleted,Long profileVersion,String profileSeatType,String nickname,String username,String avatarAssetId,Long avatarVersion) {
        public PersonStamp(Long id,Integer status,Long version,Integer profileEnabled,Integer profileDeleted,Long profileVersion,String profileSeatType,String nickname,String username){this(id,status,version,profileEnabled,profileDeleted,profileVersion,profileSeatType,nickname,username,null,null);}
    }
    record RoleStamp(Long relationId,Long roleId,Long adminId,String roleCode,Integer status) { }
    record QualificationStamp(Long id,Long adminId,String kind,String state,Long version,LocalDateTime startsAt,LocalDateTime endsAt) { }
    record GroupStamp(Long id,String name,Long supervisorAdminId,String status,Long version) { }
    record RelationshipStamp(String kind,Long id,Long customerId,Long agentId,Long groupId,Long ownerId,String state,Integer deleted,Long version,LocalDateTime startsAt,LocalDateTime endsAt) { }
    record RootDisplayRow(Long customerId,String nickname,String customerNo,String status,LocalDateTime registeredAt,LocalDateTime sourceUpdatedAt,LocalDateTime assignedAt,LocalDateTime poolEnteredAt,Long poolVersion,String avatarObjectKey,String vRank,String userLevel) {
        public RootDisplayRow(Long customerId,String nickname,String customerNo,String status,LocalDateTime registeredAt,LocalDateTime sourceUpdatedAt,LocalDateTime assignedAt,LocalDateTime poolEnteredAt,Long poolVersion){this(customerId,nickname,customerNo,status,registeredAt,sourceUpdatedAt,assignedAt,poolEnteredAt,poolVersion,null,null,null);}
    }
    record CustomerTagRow(Long id,Long customerId,String tag,LocalDateTime createdAt,LocalDateTime updatedAt) { }
    record ActivityIdentityRow(Long customerId,Long eventId,Long seq,String sourceRef,LocalDateTime occurredAt) { }
    record TaskRow(Long customerId,Integer enabled,Long preferenceVersion,Long openCycleId,LocalDateTime lastExecutionAt,
        LocalDateTime lastSucceededAt,LocalDateTime nextDueAt,Integer firstContact,String pendingConversationNo,Long pendingThroughMessageId,
        Long pendingReplyCount,Integer waitingReply,Integer due,String stoppedReason,String activityStatus,String windowStatus,
        LocalDateTime preferenceUpdatedAt,Integer preferencePresent,Long executionId,Long contactFactId,LocalDateTime waitingSinceAt) { }
    record PendingReplyRow(Long customerId,Long messageId,String conversationNo,Long throughMessageId,LocalDateTime createdAt) { }
}
