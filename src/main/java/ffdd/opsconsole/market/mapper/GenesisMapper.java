package ffdd.opsconsole.market.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.market.domain.GenesisNodeView;
import ffdd.opsconsole.market.domain.GenesisSecondaryStatsView;
import ffdd.opsconsole.market.domain.GenesisSeriesView;
import ffdd.opsconsole.market.infrastructure.GenesisSeriesEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface GenesisMapper extends BaseMapper<GenesisSeriesEntity> {
    @Select("""
            SELECT COUNT(1)
              FROM nx_genesis_series
             WHERE is_deleted = 0
               AND UPPER(status) = 'ACTIVE'
            """)
    long countActiveSeries();

    @Select("""
            SELECT COUNT(1)
              FROM nx_genesis_holding
             WHERE is_deleted = 0
            """)
    long countHoldings();

    @Select("""
            SELECT id,
                   series_code AS seriesCode,
                   name,
                   total_supply AS totalSupply,
                   sold_supply AS soldSupply,
                   price_usdt AS priceUsdt,
                   royalty_bps AS royaltyBps,
                   status
              FROM nx_genesis_series
             WHERE is_deleted = 0
               AND UPPER(status) = 'ACTIVE'
             ORDER BY id DESC
             LIMIT 1
            """)
    GenesisSeriesView activeSeries();

    @Select("""
            SELECT COALESCE(MIN(CASE WHEN UPPER(status) = 'LISTED' THEN acquired_price_usdt END), 0) AS floorUsdt,
                   (
                     SELECT COALESCE(SUM(o.amount_usdt), 0)
                       FROM nx_genesis_order o
                      WHERE o.is_deleted = 0
                        AND UPPER(o.status) IN ('COMPLETED', 'PAID', 'SUCCESS')
                        AND o.completed_at >= #{since}
                   ) AS volume24hUsdt,
                   COALESCE(SUM(CASE WHEN UPPER(status) = 'LISTED' THEN 1 ELSE 0 END), 0) AS listedCount,
                   COALESCE(COUNT(DISTINCT user_id), 0) AS ownerCount
              FROM nx_genesis_holding
             WHERE is_deleted = 0
            """)
    GenesisSecondaryStatsView secondaryStats(@Param("since") LocalDateTime since);

    @Select("""
            SELECT h.id,
                   h.user_id AS userId,
                   CONCAT('U', LPAD(h.user_id, 8, '0')) AS userNo,
                   CASE
                     WHEN u.referral_code IS NOT NULL AND u.referral_code <> '' THEN u.referral_code
                     ELSE CONCAT('usr_', RIGHT(UPPER(HEX(h.user_id)), 4))
                   END AS ownerCode,
                   h.holding_no AS holdingNo,
                   h.series_code AS seriesCode,
                   h.acquired_price_usdt AS acquiredPriceUsdt,
                   CASE
                     WHEN h.order_no LIKE '%SECONDARY%' THEN CONCAT('secondary transfer ', DATE_FORMAT(h.acquired_at, '%m-%d'))
                     ELSE 'primary'
                   END AS sourceLabel,
                   UPPER(h.status) AS status,
                   CASE UPPER(h.status)
                     WHEN 'LISTED' THEN '二级挂单中'
                     WHEN 'SOLD' THEN '已转让'
                     WHEN 'ACTIVE' THEN '持有计分红'
                     ELSE UPPER(h.status)
                   END AS statusLabel,
                   CASE UPPER(h.status)
                     WHEN 'LISTED' THEN 'dim'
                     WHEN 'SOLD' THEN 'dim'
                     WHEN 'ACTIVE' THEN 'ok'
                     ELSE 'warn'
                   END AS statusTone,
                   h.acquired_at AS acquiredAt,
                   h.updated_at AS updatedAt
             FROM nx_genesis_holding h
              LEFT JOIN nx_user u ON u.id = h.user_id AND u.is_deleted = 0
             WHERE h.is_deleted = 0
             ORDER BY h.holding_no ASC, h.id ASC
             LIMIT #{limit}
             OFFSET #{offset}
            """)
    List<GenesisNodeView> listNodes(@Param("offset") int offset, @Param("limit") int limit);

}
