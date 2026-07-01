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
                stack_with_trial TINYINT(1) NOT NULL DEFAULT 0,
                stack_with_others TINYINT(1) NOT NULL DEFAULT 0,
                splittable TINYINT(1) NOT NULL DEFAULT 0,
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
                   stack_with_trial AS stackWithTrial,
                   stack_with_others AS stackWithOthers,
                   splittable,
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
                start_at, end_at, claim_surfaces, popup_enabled, stack_with_trial,
                stack_with_others, splittable, status, created_by, updated_by, is_deleted
            ) VALUES (
                #{voucherId}, #{name}, #{type}, #{amountUSD}, #{percent},
                #{minPurchaseUSD}, #{maxDiscountUSD}, CAST(#{applicableSkusJson} AS JSON), #{audience},
                #{startAt}, #{endAt}, CAST(#{claimSurfacesJson} AS JSON), #{popupEnabled}, #{stackWithTrial},
                #{stackWithOthers}, #{splittable}, #{status}, #{operator}, #{operator}, 0
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
            @Param("stackWithTrial") boolean stackWithTrial,
            @Param("stackWithOthers") boolean stackWithOthers,
            @Param("splittable") boolean splittable,
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
                   stack_with_trial = #{stackWithTrial},
                   stack_with_others = #{stackWithOthers},
                   splittable = #{splittable},
                   status = #{status},
                   updated_by = #{operator}
             WHERE voucher_id = #{voucherId}
               AND is_deleted = 0
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
            @Param("stackWithTrial") boolean stackWithTrial,
            @Param("stackWithOthers") boolean stackWithOthers,
            @Param("splittable") boolean splittable,
            @Param("status") String status,
            @Param("operator") String operator);

    @Update("""
            UPDATE nx_growth_voucher
               SET status = #{status},
                   updated_by = #{operator}
             WHERE voucher_id = #{voucherId}
               AND is_deleted = 0
            """)
    int updateStatus(@Param("voucherId") String voucherId, @Param("status") String status, @Param("operator") String operator);

    @Update("""
            UPDATE nx_growth_voucher
               SET is_deleted = 1,
                   updated_by = #{operator}
             WHERE voucher_id = #{voucherId}
               AND is_deleted = 0
            """)
    int softDelete(@Param("voucherId") String voucherId, @Param("operator") String operator);
}
