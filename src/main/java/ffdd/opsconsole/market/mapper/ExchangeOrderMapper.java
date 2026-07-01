package ffdd.opsconsole.market.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.market.domain.ExchangeOrderView;
import ffdd.opsconsole.market.infrastructure.ExchangeOrderEntity;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ExchangeOrderMapper extends BaseMapper<ExchangeOrderEntity> {
    @Select("""
            SELECT COALESCE(SUM(CASE
                     WHEN UPPER(to_asset) = 'USDT' THEN to_amount
                     WHEN UPPER(from_asset) = 'USDT' THEN from_amount
                     ELSE 0
                   END), 0)
              FROM nx_exchange_order
             WHERE is_deleted = 0
               AND UPPER(status) IN ('COMPLETED', 'SUCCESS')
               AND created_at >= CURRENT_DATE
            """)
    BigDecimal todayCompletedUsdt();

    @Select("""
            SELECT COUNT(1)
              FROM nx_exchange_order
             WHERE is_deleted = 0
               AND UPPER(status) = 'QUEUED'
            """)
    long countQueued();

    @Select("""
            SELECT COUNT(1)
              FROM nx_exchange_order
             WHERE is_deleted = 0
            """)
    long countOrders();

    @Select("""
            SELECT COUNT(1)
              FROM nx_exchange_order
             WHERE is_deleted = 0
               AND created_at >= CURRENT_DATE
               AND UPPER(status) = UPPER(#{status})
            """)
    long countTodayByStatus(@Param("status") String status);

    @Select("""
            <script>
            SELECT eo.id,
                   eo.user_id AS userId,
                   CONCAT('U', LPAD(eo.user_id, 8, '0')) AS userNo,
                   COALESCE(u.nickname, CONCAT('user-', eo.user_id)) AS nickname,
                   COALESCE(u.country_code, '--') AS countryCode,
                   eo.exchange_no AS exchangeNo,
                   eo.from_asset AS fromAsset,
                   eo.to_asset AS toAsset,
                   eo.from_amount AS fromAmount,
                   eo.to_amount AS toAmount,
                   eo.rate,
                   UPPER(eo.status) AS status,
                   CASE UPPER(eo.status)
                     WHEN 'QUEUED' THEN '次日队列'
                     WHEN 'KYC_REQUIRED' THEN '需实名'
                     WHEN 'USER_CAP' THEN '单用户超限'
                     WHEN 'PLATFORM_CAP' THEN '平台超限'
                     WHEN 'GEO_BLOCKED' THEN '地域封锁'
                     WHEN 'CANCELLED' THEN '已取消'
                     WHEN 'COMPLETED' THEN '已成交'
                     WHEN 'SUCCESS' THEN '已成交'
                     ELSE UPPER(eo.status)
                   END AS statusLabel,
                   CASE UPPER(eo.status)
                     WHEN 'QUEUED' THEN 'warn'
                     WHEN 'KYC_REQUIRED' THEN 'warn'
                     WHEN 'USER_CAP' THEN 'warn'
                     WHEN 'PLATFORM_CAP' THEN 'danger'
                     WHEN 'GEO_BLOCKED' THEN 'danger'
                     WHEN 'CANCELLED' THEN 'dim'
                     WHEN 'COMPLETED' THEN 'ok'
                     WHEN 'SUCCESS' THEN 'ok'
                     ELSE 'dim'
                   END AS statusTone,
                   CONCAT(eo.from_asset, '→', eo.to_asset) AS directionLabel,
                   CASE
                     WHEN UPPER(eo.to_asset) = 'USDT' THEN eo.to_amount
                     WHEN UPPER(eo.from_asset) = 'USDT' THEN eo.from_amount
                     ELSE 0
                   END AS amountUsdt,
                   CASE UPPER(eo.status)
                     WHEN 'KYC_REQUIRED' THEN 'kyc'
                     WHEN 'USER_CAP' THEN 'user'
                     WHEN 'PLATFORM_CAP' THEN 'platform'
                     WHEN 'GEO_BLOCKED' THEN 'geo'
                     ELSE NULL
                   END AS gateType,
                   CASE UPPER(eo.status)
                     WHEN 'KYC_REQUIRED' THEN '累计兑换达到实名触发线'
                     WHEN 'USER_CAP' THEN '超过单用户日额度'
                     WHEN 'PLATFORM_CAP' THEN '超过平台日额度'
                     WHEN 'GEO_BLOCKED' THEN '命中 J2 地域封锁'
                     WHEN 'QUEUED' THEN '超 cap 排队等待次日处理'
                     ELSE NULL
                   END AS gateReason,
                   CASE UPPER(eo.status)
                     WHEN 'QUEUED' THEN '明天 00:00'
                     ELSE NULL
                   END AS etaLabel,
                   eo.created_at AS createdAt,
                   eo.updated_at AS updatedAt
              FROM nx_exchange_order eo
              LEFT JOIN nx_user u ON u.id = eo.user_id AND u.is_deleted = 0
             WHERE eo.is_deleted = 0
             <if test='statuses != null and statuses.size() > 0'>
               AND UPPER(eo.status) IN
               <foreach collection='statuses' item='status' open='(' separator=',' close=')'>UPPER(#{status})</foreach>
             </if>
             ORDER BY eo.created_at DESC, eo.id DESC
             LIMIT #{limit}
            </script>
            """)
    List<ExchangeOrderView> listByStatuses(@Param("statuses") List<String> statuses, @Param("limit") int limit);

    @Select("""
            SELECT eo.id,
                   eo.user_id AS userId,
                   CONCAT('U', LPAD(eo.user_id, 8, '0')) AS userNo,
                   COALESCE(u.nickname, CONCAT('user-', eo.user_id)) AS nickname,
                   COALESCE(u.country_code, '--') AS countryCode,
                   eo.exchange_no AS exchangeNo,
                   eo.from_asset AS fromAsset,
                   eo.to_asset AS toAsset,
                   eo.from_amount AS fromAmount,
                   eo.to_amount AS toAmount,
                   eo.rate,
                   UPPER(eo.status) AS status,
                   CASE UPPER(eo.status)
                     WHEN 'QUEUED' THEN '次日队列'
                     WHEN 'KYC_REQUIRED' THEN '需实名'
                     WHEN 'USER_CAP' THEN '单用户超限'
                     WHEN 'PLATFORM_CAP' THEN '平台超限'
                     WHEN 'GEO_BLOCKED' THEN '地域封锁'
                     WHEN 'CANCELLED' THEN '已取消'
                     WHEN 'COMPLETED' THEN '已成交'
                     WHEN 'SUCCESS' THEN '已成交'
                     ELSE UPPER(eo.status)
                   END AS statusLabel,
                   CASE UPPER(eo.status)
                     WHEN 'QUEUED' THEN 'warn'
                     WHEN 'KYC_REQUIRED' THEN 'warn'
                     WHEN 'USER_CAP' THEN 'warn'
                     WHEN 'PLATFORM_CAP' THEN 'danger'
                     WHEN 'GEO_BLOCKED' THEN 'danger'
                     WHEN 'CANCELLED' THEN 'dim'
                     WHEN 'COMPLETED' THEN 'ok'
                     WHEN 'SUCCESS' THEN 'ok'
                     ELSE 'dim'
                   END AS statusTone,
                   CONCAT(eo.from_asset, '→', eo.to_asset) AS directionLabel,
                   CASE
                     WHEN UPPER(eo.to_asset) = 'USDT' THEN eo.to_amount
                     WHEN UPPER(eo.from_asset) = 'USDT' THEN eo.from_amount
                     ELSE 0
                   END AS amountUsdt,
                   CASE UPPER(eo.status)
                     WHEN 'KYC_REQUIRED' THEN 'kyc'
                     WHEN 'USER_CAP' THEN 'user'
                     WHEN 'PLATFORM_CAP' THEN 'platform'
                     WHEN 'GEO_BLOCKED' THEN 'geo'
                     ELSE NULL
                   END AS gateType,
                   CASE UPPER(eo.status)
                     WHEN 'KYC_REQUIRED' THEN '累计兑换达到实名触发线'
                     WHEN 'USER_CAP' THEN '超过单用户日额度'
                     WHEN 'PLATFORM_CAP' THEN '超过平台日额度'
                     WHEN 'GEO_BLOCKED' THEN '命中 J2 地域封锁'
                     WHEN 'QUEUED' THEN '超 cap 排队等待次日处理'
                     ELSE NULL
                   END AS gateReason,
                   CASE UPPER(eo.status)
                     WHEN 'QUEUED' THEN '明天 00:00'
                     ELSE NULL
                   END AS etaLabel,
                   eo.created_at AS createdAt,
                   eo.updated_at AS updatedAt
              FROM nx_exchange_order eo
              LEFT JOIN nx_user u ON u.id = eo.user_id AND u.is_deleted = 0
             WHERE eo.exchange_no = #{exchangeNo}
               AND eo.is_deleted = 0
             LIMIT 1
            """)
    ExchangeOrderView findByExchangeNo(@Param("exchangeNo") String exchangeNo);

    @Update("""
            UPDATE nx_exchange_order
               SET status = #{status},
                   updated_at = CURRENT_TIMESTAMP
             WHERE exchange_no = #{exchangeNo}
               AND is_deleted = 0
            """)
    int updateStatus(@Param("exchangeNo") String exchangeNo, @Param("status") String status);

    @Update("""
            <script>
            UPDATE nx_exchange_order
               SET status = #{status},
                   updated_at = CURRENT_TIMESTAMP
             WHERE exchange_no = #{exchangeNo}
               AND UPPER(status) IN
               <foreach collection="currentStatuses" item="currentStatus" open="(" separator="," close=")">
                 #{currentStatus}
               </foreach>
               AND is_deleted = 0
            </script>
            """)
    int updateStatusIfCurrent(
            @Param("exchangeNo") String exchangeNo,
            @Param("status") String status,
            @Param("currentStatuses") List<String> currentStatuses);

    @Update("""
            UPDATE nx_exchange_order
               SET status = 'CANCELLED',
                   updated_at = CURRENT_TIMESTAMP
             WHERE exchange_no = #{exchangeNo}
               AND UPPER(status) = 'QUEUED'
               AND is_deleted = 0
            """)
    int cancelQueued(@Param("exchangeNo") String exchangeNo);
}
