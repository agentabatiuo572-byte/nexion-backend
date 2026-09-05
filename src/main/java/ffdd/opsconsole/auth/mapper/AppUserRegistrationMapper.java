package ffdd.opsconsole.auth.mapper;

import ffdd.opsconsole.user.infrastructure.UserEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@SuppressWarnings("MybatisPlusBaseMapper")
public interface AppUserRegistrationMapper {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_user_registration_otp (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                challenge_no VARCHAR(96) NOT NULL,
                country_code VARCHAR(8) NOT NULL,
                phone VARCHAR(32) NOT NULL,
                client_ip VARCHAR(64) NOT NULL,
                auth_environment VARCHAR(16) NOT NULL,
                code_hash CHAR(64) NOT NULL,
                expires_at DATETIME NOT NULL,
                attempts INT NOT NULL DEFAULT 0,
                consumed_at DATETIME NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                is_deleted TINYINT NOT NULL DEFAULT 0,
                UNIQUE KEY uk_user_registration_otp_no (challenge_no),
                KEY idx_user_registration_otp_phone (country_code,phone,auth_environment,expires_at,consumed_at),
                KEY idx_user_registration_otp_ip (client_ip,created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createTable();

    @Select("""
            SELECT COUNT(*)
              FROM nx_user_registration_otp
             WHERE country_code=#{countryCode} AND phone=#{phone}
               AND created_at>=DATE_SUB(NOW(),INTERVAL 60 SECOND)
               AND is_deleted=0
            """)
    int countRecentPhone(
            @Param("countryCode") String countryCode,
            @Param("phone") String phone);

    @Select("SELECT COUNT(*) FROM nx_user_registration_otp WHERE country_code=#{countryCode} AND phone=#{phone} AND auth_environment=#{authEnvironment} AND created_at>=DATE_SUB(NOW(),INTERVAL #{cooldownSeconds} SECOND) AND is_deleted=0")
    int countRecentPhoneInEnvironment(@Param("countryCode") String countryCode, @Param("phone") String phone,
            @Param("authEnvironment") String authEnvironment, @Param("cooldownSeconds") int cooldownSeconds);

    @Select("""
            SELECT COUNT(*)
              FROM nx_user_registration_otp
             WHERE country_code=#{countryCode} AND phone=#{phone}
               AND created_at>=DATE_SUB(NOW(),INTERVAL 1 DAY)
               AND is_deleted=0
            """)
    int countDailyPhone(
            @Param("countryCode") String countryCode,
            @Param("phone") String phone);

    @Select("SELECT COUNT(*) FROM nx_user_registration_otp WHERE country_code=#{countryCode} AND phone=#{phone} AND auth_environment=#{authEnvironment} AND created_at>=DATE_SUB(NOW(),INTERVAL 1 DAY) AND is_deleted=0")
    int countDailyPhoneInEnvironment(@Param("countryCode") String countryCode, @Param("phone") String phone,
            @Param("authEnvironment") String authEnvironment);

    @Select("""
            SELECT COUNT(*)
              FROM nx_user_registration_otp
             WHERE client_ip=#{clientIp}
               AND created_at>=DATE_SUB(NOW(),INTERVAL 1 MINUTE)
               AND is_deleted=0
            """)
    int countRecentClient(@Param("clientIp") String clientIp);

    @Select("""
            SELECT COUNT(*)
              FROM nx_user_registration_otp
             WHERE client_ip=#{clientIp}
               AND created_at>=DATE_SUB(NOW(),INTERVAL 1 DAY)
               AND is_deleted=0
            """)
    int countDailyClient(@Param("clientIp") String clientIp);

    @Update("""
            UPDATE nx_user_registration_otp
               SET consumed_at=NOW(),updated_at=NOW()
             WHERE country_code=#{countryCode} AND phone=#{phone}
               AND consumed_at IS NULL AND is_deleted=0
            """)
    int invalidateActive(
            @Param("countryCode") String countryCode,
            @Param("phone") String phone);

    @Update("UPDATE nx_user_registration_otp SET consumed_at=NOW(),updated_at=NOW() WHERE country_code=#{countryCode} AND phone=#{phone} AND auth_environment=#{authEnvironment} AND consumed_at IS NULL AND is_deleted=0")
    int invalidateActiveInEnvironment(@Param("countryCode") String countryCode, @Param("phone") String phone,
            @Param("authEnvironment") String authEnvironment);

    @Insert("""
            INSERT INTO nx_user_registration_otp(
                challenge_no,country_code,phone,client_ip,code_hash,expires_at,attempts,
                created_at,updated_at,is_deleted)
            VALUES(
                #{challengeNo},#{countryCode},#{phone},#{clientIp},
                SHA2(CONCAT(#{code},':',#{challengeNo}),256),
                DATE_ADD(NOW(),INTERVAL #{ttlMinutes} MINUTE),0,NOW(),NOW(),0)
            """)
    int insertChallenge(
            @Param("challengeNo") String challengeNo,
            @Param("countryCode") String countryCode,
            @Param("phone") String phone,
            @Param("clientIp") String clientIp,
            @Param("code") String code,
            @Param("ttlMinutes") int ttlMinutes);

    @Insert("INSERT INTO nx_user_registration_otp(challenge_no,country_code,phone,client_ip,auth_environment,code_hash,expires_at,attempts,created_at,updated_at,is_deleted) VALUES(#{challengeNo},#{countryCode},#{phone},#{clientIp},#{authEnvironment},SHA2(CONCAT(#{code},':',#{challengeNo}),256),DATE_ADD(NOW(),INTERVAL #{ttlMinutes} MINUTE),0,NOW(),NOW(),0)")
    int insertChallengeInEnvironment(@Param("challengeNo") String challengeNo, @Param("countryCode") String countryCode,
            @Param("phone") String phone, @Param("clientIp") String clientIp, @Param("authEnvironment") String authEnvironment,
            @Param("code") String code, @Param("ttlMinutes") int ttlMinutes);

    @Update("""
            UPDATE nx_user_registration_otp
               SET consumed_at=NOW(),attempts=attempts+1,updated_at=NOW()
             WHERE challenge_no=#{challengeNo}
               AND country_code=#{countryCode} AND phone=#{phone}
               AND code_hash=SHA2(CONCAT(#{code},':',challenge_no),256)
               AND consumed_at IS NULL AND expires_at>=NOW()
               AND attempts<#{maxAttempts} AND is_deleted=0
            """)
    int consumeValidChallenge(
            @Param("challengeNo") String challengeNo,
            @Param("countryCode") String countryCode,
            @Param("phone") String phone,
            @Param("code") String code,
            @Param("maxAttempts") int maxAttempts);

    @Update("UPDATE nx_user_registration_otp SET consumed_at=NOW(),attempts=attempts+1,updated_at=NOW() WHERE challenge_no=#{challengeNo} AND country_code=#{countryCode} AND phone=#{phone} AND auth_environment=#{authEnvironment} AND code_hash=SHA2(CONCAT(#{code},':',challenge_no),256) AND consumed_at IS NULL AND expires_at>=NOW() AND attempts<#{maxAttempts} AND is_deleted=0")
    int consumeValidChallengeInEnvironment(@Param("challengeNo") String challengeNo, @Param("countryCode") String countryCode,
            @Param("phone") String phone, @Param("authEnvironment") String authEnvironment, @Param("code") String code,
            @Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE nx_user_registration_otp
               SET attempts=attempts+1,updated_at=NOW()
             WHERE challenge_no=#{challengeNo}
               AND country_code=#{countryCode} AND phone=#{phone}
               AND consumed_at IS NULL AND expires_at>=NOW()
               AND attempts<#{maxAttempts} AND is_deleted=0
            """)
    int recordInvalidAttempt(
            @Param("challengeNo") String challengeNo,
            @Param("countryCode") String countryCode,
            @Param("phone") String phone,
            @Param("maxAttempts") int maxAttempts);

    @Update("UPDATE nx_user_registration_otp SET attempts=attempts+1,updated_at=NOW() WHERE challenge_no=#{challengeNo} AND country_code=#{countryCode} AND phone=#{phone} AND auth_environment=#{authEnvironment} AND consumed_at IS NULL AND expires_at>=NOW() AND attempts<#{maxAttempts} AND is_deleted=0")
    int recordInvalidAttemptInEnvironment(@Param("challengeNo") String challengeNo, @Param("countryCode") String countryCode,
            @Param("phone") String phone, @Param("authEnvironment") String authEnvironment,
            @Param("maxAttempts") int maxAttempts);

    @Select("""
            SELECT client_ip
              FROM nx_user_registration_otp
             WHERE challenge_no=#{challengeNo}
               AND country_code=#{countryCode} AND phone=#{phone}
               AND consumed_at IS NOT NULL AND is_deleted=0
             LIMIT 1
            """)
    String consumedChallengeClientIp(
            @Param("challengeNo") String challengeNo,
            @Param("countryCode") String countryCode,
            @Param("phone") String phone);

    @Select("SELECT client_ip FROM nx_user_registration_otp WHERE challenge_no=#{challengeNo} AND country_code=#{countryCode} AND phone=#{phone} AND auth_environment=#{authEnvironment} AND consumed_at IS NOT NULL AND is_deleted=0 LIMIT 1")
    String consumedChallengeClientIpInEnvironment(@Param("challengeNo") String challengeNo, @Param("countryCode") String countryCode,
            @Param("phone") String phone, @Param("authEnvironment") String authEnvironment);

    @Select("""
            SELECT value_text
              FROM nx_admin_risk_param
             WHERE section_key='k1' AND param_key=#{key} AND is_deleted=0
             LIMIT 1
             FOR UPDATE
            """)
    String k1ParamValueForUpdate(@Param("key") String key);

    @Select("""
            SELECT COUNT(DISTINCT user_account.id)
              FROM nx_user_registration_otp registration
              JOIN nx_user user_account
                ON REPLACE(COALESCE(user_account.country_code,''),'+','')
                   = REPLACE(COALESCE(registration.country_code,''),'+','')
               AND user_account.phone=registration.phone
               AND user_account.is_deleted=0
             WHERE registration.client_ip=#{clientIp}
               AND registration.consumed_at IS NOT NULL
               AND registration.created_at>=DATE_SUB(NOW(),INTERVAL 1 DAY)
               AND registration.is_deleted=0
            """)
    int countRegisteredAccountsByClientIp24h(@Param("clientIp") String clientIp);

    @Select("""
            SELECT COUNT(DISTINCT user_account.id)
              FROM nx_user_registration_otp registration
              JOIN nx_user user_account
                ON REPLACE(COALESCE(user_account.country_code,''),'+','') = REPLACE(COALESCE(registration.country_code,''),'+','')
               AND user_account.phone=registration.phone
               AND user_account.sandbox=#{sandbox} AND user_account.is_deleted=0
             WHERE registration.client_ip=#{clientIp} AND registration.auth_environment=#{authEnvironment}
               AND registration.consumed_at IS NOT NULL AND registration.created_at>=DATE_SUB(NOW(),INTERVAL 1 DAY)
               AND registration.is_deleted=0
            """)
    int countRegisteredAccountsByClientIp24hInEnvironment(@Param("clientIp") String clientIp,
            @Param("authEnvironment") String authEnvironment, @Param("sandbox") int sandbox);

    /**
     * Compatibility resolver for legacy stored codes that contain hyphens or
     * lowercase letters. It deliberately takes no lock: an expression cannot
     * use the unique referral index. The caller rejects canonical ambiguity and
     * then reacquires exactly one stored referral code with the method below.
     */
    @Select("""
            SELECT id,referral_code,status,is_deleted
              FROM nx_user
             WHERE UPPER(REPLACE(referral_code,'-',''))=#{canonicalCode}
               AND status='ACTIVE' AND is_deleted=0
            """)
    List<UserEntity> findActiveSponsorsByCanonicalCode(@Param("canonicalCode") String canonicalCode);

    @Select("""
            SELECT *
              FROM nx_user
             WHERE referral_code=#{sponsorCode}
               AND status='ACTIVE' AND is_deleted=0
             LIMIT 1
             FOR UPDATE
            """)
    UserEntity findSponsorForUpdate(@Param("sponsorCode") String sponsorCode);

    /**
     * Resolve the canonical sponsor chain from nx_user. The path guard makes a
     * corrupted legacy cycle fail closed, and the depth limit matches F2 L1-L7.
     */
    @Select("""
            WITH RECURSIVE sponsor_chain AS (
                SELECT sponsor.id AS ownerUserId,
                       1 AS level,
                       CAST(CONCAT('/', child.id, '/', sponsor.id, '/') AS CHAR(512)) AS visited
                  FROM nx_user child
                  JOIN nx_user sponsor
                    ON sponsor.id = child.sponsor_user_id
                   AND sponsor.sandbox = child.sandbox
                   AND sponsor.status = 'ACTIVE'
                   AND sponsor.is_deleted = 0
                 WHERE child.id = #{memberUserId}
                   AND child.sandbox = #{sandbox}
                   AND child.is_deleted = 0
                UNION ALL
                SELECT sponsor.id,
                       chain_row.level + 1,
                       CONCAT(chain_row.visited, sponsor.id, '/')
                  FROM sponsor_chain chain_row
                  JOIN nx_user current_owner
                    ON current_owner.id = chain_row.ownerUserId
                   AND current_owner.sandbox = #{sandbox}
                   AND current_owner.is_deleted = 0
                  JOIN nx_user sponsor
                    ON sponsor.id = current_owner.sponsor_user_id
                   AND sponsor.sandbox = current_owner.sandbox
                   AND sponsor.status = 'ACTIVE'
                   AND sponsor.is_deleted = 0
                 WHERE chain_row.level < 7
                   AND LOCATE(CONCAT('/', sponsor.id, '/'), chain_row.visited) = 0
            )
            SELECT ownerUserId, level
              FROM sponsor_chain
             ORDER BY level ASC
            """)
    List<TeamAncestorProjection> listActiveSponsorChain(
            @Param("memberUserId") Long memberUserId,
            @Param("sandbox") int sandbox);

    /** Create one immutable owner/member/depth projection from canonical nx_user data. */
    @Insert("""
            INSERT INTO nx_team_member
              (user_id,member_user_id,member_no,nickname,v_rank,level,volume,created_at,updated_at,is_deleted)
            SELECT #{ownerUserId},
                   member.id,
                   CONCAT('U', LPAD(member.id, GREATEST(8, CHAR_LENGTH(CAST(member.id AS CHAR))), '0')),
                   LEFT(COALESCE(NULLIF(member.nickname, ''), CONCAT('User ', member.id)), 64),
                   COALESCE(NULLIF(UPPER(member.v_rank), ''), 'V0'),
                   #{level},0,NOW(),NOW(),0
              FROM nx_user member
             WHERE member.id = #{memberUserId}
               AND member.sandbox = #{sandbox}
               AND member.is_deleted = 0
               AND NOT EXISTS (
                   SELECT 1
                     FROM nx_team_member existing
                    WHERE existing.user_id = #{ownerUserId}
                      AND existing.member_user_id = #{memberUserId}
                      AND existing.level = #{level}
                      AND existing.is_deleted = 0
               )
            """)
    int insertTeamMemberProjection(
            @Param("ownerUserId") Long ownerUserId,
            @Param("memberUserId") Long memberUserId,
            @Param("level") int level,
            @Param("sandbox") int sandbox);
}
