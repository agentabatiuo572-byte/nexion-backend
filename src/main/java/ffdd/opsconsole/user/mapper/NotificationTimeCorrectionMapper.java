package ffdd.opsconsole.user.mapper;

import ffdd.opsconsole.user.mapper.NotificationTimeEvidenceMapper.*;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Narrow locks and CAS for one notification; never dispatches or changes delivery/read state. */
@SuppressWarnings("MybatisPlusBaseMapper") // Cross-table command has no generic CRUD surface.
public interface NotificationTimeCorrectionMapper {
    @Select("""
            SELECT id notificationId,user_id userId,biz_no bizNo,type,push_status pushStatus,created_at createdAt
              FROM nx_notification WHERE id=#{notificationId} AND user_id=#{userId} AND is_deleted=0 FOR UPDATE
            """)
    NotificationRow lockNotification(@Param("userId") long userId, @Param("notificationId") long notificationId);

    @Select("""
            SELECT event_id eventId,aggregate_type aggregateType,aggregate_id aggregateId,
                   event_name eventName,is_server_authoritative authoritative,payload,event_ts eventTs
              FROM nx_event_outbox FORCE INDEX (idx_event_outbox_aggregate)
             WHERE aggregate_type=#{aggregateType} AND aggregate_id=#{notificationId}
               AND event_name=#{eventName} AND is_deleted=0 LIMIT 2 FOR UPDATE
            """)
    List<EventRow> lockDelivery(@Param("aggregateType") String aggregateType,
            @Param("notificationId") String notificationId, @Param("eventName") String eventName);

    @Select("""
            SELECT event_id eventId,aggregate_type aggregateType,aggregate_id aggregateId,
                   event_name eventName,is_server_authoritative authoritative,payload,event_ts eventTs
              FROM nx_event_outbox FORCE INDEX (uk_event_outbox_event_id)
             WHERE event_id=#{eventId} AND is_deleted=0 LIMIT 2 FOR UPDATE
            """)
    List<EventRow> lockRegistration(@Param("eventId") String eventId);

    @Select("""
            SELECT source_event_id sourceEventId,event_name eventName,status,notification_count notificationCount
              FROM nx_nova_business_event_receipt FORCE INDEX (uk_nova_business_event)
             WHERE channel_key='welcome' AND source_event_id=#{eventId}
               AND BINARY channel_key='welcome' LIMIT 2 FOR UPDATE
            """)
    List<ReceiptRow> lockReceipts(@Param("eventId") String eventId);

    @Update("""
            UPDATE nx_notification SET created_at=#{corrected},updated_at=updated_at
             WHERE id=#{row.notificationId} AND user_id=#{row.userId} AND is_deleted=0
               AND BINARY type='NOVA_WELCOME' AND BINARY biz_no=#{row.bizNo}
               AND BINARY push_status=#{row.pushStatus} AND created_at=#{row.createdAt}
            """)
    int correctCreatedAt(@Param("row") NotificationRow row, @Param("corrected") LocalDateTime corrected);
}
