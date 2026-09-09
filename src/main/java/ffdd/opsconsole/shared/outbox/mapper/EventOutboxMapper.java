package ffdd.opsconsole.shared.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.shared.outbox.infrastructure.EventOutboxEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface EventOutboxMapper extends BaseMapper<EventOutboxEntity> {
    String MESSAGE_COLUMNS = """
            id,
            event_id AS eventId,
            aggregate_type AS aggregateType,
            aggregate_id AS aggregateId,
            event_type AS eventType,
            event_name AS eventName,
            family_key AS familyKey,
            event_ts AS eventTs,
            phase,
            account_age_months AS accountAgeMonths,
            cohort,
            is_server_authoritative AS serverAuthoritative,
            schema_revision AS schemaRevision,
            schema_registered AS schemaRegistered,
            analytics_event AS analyticsEvent,
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
              event_id, aggregate_type, aggregate_id, event_type,
              event_name, family_key, event_ts, phase, account_age_months, cohort,
              is_server_authoritative, schema_revision, schema_registered, analytics_event, payload,
              status, retry_count, next_retry_at, created_at, updated_at, is_deleted
            ) VALUES (
              #{eventId}, #{aggregateType}, #{aggregateId}, #{eventType},
              #{eventName}, #{familyKey}, NOW(3), #{phase}, #{accountAgeMonths}, #{cohort},
              #{serverAuthoritative}, #{schemaRevision}, #{schemaRegistered}, #{analyticsEvent}, #{payload},
              'PENDING', 0, NOW(), NOW(), NOW(), 0
            )
            """)
    int insertEvent(@Param("eventId") String eventId, @Param("aggregateType") String aggregateType,
                    @Param("aggregateId") String aggregateId, @Param("eventType") String eventType,
                    @Param("eventName") String eventName, @Param("familyKey") String familyKey,
                    @Param("phase") String phase, @Param("accountAgeMonths") int accountAgeMonths,
                    @Param("cohort") String cohort,
                    @Param("serverAuthoritative") boolean serverAuthoritative,
                    @Param("schemaRevision") Integer schemaRevision,
                    @Param("schemaRegistered") boolean schemaRegistered,
                    @Param("analyticsEvent") boolean analyticsEvent,
                    @Param("payload") String payload);

    /** Writes only a server-re-read occurrence time; generic producers retain NOW(3). */
    @Insert("""
            INSERT INTO nx_event_outbox (
              event_id, aggregate_type, aggregate_id, event_type,
              event_name, family_key, event_ts, phase, account_age_months, cohort,
              is_server_authoritative, schema_revision, schema_registered, analytics_event, payload,
              status, retry_count, next_retry_at, created_at, updated_at, is_deleted
            ) VALUES (
              #{eventId}, #{aggregateType}, #{aggregateId}, #{eventType},
              #{eventName}, #{familyKey}, #{eventTs}, #{phase}, #{accountAgeMonths}, #{cohort},
              #{serverAuthoritative}, #{schemaRevision}, #{schemaRegistered}, #{analyticsEvent}, #{payload},
              'PENDING', 0, NOW(), NOW(), NOW(), 0
            )
            """)
    int insertEventAt(@Param("eventId") String eventId, @Param("aggregateType") String aggregateType,
                      @Param("aggregateId") String aggregateId, @Param("eventType") String eventType,
                      @Param("eventName") String eventName, @Param("familyKey") String familyKey,
                      @Param("eventTs") java.time.LocalDateTime eventTs, @Param("phase") String phase,
                      @Param("accountAgeMonths") int accountAgeMonths, @Param("cohort") String cohort,
                      @Param("serverAuthoritative") boolean serverAuthoritative,
                      @Param("schemaRevision") Integer schemaRevision,
                      @Param("schemaRegistered") boolean schemaRegistered,
                      @Param("analyticsEvent") boolean analyticsEvent,
                      @Param("payload") String payload);

    @Select("""
            SELECT family_key AS familyKey, current_revision AS revision,
                   is_server_authoritative AS serverAuthoritative
              FROM nx_event_schema_registry
             WHERE event_name=#{eventName} AND status='ACTIVE' AND is_deleted=0
             LIMIT 1
            """)
    SchemaGateRow findActiveSchema(@Param("eventName") String eventName);

    @Select("""
            SELECT lifecycle_state
              FROM nx_admin_event_lifecycle
             WHERE event_name=#{eventName} AND is_deleted=0
             LIMIT 1
            """)
    String findLifecycleState(@Param("eventName") String eventName);

    @Select("""
            SELECT p.property_name AS propertyName, p.property_type AS propertyType,
                   p.required_field AS requiredField
             FROM nx_event_schema_property p
              JOIN nx_event_schema_registry s ON s.id=p.schema_id
             WHERE s.event_name=#{eventName} AND s.status='ACTIVE' AND s.is_deleted=0
               AND p.is_deleted=0
               AND p.registry_revision = s.current_revision
             ORDER BY p.id
            """)
    List<SchemaPropertyGateRow> listActiveProperties(@Param("eventName") String eventName);

    @Select("""
            <script>
            SELECT
            """ + MESSAGE_COLUMNS + """
              FROM nx_event_outbox
             WHERE is_deleted = 0
               AND status IN ('PENDING', 'FAILED')
              AND (next_retry_at IS NULL OR next_retry_at &lt;= NOW())
             ORDER BY id ASC
             LIMIT #{limit}
            </script>
            """)
    List<EventOutboxMessage> listPending(@Param("limit") int limit);

    @Select("""
            <script>
            SELECT
            """ + MESSAGE_COLUMNS + """
              FROM nx_event_outbox
             WHERE is_deleted = 0
               AND event_type = #{eventType}
               AND status IN ('PENDING', 'FAILED')
              AND (next_retry_at IS NULL OR next_retry_at &lt;= NOW())
             ORDER BY id ASC
             LIMIT #{limit}
            </script>
            """)
    List<EventOutboxMessage> listPendingByEventType(@Param("eventType") String eventType, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT
            """ + MESSAGE_COLUMNS + """
              FROM nx_event_outbox
             WHERE is_deleted = 0
               AND (LOWER(event_type) = LOWER(#{canonicalType})
                    OR LOWER(event_name) = LOWER(#{canonicalType}))
               AND status IN ('PENDING', 'FAILED')
               AND (next_retry_at IS NULL OR next_retry_at &lt;= NOW())
               AND id &gt; #{afterId}
             ORDER BY id ASC
             LIMIT #{limit}
            </script>
            """)
    List<EventOutboxMessage> listPendingByCanonicalType(@Param("canonicalType") String canonicalType,
                                                        @Param("afterId") long afterId,
                                                        @Param("limit") int limit);

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
    int markPublished(@Param("eventId") String eventId, @Param("publishedStatus") String publishedStatus);

    /**
     * Revives only a published H3 event whose one H3 consumer is explicitly
     * waiting for an active binding. The active-mission join avoids a replay
     * after a binding has been disabled or its target mission retired.
     */
    @Update("""
            UPDATE nx_event_outbox o
              JOIN nx_event_consumer_delivery d
                ON d.event_id = o.event_id
               AND d.consumer_group = #{consumerGroup}
               AND d.status = #{pendingBindingStatus}
               AND d.is_deleted = 0
              JOIN nx_growth_quest_event_binding b
                ON b.event_type = o.event_type
               AND b.status = 1
               AND b.is_deleted = 0
              JOIN nx_mission m
                ON m.mission_code = b.quest_code
               AND m.status = 1
               AND m.is_deleted = 0
               SET o.status = #{pendingStatus},
                   o.next_retry_at = NOW(),
                   o.published_at = NULL,
                   o.last_error = NULL,
                   o.updated_at = NOW()
             WHERE o.event_type = #{eventType}
               AND o.status = #{publishedStatus}
               AND o.is_deleted = 0
            """)
    int requeuePublishedPendingBinding(@Param("eventType") String eventType,
                                       @Param("consumerGroup") String consumerGroup,
                                       @Param("pendingBindingStatus") String pendingBindingStatus,
                                       @Param("pendingStatus") String pendingStatus,
                                       @Param("publishedStatus") String publishedStatus);
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

    /**
     * The redrive boundary intentionally selects only the five governed H3
     * threshold facts. It never exposes payload/source data to the admin API.
     */
    @Select("""
            SELECT event_id AS eventId,
                   event_type AS eventType,
                   retry_count AS retryCount,
                   last_error AS lastError
             FROM nx_event_outbox
             WHERE event_id = #{eventId}
               AND status = 'DEAD'
               AND is_deleted = 0
               AND event_type IN (
                   'H3_STOREFRONT_THREE_PRODUCTS_VIEWED',
                   'H3_GENESIS_SECONDARY_MARKET_VIEWED',
                   'H3_COMPUTE_COMPLETED_50',
                   'H3_REFERRAL_REGISTERED',
                   'H3_EXCHANGE_COMPLETED')
             LIMIT 1
             FOR UPDATE
            """)
    H3DeadLetterRow lockDeadH3ThresholdEvent(@Param("eventId") String eventId);

    /** Safe preview for the same governed DEAD-only recovery boundary. */
    @Select("""
            SELECT event_id AS eventId,
                   event_type AS eventType,
                   retry_count AS retryCount,
                   last_error AS lastError
              FROM nx_event_outbox
             WHERE event_id = #{eventId}
               AND status = 'DEAD'
               AND is_deleted = 0
               AND event_type IN (
                   'H3_STOREFRONT_THREE_PRODUCTS_VIEWED',
                   'H3_GENESIS_SECONDARY_MARKET_VIEWED',
                   'H3_COMPUTE_COMPLETED_50',
                   'H3_REFERRAL_REGISTERED',
                   'H3_EXCHANGE_COMPLETED')
             LIMIT 1
            """)
    H3DeadLetterRow findDeadH3ThresholdEvent(@Param("eventId") String eventId);

    /**
     * Preserve immutable source/payload fields and failed-delivery evidence.
     * The dispatcher performs the next attempt using the original event id.
     */
    @Update("""
            UPDATE nx_event_outbox
               SET status = 'PENDING',
                   next_retry_at = NOW(),
                   updated_at = NOW()
             WHERE event_id = #{eventId}
               AND status = 'DEAD'
               AND retry_count = #{expectedRetryCount}
               AND is_deleted = 0
               AND event_type IN (
                   'H3_STOREFRONT_THREE_PRODUCTS_VIEWED',
                   'H3_GENESIS_SECONDARY_MARKET_VIEWED',
                   'H3_COMPUTE_COMPLETED_50',
                   'H3_REFERRAL_REGISTERED',
                   'H3_EXCHANGE_COMPLETED')
            """)
    int redriveDeadH3ThresholdEvent(@Param("eventId") String eventId,
                                    @Param("expectedRetryCount") int expectedRetryCount);

    record SchemaGateRow(String familyKey, int revision, boolean serverAuthoritative) {}

    record SchemaPropertyGateRow(String propertyName, String propertyType, boolean requiredField) {
    }

    record H3DeadLetterRow(String eventId, String eventType, int retryCount, String lastError) {
    }
}
