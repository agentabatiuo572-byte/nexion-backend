package ffdd.opsconsole.content.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Resolves the evidence at each verified financial event, in the sampler's source transaction. */
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportPerformanceHistoryMapper {
    record Point(String eventId, long customerId, LocalDateTime atUtc) { }

    record Evidence(String eventId, Long bindingId, Long agentId, LocalDateTime bindingFrom, LocalDateTime bindingTo,
                    Long membershipId, Long groupId, LocalDateTime groupFrom, LocalDateTime groupTo,
                    Long routeId, Long routeGroupId, Long qualificationId, String qualificationState, boolean boundaryUncertain) { }

    @Select("""
        <script>
        WITH points AS (
          <foreach collection="points" item="p" separator=" UNION ALL ">
            SELECT #{p.eventId} event_id, #{p.customerId} customer_id, CAST(#{p.atUtc} AS DATETIME(6)) occurred_at
          </foreach>
        ), instants AS (
          SELECT points.*, TIMESTAMPADD(MICROSECOND,-MICROSECOND(occurred_at),occurred_at) at_second FROM points
        )
        SELECT p.event_id eventId, a.id bindingId, a.agent_admin_id agentId, a.starts_at bindingFrom, a.ends_at bindingTo,
            m.id membershipId, m.group_id groupId, m.starts_at groupFrom, m.ends_at groupTo,
            r.id routeId, r.group_id routeGroupId, q.id qualificationId, q.state qualificationState,
            (EXISTS (SELECT 1 FROM nx_support_agent_user_assignment boundary
                     WHERE boundary.user_id=p.customer_id AND boundary.is_deleted=0
                       AND (boundary.starts_at=p.at_second OR boundary.ends_at=p.at_second))
             OR (p.occurred_at=p.at_second AND (
                 (m.starts_at&gt;=p.at_second AND m.starts_at&lt;TIMESTAMPADD(SECOND,1,p.at_second))
                 OR (m.ends_at&gt;=p.at_second AND m.ends_at&lt;TIMESTAMPADD(SECOND,1,p.at_second))
                 OR (q.starts_at&gt;=p.at_second AND q.starts_at&lt;TIMESTAMPADD(SECOND,1,p.at_second))
                 OR (q.ends_at&gt;=p.at_second AND q.ends_at&lt;TIMESTAMPADD(SECOND,1,p.at_second))
                 OR (r.starts_at&gt;=p.at_second AND r.starts_at&lt;TIMESTAMPADD(SECOND,1,p.at_second))
                 OR (r.ends_at&gt;=p.at_second AND r.ends_at&lt;TIMESTAMPADD(SECOND,1,p.at_second))))) IS TRUE boundaryUncertain
        FROM instants p
        LEFT JOIN nx_support_agent_user_assignment a
          ON a.user_id=p.customer_id AND a.is_deleted=0 AND a.version&gt;0
          AND a.starts_at&lt;=p.occurred_at AND (a.ends_at IS NULL OR a.ends_at&gt;p.occurred_at)
          AND ((a.status='ACTIVE' AND a.ends_at IS NULL) OR (a.status='INACTIVE' AND a.ends_at IS NOT NULL))
        LEFT JOIN nx_support_group_member_history m
          ON m.agent_admin_id=a.agent_admin_id AND m.version&gt;0
          AND m.starts_at&lt;=p.occurred_at AND (m.ends_at IS NULL OR m.ends_at&gt;p.occurred_at)
        LEFT JOIN nx_support_customer_route_history r
          ON r.customer_id=p.customer_id AND r.version&gt;0
          AND r.starts_at&lt;=p.occurred_at AND (r.ends_at IS NULL OR r.ends_at&gt;p.occurred_at)
        LEFT JOIN nx_support_account_qualification_history q
          ON q.admin_id=a.agent_admin_id AND q.qualification_kind='SERVICE' AND q.version&gt;0
          AND q.starts_at&lt;=p.occurred_at AND (q.ends_at IS NULL OR q.ends_at&gt;p.occurred_at)
        </script>
        """)
    List<Evidence> atEvents(@Param("points") List<Point> points);

    record Witness(String layer, long rowId, long subjectId, Long relatedId, String state, long version,
                   LocalDateTime startsAt, LocalDateTime endsAt) { }

    /** The original inputs to atEvents, including interval ends returned in its evidence. */
    @Select("""
        SELECT 'assignment' layer,id rowId,user_id subjectId,agent_admin_id relatedId,
               CONCAT(status,':',is_deleted) state,version,starts_at startsAt,ends_at endsAt
          FROM nx_support_agent_user_assignment WHERE starts_at < #{before}
        UNION ALL
        SELECT 'member',id,agent_admin_id,group_id,'',version,starts_at,ends_at
          FROM nx_support_group_member_history WHERE starts_at < #{before}
        UNION ALL
        SELECT 'route',id,customer_id,group_id,'',version,starts_at,ends_at
          FROM nx_support_customer_route_history WHERE starts_at < #{before}
        UNION ALL
        SELECT 'qualification',id,admin_id,NULL,state,version,starts_at,ends_at
          FROM nx_support_account_qualification_history
         WHERE qualification_kind='SERVICE' AND starts_at < #{before}
        ORDER BY layer,rowId
        """)
    List<Witness> checkpointWitnesses(@Param("before") LocalDateTime before);

    record BoundCustomer(long customerId, long agentId, Long groupId, LocalDateTime startsAt,
                         LocalDateTime endsAt) { }

    @Select("""
        WITH segments AS (
          SELECT a.id assignmentId,m.id memberId,q.id qualificationId,a.user_id customerId,a.agent_admin_id agentId,m.group_id groupId,
                 GREATEST(a.starts_at,m.starts_at,q.starts_at) startsAt,
                 LEAST(COALESCE(a.ends_at,'9999-12-31 00:00:00'),
                       COALESCE(m.ends_at,'9999-12-31 00:00:00'),
                       COALESCE(q.ends_at,'9999-12-31 00:00:00')) endsAt
            FROM nx_support_agent_user_assignment a
            JOIN nx_user u ON u.id=a.user_id AND u.sandbox=0
            JOIN nx_support_group_member_history m ON m.agent_admin_id=a.agent_admin_id AND m.version>0
            JOIN nx_support_account_qualification_history q ON q.admin_id=a.agent_admin_id
                  AND q.qualification_kind='SERVICE' AND q.state='ENABLED' AND q.version>0
           WHERE a.is_deleted=0 AND a.version>0
             AND ((a.status='ACTIVE' AND a.ends_at IS NULL) OR (a.status='INACTIVE' AND a.ends_at IS NOT NULL))
             AND a.starts_at < #{at}
        )
        SELECT s.customerId,s.agentId,s.groupId,s.startsAt,NULLIF(s.endsAt,'9999-12-31 00:00:00') endsAt
          FROM segments s WHERE s.startsAt < s.endsAt AND s.startsAt < #{at}
           AND NOT EXISTS (SELECT 1 FROM segments x WHERE x.customerId=s.customerId
                 AND x.startsAt < x.endsAt AND x.startsAt < s.endsAt AND s.startsAt < x.endsAt
                 AND (x.assignmentId<>s.assignmentId OR x.memberId<>s.memberId OR x.qualificationId<>s.qualificationId))
           AND NOT EXISTS (SELECT 1 FROM nx_support_customer_route_history r WHERE r.customer_id=s.customerId
                 AND r.version>0 AND r.starts_at < s.endsAt AND (r.ends_at IS NULL OR s.startsAt < r.ends_at)
                 AND NOT (r.group_id <=> s.groupId))
         ORDER BY s.customerId,s.startsAt,s.agentId,s.groupId
        """)
    List<BoundCustomer> bindingHistory(@Param("at") LocalDateTime at);

    @Select("""
        SELECT month_key month, channel, coverage_from fromUtc, coverage_to toUtc, evidence_digest evidenceDigest
          FROM nx_support_performance_channel_review
         WHERE coverage_to <= #{at} AND verified_by IS NOT NULL AND evidence_ref <> ''
         ORDER BY month_key, channel, coverage_to
        """)
    List<ChannelReview> channelReviews(@Param("at") LocalDateTime at);

    record ChannelReview(String month, String channel, LocalDateTime fromUtc, LocalDateTime toUtc, String evidenceDigest) { }

    record Supervisor(long groupId, long adminId) { }

    @Select("""
        SELECT group_id groupId, MIN(supervisor_admin_id) adminId
          FROM nx_support_group_owner_history
         WHERE version>0 AND starts_at < #{at} AND (ends_at IS NULL OR ends_at >= #{at})
         GROUP BY group_id HAVING COUNT(*)=1
        """)
    List<Supervisor> supervisorsAtCutoff(@Param("at") LocalDateTime at);
}
