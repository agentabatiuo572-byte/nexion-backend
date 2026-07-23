package ffdd.opsconsole.growth.mapper;

import java.math.BigDecimal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Row-locking boundary for user voucher redemption. */
@Mapper
// Statement-only voucher lifecycle boundary; it has no single CRUD aggregate entity.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface AppGrowthLifecycleMapper {

    @Select("""
            SELECT g.grant_id AS grantId,
                   g.voucher_id AS voucherId,
                   v.voucher_type AS voucherType,
                   v.amount_usd AS amountUsd,
                   v.percent_value AS percentValue,
                   v.min_purchase_usd AS minPurchaseUsd,
                   v.max_discount_usd AS maxDiscountUsd
              FROM nx_growth_voucher_grant g
              JOIN nx_growth_voucher v
                ON v.voucher_id = g.voucher_id
               AND v.is_deleted = 0
               AND LOWER(v.status) = 'active'
             WHERE g.user_id = #{userId}
               AND g.voucher_id = #{voucherId}
               AND g.status = 'AVAILABLE'
               AND g.is_deleted = 0
               AND (v.start_at = 0 OR v.start_at <= #{nowMillis})
               AND (v.end_at = 0 OR v.end_at >= #{nowMillis})
               AND (v.applicable_skus IS NULL OR JSON_LENGTH(v.applicable_skus) = 0
                    OR JSON_CONTAINS(v.applicable_skus, JSON_QUOTE(#{productNo})))
             ORDER BY g.granted_at ASC, g.id ASC
             LIMIT 1 FOR UPDATE
            """)
    VoucherRedemptionRow lockAvailableVoucher(
            @Param("userId") Long userId,
            @Param("voucherId") String voucherId,
            @Param("productNo") String productNo,
            @Param("nowMillis") long nowMillis);

    @Update("""
            UPDATE nx_growth_voucher_grant
               SET status = 'USED', used_order_no = #{orderNo}, used_at = NOW(), updated_at = NOW()
             WHERE grant_id = #{grantId}
               AND user_id = #{userId}
               AND status = 'AVAILABLE'
               AND is_deleted = 0
            """)
    int markVoucherUsed(
            @Param("grantId") String grantId,
            @Param("userId") Long userId,
            @Param("orderNo") String orderNo);

    record VoucherRedemptionRow(
            String grantId,
            String voucherId,
            String voucherType,
            BigDecimal amountUsd,
            BigDecimal percentValue,
            BigDecimal minPurchaseUsd,
            BigDecimal maxDiscountUsd) {
    }
}
