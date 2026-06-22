package ffdd.opsconsole.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.user.domain.UserAccountListEntryView;
import ffdd.opsconsole.user.domain.UserAssetAdjustmentView;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserImpersonationSessionView;
import ffdd.opsconsole.user.domain.UserNotificationView;
import ffdd.opsconsole.user.domain.UserSecurityStatusView;
import ffdd.opsconsole.user.domain.UserSessionView;
import ffdd.opsconsole.user.domain.UserTeamMemberView;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Lang;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.scripting.defaults.RawLanguageDriver;

public interface UserOpsMapper extends BaseMapper<UserEntity> {
    @Select("SELECT COUNT(*) FROM nx_user WHERE is_deleted = 0")
    long countUsers();

    @Select("SELECT COUNT(*) FROM nx_user WHERE is_deleted = 0 AND COALESCE(status, 'ACTIVE') = 'ACTIVE'")
    long countActiveUsers();

    @Select("SELECT COUNT(*) FROM nx_user WHERE is_deleted = 0 AND COALESCE(kyc_status, 'PENDING') = 'PENDING'")
    long countKycPending();

    @Select("SELECT COUNT(*) FROM nx_user WHERE is_deleted = 0 AND COALESCE(status, 'ACTIVE') IN ('FROZEN', 'BANNED', 'RESTRICTED')")
    long countFrozenUsers();

    @Select("""
            SELECT COUNT(*) FROM nx_user_session
             WHERE is_deleted = 0
               AND revoked_at IS NULL
               AND expires_at > NOW()
            """)
    long countActiveSessions();

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_user u
              LEFT JOIN nx_user_security s ON s.user_id = u.id AND s.is_deleted = 0
              LEFT JOIN nx_user_wallet w ON w.user_id = u.id AND w.is_deleted = 0
              LEFT JOIN nx_admin_risk_score_user rs ON rs.user_no = CONCAT('U', LPAD(u.id, 8, '0')) AND rs.is_deleted = 0
             WHERE u.is_deleted = 0
             <if test='keyword != null and keyword != ""'>
               AND (u.nickname LIKE CONCAT('%', #{keyword}, '%')
                    OR u.phone LIKE CONCAT('%', #{keyword}, '%')
                    OR u.referral_code LIKE CONCAT('%', #{keyword}, '%')
                    OR CAST(u.id AS CHAR) = #{keyword})
             </if>
             <if test='statuses != null and statuses.size() > 0'>
               AND UPPER(COALESCE(u.status, 'ACTIVE')) IN
               <foreach collection='statuses' item='status' open='(' separator=',' close=')'>#{status}</foreach>
             </if>
             <if test='kycStatus != null and kycStatus != ""'>AND UPPER(COALESCE(u.kyc_status, 'PENDING')) = UPPER(#{kycStatus})</if>
             <if test='riskMin != null'>AND COALESCE(rs.model_score, 0) &gt;= #{riskMin}</if>
            </script>
            """)
    long countUsersByQuery(@Param("keyword") String keyword, @Param("statuses") List<String> statuses,
                           @Param("kycStatus") String kycStatus, @Param("riskMin") Integer riskMin);

    @Select("""
            <script>
            SELECT u.id,
                   CONCAT('U', LPAD(u.id, 8, '0')) AS userNo,
                   u.nickname,
                   CASE
                     WHEN u.phone IS NULL OR LENGTH(u.phone) &lt; 7 THEN u.phone
                     ELSE CONCAT(SUBSTRING(u.phone, 1, 3), '****', SUBSTRING(u.phone, LENGTH(u.phone) - 3))
                   END AS phoneMasked,
                   u.country_code AS countryCode,
                   COALESCE(u.status, 'ACTIVE') AS status,
                   COALESCE(u.kyc_status, 'PENDING') AS kycStatus,
                   u.user_level AS userLevel,
                   u.v_rank AS vRank,
                   COALESCE(s.two_factor_enabled, 0) AS twoFactorEnabled,
                   COALESCE(w.usdt_available, 0) AS walletUsdt,
                   COALESCE(w.nex_available, 0) AS walletNex,
                   rs.model_score AS riskScore,
                   CASE
                     WHEN rs.model_score >= 70 THEN '高风险'
                     WHEN rs.model_score >= 40 THEN '中风险'
                     WHEN rs.model_score IS NULL THEN NULL
                     ELSE '低风险'
                   END AS riskBand,
                   (SELECT COUNT(*) FROM nx_user_device d WHERE d.user_id = u.id AND d.is_deleted = 0) AS deviceCount,
                   (SELECT COUNT(*) FROM nx_user_device d WHERE d.user_id = u.id AND d.is_deleted = 0 AND d.status IN ('ONLINE','BUSY','ACTIVE','RUNNING')) AS activeDeviceCount,
                   u.created_at AS registeredAt,
                   s.last_login_at AS lastLoginAt
              FROM nx_user u
              LEFT JOIN nx_user_security s ON s.user_id = u.id AND s.is_deleted = 0
              LEFT JOIN nx_user_wallet w ON w.user_id = u.id AND w.is_deleted = 0
              LEFT JOIN nx_admin_risk_score_user rs ON rs.user_no = CONCAT('U', LPAD(u.id, 8, '0')) AND rs.is_deleted = 0
             WHERE u.is_deleted = 0
             <if test='keyword != null and keyword != ""'>
               AND (u.nickname LIKE CONCAT('%', #{keyword}, '%')
                    OR u.phone LIKE CONCAT('%', #{keyword}, '%')
                    OR u.referral_code LIKE CONCAT('%', #{keyword}, '%')
                    OR CAST(u.id AS CHAR) = #{keyword})
             </if>
             <if test='statuses != null and statuses.size() > 0'>
               AND UPPER(COALESCE(u.status, 'ACTIVE')) IN
               <foreach collection='statuses' item='status' open='(' separator=',' close=')'>#{status}</foreach>
             </if>
             <if test='kycStatus != null and kycStatus != ""'>AND UPPER(COALESCE(u.kyc_status, 'PENDING')) = UPPER(#{kycStatus})</if>
             <if test='riskMin != null'>AND COALESCE(rs.model_score, 0) &gt;= #{riskMin}</if>
             ORDER BY u.id DESC LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<UserAccountView> pageUsers(@Param("keyword") String keyword, @Param("statuses") List<String> statuses,
                                    @Param("kycStatus") String kycStatus, @Param("riskMin") Integer riskMin,
                                    @Param("offset") int offset, @Param("pageSize") int pageSize);

    @Select("""
            SELECT COUNT(*)
              FROM nx_user
             WHERE is_deleted = 0
               AND UPPER(COALESCE(kyc_status, 'PENDING')) = UPPER(#{kycStatus})
            """)
    long countByKycStatus(@Param("kycStatus") String kycStatus);

    @Select("""
            SELECT u.id,
                   CONCAT('U', LPAD(u.id, 8, '0')) AS userNo,
                   u.nickname,
                   CASE
                    WHEN u.phone IS NULL OR LENGTH(u.phone) &lt; 7 THEN u.phone
                     ELSE CONCAT(SUBSTRING(u.phone, 1, 3), '****', SUBSTRING(u.phone, LENGTH(u.phone) - 3))
                   END AS phoneMasked,
                   u.country_code AS countryCode,
                   COALESCE(u.status, 'ACTIVE') AS status,
                   COALESCE(u.kyc_status, 'PENDING') AS kycStatus,
                   u.user_level AS userLevel,
                   u.v_rank AS vRank,
                   COALESCE(s.two_factor_enabled, 0) AS twoFactorEnabled,
                   COALESCE(w.usdt_available, 0) AS walletUsdt,
                   COALESCE(w.nex_available, 0) AS walletNex,
                   rs.model_score AS riskScore,
                   CASE
                     WHEN rs.model_score >= 70 THEN '高风险'
                     WHEN rs.model_score >= 40 THEN '中风险'
                     WHEN rs.model_score IS NULL THEN NULL
                     ELSE '低风险'
                   END AS riskBand,
                   (SELECT COUNT(*) FROM nx_user_device d WHERE d.user_id = u.id AND d.is_deleted = 0) AS deviceCount,
                   (SELECT COUNT(*) FROM nx_user_device d WHERE d.user_id = u.id AND d.is_deleted = 0 AND d.status IN ('ONLINE','BUSY','ACTIVE','RUNNING')) AS activeDeviceCount,
                   u.created_at AS registeredAt,
                   s.last_login_at AS lastLoginAt
              FROM nx_user u
              LEFT JOIN nx_user_security s ON s.user_id = u.id AND s.is_deleted = 0
              LEFT JOIN nx_user_wallet w ON w.user_id = u.id AND w.is_deleted = 0
              LEFT JOIN nx_admin_risk_score_user rs ON rs.user_no = CONCAT('U', LPAD(u.id, 8, '0')) AND rs.is_deleted = 0
             WHERE u.id = #{userId} AND u.is_deleted = 0
            LIMIT 1
            """)
    @Lang(RawLanguageDriver.class)
    UserAccountView findById(@Param("userId") Long userId);

    @Select("""
            SELECT u.id AS userId,
                   COALESCE(s.two_factor_enabled, 0) AS twoFactorEnabled,
                   COALESCE(s.login_fail_count, 0) AS loginFailCount,
                   0 AS locked,
                   CASE WHEN u.password_hash LIKE 'RESET_REQUIRED$%' THEN 1 ELSE 0 END AS passwordResetRequired,
                   0 AS lockThreshold,
                   0 AS lockDurationMinutes
              FROM nx_user u
              LEFT JOIN nx_user_security s ON s.user_id = u.id AND s.is_deleted = 0
             WHERE u.id = #{userId} AND u.is_deleted = 0
             LIMIT 1
            """)
    UserSecurityStatusView securityStatus(@Param("userId") Long userId);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_user_session
             WHERE is_deleted = 0
             <if test='userId != null'>AND user_id = #{userId}</if>
            </script>
            """)
    long countSessions(@Param("userId") Long userId);

    @Select("""
            <script>
            SELECT user_id AS userId,
                   refresh_token_id AS refreshTokenId,
                   device_name AS deviceName,
                   CASE
                     WHEN client_ip IS NULL OR client_ip = '' THEN client_ip
                     WHEN LOCATE('.', REVERSE(client_ip)) > 0 THEN CONCAT(SUBSTRING(client_ip, 1, LENGTH(client_ip) - LOCATE('.', REVERSE(client_ip))), '.*')
                     ELSE client_ip
                   END AS clientIpMasked,
                   CASE
                     WHEN revoked_at IS NOT NULL THEN 'REVOKED'
                     WHEN expires_at &lt;= NOW() THEN 'EXPIRED'
                     ELSE 'ACTIVE'
                   END AS status,
                   created_at AS issuedAt,
                   expires_at AS expiresAt,
                   revoked_at AS revokedAt
              FROM nx_user_session
             WHERE is_deleted = 0
             <if test='userId != null'>AND user_id = #{userId}</if>
             ORDER BY created_at DESC, id DESC LIMIT #{limit}
            </script>
            """)
    List<UserSessionView> sessions(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT user_id AS userId,
                   refresh_token_id AS refreshTokenId,
                   device_name AS deviceName,
                   CASE
                     WHEN client_ip IS NULL OR client_ip = '' THEN client_ip
                     WHEN LOCATE('.', REVERSE(client_ip)) > 0 THEN CONCAT(SUBSTRING(client_ip, 1, LENGTH(client_ip) - LOCATE('.', REVERSE(client_ip))), '.*')
                     ELSE client_ip
                   END AS clientIpMasked,
                   CASE
                     WHEN revoked_at IS NOT NULL THEN 'REVOKED'
                     WHEN expires_at &lt;= NOW() THEN 'EXPIRED'
                     ELSE 'ACTIVE'
                   END AS status,
                   created_at AS issuedAt,
                   expires_at AS expiresAt,
                   revoked_at AS revokedAt
              FROM nx_user_session
             WHERE is_deleted = 0
             <if test='userId != null'>AND user_id = #{userId}</if>
             ORDER BY created_at DESC, id DESC LIMIT #{offset}, #{pageSize}
            </script>
            """)
    List<UserSessionView> pageSessions(
            @Param("userId") Long userId,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    @Select("""
            SELECT member_user_id AS memberUserId,
                   member_no AS memberNo,
                   nickname,
                   v_rank AS vRank,
                   level,
                   volume,
                   created_at AS createdAt
              FROM nx_team_member
             WHERE user_id = #{userId}
               AND is_deleted = 0
             ORDER BY level ASC, volume DESC, id DESC
             LIMIT #{limit}
            """)
    List<UserTeamMemberView> teamMembers(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
              FROM nx_team_member
             WHERE user_id = #{userId}
               AND is_deleted = 0
            """)
    long countTeamMembers(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
              FROM nx_team_member
             WHERE user_id = #{userId}
               AND level = 1
               AND is_deleted = 0
            """)
    long countDirectTeamMembers(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE(SUM(volume), 0)
              FROM nx_team_member
             WHERE user_id = #{userId}
               AND is_deleted = 0
            """)
    BigDecimal sumTeamVolume(@Param("userId") Long userId);

    @Select("""
            SELECT biz_no AS bizNo,
                   type,
                   title,
                   body,
                   read_flag AS readFlag,
                   push_status AS pushStatus,
                   push_attempts AS pushAttempts,
                   next_push_at AS nextPushAt,
                   last_push_error AS lastPushError,
                   pushed_at AS pushedAt,
                   created_at AS createdAt
              FROM nx_notification
             WHERE user_id = #{userId}
               AND is_deleted = 0
             ORDER BY created_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<UserNotificationView> notifications(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
              FROM nx_notification
             WHERE user_id = #{userId}
               AND read_flag = 0
               AND is_deleted = 0
            """)
    long countUnreadNotifications(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
              FROM nx_notification
             WHERE user_id = #{userId}
               AND UPPER(push_status) = 'PENDING'
               AND is_deleted = 0
            """)
    long countPendingNotifications(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
              FROM nx_notification
             WHERE user_id = #{userId}
               AND UPPER(push_status) IN ('FAILED', 'ERROR')
               AND is_deleted = 0
            """)
    long countFailedNotifications(@Param("userId") Long userId);

    @Select("""
            <script>
            SELECT l.user_id AS userId,
                   CONCAT('U', LPAD(l.user_id, 8, '0')) AS userNo,
                   COALESCE(u.nickname, '未知用户') AS nickname,
                   UPPER(l.kind) AS kind,
                   l.reason,
                   UPPER(l.status) AS status,
                   l.expires_at AS expiresAt,
                   l.created_by AS createdBy,
                   l.created_at AS createdAt,
                   l.released_by AS releasedBy,
                   l.release_reason AS releaseReason,
                   l.released_at AS releasedAt
              FROM nx_account_list l
              LEFT JOIN nx_user u ON u.id = l.user_id AND u.is_deleted = 0
             WHERE l.is_deleted = 0
             <if test='status != null and status != ""'>AND UPPER(l.status) = UPPER(#{status})</if>
             ORDER BY l.updated_at DESC, l.id DESC
             LIMIT #{limit}
            </script>
            """)
    List<UserAccountListEntryView> accountLists(@Param("status") String status, @Param("limit") int limit);

    @Select("""
            SELECT l.user_id AS userId,
                   CONCAT('U', LPAD(l.user_id, 8, '0')) AS userNo,
                   COALESCE(u.nickname, '未知用户') AS nickname,
                   UPPER(l.kind) AS kind,
                   l.reason,
                   UPPER(l.status) AS status,
                   l.expires_at AS expiresAt,
                   l.created_by AS createdBy,
                   l.created_at AS createdAt,
                   l.released_by AS releasedBy,
                   l.release_reason AS releaseReason,
                   l.released_at AS releasedAt
              FROM nx_account_list l
              LEFT JOIN nx_user u ON u.id = l.user_id AND u.is_deleted = 0
             WHERE l.user_id = #{userId} AND l.is_deleted = 0
             LIMIT 1
            """)
    UserAccountListEntryView findAccountList(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_account_list (
                user_id, kind, reason, status, expires_at, created_by, created_at, updated_at, is_deleted
            ) VALUES (
                #{userId}, #{kind}, #{reason}, 'ACTIVE', #{expiresAt}, #{operator}, NOW(), NOW(), 0
            )
            ON DUPLICATE KEY UPDATE
                kind = VALUES(kind),
                reason = VALUES(reason),
                status = 'ACTIVE',
                expires_at = VALUES(expires_at),
                created_by = VALUES(created_by),
                released_by = NULL,
                release_reason = NULL,
                released_at = NULL,
                updated_at = NOW(),
                is_deleted = 0
            """)
    int upsertAccountList(@Param("userId") Long userId, @Param("kind") String kind,
                          @Param("reason") String reason, @Param("operator") String operator,
                          @Param("expiresAt") LocalDateTime expiresAt);

    @Update("""
            UPDATE nx_account_list
               SET status = 'REMOVED',
                   released_by = #{operator},
                   release_reason = #{reason},
                   released_at = NOW(),
                   updated_at = NOW()
             WHERE user_id = #{userId}
               AND is_deleted = 0
            """)
    int removeAccountList(@Param("userId") Long userId, @Param("reason") String reason, @Param("operator") String operator);

    @Select("""
            SELECT s.session_no AS sessionNo,
                   s.user_id AS userId,
                   CONCAT('U', LPAD(s.user_id, 8, '0')) AS userNo,
                   COALESCE(u.nickname, '未知用户') AS nickname,
                   CASE
                    WHEN s.status = 'ACTIVE' AND s.expires_at &lt;= NOW() THEN 'EXPIRED'
                     ELSE UPPER(s.status)
                   END AS status,
                   s.ttl_minutes AS ttlMinutes,
                   s.operator,
                   s.reason,
                   s.expires_at AS expiresAt,
                   s.created_at AS createdAt,
                   s.terminated_at AS endedAt,
                   s.terminated_by AS endedBy,
                   s.terminate_reason AS endReason,
                   GREATEST(TIMESTAMPDIFF(MINUTE, NOW(), s.expires_at), 0) AS leftMinutes
              FROM nx_user_impersonation_session s
              LEFT JOIN nx_user u ON u.id = s.user_id AND u.is_deleted = 0
             WHERE s.is_deleted = 0
             ORDER BY s.created_at DESC, s.id DESC
            LIMIT #{limit}
            """)
    @Lang(RawLanguageDriver.class)
    List<UserImpersonationSessionView> impersonations(@Param("limit") int limit);

    @Select("""
            SELECT s.session_no AS sessionNo,
                   s.user_id AS userId,
                   CONCAT('U', LPAD(s.user_id, 8, '0')) AS userNo,
                   COALESCE(u.nickname, '未知用户') AS nickname,
                   CASE
                    WHEN s.status = 'ACTIVE' AND s.expires_at &lt;= NOW() THEN 'EXPIRED'
                     ELSE UPPER(s.status)
                   END AS status,
                   s.ttl_minutes AS ttlMinutes,
                   s.operator,
                   s.reason,
                   s.expires_at AS expiresAt,
                   s.created_at AS createdAt,
                   s.terminated_at AS endedAt,
                   s.terminated_by AS endedBy,
                   s.terminate_reason AS endReason,
                   GREATEST(TIMESTAMPDIFF(MINUTE, NOW(), s.expires_at), 0) AS leftMinutes
              FROM nx_user_impersonation_session s
              LEFT JOIN nx_user u ON u.id = s.user_id AND u.is_deleted = 0
             WHERE s.session_no = #{sessionNo} AND s.is_deleted = 0
            LIMIT 1
            """)
    @Lang(RawLanguageDriver.class)
    UserImpersonationSessionView findImpersonation(@Param("sessionNo") String sessionNo);

    @Update("""
            UPDATE nx_user_impersonation_session
               SET status = 'TERMINATED',
                   terminated_by = #{operator},
                   terminate_reason = #{reason},
                   terminated_at = NOW(),
                   updated_at = NOW()
             WHERE session_no = #{sessionNo}
               AND is_deleted = 0
            """)
    int terminateImpersonation(@Param("sessionNo") String sessionNo, @Param("reason") String reason, @Param("operator") String operator);

    @Update("""
            UPDATE nx_user
               SET status = #{status}, updated_at = NOW()
             WHERE id = #{userId} AND is_deleted = 0
            """)
    int updateUserStatus(@Param("userId") Long userId, @Param("status") String status);

    @Update("""
            UPDATE nx_user
               SET kyc_status = #{kycStatus}, updated_at = NOW()
             WHERE id = #{userId} AND is_deleted = 0
            """)
    int updateKycStatus(@Param("userId") Long userId, @Param("kycStatus") String kycStatus);

    @Select("""
            SELECT user_id AS userId,
                   refresh_token_id AS refreshTokenId,
                   device_name AS deviceName,
                   CASE
                     WHEN client_ip IS NULL OR client_ip = '' THEN client_ip
                     WHEN LOCATE('.', REVERSE(client_ip)) > 0 THEN CONCAT(SUBSTRING(client_ip, 1, LENGTH(client_ip) - LOCATE('.', REVERSE(client_ip))), '.*')
                     ELSE client_ip
                   END AS clientIpMasked,
                   CASE
                     WHEN revoked_at IS NOT NULL THEN 'REVOKED'
                    WHEN expires_at &lt;= NOW() THEN 'EXPIRED'
                     ELSE 'ACTIVE'
                   END AS status,
                   created_at AS issuedAt,
                   expires_at AS expiresAt,
                   revoked_at AS revokedAt
              FROM nx_user_session
             WHERE refresh_token_id = #{refreshTokenId} AND is_deleted = 0
            LIMIT 1
            """)
    @Lang(RawLanguageDriver.class)
    UserSessionView findSession(@Param("refreshTokenId") String refreshTokenId);

    @Update("""
            UPDATE nx_user_session
               SET revoked_at = NOW(), updated_at = NOW()
             WHERE refresh_token_id = #{refreshTokenId} AND is_deleted = 0
            """)
    int revokeSession(@Param("refreshTokenId") String refreshTokenId);

    @Update("""
            UPDATE nx_user_session
               SET revoked_at = NOW(), updated_at = NOW()
             WHERE user_id = #{userId}
               AND revoked_at IS NULL
               AND is_deleted = 0
            """)
    int revokeUserSessions(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_user_security (
                user_id, two_factor_enabled, login_fail_count, created_at, updated_at, is_deleted
            ) VALUES (#{userId}, 0, 0, NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE
                two_factor_enabled = 0,
                updated_at = NOW(),
                is_deleted = 0
            """)
    int disableTwoFactor(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user
               SET password_hash = #{resetMarker}, updated_at = NOW()
             WHERE id = #{userId} AND is_deleted = 0
            """)
    int markPasswordResetRequired(@Param("userId") Long userId, @Param("resetMarker") String resetMarker);

    @Insert("""
            INSERT INTO nx_user_security (
                user_id, two_factor_enabled, login_fail_count, created_at, updated_at, is_deleted
            ) VALUES (#{userId}, 0, 0, NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE
                login_fail_count = 0,
                updated_at = NOW(),
                is_deleted = 0
            """)
    int resetLoginFailures(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_user_impersonation_session (
                session_no, user_id, status, ttl_minutes, operator, reason, expires_at, created_at, updated_at, is_deleted
            ) VALUES (#{sessionNo}, #{userId}, 'ACTIVE', #{ttlMinutes}, #{operator}, #{reason}, #{expiresAt}, NOW(), NOW(), 0)
            """)
    int insertImpersonationSession(@Param("sessionNo") String sessionNo, @Param("userId") Long userId,
                                   @Param("ttlMinutes") int ttlMinutes, @Param("operator") String operator,
                                   @Param("reason") String reason, @Param("expiresAt") LocalDateTime expiresAt);

    @Insert("""
            INSERT INTO nx_wallet_asset_adjustment (
                adjustment_no, user_id, asset, direction, amount, reason_code, reason, maker, status, created_at, updated_at, is_deleted
            ) VALUES (#{adjustmentNo}, #{userId}, #{asset}, #{direction}, #{amount}, 'OPS_USER_ADJUSTMENT', #{reason}, #{operator}, 'PENDING_REVIEW', NOW(), NOW(), 0)
            """)
    int insertAssetAdjustment(@Param("adjustmentNo") String adjustmentNo, @Param("userId") Long userId,
                              @Param("asset") String asset, @Param("direction") String direction,
                              @Param("amount") BigDecimal amount, @Param("reason") String reason,
                              @Param("operator") String operator);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_wallet_asset_adjustment a
              LEFT JOIN nx_user u ON u.id = a.user_id AND u.is_deleted = 0
             WHERE a.is_deleted = 0
             <if test='status != null and status != ""'>AND UPPER(a.status) = UPPER(#{status})</if>
             <if test='historyOnly != null and historyOnly'>AND UPPER(a.status) NOT IN ('PENDING', 'PENDING_REVIEW', 'SUSPENDED')</if>
             <if test='asset != null and asset != ""'>AND UPPER(a.asset) = UPPER(#{asset})</if>
             <if test='userId != null'>AND a.user_id = #{userId}</if>
             <if test='keyword != null and keyword != ""'>
               AND (a.adjustment_no LIKE CONCAT('%', #{keyword}, '%')
                    OR a.reason LIKE CONCAT('%', #{keyword}, '%')
                    OR u.nickname LIKE CONCAT('%', #{keyword}, '%')
                    OR CAST(a.user_id AS CHAR) = #{keyword})
             </if>
            </script>
            """)
    long countAssetAdjustments(
            @Param("status") String status,
            @Param("asset") String asset,
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            @Param("historyOnly") Boolean historyOnly);

    @Select("""
            <script>
            SELECT a.adjustment_no AS adjustmentNo,
                   a.user_id AS userId,
                   CONCAT('U', LPAD(a.user_id, 8, '0')) AS userNo,
                   COALESCE(u.nickname, '未知用户') AS nickname,
                   a.asset,
                   a.direction,
                   a.amount,
                   CONCAT(CASE WHEN a.direction = 'CREDIT' THEN '+' ELSE '-' END,
                          CASE WHEN a.asset = 'USDT' THEN CONCAT('$', CAST(a.amount AS CHAR))
                               ELSE CONCAT(CAST(a.amount AS CHAR), ' NEX') END) AS amountLabel,
                   a.reason_code AS reasonCode,
                   a.reason,
                   a.maker,
                   a.checker,
                   a.status,
                   CASE UPPER(a.status)
                     WHEN 'PENDING' THEN '待复核'
                     WHEN 'PENDING_REVIEW' THEN '待复核'
                     WHEN 'APPROVED' THEN '已通过'
                     WHEN 'REJECTED' THEN '已驳回'
                     WHEN 'SUSPENDED' THEN '红线挂起'
                     ELSE a.status
                   END AS statusLabel,
                   CASE UPPER(a.status)
                     WHEN 'APPROVED' THEN 'ok'
                     WHEN 'REJECTED' THEN 'bad'
                     WHEN 'SUSPENDED' THEN 'bad'
                     ELSE 'warn'
                   END AS statusTone,
                   CASE WHEN a.direction = 'CREDIT' THEN 1 ELSE 0 END AS credit,
                   CASE WHEN a.direction = 'CREDIT' AND a.amount > 500 THEN 1 ELSE 0 END AS escalated,
                   a.ledger_id AS ledgerId,
                   CASE WHEN a.ledger_id IS NULL THEN 'D4 待过账' ELSE CONCAT('账本 #', a.ledger_id) END AS sink,
                   a.review_reason AS reviewReason,
                   a.reviewed_at AS reviewedAt,
                   a.created_at AS createdAt,
                   a.updated_at AS updatedAt
              FROM nx_wallet_asset_adjustment a
              LEFT JOIN nx_user u ON u.id = a.user_id AND u.is_deleted = 0
             WHERE a.is_deleted = 0
             <if test='status != null and status != ""'>AND UPPER(a.status) = UPPER(#{status})</if>
             <if test='historyOnly != null and historyOnly'>AND UPPER(a.status) NOT IN ('PENDING', 'PENDING_REVIEW', 'SUSPENDED')</if>
             <if test='asset != null and asset != ""'>AND UPPER(a.asset) = UPPER(#{asset})</if>
             <if test='userId != null'>AND a.user_id = #{userId}</if>
             <if test='keyword != null and keyword != ""'>
               AND (a.adjustment_no LIKE CONCAT('%', #{keyword}, '%')
                    OR a.reason LIKE CONCAT('%', #{keyword}, '%')
                    OR u.nickname LIKE CONCAT('%', #{keyword}, '%')
                    OR CAST(a.user_id AS CHAR) = #{keyword})
             </if>
             ORDER BY a.created_at DESC, a.id DESC
             LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<UserAssetAdjustmentView> pageAssetAdjustments(
            @Param("status") String status,
            @Param("asset") String asset,
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            @Param("historyOnly") Boolean historyOnly,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    @Select("""
            SELECT a.adjustment_no AS adjustmentNo,
                   a.user_id AS userId,
                   CONCAT('U', LPAD(a.user_id, 8, '0')) AS userNo,
                   COALESCE(u.nickname, '未知用户') AS nickname,
                   a.asset,
                   a.direction,
                   a.amount,
                   CONCAT(CASE WHEN a.direction = 'CREDIT' THEN '+' ELSE '-' END,
                          CASE WHEN a.asset = 'USDT' THEN CONCAT('$', CAST(a.amount AS CHAR))
                               ELSE CONCAT(CAST(a.amount AS CHAR), ' NEX') END) AS amountLabel,
                   a.reason_code AS reasonCode,
                   a.reason,
                   a.maker,
                   a.checker,
                   a.status,
                   CASE UPPER(a.status)
                     WHEN 'PENDING' THEN '待复核'
                     WHEN 'PENDING_REVIEW' THEN '待复核'
                     WHEN 'APPROVED' THEN '已通过'
                     WHEN 'REJECTED' THEN '已驳回'
                     WHEN 'SUSPENDED' THEN '红线挂起'
                     ELSE a.status
                   END AS statusLabel,
                   CASE UPPER(a.status)
                     WHEN 'APPROVED' THEN 'ok'
                     WHEN 'REJECTED' THEN 'bad'
                     WHEN 'SUSPENDED' THEN 'bad'
                     ELSE 'warn'
                   END AS statusTone,
                   CASE WHEN a.direction = 'CREDIT' THEN 1 ELSE 0 END AS credit,
                   CASE WHEN a.direction = 'CREDIT' AND a.amount > 500 THEN 1 ELSE 0 END AS escalated,
                   a.ledger_id AS ledgerId,
                   CASE WHEN a.ledger_id IS NULL THEN 'D4 待过账' ELSE CONCAT('账本 #', a.ledger_id) END AS sink,
                   a.review_reason AS reviewReason,
                   a.reviewed_at AS reviewedAt,
                   a.created_at AS createdAt,
                   a.updated_at AS updatedAt
              FROM nx_wallet_asset_adjustment a
              LEFT JOIN nx_user u ON u.id = a.user_id AND u.is_deleted = 0
             WHERE a.adjustment_no = #{adjustmentNo}
               AND a.is_deleted = 0
             LIMIT 1
            """)
    UserAssetAdjustmentView findAssetAdjustment(@Param("adjustmentNo") String adjustmentNo);

    @Update("""
            UPDATE nx_wallet_asset_adjustment
               SET status = #{status},
                   checker = #{checker},
                   review_reason = #{reason},
                   reviewed_at = NOW(),
                   updated_at = NOW()
             WHERE adjustment_no = #{adjustmentNo}
               AND is_deleted = 0
            """)
    int reviewAssetAdjustment(
            @Param("adjustmentNo") String adjustmentNo,
            @Param("status") String status,
            @Param("checker") String checker,
            @Param("reason") String reason);
}
