package ffdd.opsconsole.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.auth.infrastructure.AppUserSecurityEntity;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AppUserSecurityMapper extends BaseMapper<AppUserSecurityEntity> {
    @Select("SELECT password_hash FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    String passwordHashForUpdate(@Param("userId") Long userId);

    @Select("SELECT COALESCE((SELECT two_factor_enabled FROM nx_user_security WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1),0)=1")
    boolean twoFactorEnabled(@Param("userId") Long userId);

    @Select("SELECT password_changed_at FROM nx_user_security WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1")
    LocalDateTime passwordChangedAt(@Param("userId") Long userId);

    @Update("UPDATE nx_user SET password_hash=#{passwordHash},updated_at=NOW() WHERE id=#{userId} AND status='ACTIVE' AND is_deleted=0")
    int updatePasswordHash(@Param("userId") Long userId, @Param("passwordHash") String passwordHash);

    @Insert("""
            INSERT INTO nx_user_security (
                user_id,two_factor_enabled,login_fail_count,password_reset_required,password_changed_at,
                created_at,updated_at,is_deleted
            ) VALUES (#{userId},0,0,0,NOW(),NOW(),NOW(),0)
            ON DUPLICATE KEY UPDATE password_changed_at=NOW(),password_reset_required=0,updated_at=NOW(),is_deleted=0
            """)
    int markPasswordChanged(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_user_security (
                user_id,two_factor_enabled,login_fail_count,password_reset_required,
                created_at,updated_at,is_deleted
            ) VALUES (#{userId},#{enabled},0,0,NOW(),NOW(),0)
            ON DUPLICATE KEY UPDATE two_factor_enabled=#{enabled},updated_at=NOW(),is_deleted=0
            """)
    int upsertTwoFactor(@Param("userId") Long userId, @Param("enabled") boolean enabled);

    @Insert("""
            INSERT IGNORE INTO nx_user_account_deletion_request(
                request_no,user_id,idempotency_key,status,requested_at,created_at,updated_at,is_deleted)
            VALUES(#{requestNo},#{userId},#{idempotencyKey},'REQUESTED',NOW(),NOW(),NOW(),0)
            """)
    int insertAccountDeletionRequest(
            @Param("requestNo") String requestNo,
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT request_no AS requestNo,user_id AS userId,status,version,
                   requested_at AS requestedAt,reviewed_at AS reviewedAt,
                   completed_at AS completedAt,reason,reviewed_by AS reviewedBy,
                   cancelled_at AS cancelledAt,block_reason AS blockReason
              FROM nx_user_account_deletion_request
             WHERE request_no=#{requestNo} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    Map<String, Object> accountDeletionByRequestNoForUpdate(@Param("requestNo") String requestNo);

    @Select("""
            SELECT request_no AS requestNo,user_id AS userId,status,version,
                   requested_at AS requestedAt,reviewed_at AS reviewedAt,
                   completed_at AS completedAt,reason,block_reason AS blockReason,
                   cancelled_at AS cancelledAt
              FROM nx_user_account_deletion_request
             WHERE is_deleted=0
               AND (#{status} IS NULL OR #{status}='' OR status=#{status})
             ORDER BY requested_at DESC,id DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<Map<String, Object>> listAccountDeletions(
            @Param("status") String status, @Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            SELECT request_no AS requestNo,status,version,requested_at AS requestedAt,
                   reviewed_at AS reviewedAt,completed_at AS completedAt,reason,
                   block_reason AS blockReason,cancelled_at AS cancelledAt
              FROM nx_user_account_deletion_request
             WHERE user_id=#{userId} AND idempotency_key=#{idempotencyKey} AND is_deleted=0
             LIMIT 1 FOR UPDATE
            """)
    Map<String, Object> accountDeletionRequestForUpdate(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT request_no AS requestNo,status,version,requested_at AS requestedAt,
                   reviewed_at AS reviewedAt,completed_at AS completedAt,reason,
                   block_reason AS blockReason,cancelled_at AS cancelledAt
              FROM nx_user_account_deletion_request
             WHERE user_id=#{userId} AND is_deleted=0
             ORDER BY id DESC LIMIT 1
            """)
    Map<String, Object> latestAccountDeletionRequest(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user_account_deletion_request
               SET status=#{toStatus},version=version+1,reason=#{reason},
                   reviewed_by=#{reviewedBy},reviewed_at=NOW(),
                   block_reason=CASE WHEN #{toStatus}='BLOCKED' THEN #{reason} ELSE block_reason END,
                   cancelled_at=CASE WHEN #{toStatus}='CANCELLED' THEN NOW() ELSE cancelled_at END,
                   completed_at=CASE WHEN #{toStatus}='COMPLETED' THEN NOW() ELSE completed_at END,
                   updated_at=NOW()
             WHERE request_no=#{requestNo} AND status=#{fromStatus} AND version=#{expectedVersion} AND is_deleted=0
            """)
    int transitionAccountDeletion(
            @Param("requestNo") String requestNo,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("reason") String reason,
            @Param("reviewedBy") Long reviewedBy);

    @Select("""
            SELECT CASE WHEN EXISTS(
                SELECT 1 FROM nx_user_wallet w
                 WHERE w.user_id=#{userId} AND w.is_deleted=0
                   AND (w.usdt_available > 0 OR w.nex_available > 0 OR w.pending_withdraw > 0)
            ) OR EXISTS(
                SELECT 1 FROM nx_withdrawal_order w
                 WHERE w.user_id=#{userId} AND w.is_deleted=0
                   AND w.status NOT IN ('COMPLETED','SUCCESS','FAILED','REJECTED','CANCELLED')
            ) OR EXISTS(
                SELECT 1 FROM nx_deposit_order d
                 WHERE d.user_id=#{userId} AND d.is_deleted=0
                   AND d.status NOT IN ('COMPLETED','SUCCESS','FAILED','REJECTED','CANCELLED')
            ) OR EXISTS(
                SELECT 1 FROM nx_exchange_order e
                 WHERE e.user_id=#{userId} AND e.is_deleted=0
                   AND e.status NOT IN ('COMPLETED','SUCCESS','FAILED','REJECTED','CANCELLED')
            ) OR EXISTS(
                SELECT 1 FROM nx_staking_position s
                 WHERE s.user_id=#{userId} AND s.is_deleted=0
                   AND s.status NOT IN ('COMPLETED','CLOSED','CANCELLED','WITHDRAWN')
            ) OR EXISTS(
                SELECT 1 FROM nx_order o
                 WHERE o.user_id=#{userId} AND o.is_deleted=0
                   AND (o.order_status NOT IN ('COMPLETED','CANCELLED','CLOSED','FAILED','REFUNDED')
                        OR o.payment_status NOT IN ('PAID','FAILED','CANCELLED','REFUNDED','EXPIRED'))
            ) THEN 1 ELSE 0 END
            """)
    int hasUnsettledFundsOrOrders(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user
               SET status='DISABLED',nickname=CONCAT('Deleted user ',id),
                   country_code='XX',phone=CONCAT('deleted-',id),client_ip='0.0.0.0',
                   avatar_url=NULL,bio=NULL,region=NULL,timezone=NULL,updated_at=NOW()
             WHERE id=#{userId} AND status IN ('ACTIVE','FROZEN') AND is_deleted=0
            """)
    int disableAndAnonymizeUser(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user_profile
               SET display_name=NULL,email=NULL,bio=NULL,updated_at=NOW()
             WHERE user_id=#{userId} AND is_deleted=0
            """)
    int anonymizeUserProfile(@Param("userId") Long userId);
}
