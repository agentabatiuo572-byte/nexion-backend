package ffdd.opsconsole.bi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDelivery;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.shared.outbox.infrastructure.EventOutboxEntity;
import ffdd.opsconsole.shared.outbox.mapper.EventConsumerDeliveryMapper;
import ffdd.opsconsole.shared.outbox.mapper.EventOutboxMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Only verifies the production facts already written atomically by L6 ingestion. */
public interface L6BehaviorEvidenceMapper extends BaseMapper<EventOutboxEntity> {
    String GROUP = "l6-behavior-evidence";
    String TYPES = "('app.page_viewed','app.element_clicked')";

    // Missing historical facts remain pending. Filter BEFORE limit so they cannot block new facts.
    @Select("""
            SELECT /*+ MAX_EXECUTION_TIME(1500) */ o.id FROM nx_event_outbox o
             WHERE o.is_deleted=0 AND o.event_type IN
            """ + TYPES + " AND BINARY o.event_type IN " + TYPES + """
               AND o.status IN ('PENDING','FAILED') AND BINARY o.status IN ('PENDING','FAILED')
               AND (o.next_retry_at IS NULL OR o.next_retry_at <= NOW())
               AND EXISTS (SELECT 1 FROM nx_behavior_event_fact f
                     WHERE f.event_id=o.event_id AND BINARY f.event_id=BINARY o.event_id)
             ORDER BY o.id LIMIT #{limit}
            """)
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
               AND consumer_group='l6-behavior-evidence' AND BINARY consumer_group='l6-behavior-evidence'
               AND is_deleted=0 FOR UPDATE
            """)
    @Options(timeout=3)
    EventConsumerDelivery receipt(@Param("eventId") String eventId);

    // Hold the fact against retention cleanup until the receipt and outbox commit together.
    @Select("""
            SELECT event_id AS eventId,event_name AS eventName,session_hash AS sessionHash,actor_hash AS actorHash,
                   route,page_level AS pageLevel,parent_l1 AS parentL1,parent_l2 AS parentL2,
                   dwell_ms AS dwellMs,x_norm AS xNorm,y_norm AS yNorm,zone,element_id AS elementId,
                   device_type AS deviceType,locale,source_environment AS sourceEnvironment
              FROM nx_behavior_event_fact
             WHERE event_id=#{eventId} AND BINARY event_id=BINARY #{eventId} FOR SHARE
            """)
    @Options(timeout=3)
    Fact fact(@Param("eventId") String eventId);

    @Insert("""
            INSERT INTO nx_event_consumer_delivery
              (event_id,consumer_group,topic,msg_id,event_type,aggregate_type,aggregate_id,status,
               attempt_count,rocketmq_reconsume_times,created_commissions,processed_at,
               first_seen_at,last_seen_at,created_at,updated_at,is_deleted)
            VALUES (#{eventId},'l6-behavior-evidence','spring-local-l6-evidence',#{eventId},#{eventType},
                    #{aggregateType},#{aggregateId},'SUCCESS',1,0,1,NOW(),NOW(),NOW(),NOW(),NOW(),0)
            """)
    int insertReceipt(EventOutboxMessage message);

    @Update("""
            UPDATE nx_event_outbox SET status='PUBLISHED',published_at=NOW(),updated_at=NOW(),last_error=NULL
             WHERE id=#{id} AND is_deleted=0 AND BINARY status IN ('PENDING','FAILED')
            """)
    int publish(@Param("id") long id);

    // A failed proof is not a delivery attempt and must never age into DEAD automatically.
    @Update("""
            UPDATE nx_event_outbox SET last_error=#{code},next_retry_at=DATE_ADD(NOW(),INTERVAL 10 MINUTE),updated_at=NOW()
             WHERE id=#{id} AND is_deleted=0 AND BINARY status IN ('PENDING','FAILED')
               AND BINARY event_type IN ('app.page_viewed','app.element_clicked')
            """)
    int defer(@Param("id") long id,@Param("code") String code);

    record Fact(String eventId,String eventName,String sessionHash,String actorHash,String route,Integer pageLevel,
                String parentL1,String parentL2,Long dwellMs,BigDecimal xNorm,BigDecimal yNorm,String zone,
                String elementId,String deviceType,String locale,String sourceEnvironment) {}
}
