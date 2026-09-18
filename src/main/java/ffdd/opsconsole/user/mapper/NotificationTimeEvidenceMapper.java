package ffdd.opsconsole.user.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Bounded, read-only evidence queries. No notification or outbox state is changed. */
@SuppressWarnings("MybatisPlusBaseMapper") // Cross-table read model: deliberately has no entity CRUD surface.
public interface NotificationTimeEvidenceMapper {
    @Select("""
            SELECT id notificationId, user_id userId, biz_no bizNo, type, push_status pushStatus,
                   created_at createdAt
              FROM nx_notification
             WHERE id=#{notificationId} AND user_id=#{userId} AND is_deleted=0
            """)
    NotificationRow notification(@Param("userId") long userId, @Param("notificationId") long notificationId);

    @Select("""
            SELECT event_id eventId, aggregate_type aggregateType, aggregate_id aggregateId,
                   event_name eventName, is_server_authoritative authoritative, payload,
                   event_ts eventTs
              FROM nx_event_outbox
             WHERE aggregate_id=#{notificationId} AND is_deleted=0
               AND ((aggregate_type='NOVA_NOTIFICATION' AND event_name='nova.push_sent')
                 OR (aggregate_type='NOTIFICATION' AND event_name='notification.delivered'))
             ORDER BY id LIMIT 3
            """)
    List<EventRow> deliveries(@Param("notificationId") String notificationId);

    @Select("""
            SELECT event_id eventId, aggregate_type aggregateType, aggregate_id aggregateId,
                   event_name eventName, is_server_authoritative authoritative, payload,
                   event_ts eventTs
              FROM nx_event_outbox WHERE event_id=#{eventId} AND is_deleted=0 LIMIT 2
            """)
    List<EventRow> registration(@Param("eventId") String eventId);

    @Select("""
            SELECT source_event_id sourceEventId, event_name eventName, status, notification_count notificationCount
              FROM nx_nova_business_event_receipt
             WHERE BINARY channel_key='welcome' AND source_event_id=#{eventId} LIMIT 2
            """)
    List<ReceiptRow> receipts(@Param("eventId") String eventId);

    record NotificationRow(Long notificationId, Long userId, String bizNo, String type,
                           String pushStatus, LocalDateTime createdAt) {}
    record EventRow(String eventId, String aggregateType, String aggregateId, String eventName,
                    Boolean authoritative, String payload, LocalDateTime eventTs) {}
    record ReceiptRow(String sourceEventId, String eventName, String status, Integer notificationCount) {}
}

