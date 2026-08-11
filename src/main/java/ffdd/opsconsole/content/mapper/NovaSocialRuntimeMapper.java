package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.NotificationEventFact;
import ffdd.opsconsole.content.domain.NovaBusinessEventFact;
import ffdd.opsconsole.content.domain.NovaBusinessFanoutProgress;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NovaSocialRuntimeMapper extends BaseMapper<Object> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_nova_social_runtime_slot (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              slot_key VARCHAR(128) NOT NULL,
              lease_owner VARCHAR(128) NOT NULL,
              lease_until DATETIME NOT NULL,
              completed_at DATETIME NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              UNIQUE KEY uk_nova_social_runtime_slot (slot_key),
              KEY idx_nova_social_runtime_lease (completed_at, lease_until)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createRuntimeSlotTable();

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_nova_business_event_receipt (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              channel_key VARCHAR(64) NOT NULL,
              source_event_id VARCHAR(64) NOT NULL,
              event_name VARCHAR(128) NOT NULL,
              status VARCHAR(32) NOT NULL,
              reason VARCHAR(255) NOT NULL DEFAULT '',
              notification_count INT NOT NULL DEFAULT 0,
              fanout_cursor_user_id BIGINT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              UNIQUE KEY uk_nova_business_event (channel_key, source_event_id),
              KEY idx_nova_business_event_status (status, updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createBusinessEventReceiptTable();

    @Select("""
            SELECT COUNT(1) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_nova_business_event_receipt'
               AND COLUMN_NAME = 'fanout_cursor_user_id'
            """)
    int fanoutCursorColumnCount();

    @Update("ALTER TABLE nx_nova_business_event_receipt ADD COLUMN fanout_cursor_user_id BIGINT NOT NULL DEFAULT 0 AFTER notification_count")
    void addFanoutCursorColumn();

    @Insert("""
            INSERT IGNORE INTO nx_event_schema_registry (
                event_name, owner_domain, family_key, producer, consumers,
                is_server_authoritative, sampling_policy, current_revision, status,
                created_by, updated_by, reason, created_at, updated_at, is_deleted)
            VALUES (
                #{eventName}, 'nova', 'nova', 'server', 'A4,L1',
                1, '100%', 1, 'ACTIVE',
                'system', 'system', 'I2 Nova server-authoritative event bootstrap',
                NOW(), NOW(), 0)
            """)
    int insertNovaEventSchema(@Param("eventName") String eventName);

    @Insert("""
            INSERT IGNORE INTO nx_event_schema_property (
                schema_id, property_name, property_type, pii, required_field,
                registry_revision, created_at, updated_at, is_deleted)
            SELECT s.id, #{propertyName}, #{propertyType}, 0, #{requiredField},
                   s.current_revision, NOW(), NOW(), 0
              FROM nx_event_schema_registry s
             WHERE s.event_name = #{eventName}
               AND s.status = 'ACTIVE'
               AND s.is_deleted = 0
            """)
    int insertNovaEventProperty(
            @Param("eventName") String eventName,
            @Param("propertyName") String propertyName,
            @Param("propertyType") String propertyType,
            @Param("requiredField") boolean requiredField);

    @Insert("""
            INSERT IGNORE INTO nx_nova_social_runtime_slot (
                slot_key, lease_owner, lease_until, completed_at, created_at, updated_at)
            VALUES (#{slotKey}, #{leaseOwner}, #{leaseUntil}, NULL, #{now}, #{now})
            """)
    int insertSlotClaim(@Param("slotKey") String slotKey,
                        @Param("leaseOwner") String leaseOwner,
                        @Param("leaseUntil") LocalDateTime leaseUntil,
                        @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_nova_social_runtime_slot
               SET lease_owner = #{leaseOwner}, lease_until = #{leaseUntil}, updated_at = #{now}
             WHERE slot_key = #{slotKey}
               AND completed_at IS NULL
               AND NOT (lease_until > #{now})
            """)
    int takeoverExpiredSlot(@Param("slotKey") String slotKey,
                            @Param("leaseOwner") String leaseOwner,
                            @Param("leaseUntil") LocalDateTime leaseUntil,
                            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_nova_social_runtime_slot
               SET completed_at = #{now}, lease_until = #{now}, updated_at = #{now}
             WHERE slot_key = #{slotKey}
               AND lease_owner = #{leaseOwner}
               AND completed_at IS NULL
            """)
    int completeSlot(@Param("slotKey") String slotKey,
                     @Param("leaseOwner") String leaseOwner,
                     @Param("now") LocalDateTime now);

    @Select("""
            SELECT MAX(n.created_at)
              FROM nx_notification n
             WHERE n.is_deleted = 0
               AND n.type = 'NOVA_SOCIAL'
            """)
    LocalDateTime latestNotificationAt();

    @Select("""
            SELECT MAX(n.created_at)
              FROM nx_notification n
             WHERE n.is_deleted = 0
               AND n.type = #{notificationType}
            """)
    LocalDateTime latestNotificationAtByType(@Param("notificationType") String notificationType);

    @Select("""
            <script>
            SELECT e.event_id sourceEventId,
                   e.event_name eventName,
                   CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(e.payload, '$.user_id')), 'null') AS UNSIGNED) userId,
                   UPPER(COALESCE(e.phase, '')) phase,
                   e.account_age_months accountAgeMonths,
                   COALESCE(e.cohort, '') cohort,
                   COALESCE(e.event_ts, e.created_at) occurredAt
              FROM nx_event_outbox e
             WHERE e.is_deleted = 0
               AND e.is_server_authoritative = 1
               AND (e.analytics_event = 0 OR e.schema_registered = 1)
               AND e.status != 'DEAD'
               AND e.event_name IN
               <foreach item="eventName" collection="eventNames" open="(" separator="," close=")">
                   #{eventName}
               </foreach>
               AND NOT EXISTS (
                   SELECT 1
                     FROM nx_nova_business_event_receipt r
                    WHERE r.channel_key = #{channel}
                      AND r.source_event_id = e.event_id
                       AND r.status != 'PROCESSING'
               )
             ORDER BY COALESCE(e.event_ts, e.created_at), e.id
             LIMIT #{limit}
            </script>
            """)
    List<NovaBusinessEventFact> pendingBusinessFacts(
            @Param("channel") String channel,
            @Param("eventNames") List<String> eventNames,
            @Param("limit") int limit);

    @Insert("""
            INSERT IGNORE INTO nx_nova_business_event_receipt (
                channel_key, source_event_id, event_name, status, reason,
                notification_count, created_at, updated_at)
            VALUES (
                #{channel}, #{sourceEventId}, #{eventName}, 'PROCESSING', '',
                0, #{now}, #{now})
            """)
    int claimBusinessFact(
            @Param("channel") String channel,
            @Param("sourceEventId") String sourceEventId,
            @Param("eventName") String eventName,
            @Param("now") LocalDateTime now);

    @Select("""
            SELECT fanout_cursor_user_id cursorUserId, notification_count notificationCount
              FROM nx_nova_business_event_receipt
             WHERE channel_key = #{channel}
               AND source_event_id = #{sourceEventId}
               AND status = 'PROCESSING'
            """)
    NovaBusinessFanoutProgress businessFanoutProgress(
            @Param("channel") String channel, @Param("sourceEventId") String sourceEventId);

    @Select("""
            SELECT MAX(batch.id)
              FROM (
                    SELECT u.id FROM nx_user u
                     WHERE u.is_deleted = 0 AND UPPER(u.status) = 'ACTIVE'
                       AND u.id > #{afterUserId}
                     ORDER BY u.id LIMIT #{limit}
                   ) batch
            """)
    Long fanoutBatchUpperUserId(@Param("afterUserId") long afterUserId, @Param("limit") int limit);

    @Update("""
            UPDATE nx_nova_business_event_receipt
               SET fanout_cursor_user_id = #{nextCursorUserId},
                   notification_count = notification_count + #{delivered},
                   reason = 'FANOUT_BATCH_COMMITTED', updated_at = #{now}
             WHERE channel_key = #{channel} AND source_event_id = #{sourceEventId}
               AND status = 'PROCESSING' AND fanout_cursor_user_id = #{expectedCursorUserId}
            """)
    int advanceBusinessFanout(
            @Param("channel") String channel, @Param("sourceEventId") String sourceEventId,
            @Param("expectedCursorUserId") long expectedCursorUserId,
            @Param("nextCursorUserId") long nextCursorUserId,
            @Param("delivered") int delivered, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_nova_business_event_receipt
               SET status = #{status},
                   reason = #{reason},
                   notification_count = #{notificationCount},
                   updated_at = #{now}
             WHERE channel_key = #{channel}
               AND source_event_id = #{sourceEventId}
               AND status = 'PROCESSING'
            """)
    int completeBusinessFact(
            @Param("channel") String channel,
            @Param("sourceEventId") String sourceEventId,
            @Param("status") String status,
            @Param("reason") String reason,
            @Param("notificationCount") int notificationCount,
            @Param("now") LocalDateTime now);

    @Insert("""
            INSERT IGNORE INTO nx_notification (
                biz_no, user_id, type, priority, title, body, cta_label, cta_href,
                read_flag, push_status, push_attempts, next_push_at,
                created_at, updated_at, is_deleted)
            SELECT #{bizNo}, u.id, 'NOVA_SOCIAL', 'normal',
                   LEFT(CASE
                       WHEN LOWER(COALESCE(u.language, '')) LIKE 'vi%' THEN #{titleVi}
                       WHEN LOWER(COALESCE(u.language, '')) LIKE 'zh%' THEN #{titleZh}
                       ELSE #{titleEn}
                   END, 128),
                   LEFT(CASE
                       WHEN LOWER(COALESCE(u.language, '')) LIKE 'vi%' THEN #{bodyVi}
                       WHEN LOWER(COALESCE(u.language, '')) LIKE 'zh%' THEN #{bodyZh}
                       ELSE #{bodyEn}
                   END, 512),
                   NULL, NULLIF(#{ctaHref}, ''),
                   0, 'QUEUED', 0, #{now}, #{now}, #{now}, 0
              FROM nx_user u
              JOIN nx_nova_social_event e
                ON e.id = #{eventId}
               AND e.is_deleted = 0
               AND e.status = 'ACTIVE'
               AND e.expires_at > #{now}
              JOIN nx_nova_channel c
                ON c.channel_key = 'social'
               AND c.is_deleted = 0
               AND c.enabled = 1
              JOIN nx_nova_template t
                ON t.channel_key = c.channel_key
               AND t.is_deleted = 0
               AND t.status = 'PUBLISHED'
             WHERE u.is_deleted = 0
               AND UPPER(u.status) = 'ACTIVE'
               AND NOT EXISTS (
                   SELECT 1
                     FROM nx_notification previous
                    WHERE previous.user_id = u.id
                      AND previous.is_deleted = 0
                      AND previous.type = 'NOVA_SOCIAL'
                      AND previous.created_at > #{cooldownSince}
               )
            """)
    int enqueueNotifications(
            @Param("eventId") long eventId,
            @Param("bizNo") String bizNo,
            @Param("titleZh") String titleZh,
            @Param("bodyZh") String bodyZh,
            @Param("titleVi") String titleVi,
            @Param("bodyVi") String bodyVi,
            @Param("titleEn") String titleEn,
            @Param("bodyEn") String bodyEn,
            @Param("ctaHref") String ctaHref,
            @Param("cooldownSince") LocalDateTime cooldownSince,
            @Param("now") LocalDateTime now);

    @Insert("""
            INSERT IGNORE INTO nx_notification (
                biz_no, user_id, type, priority, title, body, cta_label, cta_href,
                read_flag, push_status, push_attempts, next_push_at,
                created_at, updated_at, is_deleted)
            SELECT #{bizNo}, u.id, #{notificationType}, 'normal',
                   LEFT(CASE
                       WHEN LOWER(COALESCE(u.language, '')) LIKE 'vi%' THEN #{titleVi}
                       WHEN LOWER(COALESCE(u.language, '')) LIKE 'zh%' THEN #{titleZh}
                       ELSE #{titleEn}
                   END, 128),
                   LEFT(CASE
                       WHEN LOWER(COALESCE(u.language, '')) LIKE 'vi%' THEN #{bodyVi}
                       WHEN LOWER(COALESCE(u.language, '')) LIKE 'zh%' THEN #{bodyZh}
                       ELSE #{bodyEn}
                   END, 512),
                   NULL, NULLIF(#{ctaHref}, ''),
                   0, 'QUEUED', 0, #{now}, #{now}, #{now}, 0
              FROM nx_user u
              JOIN nx_nova_channel c
                ON c.channel_key = #{channel}
               AND c.is_deleted = 0
               AND c.enabled = 1
              JOIN nx_nova_template t
                ON t.channel_key = c.channel_key
               AND t.is_deleted = 0
               AND t.status = 'PUBLISHED'
             WHERE u.is_deleted = 0
               AND UPPER(u.status) = 'ACTIVE'
               AND (#{userId} IS NULL OR u.id = #{userId})
               AND NOT EXISTS (
                   SELECT 1
                     FROM nx_notification previous
                    WHERE previous.user_id = u.id
                      AND previous.is_deleted = 0
                      AND previous.type = #{notificationType}
                      AND previous.created_at > #{cooldownSince}
               )
            """)
    int enqueueBusinessNotifications(
            @Param("channel") String channel,
            @Param("notificationType") String notificationType,
            @Param("sourceEventId") String sourceEventId,
            @Param("userId") Long userId,
            @Param("bizNo") String bizNo,
            @Param("titleZh") String titleZh,
            @Param("bodyZh") String bodyZh,
            @Param("titleVi") String titleVi,
            @Param("bodyVi") String bodyVi,
            @Param("titleEn") String titleEn,
            @Param("bodyEn") String bodyEn,
            @Param("ctaHref") String ctaHref,
            @Param("cooldownSince") LocalDateTime cooldownSince,
            @Param("now") LocalDateTime now);

    @Insert("""
            INSERT IGNORE INTO nx_notification (
                biz_no, user_id, type, priority, title, body, cta_label, cta_href,
                read_flag, push_status, push_attempts, next_push_at,
                created_at, updated_at, is_deleted)
            SELECT #{bizNo}, u.id, #{notificationType}, 'normal',
                   LEFT(CASE WHEN LOWER(COALESCE(u.language, '')) LIKE 'vi%' THEN #{titleVi}
                             WHEN LOWER(COALESCE(u.language, '')) LIKE 'zh%' THEN #{titleZh}
                             ELSE #{titleEn} END, 128),
                   LEFT(CASE WHEN LOWER(COALESCE(u.language, '')) LIKE 'vi%' THEN #{bodyVi}
                             WHEN LOWER(COALESCE(u.language, '')) LIKE 'zh%' THEN #{bodyZh}
                             ELSE #{bodyEn} END, 512),
                   NULL, NULLIF(#{ctaHref}, ''), 0, 'QUEUED', 0, #{now}, #{now}, #{now}, 0
              FROM nx_user u
              JOIN nx_nova_channel c ON c.channel_key = #{channel} AND c.is_deleted = 0 AND c.enabled = 1
              JOIN nx_nova_template t ON t.channel_key = c.channel_key AND t.is_deleted = 0 AND t.status = 'PUBLISHED'
             WHERE u.is_deleted = 0 AND UPPER(u.status) = 'ACTIVE'
               AND u.id > #{afterUserId} AND NOT (u.id > #{upperUserId})
               AND NOT EXISTS (
                   SELECT 1 FROM nx_notification previous
                    WHERE previous.user_id = u.id AND previous.is_deleted = 0
                      AND previous.type = #{notificationType}
                      AND previous.created_at > #{cooldownSince})
            """)
    int enqueueBusinessNotificationBatch(
            @Param("channel") String channel, @Param("notificationType") String notificationType,
            @Param("bizNo") String bizNo, @Param("afterUserId") long afterUserId,
            @Param("upperUserId") long upperUserId,
            @Param("titleZh") String titleZh, @Param("bodyZh") String bodyZh,
            @Param("titleVi") String titleVi, @Param("bodyVi") String bodyVi,
            @Param("titleEn") String titleEn, @Param("bodyEn") String bodyEn,
            @Param("ctaHref") String ctaHref,
            @Param("cooldownSince") LocalDateTime cooldownSince, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_notification
               SET push_status = 'DELIVERED',
                   pushed_at = #{now},
                   next_push_at = NULL,
                   updated_at = #{now}
             WHERE biz_no = #{bizNo}
               AND push_status = 'QUEUED'
               AND is_deleted = 0
            """)
    int markNotificationsDelivered(@Param("bizNo") String bizNo, @Param("now") LocalDateTime now);

    @Select("""
            SELECT n.id notificationId, n.user_id userId, LOWER(n.type) kind,
                   LOWER(n.priority) priority, COALESCE(n.cta_href, '') ctaHref,
                   (n.read_flag = 1) alreadyRead, #{currentPhase} phase,
                   GREATEST(TIMESTAMPDIFF(MONTH, u.created_at, #{now}), 0) accountAgeMonths,
                   DATE_FORMAT(u.created_at, '%x-W%v') cohort
             FROM nx_notification n
              JOIN nx_user u ON u.id = n.user_id AND u.is_deleted = 0
             WHERE n.biz_no = #{bizNo}
               AND n.push_status = 'DELIVERED'
               AND n.is_deleted = 0
             ORDER BY n.id
            """)
    List<NotificationEventFact> notificationFacts(
            @Param("bizNo") String bizNo,
            @Param("currentPhase") String currentPhase,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_nova_social_event
               SET dispatch_count = dispatch_count + 1,
                   last_dispatched_at = #{now},
                   updated_at = #{now}
             WHERE id = #{eventId}
               AND is_deleted = 0
               AND status = 'ACTIVE'
               AND expires_at > #{now}
            """)
    int markDispatchedIfStillActive(@Param("eventId") long eventId, @Param("now") LocalDateTime now);
}
