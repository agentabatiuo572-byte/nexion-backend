package ffdd.opsconsole.shared.idempotency.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyRecordEntity;
import java.util.List;
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

    @Select("""
            <script>
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
             WHERE idempotency_key = #{idempotencyKey}
               AND is_deleted = 0
               AND scope IN
             <foreach collection='scopes' item='scope' open='(' separator=',' close=')'>
               #{scope}
             </foreach>
             ORDER BY id ASC
            </script>
            """)
    List<AdminIdempotencyRecordEntity> selectSupportCommand(
            @Param("scopes") List<String> scopes,
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

    /**
     * A PROCESSING row past its lease may be the result of a process crash after
     * claim but before the business transaction could write a terminal result.
     * Do not recycle it: callers cannot know whether an external side effect
     * happened.  UNKNOWN is deliberately terminal and therefore fail-closed.
     */
    @Update("""
            UPDATE nx_admin_idempotency_record
               SET status = 'UNKNOWN',
                   response_json = NULL,
                   error_message = 'IDEMPOTENCY_RESULT_UNKNOWN_AFTER_EXPIRY',
                   updated_at = NOW()
             WHERE id = #{id}
               AND scope = #{scope}
               AND idempotency_key = #{idempotencyKey}
               AND status = 'PROCESSING'
               AND expires_at <= NOW()
               AND is_deleted = 0
            """)
    int markCurrentExpiredProcessingUnknown(
            @Param("id") Long id,
            @Param("scope") String scope,
            @Param("idempotencyKey") String idempotencyKey);

    /**
     * Claim only a bounded, deterministic set of expired crash remnants.  The
     * non-blocking lock is essential: request completion owns the same row and
     * must never wait behind a best-effort scheduler sweep.
     */
    @Select("""
            SELECT id
              FROM nx_admin_idempotency_record FORCE INDEX (idx_admin_idem_expiry_claim)
             WHERE status = 'PROCESSING'
               AND is_deleted = 0
               AND expires_at <= NOW()
             ORDER BY expires_at ASC, id ASC
             LIMIT #{limit}
               FOR UPDATE SKIP LOCKED
            """)
    List<Long> lockExpiredProcessingBatch(@Param("limit") int limit);

    @Update("""
            <script>
            UPDATE nx_admin_idempotency_record
               SET status = 'UNKNOWN',
                   response_json = NULL,
                   error_message = 'IDEMPOTENCY_RESULT_UNKNOWN_AFTER_EXPIRY',
                   updated_at = NOW()
             WHERE id IN
             <foreach collection='ids' item='id' open='(' separator=',' close=')'>
               #{id}
             </foreach>
               AND status = 'PROCESSING'
               AND expires_at &lt;= NOW()
               AND is_deleted = 0
            </script>
            """)
    int markLockedExpiredProcessingUnknown(@Param("ids") List<Long> ids);
}
