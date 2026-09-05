package ffdd.opsconsole.developer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppDeveloperWebhookMapper extends BaseMapper<Object> {
    @Select("SELECT id,user_id userId,request_hash requestHash,name,url,events_json eventsJson,status,COALESCE((SELECT d.status FROM nx_developer_webhook_delivery d WHERE d.webhook_id=nx_developer_webhook.id ORDER BY d.id DESC LIMIT 1),'NOT_DELIVERED') deliveryStatus,version,secret_rotation_key rotationKey,secret_rotation_hash rotationHash,secret_ciphertext secretCiphertext,created_at createdAt,updated_at updatedAt,source_environment sourceEnvironment,run_id runId FROM nx_developer_webhook WHERE id=#{id} AND is_deleted=0 LIMIT 1")
    WebhookRow byIdForDelivery(@Param("id") Long id);
    @Select("SELECT id FROM nx_developer_webhook WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND status='ACTIVE' AND secret_ciphertext IS NOT NULL AND secret_ciphertext<>'' AND is_deleted=0 AND JSON_CONTAINS(events_json, JSON_QUOTE(#{eventType}))")
    java.util.List<Long> activeMatchingIds(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment,
                                            @Param("runId") String runId, @Param("eventType") String eventType);
    @Select("SELECT id,user_id userId,request_hash requestHash,name,url,events_json eventsJson,status,delivery_status deliveryStatus,version,secret_rotation_key rotationKey,secret_rotation_hash rotationHash,secret_ciphertext secretCiphertext,created_at createdAt,updated_at updatedAt,source_environment sourceEnvironment,run_id runId FROM nx_developer_webhook WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND idempotency_key=#{idempotencyKey} AND is_deleted=0 LIMIT 1")
    WebhookRow byIdempotency(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId, @Param("idempotencyKey") String idempotencyKey);
    @Select("SELECT id,user_id userId,request_hash requestHash,name,url,events_json eventsJson,status,COALESCE((SELECT d.status FROM nx_developer_webhook_delivery d WHERE d.webhook_id=nx_developer_webhook.id ORDER BY d.id DESC LIMIT 1),'NOT_DELIVERED') deliveryStatus,version,secret_rotation_key rotationKey,secret_rotation_hash rotationHash,secret_ciphertext secretCiphertext,created_at createdAt,updated_at updatedAt,source_environment sourceEnvironment,run_id runId FROM nx_developer_webhook WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND is_deleted=0 ORDER BY created_at DESC,id DESC")
    java.util.List<WebhookRow> list(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId);
    @Select("SELECT id,user_id userId,request_hash requestHash,name,url,events_json eventsJson,status,COALESCE((SELECT d.status FROM nx_developer_webhook_delivery d WHERE d.webhook_id=nx_developer_webhook.id ORDER BY d.id DESC LIMIT 1),'NOT_DELIVERED') deliveryStatus,version,secret_rotation_key rotationKey,secret_rotation_hash rotationHash,secret_ciphertext secretCiphertext,created_at createdAt,updated_at updatedAt,source_environment sourceEnvironment,run_id runId FROM nx_developer_webhook WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND is_deleted=0 ORDER BY created_at DESC,id DESC LIMIT #{limit}")
    java.util.List<WebhookRow> listBounded(@Param("userId") Long userId,
                                           @Param("sourceEnvironment") String sourceEnvironment,
                                           @Param("runId") String runId,
                                           @Param("limit") int limit);
    @Select("SELECT COUNT(*) FROM nx_developer_webhook WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND is_deleted=0")
    int countExisting(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment,
                      @Param("runId") String runId);
    @Select("SELECT id,user_id userId,request_hash requestHash,name,url,events_json eventsJson,status,delivery_status deliveryStatus,version,secret_rotation_key rotationKey,secret_rotation_hash rotationHash,secret_ciphertext secretCiphertext,created_at createdAt,updated_at updatedAt,source_environment sourceEnvironment,run_id runId FROM nx_developer_webhook WHERE id=#{id} AND user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND is_deleted=0 LIMIT 1")
    WebhookRow byId(@Param("id") Long id, @Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId);
    @Insert("INSERT IGNORE INTO nx_developer_webhook(user_id,idempotency_key,request_hash,name,url,events_json,secret_hash,secret_ciphertext,status,delivery_status,source_environment,run_id,created_at,updated_at,is_deleted) VALUES(#{userId},#{idempotencyKey},#{requestHash},#{name},#{url},#{eventsJson},#{secretHash},#{secretCiphertext},'ACTIVE','NOT_DELIVERED',#{sourceEnvironment},#{runId},NOW(),NOW(),0)")
    int insertWebhook(WebhookWrite write);
    @Update("UPDATE nx_developer_webhook SET name=#{name},url=#{url},events_json=#{eventsJson},version=version+1,updated_at=NOW() WHERE id=#{id} AND user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND version=#{version} AND is_deleted=0")
    int update(WebhookUpdate update);
    @Update("UPDATE nx_developer_webhook SET secret_hash=#{secretHash},secret_ciphertext=#{secretCiphertext},secret_rotation_key=#{rotationKey},secret_rotation_hash=#{rotationHash},version=version+1,updated_at=NOW() WHERE id=#{id} AND user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND version=#{version} AND is_deleted=0")
    int rotateSecretWithCiphertext(@Param("id") Long id, @Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId, @Param("version") Long version, @Param("secretHash") String secretHash, @Param("secretCiphertext") String secretCiphertext, @Param("rotationKey") String rotationKey, @Param("rotationHash") String rotationHash);
    @Update("UPDATE nx_developer_webhook SET status='DELETED',is_deleted=1,version=version+1,updated_at=NOW() WHERE id=#{id} AND user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND version=#{version} AND is_deleted=0")
    int delete(@Param("id") Long id, @Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId, @Param("version") Long version);
    @Update("UPDATE nx_developer_webhook SET status=#{status},version=version+1,updated_at=NOW() WHERE id=#{id} AND user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND version=#{version} AND is_deleted=0")
    int setStatus(@Param("id") Long id, @Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment,
                  @Param("runId") String runId, @Param("version") Long version, @Param("status") String status);
    @Select("SELECT secret_hash FROM nx_developer_webhook WHERE id=#{id} AND user_id=#{userId} AND is_deleted=0 LIMIT 1")
    String secretHash(@Param("id") Long id);

    record WebhookWrite(Long userId, String idempotencyKey, String requestHash, String name, String url, String eventsJson,
                        String secretHash, String secretCiphertext, String sourceEnvironment, String runId) {
        public WebhookWrite(Long userId, String idempotencyKey, String requestHash, String name, String url, String eventsJson,
                            String secretHash, String sourceEnvironment, String runId) {
            this(userId, idempotencyKey, requestHash, name, url, eventsJson, secretHash, null, sourceEnvironment, runId);
        }
    }
    record WebhookUpdate(Long id, Long userId, String name, String url, String eventsJson, Long version, String sourceEnvironment, String runId) { }
    record WebhookRow(Long id, Long userId, String requestHash, String name, String url, String eventsJson, String status, String deliveryStatus,
                      Long version, String rotationKey, String rotationHash, String secretCiphertext,
                      LocalDateTime createdAt, LocalDateTime updatedAt, String sourceEnvironment, String runId) {
        public WebhookRow(Long id, Long userId, String requestHash, String name, String url, String eventsJson, String status, String deliveryStatus,
                          Long version, String rotationKey, String rotationHash, LocalDateTime createdAt, LocalDateTime updatedAt,
                          String sourceEnvironment, String runId) {
            this(id, userId, requestHash, name, url, eventsJson, status, deliveryStatus, version, rotationKey, rotationHash,
                    null, createdAt, updatedAt, sourceEnvironment, runId);
        }
    }
}
