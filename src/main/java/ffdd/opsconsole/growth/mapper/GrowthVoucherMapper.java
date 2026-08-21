package ffdd.opsconsole.growth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GrowthVoucherMapper extends BaseMapper<Object> {
    @Update("""
            CREATE TABLE IF NOT EXISTS nx_growth_voucher (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                voucher_id VARCHAR(80) NOT NULL,
                voucher_name VARCHAR(120) NOT NULL,
                voucher_type VARCHAR(20) NOT NULL,
                amount_usd DECIMAL(24,6) NOT NULL DEFAULT 0,
                percent_value DECIMAL(10,4) NOT NULL DEFAULT 0,
                min_purchase_usd DECIMAL(24,6) NOT NULL DEFAULT 0,
                max_discount_usd DECIMAL(24,6) NOT NULL DEFAULT 0,
                applicable_skus JSON NULL,
                audience VARCHAR(30) NOT NULL,
                start_at BIGINT NOT NULL DEFAULT 0,
                end_at BIGINT NOT NULL DEFAULT 0,
                claim_surfaces JSON NULL,
                popup_enabled TINYINT(1) NOT NULL DEFAULT 0,
                popup_delay_ms BIGINT NOT NULL DEFAULT 1300,
                popup_cooldown_hours BIGINT NOT NULL DEFAULT 24,
                popup_max_per_session BIGINT NOT NULL DEFAULT 1,
                popup_cadence_enabled TINYINT(1) NOT NULL DEFAULT 1,
                stack_with_trial TINYINT(1) NOT NULL DEFAULT 0,
                stack_with_others TINYINT(1) NOT NULL DEFAULT 0,
                splittable TINYINT(1) NOT NULL DEFAULT 0,
                issuance_limit BIGINT NOT NULL DEFAULT 0,
                version BIGINT NOT NULL DEFAULT 1,
                status VARCHAR(20) NOT NULL,
                created_by VARCHAR(80) NULL,
                updated_by VARCHAR(80) NULL,
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                is_deleted TINYINT(1) NOT NULL DEFAULT 0,
                UNIQUE KEY uk_nx_growth_voucher_id (voucher_id),
                KEY idx_nx_growth_voucher_status (status),
                KEY idx_nx_growth_voucher_deleted (is_deleted)
            )
            """)
    void ensureTable();

    @Select("""
            SELECT voucher_id AS id,
                   voucher_name AS name,
                   voucher_type AS type,
                   amount_usd AS amountUSD,
                   percent_value AS percent,
                   min_purchase_usd AS minPurchaseUSD,
                   max_discount_usd AS maxDiscountUSD,
                   COALESCE(JSON_EXTRACT(applicable_skus, '$'), JSON_ARRAY()) AS applicableSkusJson,
                   audience,
                   start_at AS startAt,
                   end_at AS endAt,
                   COALESCE(JSON_EXTRACT(claim_surfaces, '$'), JSON_ARRAY()) AS claimSurfacesJson,
                   popup_enabled AS popupEnabled,
                   popup_delay_ms AS popupDelayMs,
                   popup_cooldown_hours AS popupCooldownHours,
                   popup_max_per_session AS popupMaxPerSession,
                   popup_cadence_enabled AS popupCadenceEnabled,
                   stack_with_trial AS stackWithTrial,
                   stack_with_others AS stackWithOthers,
                   splittable,
                   issuance_limit AS issuanceLimit,
                   version,
                   (SELECT COUNT(1)
                      FROM nx_growth_voucher_grant g
                     WHERE g.voucher_id = nx_growth_voucher.voucher_id
                       AND g.is_deleted = 0) AS issuedCount,
                   (SELECT COUNT(1)
                      FROM nx_growth_voucher_grant g
                     WHERE g.voucher_id = nx_growth_voucher.voucher_id
                       AND g.status = 'AVAILABLE'
                       AND g.is_deleted = 0) AS availableCount,
                   (SELECT COUNT(1)
                      FROM nx_growth_voucher_grant g
                     WHERE g.voucher_id = nx_growth_voucher.voucher_id
                       AND g.status = 'USED'
                       AND g.is_deleted = 0) AS redeemedCount,
                   (SELECT COUNT(1)
                      FROM nx_growth_voucher_grant g
                     WHERE g.voucher_id = nx_growth_voucher.voucher_id
                       AND g.status = 'REVOKED'
                       AND g.is_deleted = 0) AS revokedCount,
                   (SELECT COUNT(DISTINCT CONCAT(g.source_type, ':', g.source_id))
                      FROM nx_growth_voucher_grant g
                     WHERE g.voucher_id = nx_growth_voucher.voucher_id
                       AND g.is_deleted = 0) AS batchCount,
                   status
              FROM nx_growth_voucher
             WHERE is_deleted = 0
             ORDER BY updated_at DESC, id DESC
            """)
    List<Map<String, Object>> listVouchers();

    @Select("SELECT COUNT(1) FROM nx_growth_voucher WHERE voucher_id = #{voucherId} AND is_deleted = 0")
    long countByVoucherId(@Param("voucherId") String voucherId);

    @Insert("""
            INSERT INTO nx_growth_voucher (
                voucher_id, voucher_name, voucher_type, amount_usd, percent_value,
                min_purchase_usd, max_discount_usd, applicable_skus, audience,
                start_at, end_at, claim_surfaces, popup_enabled, popup_delay_ms,
                popup_cooldown_hours, popup_max_per_session, popup_cadence_enabled, stack_with_trial,
                stack_with_others, splittable, issuance_limit, version,
                status, created_by, updated_by, is_deleted
            ) VALUES (
                #{voucherId}, #{name}, #{type}, #{amountUSD}, #{percent},
                #{minPurchaseUSD}, #{maxDiscountUSD}, CAST(#{applicableSkusJson} AS JSON), #{audience},
                #{startAt}, #{endAt}, CAST(#{claimSurfacesJson} AS JSON), #{popupEnabled}, #{popupDelayMs},
                #{popupCooldownHours}, #{popupMaxPerSession}, #{popupCadenceEnabled}, #{stackWithTrial},
                #{stackWithOthers}, #{splittable}, #{issuanceLimit}, 1,
                #{status}, #{operator}, #{operator}, 0
            )
            """)
    int insertVoucher(
            @Param("voucherId") String voucherId,
            @Param("name") String name,
            @Param("type") String type,
            @Param("amountUSD") BigDecimal amountUSD,
            @Param("percent") BigDecimal percent,
            @Param("minPurchaseUSD") BigDecimal minPurchaseUSD,
            @Param("maxDiscountUSD") BigDecimal maxDiscountUSD,
            @Param("applicableSkusJson") String applicableSkusJson,
            @Param("audience") String audience,
            @Param("startAt") long startAt,
            @Param("endAt") long endAt,
            @Param("claimSurfacesJson") String claimSurfacesJson,
            @Param("popupEnabled") boolean popupEnabled,
            @Param("popupDelayMs") long popupDelayMs,
            @Param("popupCooldownHours") long popupCooldownHours,
            @Param("popupMaxPerSession") long popupMaxPerSession,
            @Param("popupCadenceEnabled") boolean popupCadenceEnabled,
            @Param("stackWithTrial") boolean stackWithTrial,
            @Param("stackWithOthers") boolean stackWithOthers,
            @Param("splittable") boolean splittable,
            @Param("issuanceLimit") long issuanceLimit,
            @Param("status") String status,
            @Param("operator") String operator);

    @Update("""
            UPDATE nx_growth_voucher
               SET voucher_name = #{name},
                   voucher_type = #{type},
                   amount_usd = #{amountUSD},
                   percent_value = #{percent},
                   min_purchase_usd = #{minPurchaseUSD},
                   max_discount_usd = #{maxDiscountUSD},
                   applicable_skus = CAST(#{applicableSkusJson} AS JSON),
                   audience = #{audience},
                   start_at = #{startAt},
                   end_at = #{endAt},
                   claim_surfaces = CAST(#{claimSurfacesJson} AS JSON),
                   popup_enabled = #{popupEnabled},
                   popup_delay_ms = #{popupDelayMs},
                   popup_cooldown_hours = #{popupCooldownHours},
                   popup_max_per_session = #{popupMaxPerSession},
                   popup_cadence_enabled = #{popupCadenceEnabled},
                   stack_with_trial = #{stackWithTrial},
                   stack_with_others = #{stackWithOthers},
                   splittable = #{splittable},
                   issuance_limit = #{issuanceLimit},
                   status = #{status},
                   updated_by = #{operator},
                   version = version + 1
             WHERE voucher_id = #{voucherId}
               AND is_deleted = 0
               AND version = #{expectedVersion}
            """)
    int updateVoucher(
            @Param("voucherId") String voucherId,
            @Param("name") String name,
            @Param("type") String type,
            @Param("amountUSD") BigDecimal amountUSD,
            @Param("percent") BigDecimal percent,
            @Param("minPurchaseUSD") BigDecimal minPurchaseUSD,
            @Param("maxDiscountUSD") BigDecimal maxDiscountUSD,
            @Param("applicableSkusJson") String applicableSkusJson,
            @Param("audience") String audience,
            @Param("startAt") long startAt,
            @Param("endAt") long endAt,
            @Param("claimSurfacesJson") String claimSurfacesJson,
            @Param("popupEnabled") boolean popupEnabled,
            @Param("popupDelayMs") long popupDelayMs,
            @Param("popupCooldownHours") long popupCooldownHours,
            @Param("popupMaxPerSession") long popupMaxPerSession,
            @Param("popupCadenceEnabled") boolean popupCadenceEnabled,
            @Param("stackWithTrial") boolean stackWithTrial,
            @Param("stackWithOthers") boolean stackWithOthers,
            @Param("splittable") boolean splittable,
            @Param("issuanceLimit") long issuanceLimit,
            @Param("status") String status,
            @Param("expectedVersion") long expectedVersion,
            @Param("operator") String operator);

    @Update("""
            UPDATE nx_growth_voucher
               SET status = #{status},
                   updated_by = #{operator},
                   version = version + 1
             WHERE voucher_id = #{voucherId}
               AND is_deleted = 0
               AND version = #{expectedVersion}
            """)
    int updateStatus(
            @Param("voucherId") String voucherId,
            @Param("status") String status,
            @Param("expectedVersion") long expectedVersion,
            @Param("operator") String operator);

    @Update("""
            UPDATE nx_growth_voucher
               SET is_deleted = 1,
                   updated_by = #{operator},
                   version = version + 1
             WHERE voucher_id = #{voucherId}
               AND is_deleted = 0
               AND version = #{expectedVersion}
            """)
    int softDelete(
            @Param("voucherId") String voucherId,
            @Param("expectedVersion") long expectedVersion,
            @Param("operator") String operator);

    @Update("""
            UPDATE nx_growth_voucher_grant
               SET status = 'REVOKED',
                   updated_at = NOW()
             WHERE voucher_id = #{voucherId}
               AND status = 'AVAILABLE'
               AND is_deleted = 0
            """)
    int revokeAvailableGrants(@Param("voucherId") String voucherId);

    @Update("""
            UPDATE nx_growth_voucher
               SET version = version + 1,
                   updated_by = #{operator}
             WHERE voucher_id = #{voucherId}
               AND is_deleted = 0
               AND version = #{expectedVersion}
            """)
    int touchVersion(
            @Param("voucherId") String voucherId,
            @Param("expectedVersion") long expectedVersion,
            @Param("operator") String operator);
}
