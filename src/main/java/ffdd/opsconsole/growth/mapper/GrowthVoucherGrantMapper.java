package ffdd.opsconsole.growth.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
// Statement-only voucher grant boundary; explicit SQL owns the cross-table lifecycle.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface GrowthVoucherGrantMapper {

    @Update("""
            CREATE TABLE IF NOT EXISTS nx_growth_voucher_grant (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                grant_id VARCHAR(96) NOT NULL,
                grant_key VARCHAR(160) NOT NULL,
                voucher_id VARCHAR(80) NOT NULL,
                user_id BIGINT NOT NULL,
                source_type VARCHAR(32) NOT NULL,
                source_id VARCHAR(96) NOT NULL,
                status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
                used_order_no VARCHAR(96) NULL,
                operator VARCHAR(80) NOT NULL,
                reason VARCHAR(200) NOT NULL,
                granted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                used_at DATETIME NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                is_deleted TINYINT(1) NOT NULL DEFAULT 0,
                UNIQUE KEY uk_nx_growth_voucher_grant_id (grant_id),
                UNIQUE KEY uk_nx_growth_voucher_grant_key (grant_key),
                UNIQUE KEY uk_nx_growth_voucher_grant_source (voucher_id, user_id, source_type, source_id),
                KEY idx_nx_growth_voucher_grant_user (user_id, status),
                KEY idx_nx_growth_voucher_grant_voucher (voucher_id, status)
            )
            """)
    void ensureGrantTable();

    @Select("""
            SELECT id
              FROM nx_user
             WHERE id = #{userId} AND is_deleted = 0 AND status = 'ACTIVE'
             LIMIT 1 FOR UPDATE
            """)
    Long lockActiveUser(@Param("userId") Long userId);

    @Select("""
            SELECT voucher_id AS voucherId, status, start_at AS startAt, end_at AS endAt
              FROM nx_growth_voucher
             WHERE voucher_id = #{voucherId}
               AND is_deleted = 0
               AND status = 'active'
               AND (start_at = 0 OR start_at <= #{nowMillis})
               AND (end_at = 0 OR end_at >= #{nowMillis})
             LIMIT 1 FOR UPDATE
            """)
    Map<String, Object> lockGrantableVoucher(
            @Param("voucherId") String voucherId,
            @Param("nowMillis") long nowMillis);

    @Insert("""
            INSERT IGNORE INTO nx_growth_voucher_grant (
                grant_id, grant_key, voucher_id, user_id, source_type, source_id,
                status, operator, reason, is_deleted
            ) VALUES (
                #{grantId}, #{grantKey}, #{voucherId}, #{userId}, #{sourceType}, #{sourceId},
                'AVAILABLE', #{operator}, #{reason}, 0
            )
            """)
    int insertGrant(
            @Param("grantId") String grantId,
            @Param("grantKey") String grantKey,
            @Param("voucherId") String voucherId,
            @Param("userId") Long userId,
            @Param("sourceType") String sourceType,
            @Param("sourceId") String sourceId,
            @Param("operator") String operator,
            @Param("reason") String reason);

    @Select("""
            SELECT grant_id AS grantId, grant_key AS grantKey, voucher_id AS voucherId,
                   user_id AS userId, source_type AS sourceType, source_id AS sourceId, status
              FROM nx_growth_voucher_grant
             WHERE grant_key = #{grantKey} AND is_deleted = 0
             LIMIT 1
            """)
    Map<String, Object> findByGrantKey(@Param("grantKey") String grantKey);
}
