package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import ffdd.opsconsole.content.infrastructure.DisclosureAckStatusEntity;

public interface DisclosureAckStatusMapper extends BaseMapper<DisclosureAckStatusEntity> {
    @Insert("""
            <script>
            INSERT INTO nx_disclosure_ack_status
              (user_id, jurisdiction_code, required_version, acknowledged_version, ack_status,
               created_at, updated_at, is_deleted)
            SELECT u.id, #{jurisdiction}, #{version}, NULL, 'STALE', #{now}, #{now}, 0
              FROM nx_user u
             WHERE u.is_deleted = 0 AND UPPER(u.country_code) IN
             <foreach item="code" collection="countryCodes" open="(" separator="," close=")">
               UPPER(#{code})
             </foreach>
            ON DUPLICATE KEY UPDATE required_version = VALUES(required_version),
                                    acknowledged_version = NULL,
                                    ack_status = 'STALE',
                                    acknowledged_at = NULL,
                                    updated_at = VALUES(updated_at),
                                    is_deleted = 0
            </script>
            """)
    int markJurisdictionUsersStale(@Param("jurisdiction") String jurisdiction,
                                   @Param("countryCodes") java.util.List<String> countryCodes,
                                   @Param("version") String version,
                                   @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_disclosure_ack_status
               SET required_version = #{version}, acknowledged_version = #{version},
                   updated_at = #{now}
             WHERE jurisdiction_code = #{jurisdiction} AND ack_status = 'ACKED' AND is_deleted = 0
            """)
    int carryForwardAcknowledgedVersion(@Param("jurisdiction") String jurisdiction,
                                        @Param("version") String version,
                                        @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*) FROM nx_disclosure_ack_status
             WHERE jurisdiction_code = #{jurisdiction} AND is_deleted = 0
            """)
    long countAffected(@Param("jurisdiction") String jurisdiction);

    @Select("""
            SELECT COUNT(*) FROM nx_disclosure_ack_status
             WHERE jurisdiction_code = #{jurisdiction} AND ack_status = 'ACKED' AND is_deleted = 0
            """)
    long countAcknowledged(@Param("jurisdiction") String jurisdiction);

    @Select("SELECT UPPER(country_code) FROM nx_user WHERE id = #{userId} AND is_deleted = 0 LIMIT 1")
    String findUserCountryCode(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE((SELECT config_value FROM nx_config_item
                              WHERE config_key='growth.phase.current' AND status=1 AND is_deleted=0 LIMIT 1),'P1') phase,
                   GREATEST(TIMESTAMPDIFF(MONTH,u.created_at,NOW()),0) accountAgeMonths,
                   DATE_FORMAT(u.created_at,'%x-W%v') cohort
              FROM nx_user u WHERE u.id=#{userId} AND u.is_deleted=0 LIMIT 1
            """)
    UserAttribution userAttribution(@Param("userId") Long userId);

    @Select("""
            SELECT country FROM nx_kyc_profile
             WHERE user_id = #{userId} AND is_deleted = 0
               AND UPPER(status) IN ('APPROVED', 'VERIFIED', 'PASSED')
             LIMIT 1
            """)
    String findVerifiedKycCountry(@Param("userId") Long userId);

    @Select("""
            SELECT user_id AS userId, jurisdiction_code AS jurisdictionCode,
                   required_version AS requiredVersion, acknowledged_version AS acknowledgedVersion,
                   ack_status AS ackStatus, acknowledged_at AS acknowledgedAt
              FROM nx_disclosure_ack_status
             WHERE user_id = #{userId} AND jurisdiction_code = #{jurisdiction} AND is_deleted = 0
             LIMIT 1
            """)
    DisclosureAckStatusEntity findUserAck(@Param("userId") Long userId, @Param("jurisdiction") String jurisdiction);

    @Insert("""
            INSERT INTO nx_disclosure_ack_status
              (user_id, jurisdiction_code, required_version, acknowledged_version, ack_status,
               acknowledged_at, created_at, updated_at, is_deleted)
            VALUES (#{userId}, #{jurisdiction}, #{version}, #{version}, 'ACKED', #{now}, #{now}, #{now}, 0)
            ON DUPLICATE KEY UPDATE required_version = VALUES(required_version),
                                    acknowledged_version = VALUES(acknowledged_version),
                                    ack_status = 'ACKED', acknowledged_at = VALUES(acknowledged_at),
                                    updated_at = VALUES(updated_at), is_deleted = 0
            """)
    int acknowledge(@Param("userId") Long userId, @Param("jurisdiction") String jurisdiction,
                    @Param("version") String version, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_disclosure_jurisdiction
               SET blocked_count = COALESCE(blocked_count, 0) + 1, updated_at = #{now}
             WHERE jurisdiction_code = #{jurisdiction} AND is_deleted = 0
            """)
    int incrementBlocked(@Param("jurisdiction") String jurisdiction, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_disclosure_read_token
              (token_hash, user_id, jurisdiction_code, version_label, expires_at, created_at)
            VALUES (#{tokenHash}, #{userId}, #{jurisdiction}, #{version}, #{expiresAt}, #{now})
            ON DUPLICATE KEY UPDATE token_hash = VALUES(token_hash), expires_at = VALUES(expires_at),
                                    created_at = VALUES(created_at), consumed_at = NULL
            """)
    int insertReadToken(@Param("tokenHash") String tokenHash,
                        @Param("userId") Long userId,
                        @Param("jurisdiction") String jurisdiction,
                        @Param("version") String version,
                        @Param("expiresAt") LocalDateTime expiresAt,
                        @Param("now") LocalDateTime now);

    @org.apache.ibatis.annotations.Delete("""
            DELETE FROM nx_disclosure_read_token
             WHERE user_id = #{userId} AND (expires_at < #{now} OR consumed_at IS NOT NULL)
            """)
    int deleteExpiredReadTokens(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_disclosure_read_token
               SET consumed_at = #{now}
             WHERE token_hash = #{tokenHash} AND user_id = #{userId}
               AND jurisdiction_code = #{jurisdiction} AND version_label = #{version}
               AND consumed_at IS NULL AND expires_at >= #{now} AND created_at <= #{readBefore}
            """)
    int consumeReadToken(@Param("tokenHash") String tokenHash,
                         @Param("userId") Long userId,
                         @Param("jurisdiction") String jurisdiction,
                         @Param("version") String version,
                         @Param("now") LocalDateTime now,
                         @Param("readBefore") LocalDateTime readBefore);

    @Insert("""
            INSERT IGNORE INTO nx_disclosure_gate_block_event
              (user_id, jurisdiction_code, action_key, business_flow_id, blocked_at)
            VALUES (#{userId}, #{jurisdiction}, #{actionKey}, #{businessFlowId}, #{now})
            """)
    int recordBlockedIfAbsent(@Param("userId") Long userId,
                              @Param("jurisdiction") String jurisdiction,
                              @Param("actionKey") String actionKey,
                              @Param("businessFlowId") String businessFlowId,
                              @Param("now") LocalDateTime now);

    record UserAttribution(String phase, Integer accountAgeMonths, String cohort) {
    }
}
