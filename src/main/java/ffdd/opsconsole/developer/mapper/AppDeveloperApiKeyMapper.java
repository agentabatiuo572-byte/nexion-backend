package ffdd.opsconsole.developer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppDeveloperApiKeyMapper extends BaseMapper<Object> {
    @Select("SELECT id,key_id,user_id,request_hash,name,key_prefix prefix,key_last4 last4,status,created_at createdAt,revoked_at revokedAt,source_environment sourceEnvironment,run_id runId FROM nx_developer_api_key WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND idempotency_key=#{idempotencyKey} AND is_deleted=0 LIMIT 1")
    KeyRow byIdempotency(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment,
                         @Param("runId") String runId, @Param("idempotencyKey") String idempotencyKey);
    @Select("SELECT id,key_id,user_id,request_hash,name,key_prefix prefix,key_last4 last4,status,created_at createdAt,revoked_at revokedAt,source_environment sourceEnvironment,run_id runId FROM nx_developer_api_key WHERE user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND is_deleted=0 ORDER BY created_at DESC,id DESC")
    java.util.List<KeyRow> list(@Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId);
    @Select("SELECT id,key_id,user_id,request_hash,name,key_prefix prefix,key_last4 last4,status,created_at createdAt,revoked_at revokedAt,source_environment sourceEnvironment,run_id runId FROM nx_developer_api_key WHERE id=#{id} AND user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND is_deleted=0 LIMIT 1")
    KeyRow byId(@Param("id") Long id, @Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId);
    @Select("SELECT id,key_id,user_id,request_hash,name,key_prefix prefix,key_last4 last4,status,created_at createdAt,revoked_at revokedAt,source_environment sourceEnvironment,run_id runId FROM nx_developer_api_key WHERE key_hash=#{hash} AND status='ACTIVE' AND is_deleted=0 LIMIT 1")
    KeyRow activeByHash(@Param("hash") String hash);
    @Insert("INSERT IGNORE INTO nx_developer_api_key(key_id,user_id,idempotency_key,request_hash,name,key_hash,key_prefix,key_last4,status,source_environment,run_id,created_at,updated_at,is_deleted) VALUES(#{keyId},#{userId},#{idempotencyKey},#{requestHash},#{name},#{secretHash},#{prefix},#{last4},'ACTIVE',#{sourceEnvironment},#{runId},NOW(),NOW(),0)")
    int insertKey(KeyWrite write);
    @Update("UPDATE nx_developer_api_key SET status='REVOKED',revoked_at=NOW(),updated_at=NOW() WHERE id=#{id} AND user_id=#{userId} AND source_environment=#{sourceEnvironment} AND run_id=#{runId} AND status='ACTIVE' AND is_deleted=0")
    int revoke(@Param("id") Long id, @Param("userId") Long userId, @Param("sourceEnvironment") String sourceEnvironment, @Param("runId") String runId);
    @Update("UPDATE nx_developer_api_key SET last_used_at=NOW(),updated_at=NOW() WHERE id=#{id} AND status='ACTIVE' AND is_deleted=0")
    int touchLastUsed(@Param("id") Long id);

    record KeyWrite(String keyId, Long userId, String idempotencyKey, String requestHash, String name,
                    String secretHash, String prefix, String last4, String sourceEnvironment, String runId) { }
    record KeyRow(Long id, String keyId, Long userId, String requestHash, String name, String prefix, String last4,
                  String status, LocalDateTime createdAt, LocalDateTime revokedAt, String sourceEnvironment, String runId) { }
}
