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
}
