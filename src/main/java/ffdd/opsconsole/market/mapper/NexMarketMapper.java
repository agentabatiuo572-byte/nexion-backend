package ffdd.opsconsole.market.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.market.domain.NexPricePointView;
import ffdd.opsconsole.market.infrastructure.PriceIndexEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface NexMarketMapper extends BaseMapper<PriceIndexEntity> {
    @Select("""
            SELECT COUNT(*)
              FROM nx_price_index
             WHERE is_deleted = 0
               AND status = 'ACTIVE'
               AND metric_code IN ('NEX','NEX_USDT')
            """)
    int countActiveNexUsdtPrices();

    @Select("""
            SELECT price_usdt
              FROM nx_price_index
             WHERE is_deleted = 0
               AND status = 'ACTIVE'
               AND metric_code IN ('NEX','NEX_USDT')
             ORDER BY sampled_at DESC, id DESC
             LIMIT 1
            """)
    BigDecimal latestNexUsdtPrice();

    @Select("""
            SELECT CAST(sparkline AS CHAR)
              FROM nx_price_index
             WHERE is_deleted = 0
               AND status = 'ACTIVE'
               AND metric_code IN ('NEX','NEX_USDT')
             ORDER BY sampled_at DESC, id DESC
             LIMIT 1
            """)
    String latestNexSparkline();

    @Select("""
            SELECT price_usdt AS priceUsdt,
                   delta_percent AS deltaPercent,
                   sampled_at AS sampledAt
              FROM nx_price_index
             WHERE is_deleted = 0
               AND status = 'ACTIVE'
               AND metric_code IN ('NEX','NEX_USDT')
             ORDER BY sampled_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<NexPricePointView> latestNexPricePoints(@Param("limit") int limit);

    @Insert("""
            INSERT INTO nx_price_index(
              metric_code, metric_label, unit_label, price_usdt, delta_percent,
              volume_24h_usdt, sparkline, status, sampled_at, is_deleted
            ) VALUES(
              'NEX_USDT', 'NEX / USDT', 'per NEX', #{priceUsdt}, #{deltaPercent},
              0, CAST(#{sparklineJson} AS JSON), 'ACTIVE', #{sampledAt}, 0
            )
            """)
    int insertNexUsdtPrice(@Param("priceUsdt") BigDecimal priceUsdt,
                           @Param("deltaPercent") BigDecimal deltaPercent,
                           @Param("sparklineJson") String sparklineJson,
                           @Param("sampledAt") LocalDateTime sampledAt);
}
