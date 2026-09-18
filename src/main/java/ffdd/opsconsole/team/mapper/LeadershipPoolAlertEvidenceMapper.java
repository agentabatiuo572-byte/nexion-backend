package ffdd.opsconsole.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDelivery;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.shared.outbox.infrastructure.EventOutboxEntity;
import ffdd.opsconsole.shared.outbox.mapper.EventConsumerDeliveryMapper;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;

import java.util.List;
import org.apache.ibatis.annotations.*;

/** Verifies the original FAILED alert audit; never invokes settlement or changes configuration. */
public interface LeadershipPoolAlertEvidenceMapper extends BaseMapper<EventOutboxEntity> {
    String GROUP = "f4-config-alert-evidence";
    String TYPE = "leadership_pool.settlement_blocked";
    // The original producer records these five fields together in one REQUIRES_NEW transaction.
    String AUDIT_MATCH = """
        a.biz_no=CONCAT('F4-CONFIG-BLOCKED-',o.aggregate_id)
        AND BINARY a.biz_no=BINARY CONCAT('F4-CONFIG-BLOCKED-',o.aggregate_id)
        AND a.is_deleted=0
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'$.source'))=BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.source'))
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'$.configKey'))=BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.config_key'))
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'$.reason'))=BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.reason'))
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'$.valueFingerprint'))=BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.value_fingerprint'))
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'$.blockedAt'))=BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.blocked_at'))
        """;
    // Missing historical evidence is excluded BEFORE LIMIT and remains pending for diagnosis.
    @Select("""
        SELECT /*+ MAX_EXECUTION_TIME(1500) */ o.id FROM nx_event_outbox o
         WHERE o.is_deleted=0 AND o.event_type='leadership_pool.settlement_blocked'
           AND BINARY o.event_type='leadership_pool.settlement_blocked'
           AND o.status IN ('PENDING','FAILED') AND BINARY o.status IN ('PENDING','FAILED')
           AND (o.next_retry_at IS NULL OR o.next_retry_at <= NOW())
           AND EXISTS (SELECT 1 FROM nx_audit_log a WHERE
        """ + AUDIT_MATCH + ") ORDER BY o.id LIMIT #{limit}")
    @Options(timeout=3)
    List<Long> eligible(@Param("limit") int limit);

    @Select("SELECT " + EventOutboxMapper.MESSAGE_COLUMNS + """
             FROM nx_event_outbox WHERE id=#{id} AND is_deleted=0
               AND status IN ('PENDING','FAILED') AND BINARY status IN ('PENDING','FAILED')
               AND (next_retry_at IS NULL OR next_retry_at <= NOW()) FOR UPDATE
            """)
    @Options(timeout=3)
    EventOutboxMessage lockPending(@Param("id") long id);

    @Select("SELECT " + EventConsumerDeliveryMapper.DELIVERY_COLUMNS + """
             FROM nx_event_consumer_delivery
             WHERE event_id=#{eventId} AND BINARY event_id=BINARY #{eventId}
               AND consumer_group='f4-config-alert-evidence' AND BINARY consumer_group='f4-config-alert-evidence'
               AND is_deleted=0 FOR UPDATE
            """)
    @Options(timeout=3)
    EventConsumerDelivery receipt(@Param("eventId") String eventId);

    // Exclusive audit lock serializes claims by distinct event IDs and prevents retention cleanup.
    @Select("""
        SELECT a.id,a.action,a.resource_type AS resourceType,a.resource_id AS resourceId,
               a.actor_type AS actorType,a.actor_username AS actorUsername,a.result,a.risk_level AS riskLevel,
               a.detail_json AS detailJson
          FROM nx_audit_log a JOIN nx_event_outbox o ON o.id=#{id}
         WHERE
        """ + AUDIT_MATCH + " ORDER BY a.id LIMIT 2 FOR UPDATE")
    @Options(timeout=3)
    List<AuditFact> facts(@Param("id") long id);

    @Select("SELECT " + EventConsumerDeliveryMapper.DELIVERY_COLUMNS + """
        FROM nx_event_consumer_delivery
         WHERE consumer_group='f4-config-alert-evidence' AND BINARY consumer_group='f4-config-alert-evidence'
           AND BINARY msg_id=BINARY CONCAT('A2:',#{auditId}) AND is_deleted=0 LIMIT 2 FOR UPDATE
        """)
    @Options(timeout=3)
    List<EventConsumerDelivery> claims(@Param("auditId") long auditId);

    @Insert("""
        INSERT INTO nx_event_consumer_delivery
          (event_id,consumer_group,topic,msg_id,event_type,aggregate_type,aggregate_id,status,
           attempt_count,rocketmq_reconsume_times,created_commissions,processed_at,
           first_seen_at,last_seen_at,created_at,updated_at,is_deleted)
        VALUES (#{message.eventId},'f4-config-alert-evidence','spring-local-f4-alert-evidence',CONCAT('A2:',#{auditId}),
                #{message.eventType},#{message.aggregateType},#{message.aggregateId},'SUCCESS',1,0,0,NOW(),NOW(),NOW(),NOW(),NOW(),0)
        """)
    int insertReceipt(@Param("message") EventOutboxMessage message,@Param("auditId") long auditId);

    @Update("""
            UPDATE nx_event_outbox SET status='PUBLISHED',published_at=NOW(),updated_at=NOW(),last_error=NULL
             WHERE id=#{id} AND is_deleted=0 AND BINARY status IN ('PENDING','FAILED')
            """)
    int publish(@Param("id") long id);

    // A failed proof is not a delivery attempt and must never age into DEAD automatically.
    @Update("""
            UPDATE nx_event_outbox SET last_error=#{code},next_retry_at=DATE_ADD(NOW(),INTERVAL 10 MINUTE),updated_at=NOW()
             WHERE id=#{id} AND is_deleted=0 AND BINARY status IN ('PENDING','FAILED')
               AND BINARY event_type='leadership_pool.settlement_blocked'
            """)
    int defer(@Param("id") long id,@Param("code") String code);

    record AuditFact(Long id,String action,String resourceType,String resourceId,String actorType,
                     String actorUsername,String result,String riskLevel,String detailJson) {}
}
