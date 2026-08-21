package ffdd.opsconsole.developer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Durable, scoped webhook outbox. All state transitions are conditional so a worker cannot double-send a row. */
@Mapper
public interface AppDeveloperWebhookDeliveryMapper extends BaseMapper<Object> {
    @Insert("INSERT IGNORE INTO nx_developer_webhook_delivery(webhook_id,user_id,source_environment,run_id,event_id,event_type,payload_json,status,attempt_count,max_attempts,next_retry_at,created_at,updated_at) VALUES(#{write.webhookId},#{write.userId},#{write.sourceEnvironment},#{write.runId},#{write.eventId},#{write.eventType},#{write.payloadJson},'PENDING',0,#{write.maxAttempts},#{write.nextRetryAt},NOW(),NOW())")
    int insertIgnore(@Param("write") DeliveryWrite write);

    @Select("SELECT id,webhook_id webhookId,user_id userId,source_environment sourceEnvironment,run_id runId,event_id eventId,event_type eventType,payload_json payloadJson,status,attempt_count attemptCount,max_attempts maxAttempts,last_status_code lastStatusCode,last_error lastError,next_retry_at nextRetryAt,created_at createdAt,updated_at updatedAt FROM nx_developer_webhook_delivery WHERE status IN ('PENDING','RETRYING') AND next_retry_at<=#{now} ORDER BY id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<DeliveryRow> due(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** Claims rows in the caller transaction before network I/O. */
    @Update("UPDATE nx_developer_webhook_delivery SET status='DELIVERING',attempt_count=attempt_count+1,updated_at=NOW() WHERE id=#{id} AND status IN ('PENDING','RETRYING')")
    int claim(@Param("id") Long id);

    /** Returns rows stranded by a crashed worker to the durable retry queue after their lease expires. */
    @Update("UPDATE nx_developer_webhook_delivery SET status='RETRYING',last_error='DELIVERY_LEASE_EXPIRED',next_retry_at=#{now},updated_at=#{now} WHERE status='DELIVERING' AND updated_at<=#{cutoff}")
    int reclaimStaleDelivering(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);

    default List<DeliveryRow> claimDue(int limit, LocalDateTime now, Object ignored) {
        List<DeliveryRow> rows = due(now, limit);
        return rows.stream().filter(row -> claim(row.id()) == 1).toList();
    }

    @Update("UPDATE nx_developer_webhook_delivery d JOIN nx_developer_webhook w ON w.id=d.webhook_id SET d.status='SUCCEEDED',d.last_status_code=#{code},d.last_error=NULL,d.delivered_at=#{at},d.updated_at=#{at},w.delivery_status='SUCCEEDED',w.updated_at=#{at} WHERE d.id=#{id} AND d.status='DELIVERING' AND d.attempt_count=#{attempts}")
    int markSucceeded(@Param("id") Long id, @Param("attempts") int attempts, @Param("code") int code, @Param("at") LocalDateTime at);

    @Update("UPDATE nx_developer_webhook_delivery d JOIN nx_developer_webhook w ON w.id=d.webhook_id SET d.status='RETRYING',d.last_status_code=#{code},d.last_error=#{error},d.next_retry_at=#{nextAt},d.updated_at=#{nextAt},w.delivery_status='RETRYING',w.updated_at=#{nextAt} WHERE d.id=#{id} AND d.status='DELIVERING' AND d.attempt_count=#{attempts}")
    int markRetry(@Param("id") Long id, @Param("attempts") int attempts, @Param("code") Integer code, @Param("error") String error, @Param("nextAt") LocalDateTime nextAt);

    /**
     * Terminal rows retain the terminal transition time in next_retry_at because the deployed schema keeps this
     * column NOT NULL. Terminal statuses are excluded by due(), so this timestamp is observability-only and can
     * never make a DEAD row eligible for another attempt.
     */
    @Update("UPDATE nx_developer_webhook_delivery d JOIN nx_developer_webhook w ON w.id=d.webhook_id SET d.status='DEAD',d.last_status_code=#{code},d.last_error=#{error},d.next_retry_at=#{at},d.updated_at=#{at},w.delivery_status='DEAD',w.updated_at=#{at} WHERE d.id=#{id} AND d.status='DELIVERING' AND d.attempt_count=#{attempts}")
    int markDead(@Param("id") Long id, @Param("attempts") int attempts, @Param("code") Integer code, @Param("error") String error, @Param("at") LocalDateTime at);

    @Update("UPDATE nx_developer_webhook_delivery d JOIN nx_developer_webhook w ON w.id=d.webhook_id SET d.status='NOT_DELIVERED',d.last_error=#{error},d.next_retry_at=#{at},d.updated_at=#{at},w.delivery_status='NOT_DELIVERED',w.updated_at=#{at} WHERE d.id=#{id} AND d.status='DELIVERING'")
    int markNotDelivered(@Param("id") Long id, @Param("error") String error, @Param("at") LocalDateTime at);

    @Select("SELECT d.id,d.webhook_id webhookId,d.user_id userId,d.source_environment sourceEnvironment,d.run_id runId,d.event_id eventId,d.event_type eventType,d.payload_json payloadJson,d.status,d.attempt_count attemptCount,d.max_attempts maxAttempts,d.last_status_code lastStatusCode,d.last_error lastError,d.next_retry_at nextRetryAt,d.created_at createdAt,d.updated_at updatedAt FROM nx_developer_webhook_delivery d JOIN nx_developer_webhook w ON w.id=d.webhook_id WHERE d.webhook_id=#{webhookId} AND w.user_id=#{userId} AND w.source_environment=#{sourceEnvironment} AND w.run_id=#{runId} ORDER BY d.id DESC LIMIT #{limit}")
    List<DeliveryRow> listForWebhook(@Param("webhookId") Long webhookId, @Param("userId") Long userId,
                                      @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId,
                                      @Param("limit") int limit);

    record DeliveryWrite(Long webhookId, Long userId, String sourceEnvironment, String runId, String eventId,
                         String eventType, String payloadJson, int maxAttempts, LocalDateTime nextRetryAt) { }
    record DeliveryRow(Long id, Long webhookId, Long userId, String sourceEnvironment, String runId, String eventId,
                       String eventType, String payloadJson, String status, int attemptCount, int maxAttempts,
                       Integer lastStatusCode, String lastError, LocalDateTime nextRetryAt,
                       LocalDateTime createdAt, LocalDateTime updatedAt) {
        public DeliveryRow(Long id, Long webhookId, Long userId, String sourceEnvironment, String runId, String eventId,
                           String eventType, String payloadJson, String status, int attemptCount, int maxAttempts,
                           Integer lastStatusCode, String lastError, LocalDateTime createdAt, LocalDateTime updatedAt) {
            this(id, webhookId, userId, sourceEnvironment, runId, eventId, eventType, payloadJson, status, attemptCount,
                    maxAttempts, lastStatusCode, lastError, createdAt, createdAt, updatedAt);
        }
    }
}
