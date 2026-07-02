package ffdd.opsconsole.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.platform.infrastructure.AdminAccountStateEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AdminAccountStateMapper extends BaseMapper<AdminAccountStateEntity> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_admin_account_state (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              admin_id BIGINT NOT NULL,
              tfa_required TINYINT NOT NULL DEFAULT 1,
              last_login_at DATETIME NULL,
              tfa_reset_at DATETIME NULL,
              sessions_revoked_at DATETIME NULL,
              credential_delivery_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              is_deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_admin_account_state_admin (admin_id),
              KEY idx_admin_account_state_delivery (credential_delivery_status),
              CONSTRAINT fk_admin_account_state_admin
                FOREIGN KEY (admin_id) REFERENCES nx_admin(id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createAccountStateTable();

    @Select("""
            SELECT *
              FROM nx_admin_account_state
             WHERE admin_id = #{adminId}
               AND is_deleted = 0
             LIMIT 1
            """)
    AdminAccountStateEntity selectActiveByAdminId(@Param("adminId") Long adminId);

    @Insert("""
            INSERT INTO nx_admin_account_state (
              admin_id, tfa_required, credential_delivery_status, is_deleted
            ) VALUES (
              #{adminId}, 1, #{credentialDeliveryStatus}, 0
            )
            ON DUPLICATE KEY UPDATE
              credential_delivery_status = VALUES(credential_delivery_status),
              tfa_required = 1,
              updated_at = NOW(),
              is_deleted = 0
            """)
    int upsertCreatedState(
            @Param("adminId") Long adminId,
            @Param("credentialDeliveryStatus") String credentialDeliveryStatus);

    @Insert("""
            INSERT INTO nx_admin_account_state (
              admin_id, tfa_required, tfa_reset_at, credential_delivery_status, is_deleted
            ) VALUES (
              #{adminId}, 1, #{resetAt}, 'ACTIVE', 0
            )
            ON DUPLICATE KEY UPDATE
              tfa_reset_at = VALUES(tfa_reset_at),
              tfa_required = 1,
              updated_at = NOW(),
              is_deleted = 0
            """)
    int upsertTfaResetAt(@Param("adminId") Long adminId, @Param("resetAt") LocalDateTime resetAt);

    @Insert("""
            INSERT INTO nx_admin_account_state (
              admin_id, tfa_required, sessions_revoked_at, credential_delivery_status, is_deleted
            ) VALUES (
              #{adminId}, 1, #{revokedAt}, 'ACTIVE', 0
            )
            ON DUPLICATE KEY UPDATE
              sessions_revoked_at = VALUES(sessions_revoked_at),
              updated_at = NOW(),
              is_deleted = 0
            """)
    int upsertSessionsRevokedAt(@Param("adminId") Long adminId, @Param("revokedAt") LocalDateTime revokedAt);
}
