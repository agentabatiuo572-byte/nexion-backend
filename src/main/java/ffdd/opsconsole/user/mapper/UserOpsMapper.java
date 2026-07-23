package ffdd.opsconsole.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.user.domain.UserAccountListEntryView;
import ffdd.opsconsole.user.domain.UserAccountControlFactView;
import ffdd.opsconsole.user.domain.UserAssetAdjustmentView;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserImpersonationSessionView;
import ffdd.opsconsole.user.domain.UserKycRecord;
import ffdd.opsconsole.user.domain.UserKycStatusHistoryView;
import ffdd.opsconsole.user.domain.UserKycReverificationView;
import ffdd.opsconsole.user.domain.UserNotificationView;
import ffdd.opsconsole.user.domain.UserReadonlyDeviceView;
import ffdd.opsconsole.user.domain.UserSecurityStatusView;
import ffdd.opsconsole.user.domain.UserSecurityUserRow;
import ffdd.opsconsole.user.domain.UserSessionView;
import ffdd.opsconsole.user.domain.UserTeamMemberView;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface UserOpsMapper extends BaseMapper<UserEntity> {
    @Select("SELECT COALESCE((SELECT two_factor_enabled FROM nx_user_security WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1),0)=1")
    boolean isTwoFactorEnabled(@Param("userId") Long userId);

    @Select("SELECT COALESCE((SELECT password_reset_required FROM nx_user_security WHERE user_id=#{userId} AND is_deleted=0 LIMIT 1),0)=1")
    boolean isPasswordResetRequired(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_user_security(user_id,two_factor_enabled,login_fail_count,password_reset_required,created_at,updated_at,is_deleted)
            VALUES(#{userId},0,#{failedCount},0,NOW(),NOW(),0)
            ON DUPLICATE KEY UPDATE login_fail_count=VALUES(login_fail_count),updated_at=NOW(),is_deleted=0
            """)
    int syncLoginFailure(@Param("userId") Long userId, @Param("failedCount") int failedCount);

    @Update("UPDATE nx_user_security SET login_fail_count=0,updated_at=NOW() WHERE user_id=#{userId} AND is_deleted=0")
    int clearLoginFailure(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user
               SET password_hash=#{passwordHash},updated_at=NOW()
             WHERE id=#{userId} AND is_deleted=0
            """)
    int updatePasswordHash(@Param("userId") Long userId, @Param("passwordHash") String passwordHash);

    @Update("""
            UPDATE nx_user_security
               SET password_reset_required=0,updated_at=NOW()
             WHERE user_id=#{userId} AND password_reset_required=1 AND is_deleted=0
            """)
    int clearPasswordResetRequired(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user_otp_challenge
               SET consumed_at=NOW(),attempts=attempts+1,updated_at=NOW()
             WHERE user_id=#{userId} AND challenge_no=#{challengeNo}
               AND code_hash=SHA2(CONCAT(#{code}, ':', challenge_no),256)
               AND consumed_at IS NULL AND expires_at>=NOW() AND attempts<5 AND is_deleted=0
            """)
    int consumeValidLoginOtp(
            @Param("userId") Long userId,
            @Param("challengeNo") String challengeNo,
            @Param("code") String code);

    @Insert("""
            INSERT INTO nx_user_otp_challenge(
                challenge_no,user_id,code_hash,expires_at,attempts,created_at,updated_at,is_deleted)
            VALUES(#{challengeNo},#{userId},SHA2(CONCAT(#{code}, ':', #{challengeNo}),256),
                   DATE_ADD(NOW(), INTERVAL #{ttlMinutes} MINUTE),0,NOW(),NOW(),0)
            """)
    int createLoginOtpChallenge(
            @Param("userId") Long userId,
            @Param("challengeNo") String challengeNo,
            @Param("code") String code,
            @Param("ttlMinutes") int ttlMinutes);

    @Update("""
            UPDATE nx_user_otp_challenge
               SET attempts=attempts+1,updated_at=NOW()
             WHERE user_id=#{userId} AND challenge_no=#{challengeNo}
               AND consumed_at IS NULL AND expires_at>=NOW() AND attempts<5 AND is_deleted=0
            """)
    int recordInvalidLoginOtpAttempt(
            @Param("userId") Long userId,
            @Param("challengeNo") String challengeNo);
    @Select("SELECT COUNT(*) FROM nx_user WHERE is_deleted = 0")
    long countUsers();

    @Select("SELECT COUNT(*) FROM nx_user WHERE is_deleted = 0 AND COALESCE(status, 'ACTIVE') = 'ACTIVE'")
    long countActiveUsers();

    @Select("SELECT COUNT(*) FROM nx_user WHERE is_deleted = 0 AND COALESCE(kyc_status, 'PENDING') = 'PENDING'")
    long countKycPending();

    @Select("SELECT COUNT(*) FROM nx_user WHERE is_deleted = 0 AND COALESCE(status, 'ACTIVE') = 'FROZEN'")
    long countFrozenUsers();

    @Select("SELECT COUNT(*) FROM nx_account_list WHERE is_deleted=0 AND status='ACTIVE' AND kind=#{kind} AND (expires_at IS NULL OR expires_at > NOW())")
    long countActiveAccountListByKind(@Param("kind") String kind);

    @Select("SELECT COUNT(*) FROM nx_user_impersonation_session WHERE is_deleted=0 AND status='ACTIVE' AND expires_at > NOW()")
    long countActiveImpersonations();

    @Select("SELECT COUNT(*) FROM nx_user_impersonation_session WHERE session_no=#{sessionNo} AND user_id=#{userId} AND is_deleted=0 AND status='ACTIVE' AND expires_at > NOW()")
    int countActiveImpersonation(@Param("sessionNo") String sessionNo, @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM nx_account_list WHERE is_deleted=0")
    long countAccountLists();

    @Select("""
            SELECT COUNT(*)
              FROM nx_account_list
             WHERE user_id=#{userId}
               AND kind='BLOCK'
               AND status='ACTIVE'
               AND (expires_at IS NULL OR expires_at > NOW())
               AND is_deleted=0
            """)
    int countActiveBlocklistByUser(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
              FROM nx_account_list
             WHERE user_id=#{userId}
               AND kind='ALLOW'
               AND status='ACTIVE'
               AND (expires_at IS NULL OR expires_at > NOW())
               AND is_deleted=0
            """)
    int countActiveAllowlistByUser(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM nx_user_impersonation_session WHERE is_deleted=0")
    long countImpersonations();

    @Select("SELECT COUNT(*) FROM nx_user_session WHERE is_deleted=0")
    long countSessions();

    @Select("""
            SELECT COUNT(*) FROM nx_user_session
             WHERE is_deleted = 0
               AND revoked_at IS NULL
               AND expires_at > NOW()
            """)
    long countActiveSessions();

    @Select("""
            SELECT u.id AS userId,
                   u.c2_freeze_source AS freezeSource,
                   u.c2_freeze_source_ref AS freezeSourceRef,
                   u.c2_freeze_reason AS freezeReason,
                   u.c2_freeze_operator AS freezeOperator,
                   u.c2_frozen_at AS frozenAt,
                   (SELECT COUNT(*)
                      FROM nx_withdrawal_order w
                     WHERE w.user_id = u.id
                       AND w.is_deleted = 0
                       AND w.status = 'FROZEN'
                       AND w.c2_frozen_by_user_status = 1) AS d2FrozenWithdrawalCount
              FROM nx_user u
             WHERE u.is_deleted = 0
               AND u.status = 'FROZEN'
             ORDER BY u.c2_frozen_at DESC, u.id DESC
             LIMIT #{limit}
            """)
    List<UserAccountControlFactView> accountControlFacts(@Param("limit") int limit);

    @Select("""
            SELECT u.id AS userId,
                   u.c2_freeze_source AS freezeSource,
                   u.c2_freeze_source_ref AS freezeSourceRef,
                   u.c2_freeze_reason AS freezeReason,
                   u.c2_freeze_operator AS freezeOperator,
                   u.c2_frozen_at AS frozenAt,
                   (SELECT COUNT(*)
                      FROM nx_withdrawal_order w
                     WHERE w.user_id = u.id
                       AND w.is_deleted = 0
                       AND w.status = 'FROZEN'
                       AND w.c2_frozen_by_user_status = 1) AS d2FrozenWithdrawalCount
              FROM nx_user u
             WHERE u.id = #{userId}
               AND u.is_deleted = 0
            """)
    UserAccountControlFactView findAccountControlFact(@Param("userId") Long userId);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_user u
              LEFT JOIN nx_user_security s ON s.user_id = u.id AND s.is_deleted = 0
              LEFT JOIN nx_user_wallet w ON w.user_id = u.id AND w.is_deleted = 0
              LEFT JOIN (
                    SELECT model_version, band_low_max, band_high_min, auto_escalate_score
                      FROM nx_admin_risk_score_model
                     WHERE state = 'active' AND is_deleted = 0
                     ORDER BY model_version DESC
                     LIMIT 1
              ) rsm ON 1 = 1
              LEFT JOIN nx_admin_risk_score_user rs
                ON rs.user_no = CONCAT('U', LPAD(u.id, 8, '0'))
               AND rs.is_deleted = 0
               AND rs.as_of >= DATE_SUB(NOW(), INTERVAL 1 DAY)
               AND rs.model_version = CONCAT('k4-v', rsm.model_version)
              LEFT JOIN nx_admin_risk_score_override rso ON rso.user_no = rs.user_no AND rso.active = 1 AND rso.is_deleted = 0
             WHERE u.is_deleted = 0
             <if test='query.keyword != null and query.keyword != ""'>
               AND (u.nickname LIKE CONCAT('%', #{query.keyword}, '%')
                    OR u.referral_code LIKE CONCAT('%', #{query.keyword}, '%')
                    OR CONCAT('U', LPAD(u.id, 8, '0')) LIKE CONCAT('%', #{query.keyword}, '%')
                    OR CAST(u.id AS CHAR) = #{query.keyword})
             </if>
             <if test='query.userId != null'>AND u.id = #{query.userId}</if>
             <if test='query.phoneHash != null and query.phoneHash != ""'>
               AND SHA2(REGEXP_REPLACE(u.phone, '[^0-9]', ''), 256) = LOWER(#{query.phoneHash})
             </if>
             <if test='query.phoneMasked != null and query.phoneMasked != ""'>
               AND CONCAT(SUBSTRING(u.phone, 1, 3), '****', SUBSTRING(u.phone, LENGTH(u.phone) - 3)) = #{query.phoneMasked}
             </if>
             <if test='query.tier != null and query.tier != ""'>AND u.user_level = #{query.tier}</if>
             <if test='query.vRank != null and query.vRank != ""'>AND u.v_rank = #{query.vRank}</if>
             <if test='query.referralCode != null and query.referralCode != ""'>AND UPPER(u.referral_code) = UPPER(#{query.referralCode})</if>
             <if test='statuses != null and statuses.size() > 0'>
               AND UPPER(COALESCE(u.status, 'ACTIVE')) IN
               <foreach collection='statuses' item='status' open='(' separator=',' close=')'>#{status}</foreach>
             </if>
             <if test='query.kycStatus != null and query.kycStatus != ""'>AND UPPER(COALESCE(u.kyc_status, 'PENDING')) = UPPER(#{query.kycStatus})</if>
             <if test='query.riskMin != null'>AND COALESCE(rso.override_score, rs.model_score) &gt;= #{query.riskMin}</if>
             <if test='query.riskBand != null and query.riskBand == "HIGH"'>AND COALESCE(rso.override_score, rs.model_score) &gt;= rsm.band_high_min</if>
             <if test='query.riskBand != null and query.riskBand == "MEDIUM"'>AND COALESCE(rso.override_score, rs.model_score) &gt;= rsm.band_low_max AND COALESCE(rso.override_score, rs.model_score) &lt; rsm.band_high_min</if>
             <if test='query.riskBand != null and query.riskBand == "LOW"'>AND COALESCE(rso.override_score, rs.model_score) &lt; rsm.band_low_max</if>
             <if test='query.depositMin != null'>
               AND (SELECT COALESCE(SUM(de.amount), 0) FROM nx_deposit_order de WHERE de.user_id = u.id AND de.is_deleted = 0 AND de.status IN ('CONFIRMED','CREDITED','SUCCESS')) &gt;= #{query.depositMin}
             </if>
             <if test='query.depositMax != null'>
               AND (SELECT COALESCE(SUM(de.amount), 0) FROM nx_deposit_order de WHERE de.user_id = u.id AND de.is_deleted = 0 AND de.status IN ('CONFIRMED','CREDITED','SUCCESS')) &lt;= #{query.depositMax}
             </if>
             <if test='query.walletUsdtMin != null'>AND COALESCE(w.usdt_available, 0) &gt;= #{query.walletUsdtMin}</if>
             <if test='query.walletUsdtMax != null'>AND COALESCE(w.usdt_available, 0) &lt;= #{query.walletUsdtMax}</if>
             <if test='query.walletNexMin != null'>AND COALESCE(w.nex_available, 0) &gt;= #{query.walletNexMin}</if>
             <if test='query.walletNexMax != null'>AND COALESCE(w.nex_available, 0) &lt;= #{query.walletNexMax}</if>
             <if test='query.joinedFrom != null and query.joinedFrom != ""'>AND u.created_at &gt;= CONCAT(#{query.joinedFrom}, ' 00:00:00')</if>
             <if test='query.joinedTo != null and query.joinedTo != ""'>AND u.created_at &lt; DATE_ADD(CONCAT(#{query.joinedTo}, ' 00:00:00'), INTERVAL 1 DAY)</if>
            </script>
            """)
    long countUsersByQuery(@Param("query") UserQueryRequest query, @Param("statuses") List<String> statuses);

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
                   COALESCE(rso.override_score, rs.model_score) AS riskScore,
                   CASE
                     WHEN COALESCE(rso.override_score, rs.model_score) >= rsm.band_high_min THEN '高风险'
                     WHEN COALESCE(rso.override_score, rs.model_score) >= rsm.band_low_max THEN '中风险'
                     WHEN COALESCE(rso.override_score, rs.model_score) IS NULL THEN NULL
                     ELSE '低风险'
                   END AS riskBand,
                   (SELECT COUNT(*) FROM nx_user_device d WHERE d.user_id = u.id AND d.is_deleted = 0) AS deviceCount,
                   (SELECT COUNT(*) FROM nx_user_device d WHERE d.user_id = u.id AND d.is_deleted = 0 AND d.status IN ('ONLINE','BUSY','ACTIVE','RUNNING')) AS activeDeviceCount,
                   u.created_at AS registeredAt,
                   s.last_login_at AS lastLoginAt
              FROM nx_user u
              LEFT JOIN nx_user_security s ON s.user_id = u.id AND s.is_deleted = 0
              LEFT JOIN nx_user_wallet w ON w.user_id = u.id AND w.is_deleted = 0
              LEFT JOIN (
                    SELECT model_version, band_low_max, band_high_min, auto_escalate_score
                      FROM nx_admin_risk_score_model
                     WHERE state = 'active' AND is_deleted = 0
                     ORDER BY model_version DESC
                     LIMIT 1
              ) rsm ON 1 = 1
              LEFT JOIN nx_admin_risk_score_user rs
                ON rs.user_no = CONCAT('U', LPAD(u.id, 8, '0'))
               AND rs.is_deleted = 0
               AND rs.as_of >= DATE_SUB(NOW(), INTERVAL 1 DAY)
               AND rs.model_version = CONCAT('k4-v', rsm.model_version)
              LEFT JOIN nx_admin_risk_score_override rso ON rso.user_no = rs.user_no AND rso.active = 1 AND rso.is_deleted = 0
             WHERE u.is_deleted = 0
             <if test='query.keyword != null and query.keyword != ""'>
               AND (u.nickname LIKE CONCAT('%', #{query.keyword}, '%')
                    OR u.referral_code LIKE CONCAT('%', #{query.keyword}, '%')
                    OR CONCAT('U', LPAD(u.id, 8, '0')) LIKE CONCAT('%', #{query.keyword}, '%')
                    OR CAST(u.id AS CHAR) = #{query.keyword})
             </if>
             <if test='query.userId != null'>AND u.id = #{query.userId}</if>
             <if test='query.phoneHash != null and query.phoneHash != ""'>
               AND SHA2(REGEXP_REPLACE(u.phone, '[^0-9]', ''), 256) = LOWER(#{query.phoneHash})
             </if>
             <if test='query.phoneMasked != null and query.phoneMasked != ""'>
               AND CONCAT(SUBSTRING(u.phone, 1, 3), '****', SUBSTRING(u.phone, LENGTH(u.phone) - 3)) = #{query.phoneMasked}
             </if>
             <if test='query.tier != null and query.tier != ""'>AND u.user_level = #{query.tier}</if>
             <if test='query.vRank != null and query.vRank != ""'>AND u.v_rank = #{query.vRank}</if>
             <if test='query.referralCode != null and query.referralCode != ""'>AND UPPER(u.referral_code) = UPPER(#{query.referralCode})</if>
             <if test='statuses != null and statuses.size() > 0'>
               AND UPPER(COALESCE(u.status, 'ACTIVE')) IN
               <foreach collection='statuses' item='status' open='(' separator=',' close=')'>#{status}</foreach>
             </if>
             <if test='query.kycStatus != null and query.kycStatus != ""'>AND UPPER(COALESCE(u.kyc_status, 'PENDING')) = UPPER(#{query.kycStatus})</if>
             <if test='query.riskMin != null'>AND COALESCE(rso.override_score, rs.model_score) &gt;= #{query.riskMin}</if>
             <if test='query.riskBand != null and query.riskBand == "HIGH"'>AND COALESCE(rso.override_score, rs.model_score) &gt;= rsm.band_high_min</if>
             <if test='query.riskBand != null and query.riskBand == "MEDIUM"'>AND COALESCE(rso.override_score, rs.model_score) &gt;= rsm.band_low_max AND COALESCE(rso.override_score, rs.model_score) &lt; rsm.band_high_min</if>
             <if test='query.riskBand != null and query.riskBand == "LOW"'>AND COALESCE(rso.override_score, rs.model_score) &lt; rsm.band_low_max</if>
             <if test='query.depositMin != null'>
               AND (SELECT COALESCE(SUM(de.amount), 0) FROM nx_deposit_order de WHERE de.user_id = u.id AND de.is_deleted = 0 AND de.status IN ('CONFIRMED','CREDITED','SUCCESS')) &gt;= #{query.depositMin}
             </if>
             <if test='query.depositMax != null'>
               AND (SELECT COALESCE(SUM(de.amount), 0) FROM nx_deposit_order de WHERE de.user_id = u.id AND de.is_deleted = 0 AND de.status IN ('CONFIRMED','CREDITED','SUCCESS')) &lt;= #{query.depositMax}
             </if>
             <if test='query.walletUsdtMin != null'>AND COALESCE(w.usdt_available, 0) &gt;= #{query.walletUsdtMin}</if>
             <if test='query.walletUsdtMax != null'>AND COALESCE(w.usdt_available, 0) &lt;= #{query.walletUsdtMax}</if>
             <if test='query.walletNexMin != null'>AND COALESCE(w.nex_available, 0) &gt;= #{query.walletNexMin}</if>
             <if test='query.walletNexMax != null'>AND COALESCE(w.nex_available, 0) &lt;= #{query.walletNexMax}</if>
             <if test='query.joinedFrom != null and query.joinedFrom != ""'>AND u.created_at &gt;= CONCAT(#{query.joinedFrom}, ' 00:00:00')</if>
             <if test='query.joinedTo != null and query.joinedTo != ""'>AND u.created_at &lt; DATE_ADD(CONCAT(#{query.joinedTo}, ' 00:00:00'), INTERVAL 1 DAY)</if>
             ORDER BY u.id DESC LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<UserAccountView> pageUsers(@Param("query") UserQueryRequest query, @Param("statuses") List<String> statuses,
                                    @Param("offset") int offset, @Param("pageSize") int pageSize);

    @Select("""
            SELECT COUNT(*)
              FROM nx_user
             WHERE is_deleted = 0
               AND UPPER(COALESCE(kyc_status, 'PENDING')) = UPPER(#{kycStatus})
            """)
    long countByKycStatus(@Param("kycStatus") String kycStatus);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_kyc_profile k
              JOIN nx_user u ON u.id=k.user_id AND u.is_deleted=0
             WHERE k.is_deleted=0
             <if test='kycStatus != null and kycStatus != ""'>
               AND UPPER(k.status)=UPPER(#{kycStatus})
             </if>
            </script>
            """)
    long countKycRecords(@Param("kycStatus") String kycStatus);

    @Select("""
            <script>
            SELECT k.user_id AS userId,
                   CONCAT('U', LPAD(k.user_id, 8, '0')) AS userNo,
                   u.nickname,
                   CASE WHEN u.phone IS NULL OR LENGTH(u.phone) &lt; 7 THEN u.phone
                        ELSE CONCAT(SUBSTRING(u.phone,1,3),'****',SUBSTRING(u.phone,LENGTH(u.phone)-3)) END AS phoneMasked,
                   COALESCE(k.country,u.country_code) AS countryCode,
                   COALESCE(u.status,'ACTIVE') AS accountStatus,
                   u.user_level AS userLevel,
                   k.status,
                   k.paired_address AS pairedAddress,
                   k.network,
                   k.paired_at AS pairedAt,
                   k.trigger_source AS triggerSource,
                   k.version
              FROM nx_kyc_profile k
              JOIN nx_user u ON u.id=k.user_id AND u.is_deleted=0
             WHERE k.is_deleted=0
             <if test='kycStatus != null and kycStatus != ""'>
               AND UPPER(k.status)=UPPER(#{kycStatus})
             </if>
             ORDER BY k.updated_at DESC,k.id DESC
             LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<UserKycRecord> pageKycRecords(
            @Param("kycStatus") String kycStatus,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    @Select("""
            SELECT k.user_id AS userId,
                   CONCAT('U', LPAD(k.user_id, 8, '0')) AS userNo,
                   u.nickname,
                   CASE WHEN u.phone IS NULL OR LENGTH(u.phone) < 7 THEN u.phone
                        ELSE CONCAT(SUBSTRING(u.phone,1,3),'****',SUBSTRING(u.phone,LENGTH(u.phone)-3)) END AS phoneMasked,
                   COALESCE(k.country,u.country_code) AS countryCode,
                   COALESCE(u.status,'ACTIVE') AS accountStatus,
                   u.user_level AS userLevel,
                   k.status,
                   k.paired_address AS pairedAddress,
                   k.network,
                   k.paired_at AS pairedAt,
                   k.trigger_source AS triggerSource,
                   k.version
              FROM nx_kyc_profile k
              JOIN nx_user u ON u.id=k.user_id AND u.is_deleted=0
             WHERE k.user_id=#{userId} AND k.is_deleted=0
             LIMIT 1
            """)
    UserKycRecord findKycRecord(@Param("userId") Long userId);

    @Select("""
            SELECT before_status AS beforeStatus,after_status AS afterStatus,
                   reason_code AS reasonCode,reason,evidence_ref AS evidenceRef,
                   source,operator,ticket_id AS ticketId,created_at AS createdAt
              FROM nx_kyc_status_history
             WHERE user_id=#{userId}
             ORDER BY id DESC LIMIT #{limit}
            """)
    List<UserKycStatusHistoryView> kycStatusHistory(@Param("userId") Long userId, @Param("limit") int limit);

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
                   COALESCE(rso.override_score, rs.model_score) AS riskScore,
                   CASE
                     WHEN COALESCE(rso.override_score, rs.model_score) >= rsm.band_high_min THEN '高风险'
                     WHEN COALESCE(rso.override_score, rs.model_score) >= rsm.band_low_max THEN '中风险'
                     WHEN COALESCE(rso.override_score, rs.model_score) IS NULL THEN NULL
                     ELSE '低风险'
                   END AS riskBand,
                   (SELECT COUNT(*) FROM nx_user_device d WHERE d.user_id = u.id AND d.is_deleted = 0) AS deviceCount,
                   (SELECT COUNT(*) FROM nx_user_device d WHERE d.user_id = u.id AND d.is_deleted = 0 AND d.status IN ('ONLINE','BUSY','ACTIVE','RUNNING')) AS activeDeviceCount,
                   u.created_at AS registeredAt,
                   s.last_login_at AS lastLoginAt
              FROM nx_user u
              LEFT JOIN nx_user_security s ON s.user_id = u.id AND s.is_deleted = 0
              LEFT JOIN nx_user_wallet w ON w.user_id = u.id AND w.is_deleted = 0
              LEFT JOIN (
                    SELECT model_version, band_low_max, band_high_min, auto_escalate_score
                      FROM nx_admin_risk_score_model
                     WHERE state = 'active' AND is_deleted = 0
                     ORDER BY model_version DESC
                     LIMIT 1
              ) rsm ON 1 = 1
              LEFT JOIN nx_admin_risk_score_user rs
                ON rs.user_no = CONCAT('U', LPAD(u.id, 8, '0'))
               AND rs.is_deleted = 0
               AND rs.as_of >= DATE_SUB(NOW(), INTERVAL 1 DAY)
               AND rs.model_version = CONCAT('k4-v', rsm.model_version)
              LEFT JOIN nx_admin_risk_score_override rso ON rso.user_no = rs.user_no AND rso.active = 1 AND rso.is_deleted = 0
             WHERE u.id = #{userId} AND u.is_deleted = 0
            LIMIT 1
            </script>
            """)
    UserAccountView findById(@Param("userId") Long userId);

    @Select("""
            SELECT instance_no AS instanceNo,
                   name,
                   device_type AS deviceType,
                   generation,
                   status,
                   hashrate,
                   daily_usdt AS dailyUsdt,
                   daily_nex AS dailyNex,
                   last_seen_at AS lastSeenAt
              FROM nx_user_device
             WHERE user_id = #{userId}
               AND is_deleted = 0
             ORDER BY updated_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<UserReadonlyDeviceView> readonlyDevices(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("""
            SELECT u.id
              FROM nx_user u
              LEFT JOIN nx_user_profile p ON p.user_id = u.id AND p.is_deleted = 0
             WHERE u.is_deleted = 0
               AND (
                    CAST(u.id AS CHAR) = #{lookupKey}
                    OR UPPER(u.referral_code) = UPPER(#{lookupKey})
                    OR UPPER(REPLACE(u.referral_code, '-', '')) = UPPER(REPLACE(#{lookupKey}, '-', ''))
                    OR UPPER(CONCAT('U', LPAD(u.id, 8, '0'))) = UPPER(REPLACE(#{lookupKey}, '-', ''))
                    OR UPPER(p.email) = UPPER(#{lookupKey})
               )
             LIMIT 1
            """)
    Long findUserIdByLookupKey(@Param("lookupKey") String lookupKey);

    @Select("""
            SELECT wallet_address
              FROM nx_user_profile
             WHERE user_id = #{userId}
               AND is_deleted = 0
             LIMIT 1
            """)
    String findWalletAddressByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT u.id AS userId,
                   COALESCE(s.two_factor_enabled, 0) AS twoFactorEnabled,
                   GREATEST(
                       COALESCE(s.login_fail_count, 0),
                       COALESCE((
                           SELECT MAX(g.failed_count)
                             FROM nx_user_login_guard g
                            WHERE g.user_id = u.id
                       ), 0)
                   ) AS loginFailCount,
                   CASE WHEN EXISTS (
                       SELECT 1
                         FROM nx_user_login_guard g
                        WHERE g.user_id = u.id
                          AND g.locked_until IS NOT NULL
                          AND g.locked_until > NOW()
                   ) THEN 1 ELSE 0 END AS locked,
                   COALESCE(s.password_reset_required, 0) AS passwordResetRequired,
                   0 AS lockThreshold,
                   0 AS lockDurationMinutes
              FROM nx_user u
              LEFT JOIN nx_user_security s ON s.user_id = u.id AND s.is_deleted = 0
             WHERE u.id = #{userId} AND u.is_deleted = 0
             LIMIT 1
            """)
    UserSecurityStatusView securityStatus(@Param("userId") Long userId);

    @Select("""
            SELECT SUBSTRING_INDEX(src.source_no, ':', -1) AS action,
                   t.ticket_id AS ticketId,
                   'VERIFIED' AS status,
                   t.reviewed_by AS verifiedBy,
                   t.reviewed_at AS verifiedAt,
                   DATE_ADD(t.reviewed_at, INTERVAL #{rememberDays} DAY) AS expiresAt
              FROM nx_admin_risk_kyc_review_ticket t
              JOIN nx_user u ON u.id=#{userId} AND u.is_deleted=0
               AND CONCAT('U', LPAD(u.id, 8, '0'))=t.user_no
              JOIN nx_admin_risk_kyc_review_source src
                ON src.ticket_id=t.ticket_id AND src.source_domain='C5' AND src.is_deleted=0
              LEFT JOIN nx_c5_kyc_reverification_consumption c
                ON c.ticket_id=t.ticket_id AND c.is_deleted=0
             WHERE t.status='passed' AND t.reviewed_at IS NOT NULL
               AND t.reviewed_at >= DATE_SUB(NOW(), INTERVAL #{rememberDays} DAY)
               AND t.is_deleted=0 AND c.id IS NULL
             ORDER BY t.reviewed_at DESC, t.id DESC
            """)
    List<UserKycReverificationView> availableC5KycReverifications(
            @Param("userId") Long userId,
            @Param("rememberDays") int rememberDays);

    @Select("""
            SELECT COUNT(*)
              FROM nx_admin_risk_kyc_review_ticket t
              JOIN nx_user u ON u.id=#{userId} AND u.is_deleted=0
               AND CONCAT('U', LPAD(u.id, 8, '0'))=t.user_no
              JOIN nx_admin_risk_kyc_review_source src
                ON src.ticket_id=t.ticket_id AND src.source_domain='C5'
               AND src.source_no=CONCAT('U', LPAD(u.id, 8, '0'), ':', #{action}) AND src.is_deleted=0
              LEFT JOIN nx_c5_kyc_reverification_consumption c
                ON c.ticket_id=t.ticket_id AND c.is_deleted=0
             WHERE t.ticket_id=#{ticketId} AND t.status='passed' AND t.reviewed_at IS NOT NULL
               AND t.reviewed_at >= DATE_SUB(NOW(), INTERVAL #{rememberDays} DAY)
               AND t.is_deleted=0
               AND (c.id IS NULL OR c.idempotency_key=#{idempotencyKey})
            """)
    int countUsableC5KycReverification(
            @Param("userId") Long userId,
            @Param("ticketId") String ticketId,
            @Param("action") String action,
            @Param("rememberDays") int rememberDays,
            @Param("idempotencyKey") String idempotencyKey);

    @Insert("""
            INSERT INTO nx_c5_kyc_reverification_consumption(
                ticket_id,user_id,action_code,idempotency_key,consumed_by,consumed_at,is_deleted)
            VALUES(#{ticketId},#{userId},#{action},#{idempotencyKey},#{operator},NOW(),0)
            """)
    int consumeC5KycReverification(
            @Param("userId") Long userId,
            @Param("ticketId") String ticketId,
            @Param("action") String action,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("operator") String operator);

    @Select("SELECT COUNT(*) FROM nx_user_security WHERE is_deleted=0 AND two_factor_enabled=1")
    long countTwoFactorEnabledUsers();

    @Select("""
            SELECT COUNT(DISTINCT s.user_id)
              FROM nx_user_security s
              JOIN (
                  SELECT user_id, MAX(failed_count) AS failed_count, MAX(locked_until) AS locked_until
                    FROM nx_user_login_guard
                   WHERE user_id IS NOT NULL
                   GROUP BY user_id
              ) g ON g.user_id=s.user_id
             WHERE s.is_deleted=0 AND g.locked_until>NOW()
               AND GREATEST(COALESCE(s.login_fail_count,0),COALESCE(g.failed_count,0)) < #{longThreshold}
            """)
    long countActiveShortLocks(@Param("longThreshold") int longThreshold);

    @Select("""
            SELECT COUNT(DISTINCT s.user_id)
              FROM nx_user_security s
              JOIN (
                  SELECT user_id, MAX(failed_count) AS failed_count, MAX(locked_until) AS locked_until
                    FROM nx_user_login_guard
                   WHERE user_id IS NOT NULL
                   GROUP BY user_id
              ) g ON g.user_id=s.user_id
             WHERE s.is_deleted=0 AND g.locked_until>NOW()
               AND GREATEST(COALESCE(s.login_fail_count,0),COALESCE(g.failed_count,0)) >= #{longThreshold}
            """)
    long countActiveLongLocks(@Param("longThreshold") int longThreshold);

    @Select("SELECT COUNT(*) FROM nx_event_outbox WHERE event_type=#{eventType} AND created_at>=CURRENT_DATE AND is_deleted=0")
    long countEventToday(@Param("eventType") String eventType);

    @Select("SELECT COUNT(*) FROM nx_user_otp_challenge WHERE created_at>=CURRENT_DATE AND is_deleted=0")
    long countRegistrationOtpToday();

    @Select("SELECT COUNT(*) FROM nx_event_outbox WHERE event_type='auth.captcha_required' AND created_at>=CURRENT_DATE AND is_deleted=0")
    long countRegistrationCaptchaTriggeredToday();

    @Select("""
            SELECT COUNT(*)
              FROM nx_event_outbox
             WHERE event_type='auth.login_locked'
               AND created_at>=CURRENT_DATE
               AND is_deleted=0
               AND JSON_VALID(payload)=1
               AND COALESCE(
                     JSON_UNQUOTE(JSON_EXTRACT(CASE WHEN JSON_VALID(payload) THEN payload ELSE '{}' END, '$.lockType')),
                     JSON_UNQUOTE(JSON_EXTRACT(CASE WHEN JSON_VALID(payload) THEN payload ELSE '{}' END, '$.lock_type'))
                   )=#{lockType}
            """)
    long countRegistrationLoginLocksToday(@Param("lockType") String lockType);

    @Select("""
            SELECT COUNT(*)
              FROM nx_admin_risk_multi_account_cluster
             WHERE is_deleted=0
               AND threshold_hit=1
               AND created_at>=DATE_SUB(NOW(), INTERVAL 7 DAY)
            """)
    long countRegistrationStuffingClusters7d();

    @Select("""
            SELECT s.user_id AS userId,
                   CONCAT('U', LPAD(s.user_id, 8, '0')) AS userNo,
                   COALESCE(u.nickname, '未知用户') AS nickname,
                   COALESCE(s.two_factor_enabled, 0) AS twoFactorEnabled,
                   GREATEST(COALESCE(s.login_fail_count, 0), COALESCE(g.failed_count, 0)) AS loginFailCount,
                   1 AS locked,
                   COALESCE(s.password_reset_required, 0) AS passwordResetRequired,
                   CASE
                     WHEN GREATEST(COALESCE(s.login_fail_count, 0), COALESCE(g.failed_count, 0)) >= #{longLockThreshold} THEN 'LONG'
                     ELSE 'SHORT'
                   END AS lockKind,
                   CASE
                     WHEN GREATEST(COALESCE(s.login_fail_count, 0), COALESCE(g.failed_count, 0)) >= #{longLockThreshold} THEN CONCAT(#{longLockHours}, ' 小时长锁')
                     ELSE CONCAT(#{shortLockMinutes}, ' 分钟短锁')
                   END AS lockLabel,
                   CASE
                     WHEN GREATEST(COALESCE(s.login_fail_count, 0), COALESCE(g.failed_count, 0)) >= #{longLockThreshold} THEN '服务端登录保护长锁已触发'
                     ELSE '服务端登录保护短锁已触发'
                   END AS lockReason,
                   CONCAT('锁定至 ', DATE_FORMAT(g.locked_until, '%Y-%m-%d %H:%i:%s')) AS lockLeft
              FROM nx_user_security s
              JOIN nx_user u ON u.id = s.user_id AND u.is_deleted = 0
              JOIN (
                  SELECT user_id, MAX(locked_until) AS locked_until, MAX(failed_count) AS failed_count
                    FROM nx_user_login_guard
                   WHERE user_id IS NOT NULL
                   GROUP BY user_id
              ) g ON g.user_id = s.user_id
             WHERE s.is_deleted = 0
               AND g.locked_until IS NOT NULL
               AND g.locked_until > NOW()
             ORDER BY GREATEST(COALESCE(s.login_fail_count, 0), COALESCE(g.failed_count, 0)) DESC, s.updated_at DESC, s.user_id DESC
             LIMIT #{limit}
            """)
    List<UserSecurityUserRow> lockedSecurityUsers(
            @Param("shortLockThreshold") int shortLockThreshold,
            @Param("longLockThreshold") int longLockThreshold,
            @Param("shortLockMinutes") int shortLockMinutes,
            @Param("longLockHours") int longLockHours,
            @Param("limit") int limit);

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
            SELECT COUNT(*) FROM nx_user_session
             WHERE 1=1
               <if test='userId != null'>AND user_id = #{userId}</if>
               AND is_deleted = 0
               AND revoked_at IS NULL
               AND expires_at > NOW()
               AND COALESCE(last_active_at,updated_at,created_at) > DATE_SUB(NOW(), INTERVAL #{idleDays} DAY)
            </script>
            """)
    long countActiveSessionsByUser(@Param("userId") Long userId, @Param("idleDays") int idleDays);

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
                     WHEN COALESCE(last_active_at,updated_at,created_at) &lt;= DATE_SUB(NOW(), INTERVAL #{idleDays} DAY) THEN 'EXPIRED'
                     ELSE 'ACTIVE'
                   END AS status,
                   created_at AS issuedAt,
                   COALESCE(last_active_at,updated_at,created_at) AS lastActiveAt,
                   expires_at AS expiresAt,
                   revoked_at AS revokedAt
              FROM nx_user_session
             WHERE is_deleted = 0
             <if test='userId != null'>AND user_id = #{userId}</if>
             ORDER BY created_at DESC, id DESC LIMIT #{limit}
            </script>
            """)
    List<UserSessionView> sessions(
            @Param("userId") Long userId,
            @Param("limit") int limit,
            @Param("idleDays") int idleDays);

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
                     WHEN COALESCE(last_active_at,updated_at,created_at) &lt;= DATE_SUB(NOW(), INTERVAL #{idleDays} DAY) THEN 'EXPIRED'
                     ELSE 'ACTIVE'
                   END AS status,
                   created_at AS issuedAt,
                   COALESCE(last_active_at,updated_at,created_at) AS lastActiveAt,
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
            @Param("pageSize") int pageSize,
            @Param("idleDays") int idleDays);

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
                   CASE WHEN UPPER(l.status)='ACTIVE' AND l.expires_at IS NOT NULL AND l.expires_at &lt;= NOW()
                        THEN 'EXPIRED' ELSE UPPER(l.status) END AS status,
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
                   CASE WHEN UPPER(l.status)='ACTIVE' AND l.expires_at IS NOT NULL AND l.expires_at <= NOW()
                        THEN 'EXPIRED' ELSE UPPER(l.status) END AS status,
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
            <script>
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
            </script>
            """)
    List<UserImpersonationSessionView> impersonations(@Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
              FROM nx_user_impersonation_session
             WHERE user_id = #{userId}
               AND is_deleted = 0
            """)
    long countImpersonationsByUser(@Param("userId") Long userId);

    @Select("""
            SELECT s.session_no AS sessionNo,
                   s.user_id AS userId,
                   CONCAT('U', LPAD(s.user_id, 8, '0')) AS userNo,
                   COALESCE(u.nickname, '未知用户') AS nickname,
                   CASE
                     WHEN s.status = 'ACTIVE' AND s.expires_at <= NOW() THEN 'EXPIRED'
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
             WHERE s.user_id = #{userId}
               AND s.is_deleted = 0
             ORDER BY s.created_at DESC, s.id DESC
             LIMIT #{limit}
            """)
    List<UserImpersonationSessionView> impersonationsByUser(
            @Param("userId") Long userId,
            @Param("limit") int limit);

    @Select("""
            <script>
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
            </script>
            """)
    UserImpersonationSessionView findImpersonation(@Param("sessionNo") String sessionNo);

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND is_deleted=0 FOR UPDATE")
    Long lockUser(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
              FROM nx_user_impersonation_session
             WHERE user_id=#{userId}
               AND status='ACTIVE'
               AND expires_at > NOW()
               AND is_deleted=0
             FOR UPDATE
            """)
    int countActiveImpersonationsByUser(@Param("userId") Long userId);

    @Select("""
            <script>
            SELECT s.session_no AS sessionNo,
                   s.user_id AS userId,
                   CONCAT('U', LPAD(s.user_id, 8, '0')) AS userNo,
                   COALESCE(u.nickname, CONCAT('User ', s.user_id)) AS nickname,
                   s.status,
                   s.ttl_minutes AS ttlMinutes,
                   s.operator,
                   s.reason,
                   s.expires_at AS expiresAt,
                   s.created_at AS createdAt,
                   s.terminated_at AS endedAt,
                   s.terminated_by AS endedBy,
                   s.terminate_reason AS endReason,
                   0 AS leftMinutes
              FROM nx_user_impersonation_session s
              LEFT JOIN nx_user u ON u.id=s.user_id AND u.is_deleted=0
             WHERE s.status='ACTIVE'
               AND s.expires_at &lt;= NOW()
               AND s.is_deleted=0
             ORDER BY s.expires_at ASC
             LIMIT #{limit}
            </script>
            """)
    List<UserImpersonationSessionView> expiredActiveImpersonations(@Param("limit") int limit);

    @Update("""
            UPDATE nx_user_impersonation_session
               SET status='EXPIRED',
                   terminated_by=#{operator},
                   terminate_reason=#{reason},
                   terminated_at=NOW(),
                   updated_at=NOW()
             WHERE session_no=#{sessionNo}
               AND status='ACTIVE'
               AND expires_at <= NOW()
               AND is_deleted=0
            """)
    int expireActiveImpersonation(@Param("sessionNo") String sessionNo,
                                  @Param("reason") String reason,
                                  @Param("operator") String operator);

    @Update("""
            UPDATE nx_user_impersonation_session
               SET status = 'TERMINATED',
                   terminated_by = #{operator},
                   terminate_reason = #{reason},
                   terminated_at = NOW(),
                   updated_at = NOW()
             WHERE session_no = #{sessionNo}
               AND is_deleted = 0
               AND status = 'ACTIVE'
               AND expires_at > NOW()
            """)
    int terminateActiveImpersonation(@Param("sessionNo") String sessionNo, @Param("reason") String reason, @Param("operator") String operator);

    @Update("""
            UPDATE nx_user
             SET status = #{status},
                 c2_freeze_source = CASE WHEN #{status} = 'ACTIVE' THEN NULL ELSE c2_freeze_source END,
                 c2_freeze_source_ref = CASE WHEN #{status} = 'ACTIVE' THEN NULL ELSE c2_freeze_source_ref END,
                 c2_freeze_reason = CASE WHEN #{status} = 'ACTIVE' THEN NULL ELSE c2_freeze_reason END,
                 c2_freeze_operator = CASE WHEN #{status} = 'ACTIVE' THEN NULL ELSE c2_freeze_operator END,
                 c2_frozen_at = CASE WHEN #{status} = 'ACTIVE' THEN NULL ELSE c2_frozen_at END,
                 updated_at = NOW()
             WHERE id = #{userId}
               AND is_deleted = 0
               AND status = #{expectedStatus}
            """)
    int transitionUserStatus(@Param("userId") Long userId,
                             @Param("expectedStatus") String expectedStatus,
                             @Param("status") String status);

    @Update("""
            UPDATE nx_user
               SET status = 'FROZEN',
                   c2_freeze_source = #{source},
                   c2_freeze_source_ref = #{sourceRef},
                   c2_freeze_reason = #{reason},
                   c2_freeze_operator = #{operator},
                   c2_frozen_at = NOW(),
                   updated_at = NOW()
             WHERE id = #{userId}
               AND is_deleted = 0
               AND status = #{expectedStatus}
            """)
    int freezeUserStatusWithSource(
            @Param("userId") Long userId,
            @Param("expectedStatus") String expectedStatus,
            @Param("reason") String reason,
            @Param("operator") String operator,
            @Param("source") String source,
            @Param("sourceRef") String sourceRef);

    @Select("""
            SELECT COUNT(*)
              FROM nx_user
             WHERE id = #{userId}
               AND is_deleted = 0
               AND status = 'FROZEN'
               AND c2_freeze_source = #{source}
            """)
    int countFrozenBySource(@Param("userId") Long userId, @Param("source") String source);

    @Update("""
            UPDATE nx_user
               SET status = 'ACTIVE',
                   c2_freeze_source = NULL,
                   c2_freeze_source_ref = NULL,
                   c2_freeze_reason = NULL,
                   c2_freeze_operator = NULL,
                   c2_frozen_at = NULL,
                   updated_at = NOW()
             WHERE id = #{userId}
               AND is_deleted = 0
               AND status = 'FROZEN'
               AND c2_freeze_source = #{source}
               AND c2_freeze_source_ref = #{sourceRef}
            """)
    int restoreUserStatusByFreezeSource(
            @Param("userId") Long userId,
            @Param("source") String source,
            @Param("sourceRef") String sourceRef);

    @Update("""
            UPDATE nx_user
               SET kyc_status = #{kycStatus}, updated_at = NOW()
             WHERE id = #{userId} AND is_deleted = 0
            """)
    int updateKycStatus(@Param("userId") Long userId, @Param("kycStatus") String kycStatus);

    @Update("""
            UPDATE nx_kyc_profile
               SET status=#{nextStatus}, reviewed_by=#{operator}, reviewed_at=NOW(),
                   trigger_source=#{source}, version=version+1, updated_at=NOW()
             WHERE user_id=#{userId} AND status=#{expectedStatus} AND version=#{expectedVersion} AND is_deleted=0
            """)
    int transitionKycProfile(
            @Param("userId") Long userId,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("nextStatus") String nextStatus,
            @Param("source") String source,
            @Param("operator") String operator);

    @Insert("""
            INSERT INTO nx_kyc_status_history
              (user_id,before_status,after_status,reason_code,reason,evidence_ref,source,operator,idempotency_key,ticket_id)
            VALUES
              (#{userId},#{beforeStatus},#{afterStatus},#{reasonCode},#{reason},#{evidenceRef},#{source},#{operator},#{idempotencyKey},#{ticketId})
            """)
    int insertKycStatusHistory(
            @Param("userId") Long userId,
            @Param("beforeStatus") String beforeStatus,
            @Param("afterStatus") String afterStatus,
            @Param("reasonCode") String reasonCode,
            @Param("reason") String reason,
            @Param("evidenceRef") String evidenceRef,
            @Param("source") String source,
            @Param("operator") String operator,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("ticketId") String ticketId);

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
                   COALESCE(last_active_at,updated_at,created_at) AS lastActiveAt,
                   expires_at AS expiresAt,
                   revoked_at AS revokedAt
             FROM nx_user_session
             WHERE refresh_token_id = #{refreshTokenId} AND is_deleted = 0
            LIMIT 1
            </script>
            """)
    UserSessionView findSession(@Param("refreshTokenId") String refreshTokenId);

    @Update("""
            UPDATE nx_user_session
               SET revoked_at = NOW(), updated_at = NOW()
             WHERE refresh_token_id = #{refreshTokenId}
               AND revoked_at IS NULL
               AND expires_at > NOW()
               AND is_deleted = 0
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

    @Update("""
            UPDATE nx_user_security
               SET two_factor_enabled = 0,
                   updated_at = NOW()
             WHERE user_id = #{userId}
               AND two_factor_enabled = 1
               AND is_deleted = 0
            """)
    int disableTwoFactor(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_user_security (
                user_id, two_factor_enabled, login_fail_count, password_reset_required,
                created_at, updated_at, is_deleted
            ) VALUES (#{userId}, 0, 0, 1, NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE
                updated_at = IF(password_reset_required = 0, NOW(), updated_at),
                password_reset_required = IF(password_reset_required = 0, 1, password_reset_required),
                is_deleted = 0
            """)
    int markPasswordResetRequired(@Param("userId") Long userId);

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

    @Delete("""
            DELETE FROM nx_user_login_guard
             WHERE user_id = #{userId}
            """)
    int clearLoginGuardsByUserId(@Param("userId") Long userId);

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
                adjustment_no, user_id, asset, direction, amount, amount_usd, reason_code, reason,
                evidence_ref, idempotency_key, reversal_of, maker, status, created_at, updated_at, is_deleted
            ) VALUES (
                #{adjustmentNo}, #{userId}, #{asset}, #{direction}, #{amount}, #{amountUsd}, #{reasonCode}, #{reason},
                #{evidenceRef}, #{idempotencyKey}, #{reversalOf}, #{operator}, 'PENDING_REVIEW', NOW(), NOW(), 0)
            """)
    int insertAssetAdjustment(@Param("adjustmentNo") String adjustmentNo, @Param("userId") Long userId,
                              @Param("asset") String asset, @Param("direction") String direction,
                              @Param("amount") BigDecimal amount, @Param("amountUsd") BigDecimal amountUsd,
                              @Param("reasonCode") String reasonCode, @Param("reason") String reason,
                              @Param("evidenceRef") String evidenceRef, @Param("idempotencyKey") String idempotencyKey,
                              @Param("reversalOf") String reversalOf,
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
                    OR u.phone LIKE CONCAT('%', #{keyword}, '%')
                    OR u.referral_code LIKE CONCAT('%', #{keyword}, '%')
                    OR CONCAT('U', LPAD(a.user_id, 8, '0')) LIKE CONCAT('%', #{keyword}, '%')
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
                   a.amount_usd AS amountUsd,
                   CONCAT(CASE WHEN a.direction = 'CREDIT' THEN '+' ELSE '-' END,
                          CASE WHEN a.asset = 'USDT' THEN CONCAT('$', CAST(a.amount AS CHAR))
                               ELSE CONCAT(CAST(a.amount AS CHAR), ' NEX') END) AS amountLabel,
                   a.reason_code AS reasonCode,
                   a.reason,
                   a.evidence_ref AS evidenceRef,
                   a.idempotency_key AS idempotencyKey,
                   a.reversal_of AS reversalOf,
                   (SELECT r.adjustment_no
                      FROM nx_wallet_asset_adjustment r
                     WHERE r.reversal_of=a.adjustment_no AND r.status='APPROVED' AND r.is_deleted=0
                     ORDER BY r.id DESC LIMIT 1) AS reversedBy,
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
                   CASE WHEN a.amount_usd > 500 THEN 1 ELSE 0 END AS escalated,
                   a.ledger_id AS ledgerId,
                   l.balance_after AS balanceAfter,
                   CASE WHEN a.ledger_id IS NULL THEN 'D4 待过账' ELSE CONCAT('账本 #', a.ledger_id) END AS sink,
                   a.review_reason AS reviewReason,
                   a.reviewed_at AS reviewedAt,
                   a.created_at AS createdAt,
                   a.updated_at AS updatedAt
              FROM nx_wallet_asset_adjustment a
              LEFT JOIN nx_user u ON u.id = a.user_id AND u.is_deleted = 0
              LEFT JOIN nx_wallet_ledger l ON l.id = a.ledger_id AND l.is_deleted = 0
             WHERE a.is_deleted = 0
             <if test='status != null and status != ""'>AND UPPER(a.status) = UPPER(#{status})</if>
             <if test='historyOnly != null and historyOnly'>AND UPPER(a.status) NOT IN ('PENDING', 'PENDING_REVIEW', 'SUSPENDED')</if>
             <if test='asset != null and asset != ""'>AND UPPER(a.asset) = UPPER(#{asset})</if>
             <if test='userId != null'>AND a.user_id = #{userId}</if>
             <if test='keyword != null and keyword != ""'>
               AND (a.adjustment_no LIKE CONCAT('%', #{keyword}, '%')
                    OR a.reason LIKE CONCAT('%', #{keyword}, '%')
                    OR u.nickname LIKE CONCAT('%', #{keyword}, '%')
                    OR u.phone LIKE CONCAT('%', #{keyword}, '%')
                    OR u.referral_code LIKE CONCAT('%', #{keyword}, '%')
                    OR CONCAT('U', LPAD(a.user_id, 8, '0')) LIKE CONCAT('%', #{keyword}, '%')
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
                   a.amount_usd AS amountUsd,
                   CONCAT(CASE WHEN a.direction = 'CREDIT' THEN '+' ELSE '-' END,
                          CASE WHEN a.asset = 'USDT' THEN CONCAT('$', CAST(a.amount AS CHAR))
                               ELSE CONCAT(CAST(a.amount AS CHAR), ' NEX') END) AS amountLabel,
                   a.reason_code AS reasonCode,
                   a.reason,
                   a.evidence_ref AS evidenceRef,
                   a.idempotency_key AS idempotencyKey,
                   a.reversal_of AS reversalOf,
                   (SELECT r.adjustment_no
                      FROM nx_wallet_asset_adjustment r
                     WHERE r.reversal_of=a.adjustment_no AND r.status='APPROVED' AND r.is_deleted=0
                     ORDER BY r.id DESC LIMIT 1) AS reversedBy,
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
                   CASE WHEN a.amount_usd > 500 THEN 1 ELSE 0 END AS escalated,
                   a.ledger_id AS ledgerId,
                   l.balance_after AS balanceAfter,
                   CASE WHEN a.ledger_id IS NULL THEN 'D4 待过账' ELSE CONCAT('账本 #', a.ledger_id) END AS sink,
                   a.review_reason AS reviewReason,
                   a.reviewed_at AS reviewedAt,
                   a.created_at AS createdAt,
                   a.updated_at AS updatedAt
              FROM nx_wallet_asset_adjustment a
              LEFT JOIN nx_user u ON u.id = a.user_id AND u.is_deleted = 0
              LEFT JOIN nx_wallet_ledger l ON l.id = a.ledger_id AND l.is_deleted = 0
             WHERE a.adjustment_no = #{adjustmentNo}
               AND a.is_deleted = 0
             LIMIT 1
            """)
    UserAssetAdjustmentView findAssetAdjustment(@Param("adjustmentNo") String adjustmentNo);

    @Select("""
            SELECT status
              FROM nx_wallet_asset_adjustment
             WHERE adjustment_no = #{adjustmentNo}
               AND is_deleted = 0
             FOR UPDATE
            """)
    String lockAssetAdjustmentStatus(@Param("adjustmentNo") String adjustmentNo);

    @Update("""
            UPDATE nx_wallet_asset_adjustment
               SET status = #{status},
                   checker = #{checker},
                   review_reason = #{reason},
                   reviewed_at = NOW(),
                   updated_at = NOW()
             WHERE adjustment_no = #{adjustmentNo}
               AND is_deleted = 0
               AND UPPER(status) IN ('PENDING', 'PENDING_REVIEW', 'SUSPENDED')
            """)
    int reviewAssetAdjustment(
            @Param("adjustmentNo") String adjustmentNo,
            @Param("status") String status,
            @Param("checker") String checker,
            @Param("reason") String reason);

    @Insert("""
            INSERT INTO nx_user_wallet (
                user_id,
                usdt_available,
                nex_available,
                pending_withdraw,
                lifetime_earned,
                version,
                created_at,
                updated_at,
                is_deleted
            )
            VALUES (
                #{userId},
                0,
                0,
                0,
                0,
                0,
                NOW(),
                NOW(),
                0
            )
            ON DUPLICATE KEY UPDATE
                is_deleted = 0,
                updated_at = NOW()
            """)
    int ensureUserWallet(@Param("userId") Long userId);

    @Update("""
            <script>
            UPDATE nx_user_wallet
               SET
                   <choose>
                       <when test="asset == 'USDT'">
                           usdt_available = CASE
                               WHEN #{direction} = 'CREDIT' THEN usdt_available + #{amount}
                               ELSE usdt_available - #{amount}
                           END,
                       </when>
                       <otherwise>
                           nex_available = CASE
                               WHEN #{direction} = 'CREDIT' THEN nex_available + #{amount}
                               ELSE nex_available - #{amount}
                           END,
                       </otherwise>
                   </choose>
                   version = version + 1,
                   updated_at = NOW()
             WHERE user_id = #{userId}
               AND is_deleted = 0
               <if test="direction == 'DEBIT' and asset == 'USDT'">
                   AND usdt_available &gt;= #{amount}
               </if>
               <if test="direction == 'DEBIT' and asset != 'USDT'">
                   AND nex_available &gt;= #{amount}
               </if>
            </script>
            """)
    int applyAssetAdjustmentToWallet(
            @Param("userId") Long userId,
            @Param("asset") String asset,
            @Param("direction") String direction,
            @Param("amount") BigDecimal amount);

    @Select("""
            SELECT CASE WHEN #{asset} = 'USDT' THEN usdt_available ELSE nex_available END
              FROM nx_user_wallet
             WHERE user_id = #{userId}
               AND is_deleted = 0
             LIMIT 1
            """)
    BigDecimal findWalletAvailable(
            @Param("userId") Long userId,
            @Param("asset") String asset);

    @Insert("""
            INSERT INTO nx_wallet_ledger (
                user_id,
                biz_no,
                biz_type,
                asset,
                direction,
                amount,
                balance_after,
                status,
                remark,
                created_at,
                updated_at,
                is_deleted
            )
            VALUES (
                #{userId},
                #{adjustmentNo},
                'ADJUSTMENT',
                #{asset},
                CASE WHEN #{adjustmentDirection} = 'CREDIT' THEN 'IN' ELSE 'OUT' END,
                #{amount},
                #{balanceAfter},
                'SUCCESS',
                #{remark},
                NOW(),
                NOW(),
                0
            )
            ON DUPLICATE KEY UPDATE
                user_id = VALUES(user_id),
                biz_type = VALUES(biz_type),
                amount = VALUES(amount),
                balance_after = VALUES(balance_after),
                status = VALUES(status),
                remark = VALUES(remark),
                updated_at = NOW(),
                is_deleted = 0
            """)
    int upsertApprovedAssetAdjustmentLedger(
            @Param("adjustmentNo") String adjustmentNo,
            @Param("userId") Long userId,
            @Param("asset") String asset,
            @Param("adjustmentDirection") String adjustmentDirection,
            @Param("amount") BigDecimal amount,
            @Param("balanceAfter") BigDecimal balanceAfter,
            @Param("remark") String remark);

    @Select("""
            SELECT id
              FROM nx_wallet_ledger
             WHERE biz_no = #{adjustmentNo}
               AND asset = #{asset}
               AND direction = CASE WHEN #{adjustmentDirection} = 'CREDIT' THEN 'IN' ELSE 'OUT' END
               AND is_deleted = 0
             LIMIT 1
            """)
    Long findAssetAdjustmentLedgerId(
            @Param("adjustmentNo") String adjustmentNo,
            @Param("asset") String asset,
            @Param("adjustmentDirection") String adjustmentDirection);

    @Update("""
            UPDATE nx_wallet_asset_adjustment
               SET status = 'APPROVED',
                   checker = #{checker},
                   review_reason = #{reason},
                   reviewed_at = NOW(),
                   ledger_id = #{ledgerId},
                   updated_at = NOW()
             WHERE adjustment_no = #{adjustmentNo}
               AND is_deleted = 0
               AND UPPER(status) IN ('PENDING', 'PENDING_REVIEW', 'SUSPENDED')
            """)
    int approveAssetAdjustmentWithLedger(
            @Param("adjustmentNo") String adjustmentNo,
            @Param("checker") String checker,
            @Param("reason") String reason,
            @Param("ledgerId") Long ledgerId);

    @Select("""
            SELECT COUNT(*)
              FROM nx_wallet_asset_adjustment
             WHERE reversal_of = #{adjustmentNo}
               AND is_deleted = 0
               AND UPPER(status) <> 'REJECTED'
            """)
    int countActiveAssetAdjustmentReversals(@Param("adjustmentNo") String adjustmentNo);

    @Select("""
            SELECT COALESCE(pending_withdraw, 0)
              FROM nx_user_wallet
             WHERE user_id = #{userId}
               AND is_deleted = 0
             LIMIT 1
            """)
    BigDecimal findWalletPendingWithdraw(@Param("userId") Long userId);
}
