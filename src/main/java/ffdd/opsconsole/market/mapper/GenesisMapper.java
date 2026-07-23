package ffdd.opsconsole.market.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.market.domain.GenesisNodeView;
import ffdd.opsconsole.market.domain.GenesisPolicyView;
import ffdd.opsconsole.market.domain.GenesisSecondaryStatsView;
import ffdd.opsconsole.market.domain.GenesisSeriesView;
import ffdd.opsconsole.market.infrastructure.GenesisSeriesEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface GenesisMapper extends BaseMapper<GenesisSeriesEntity> {
    @Select("""
            SELECT COUNT(1)
              FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'nx_genesis_series'
               AND COLUMN_NAME = #{columnName}
            """)
    int genesisSeriesColumnCount(@Param("columnName") String columnName);

    @Update("ALTER TABLE nx_genesis_series ADD COLUMN daily_dividend_rate_pct DECIMAL(10,6) NOT NULL DEFAULT 0 AFTER royalty_bps")
    void addDailyDividendRateColumn();

    @Update("ALTER TABLE nx_genesis_series ADD COLUMN dividend_base_formula VARCHAR(255) NOT NULL DEFAULT '' AFTER daily_dividend_rate_pct")
    void addDividendBaseFormulaColumn();

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
            SELECT total_supply AS totalSupply,
                   sold_supply AS soldSupply,
                   price_usdt AS priceUsdt,
                   daily_dividend_rate_pct AS dailyDividendRatePct,
                   royalty_bps / 100 AS royaltyPct,
                   dividend_base_formula AS dividendBaseFormula
              FROM nx_genesis_series
             WHERE is_deleted = 0
               AND UPPER(status) = 'ACTIVE'
             ORDER BY id DESC
             LIMIT 1
            """)
    GenesisPolicyView activePolicy();

    @Update("""
            UPDATE nx_genesis_series
               SET total_supply = #{totalSupply},
                   updated_at = NOW()
             WHERE is_deleted = 0
               AND UPPER(status) = 'ACTIVE'
             ORDER BY id DESC
             LIMIT 1
            """)
    int updateActiveTotalSupply(@Param("totalSupply") int totalSupply);

    @Update("""
            UPDATE nx_genesis_series
               SET price_usdt = #{priceUsdt},
                   updated_at = NOW()
             WHERE is_deleted = 0
               AND UPPER(status) = 'ACTIVE'
             ORDER BY id DESC
             LIMIT 1
            """)
    int updateActivePrice(@Param("priceUsdt") BigDecimal priceUsdt);

    @Update("""
            UPDATE nx_genesis_series
               SET daily_dividend_rate_pct = #{dailyDividendRatePct},
                   updated_at = NOW()
             WHERE is_deleted = 0
               AND UPPER(status) = 'ACTIVE'
             ORDER BY id DESC
             LIMIT 1
            """)
    int updateActiveDailyDividendRate(@Param("dailyDividendRatePct") BigDecimal dailyDividendRatePct);

    @Update("""
            UPDATE nx_genesis_series
               SET royalty_bps = #{royaltyBps},
                   updated_at = NOW()
             WHERE is_deleted = 0
               AND UPPER(status) = 'ACTIVE'
             ORDER BY id DESC
             LIMIT 1
            """)
    int updateActiveRoyaltyBps(@Param("royaltyBps") int royaltyBps);

    @Update("""
            UPDATE nx_genesis_series
               SET dividend_base_formula = #{formula},
                   updated_at = NOW()
             WHERE is_deleted = 0
               AND UPPER(status) = 'ACTIVE'
             ORDER BY id DESC
             LIMIT 1
            """)
    int updateActiveDividendBaseFormula(@Param("formula") String formula);

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
            SELECT COALESCE(SUM(amount), 0)
              FROM nx_wallet_ledger
             WHERE is_deleted = 0
               AND asset = 'USDT'
               AND direction = 'IN'
               AND status IN ('SUCCESS', 'PENDING')
               AND biz_type = 'GENESIS_DIVIDEND'
            """)
    BigDecimal genesisAccrualUsd();

    @Select("""
            SELECT biz_no
              FROM nx_wallet_ledger
             WHERE is_deleted = 0
               AND biz_type = 'GENESIS_DIVIDEND'
               AND biz_no LIKE 'G4-DIVIDEND-%-RERUN'
             ORDER BY created_at DESC, id DESC
             LIMIT 1
            """)
    String latestGenesisDividendRerunBizNo();

    @Select("""
            SELECT
              (SELECT COUNT(1) FROM nx_wallet_ledger
                WHERE is_deleted=0 AND biz_type='GENESIS_DIVIDEND'
                  AND biz_no=CONCAT('G4-DIVIDEND-',#{batchNo},'-RERUN'))
              +
              (SELECT COUNT(1) FROM nx_genesis_emission_batch
                WHERE is_deleted=0 AND batch_no=#{batchNo} AND UPPER(status)='COMPLETED')
            """)
    long countGenesisDividendRerun(@Param("batchNo") String batchNo);

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
