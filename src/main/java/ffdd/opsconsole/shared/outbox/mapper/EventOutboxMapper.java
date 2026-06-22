package ffdd.opsconsole.shared.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.shared.outbox.infrastructure.EventOutboxEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Lang;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.scripting.defaults.RawLanguageDriver;

public interface EventOutboxMapper extends BaseMapper<EventOutboxEntity> {
    String MESSAGE_COLUMNS = """
            id,
            event_id AS eventId,
            aggregate_type AS aggregateType,
            aggregate_id AS aggregateId,
            event_type AS eventType,
            payload,
            status,
            retry_count AS retryCount,
            next_retry_at AS nextRetryAt,
            published_at AS publishedAt,
            last_error AS lastError,
            created_at AS createdAt,
            updated_at AS updatedAt
            """;

    @Insert("""
            INSERT INTO nx_event_outbox (
              event_id, aggregate_type, aggregate_id, event_type, payload,
              status, retry_count, next_retry_at, created_at, updated_at, is_deleted
            ) VALUES (
              #{eventId}, #{aggregateType}, #{aggregateId}, #{eventType}, #{payload},
              'PENDING', 0, NOW(), NOW(), NOW(), 0
            )
            """)
    int insertEvent(@Param("eventId") String eventId, @Param("aggregateType") String aggregateType,
                    @Param("aggregateId") String aggregateId, @Param("eventType") String eventType,
                    @Param("payload") String payload);

    @Select("""
            SELECT
            """ + MESSAGE_COLUMNS + """
              FROM nx_event_outbox
             WHERE is_deleted = 0
               AND status IN ('PENDING', 'FAILED')
              AND (next_retry_at IS NULL OR next_retry_at &lt;= NOW())
             ORDER BY id ASC
             LIMIT #{limit}
            """)
    @Lang(RawLanguageDriver.class)
    List<EventOutboxMessage> listPending(@Param("limit") int limit);

    @Select("""
            SELECT
            """ + MESSAGE_COLUMNS + """
              FROM nx_event_outbox
             WHERE is_deleted = 0
               AND event_type = #{eventType}
               AND status IN ('PENDING', 'FAILED')
              AND (next_retry_at IS NULL OR next_retry_at &lt;= NOW())
             ORDER BY id ASC
             LIMIT #{limit}
            """)
    @Lang(RawLanguageDriver.class)
    List<EventOutboxMessage> listPendingByEventType(@Param("eventType") String eventType, @Param("limit") int limit);

    @Select("""
            SELECT
            """ + MESSAGE_COLUMNS + """
              FROM nx_event_outbox
             WHERE is_deleted = 0
               AND aggregate_type = #{aggregateType}
               AND aggregate_id = #{aggregateId}
             ORDER BY id DESC
             LIMIT #{limit}
            """)
    List<EventOutboxMessage> listByAggregate(@Param("aggregateType") String aggregateType,
                                             @Param("aggregateId") String aggregateId,
                                             @Param("limit") int limit);

    @Select("""
            SELECT
            """ + MESSAGE_COLUMNS + """
              FROM nx_event_outbox
             WHERE is_deleted = 0
               AND status = #{status}
             ORDER BY updated_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<EventOutboxMessage> listByStatus(@Param("status") String status, @Param("limit") int limit);

    @Update("""
            UPDATE nx_event_outbox
               SET status = #{publishedStatus},
                   published_at = NOW(),
                   updated_at = NOW(),
                   last_error = NULL
            WHERE event_id = #{eventId}
              AND is_deleted = 0
              AND status != #{publishedStatus}
            """)
    @Lang(RawLanguageDriver.class)
    int markPublished(@Param("eventId") String eventId, @Param("publishedStatus") String publishedStatus);

    @Update("""
            UPDATE nx_event_outbox
               SET status = CASE WHEN retry_count + 1 >= #{maxRetries} THEN #{deadStatus} ELSE #{failedStatus} END,
                   next_retry_at = CASE
                     WHEN retry_count + 1 >= #{maxRetries} THEN NULL
                     ELSE DATE_ADD(NOW(), INTERVAL LEAST(300, POW(2, LEAST(retry_count + 1, 8))) SECOND)
                   END,
                   retry_count = retry_count + 1,
                   last_error = #{errorMessage},
                   updated_at = NOW()
             WHERE event_id = #{eventId}
               AND is_deleted = 0
               AND status IN (#{pendingStatus}, #{failedStatus})
            """)
    int markFailed(@Param("eventId") String eventId, @Param("errorMessage") String errorMessage,
                   @Param("maxRetries") int maxRetries, @Param("deadStatus") String deadStatus,
                   @Param("failedStatus") String failedStatus, @Param("pendingStatus") String pendingStatus);
}
