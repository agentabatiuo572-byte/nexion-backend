package ffdd.opsconsole.shared.idempotency.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AdminIdempotencyRecordMapper extends BaseMapper<AdminIdempotencyRecordEntity> {
    /** Current read: an outer REPEATABLE_READ snapshot may predate the independently committed claim. */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("""
            SELECT * FROM nx_admin_idempotency_record
             WHERE id=#{id} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    AdminIdempotencyRecordEntity selectCurrentByIdForUpdate(@Param("id") Long id);

    @Select("""
            SELECT id,
                   scope,
                   idempotency_key AS idempotencyKey,
                   request_hash AS requestHash,
                   origin_admin_id AS originAdminId,
                   origin_method AS originMethod,
                   origin_path AS originPath,
                   origin_target AS originTarget,
                   origin_targets AS originTargets,
                   origin_read_authority AS originReadAuthority,
                   rollback_confirmed AS rollbackConfirmed,
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
                   origin_admin_id AS originAdminId,
                   origin_method AS originMethod,
                   origin_path AS originPath,
                   origin_target AS originTarget,
                   origin_targets AS originTargets,
                   origin_read_authority AS originReadAuthority,
                   rollback_confirmed AS rollbackConfirmed,
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
                   origin_admin_id AS originAdminId,
                   origin_method AS originMethod,
                   origin_path AS originPath,
                   origin_target AS originTarget,
                   origin_targets AS originTargets,
                   origin_read_authority AS originReadAuthority,
                   rollback_confirmed AS rollbackConfirmed,
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
                   origin_admin_id AS originAdminId,
                   origin_method AS originMethod,
                   origin_path AS originPath,
                   origin_target AS originTarget,
                   origin_targets AS originTargets,
                   origin_read_authority AS originReadAuthority,
                   rollback_confirmed AS rollbackConfirmed,
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
                   rollback_confirmed = 0,
                   response_json = NULL,
                   error_message = NULL,
                   expires_at = #{expiresAt},
                   is_deleted = 0,
                   updated_at = NOW()
             WHERE id = #{id}
               AND (expires_at <= NOW() OR is_deleted = 1)
               AND status NOT IN ('PROCESSING','UNKNOWN','CONFIRMED_SUCCEEDED','CONFIRMED_NOT_EXECUTED')
            """)
    int resetExpiredById(@Param("id") Long id,
                         @Param("requestHash") String requestHash,
                         @Param("expiresAt") java.time.LocalDateTime expiresAt);

    @Update("""
            UPDATE nx_admin_idempotency_record
               SET request_hash = #{requestHash},
                   status = 'PROCESSING',
                   rollback_confirmed = 0,
                   response_json = NULL,
                   error_message = NULL,
                   expires_at = IF(REGEXP_LIKE(scope, '^A3_(DEVELOPER_DOCS|HOW_CONTENT)_UPDATE:[1-9][0-9]{0,18}$', 'c'),
                       expires_at, #{expiresAt}),
                   updated_at = NOW()
             WHERE id = #{id}
               AND status = 'FAILED'
               AND is_deleted = 0
               AND (scope <> 'D3_RESERVE_INJECTION' OR origin_admin_id IS NULL OR response_json IS NULL)
               AND (expires_at > NOW()
                    OR NOT REGEXP_LIKE(scope, '^A3_(DEVELOPER_DOCS|HOW_CONTENT)_UPDATE:[1-9][0-9]{0,18}$', 'c'))
            """)
    int resetFailedById(@Param("id") Long id,
                        @Param("requestHash") String requestHash,
                        @Param("expiresAt") java.time.LocalDateTime expiresAt);

    @Update("""
            UPDATE nx_admin_idempotency_record
               SET status = 'PROCESSING', rollback_confirmed = 0, response_json = NULL,
                   error_message = NULL, expires_at = #{expiresAt}, updated_at = NOW()
             WHERE id = #{id} AND status = 'FAILED' AND is_deleted = 0
               AND request_hash = #{requestHash}
               AND response_json <=> CAST(#{previousResponse,jdbcType=VARCHAR} AS JSON)
            """)
    int resetSupportBulkCreateFailure(@Param("id") Long id,
                                      @Param("requestHash") String requestHash,
                                      @Param("expiresAt") java.time.LocalDateTime expiresAt,
                                      @Param("previousResponse") String previousResponse);

    @Insert("""
            INSERT INTO nx_admin_operation_mutex (lock_key) VALUES (#{scopePrefix})
            ON DUPLICATE KEY UPDATE updated_at=updated_at
            """)
    int lockA3ContentClaims(@Param("scopePrefix") String scopePrefix);

    @Select("""
            SELECT COUNT(*) FROM nx_admin_idempotency_record
             WHERE is_deleted=0 AND origin_admin_id IS NOT NULL
               AND (status IN ('UNKNOWN','PROCESSING')
                    OR (status='FAILED' AND (rollback_confirmed<>1 OR response_json IS NOT NULL)))
               AND REGEXP_LIKE(scope, #{scopePattern}, 'c')
               AND NOT (scope=#{scope} AND BINARY idempotency_key=BINARY #{key})
            """)
    int countUnresolvedA3Content(@Param("scopePattern") String scopePattern, @Param("scope") String scope,
                                 @Param("key") String key);

    @Select("""
            SELECT COUNT(*) FROM nx_admin_idempotency_record
             WHERE scope=#{scope} AND origin_method=#{method} AND BINARY origin_path=BINARY #{path}
               AND BINARY origin_target=BINARY #{target}
               AND BINARY idempotency_key != BINARY #{key} AND is_deleted=0
               AND status IN ('UNKNOWN','PROCESSING')
            """)
    int countUnresolvedOrigin(@Param("scope") String scope, @Param("method") String method,
                              @Param("path") String path, @Param("target") String target, @Param("key") String key);

    @Select("""
            <script>
            SELECT COUNT(*) FROM nx_admin_idempotency_record
             WHERE is_deleted=0 AND origin_targets IS NOT NULL
               AND JSON_OVERLAPS(origin_targets, CAST(#{targets} AS JSON))
               AND NOT (scope=#{scope} AND BINARY idempotency_key=BINARY #{key})
               AND (status='UNKNOWN' OR (status='PROCESSING' AND id NOT IN
                 <foreach collection='ancestors' item='id' open='(' separator=',' close=')'>#{id}</foreach>))
            </script>
            """)
    int countUnresolvedTargets(@Param("targets") String targets, @Param("scope") String scope,
            @Param("key") String key, @Param("ancestors") List<Long> ancestors);

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

    /** A trusted server-side business rejection keeps its result on the FAILED transaction row. */
    @Update("""
            UPDATE nx_admin_idempotency_record
               SET status = 'FAILED',
                   response_json = #{responseJson},
                   error_message = #{errorMessage},
                   updated_at = NOW()
             WHERE id = #{id}
               AND status = 'PROCESSING'
               AND is_deleted = 0
            """)
    int markFailedWithResponse(@Param("id") Long id, @Param("errorMessage") String errorMessage,
                               @Param("responseJson") String responseJson);

    @Update("""
            UPDATE nx_admin_idempotency_record SET rollback_confirmed=1, updated_at=NOW()
             WHERE id=#{id} AND status IN ('PROCESSING','FAILED','UNKNOWN') AND is_deleted=0
            """)
    int markRollbackConfirmed(@Param("id") long id);

    /**
     * A PROCESSING row past its lease may be the result of a process crash after
     * claim but before the business transaction could write a terminal result.
     * Do not recycle it: callers cannot know whether an external side effect
     * happened.  UNKNOWN is deliberately terminal and therefore fail-closed.
     */
    // Salary completion commits the wallet and receipt together under this row lock.
    String EXPIRED_PROCESSING_RESULT = """
               SET status = IF(scope LIKE 'APP:VRANK_SALARY:USER:%', 'FAILED', 'UNKNOWN'),
                   response_json = IF(scope LIKE 'APP:VRANK_SALARY:USER:%',
                       JSON_OBJECT('status','REJECTED','month',request_hash,'rejectionCode','SALARY_CLAIM_EXPIRED','httpStatus',503),
                       NULL),
                   error_message = IF(scope LIKE 'APP:VRANK_SALARY:USER:%',
                       'SALARY_CLAIM_EXPIRED','IDEMPOTENCY_RESULT_UNKNOWN_AFTER_EXPIRY'),
                   updated_at = NOW()
            """;

    @Update("""
            UPDATE nx_admin_idempotency_record
            """ + EXPIRED_PROCESSING_RESULT + """
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
            """ + EXPIRED_PROCESSING_RESULT + """
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
