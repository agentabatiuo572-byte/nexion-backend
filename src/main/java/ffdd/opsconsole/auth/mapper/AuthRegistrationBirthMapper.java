package ffdd.opsconsole.auth.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Only AUTH identity and its shared outbox source; wallet facts belong to finance. */
@SuppressWarnings("MybatisPlusBaseMapper")
public interface AuthRegistrationBirthMapper {
    @Select("SELECT id,created_at createdAt,sandbox,is_deleted isDeleted,status FROM nx_user WHERE id=#{customerId} FOR UPDATE")
    @Options(useCache=false, flushCache=Options.FlushCachePolicy.TRUE)
    Customer customer(@Param("customerId") long customerId);

    @Select("SELECT id,event_id eventId,aggregate_type aggregateType,aggregate_id aggregateId,event_type eventType,"
        + "event_name eventName,event_ts eventTs,is_server_authoritative serverAuthoritative,schema_registered schemaRegistered,"
        + "analytics_event analyticsEvent,schema_revision schemaRevision,payload,is_deleted isDeleted "
        + "FROM nx_event_outbox WHERE event_id=#{eventId} FOR SHARE")
    @Options(useCache=false, flushCache=Options.FlushCachePolicy.TRUE)
    RegistrationEvent event(@Param("eventId") String eventId);

    record Customer(Long id, LocalDateTime createdAt, Integer sandbox, Integer isDeleted, String status) {}
    record RegistrationEvent(Long id, String eventId, String aggregateType, String aggregateId,
        String eventType, String eventName, LocalDateTime eventTs, Boolean serverAuthoritative,
        Boolean schemaRegistered, Boolean analyticsEvent, Integer schemaRevision, String payload, Integer isDeleted) {}
}
