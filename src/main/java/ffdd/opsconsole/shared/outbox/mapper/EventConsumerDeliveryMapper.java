package ffdd.opsconsole.shared.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.outbox.EventConsumerDelivery;
import ffdd.opsconsole.shared.outbox.infrastructure.EventConsumerDeliveryEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Lang;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.scripting.defaults.RawLanguageDriver;

public interface EventConsumerDeliveryMapper extends BaseMapper<EventConsumerDeliveryEntity> {
    String DELIVERY_COLUMNS = """
            id,
            event_id AS eventId,
            consumer_group AS consumerGroup,
            topic,
            msg_id AS msgId,
            event_type AS eventType,
            aggregate_type AS aggregateType,
            aggregate_id AS aggregateId,
            status,
            attempt_count AS attemptCount,
            rocketmq_reconsume_times AS rocketmqReconsumeTimes,
            next_retry_at AS nextRetryAt,
            processed_at AS processedAt,
            dead_at AS deadAt,
            created_commissions AS createdCommissions,
            last_error AS lastError,
            first_seen_at AS firstSeenAt,
            last_seen_at AS lastSeenAt,
            created_at AS createdAt,
            updated_at AS updatedAt
            """;

    @Insert("""
            INSERT INTO nx_event_consumer_delivery (
              event_id, consumer_group, topic, msg_id, event_type, aggregate_type, aggregate_id,
              status, attempt_count, rocketmq_reconsume_times, first_seen_at, last_seen_at,
              created_at, updated_at, is_deleted
            ) VALUES (
              #{eventId}, #{consumerGroup}, #{topic}, #{msgId}, #{eventType}, #{aggregateType}, #{aggregateId},
              #{status}, 1, #{rocketmqReconsumeTimes}, NOW(), NOW(), NOW(), NOW(), 0
            )
            """)
    int insertClaim(@Param("eventId") String eventId, @Param("consumerGroup") String consumerGroup,
                    @Param("topic") String topic, @Param("msgId") String msgId,
                    @Param("eventType") String eventType, @Param("aggregateType") String aggregateType,
                    @Param("aggregateId") String aggregateId, @Param("status") String status,
                    @Param("rocketmqReconsumeTimes") int rocketmqReconsumeTimes);

    @Update("""
            UPDATE nx_event_consumer_delivery
               SET status = #{processingStatus},
                   topic = #{topic},
                   msg_id = #{msgId},
                   event_type = #{eventType},
                   aggregate_type = #{aggregateType},
                   aggregate_id = #{aggregateId},
                   attempt_count = attempt_count + 1,
                   rocketmq_reconsume_times = #{rocketmqReconsumeTimes},
                   next_retry_at = NULL,
                   last_error = NULL,
                   last_seen_at = NOW(),
                   updated_at = NOW()
             WHERE event_id = #{eventId}
               AND consumer_group = #{consumerGroup}
               AND is_deleted = 0
               AND (
                    status = #{failedStatus}
                   OR (status = #{processingStatus} AND updated_at &lt; #{staleBefore})
               )
            """)
    @Lang(RawLanguageDriver.class)
    int reclaim(@Param("eventId") String eventId, @Param("consumerGroup") String consumerGroup,
                @Param("topic") String topic, @Param("msgId") String msgId,
                @Param("eventType") String eventType, @Param("aggregateType") String aggregateType,
                @Param("aggregateId") String aggregateId, @Param("processingStatus") String processingStatus,
                @Param("failedStatus") String failedStatus, @Param("rocketmqReconsumeTimes") int rocketmqReconsumeTimes,
                @Param("staleBefore") LocalDateTime staleBefore);

    @Select("""
            SELECT
            """ + DELIVERY_COLUMNS + """
              FROM nx_event_consumer_delivery
             WHERE is_deleted = 0
               AND consumer_group = #{consumerGroup}
               AND event_id = #{eventId}
             LIMIT 1
            """)
    EventConsumerDelivery getByEvent(@Param("consumerGroup") String consumerGroup, @Param("eventId") String eventId);

    @Update("""
            UPDATE nx_event_consumer_delivery
               SET status = #{status},
                   next_retry_at = CASE WHEN #{dead} THEN NULL
                                        ELSE DATE_ADD(NOW(), INTERVAL LEAST(300, POW(2, LEAST(attempt_count, 8))) SECOND)
                                   END,
                   dead_at = CASE WHEN #{dead} THEN NOW() ELSE dead_at END,
                   last_error = #{lastError},
                   rocketmq_reconsume_times = #{rocketmqReconsumeTimes},
                   last_seen_at = NOW(),
                   updated_at = NOW()
             WHERE event_id = #{eventId}
               AND consumer_group = #{consumerGroup}
               AND is_deleted = 0
            """)
    int markFailure(@Param("consumerGroup") String consumerGroup, @Param("eventId") String eventId,
                    @Param("status") String status, @Param("dead") boolean dead,
                    @Param("rocketmqReconsumeTimes") int rocketmqReconsumeTimes,
                    @Param("lastError") String lastError);

    @Update("""
            UPDATE nx_event_consumer_delivery
               SET status = #{status},
                   processed_at = NOW(),
                   next_retry_at = NULL,
                   dead_at = NULL,
                   created_commissions = #{processedCount},
                   last_error = NULL,
                   last_seen_at = NOW(),
                   updated_at = NOW()
             WHERE event_id = #{eventId}
               AND consumer_group = #{consumerGroup}
               AND is_deleted = 0
            """)
    int markSuccess(@Param("consumerGroup") String consumerGroup, @Param("eventId") String eventId,
                    @Param("status") String status, @Param("processedCount") int processedCount);

    @Update("""
            UPDATE nx_event_consumer_delivery
               SET status = #{status},
                   processed_at = NOW(),
                   next_retry_at = NULL,
                   dead_at = NULL,
                   last_error = #{reason},
                   last_seen_at = NOW(),
                   updated_at = NOW()
             WHERE event_id = #{eventId}
               AND consumer_group = #{consumerGroup}
               AND is_deleted = 0
            """)
    int markSkipped(@Param("consumerGroup") String consumerGroup, @Param("eventId") String eventId,
                    @Param("status") String status, @Param("reason") String reason);

    @Select("""
            <script>
            SELECT
            """ + DELIVERY_COLUMNS + """
              FROM nx_event_consumer_delivery
             WHERE is_deleted = 0
             <if test='consumerGroup != null and consumerGroup != ""'>AND consumer_group = #{consumerGroup}</if>
               AND status = #{status}
             ORDER BY updated_at DESC, id DESC
             LIMIT #{limit}
            </script>
            """)
    List<EventConsumerDelivery> listByStatus(@Param("consumerGroup") String consumerGroup,
                                             @Param("status") String status,
                                             @Param("limit") int limit);

    @Select("""
            SELECT
            """ + DELIVERY_COLUMNS + """
              FROM nx_event_consumer_delivery
             WHERE is_deleted = 0
               AND aggregate_type = #{aggregateType}
               AND aggregate_id = #{aggregateId}
             ORDER BY updated_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<EventConsumerDelivery> listByAggregate(@Param("aggregateType") String aggregateType,
                                                @Param("aggregateId") String aggregateId,
                                                @Param("limit") int limit);

    @Select("""
            <script>
            SELECT consumer_group AS consumerGroup,
                   topic,
                   status,
                   COUNT(*) AS total,
                   COALESCE(SUM(attempt_count), 0) AS attempts,
                   MAX(updated_at) AS lastUpdatedAt
              FROM nx_event_consumer_delivery
             WHERE is_deleted = 0
             <if test='consumerGroup != null and consumerGroup != ""'>AND consumer_group = #{consumerGroup}</if>
             GROUP BY consumer_group, topic, status
             ORDER BY consumer_group ASC, topic ASC, status ASC
            </script>
            """)
    List<Map<String, Object>> summary(@Param("consumerGroup") String consumerGroup);
}
