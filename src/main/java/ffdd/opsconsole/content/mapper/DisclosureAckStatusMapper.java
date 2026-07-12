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
}
