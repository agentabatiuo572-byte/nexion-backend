package ffdd.opsconsole.shared.idempotency.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyRecordEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AdminIdempotencyRecordMapper extends BaseMapper<AdminIdempotencyRecordEntity> {
    @Select("""
            SELECT id,
                   scope,
                   idempotency_key AS idempotencyKey,
                   request_hash AS requestHash,
                   status,
                   response_json AS responseJson,
                   error_message AS errorMessage,
                   expires_at AS expiresAt,
                   created_at AS createdAt,
                   updated_at AS updatedAt,
                   is_deleted AS isDeleted
              FROM nx_admin_idempotency_record
             WHERE scope = #{scope}
               AND idempotency_key = #{idempotencyKey}
               AND is_deleted = 0
               AND expires_at > NOW()
             LIMIT 1
            """)
    AdminIdempotencyRecordEntity selectActive(
            @Param("scope") String scope,
            @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT id,
                   scope,
                   idempotency_key AS idempotencyKey,
                   request_hash AS requestHash,
                   status,
                   response_json AS responseJson,
                   error_message AS errorMessage,
                   expires_at AS expiresAt,
                   created_at AS createdAt,
                   updated_at AS updatedAt,
                   is_deleted AS isDeleted
              FROM nx_admin_idempotency_record
             WHERE scope = #{scope}
               AND idempotency_key = #{idempotencyKey}
               AND is_deleted = 0
               AND expires_at > NOW()
             LIMIT 1
               FOR UPDATE
            """)
    AdminIdempotencyRecordEntity selectActiveForUpdate(
            @Param("scope") String scope,
            @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT id,
                   scope,
                   idempotency_key AS idempotencyKey,
                   request_hash AS requestHash,
                   status,
                   response_json AS responseJson,
                   error_message AS errorMessage,
                   expires_at AS expiresAt,
                   created_at AS createdAt,
                   updated_at AS updatedAt,
                   is_deleted AS isDeleted
              FROM nx_admin_idempotency_record
             WHERE scope = #{scope}
               AND idempotency_key = #{idempotencyKey}
             LIMIT 1
            """)
    AdminIdempotencyRecordEntity selectCurrent(
            @Param("scope") String scope,
            @Param("idempotencyKey") String idempotencyKey);

    @Update("""
            UPDATE nx_admin_idempotency_record
               SET request_hash = #{requestHash},
                   status = 'PROCESSING',
                   response_json = NULL,
                   error_message = NULL,
                   expires_at = #{expiresAt},
                   is_deleted = 0,
                   updated_at = NOW()
             WHERE id = #{id}
               AND (expires_at <= NOW() OR is_deleted = 1)
            """)
    int resetExpiredById(@Param("id") Long id,
                         @Param("requestHash") String requestHash,
                         @Param("expiresAt") java.time.LocalDateTime expiresAt);

    @Update("""
            UPDATE nx_admin_idempotency_record
               SET request_hash = #{requestHash},
                   status = 'PROCESSING',
                   response_json = NULL,
                   error_message = NULL,
                   expires_at = #{expiresAt},
                   updated_at = NOW()
             WHERE id = #{id}
               AND status = 'FAILED'
               AND is_deleted = 0
            """)
    int resetFailedById(@Param("id") Long id,
                        @Param("requestHash") String requestHash,
                        @Param("expiresAt") java.time.LocalDateTime expiresAt);

    @Update("""
            UPDATE nx_admin_idempotency_record
               SET status = 'SUCCEEDED',
                   response_json = #{responseJson},
                   error_message = NULL,
                   updated_at = NOW()
             WHERE id = #{id}
               AND status = 'PROCESSING'
               AND is_deleted = 0
            """)
    int markSucceeded(@Param("id") Long id, @Param("responseJson") String responseJson);

    @Update("""
            UPDATE nx_admin_idempotency_record
               SET status = 'FAILED',
                   error_message = #{errorMessage},
                   updated_at = NOW()
             WHERE id = #{id}
               AND status = 'PROCESSING'
               AND is_deleted = 0
            """)
    int markFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage);
}
