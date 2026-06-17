package ffdd.opsconsole.market.infrastructure;

import ffdd.opsconsole.market.domain.NexMarketRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNexMarketRepository implements NexMarketRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcNexMarketRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<BigDecimal> latestNexUsdtPrice() {
        List<BigDecimal> rows = jdbcTemplate.query(
                """
                SELECT price_usdt
                FROM nx_price_index
                WHERE is_deleted=0 AND status='ACTIVE' AND metric_code IN ('NEX','NEX_USDT')
                ORDER BY sampled_at DESC,id DESC
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getBigDecimal("price_usdt"));
        return rows.stream().findFirst();
    }

    @Override
    public void publishNexUsdtPrice(BigDecimal priceUsdt, BigDecimal deltaPercent, String sparklineJson, LocalDateTime sampledAt) {
        jdbcTemplate.update(
                """
                INSERT INTO nx_price_index(
                  metric_code,metric_label,unit_label,price_usdt,delta_percent,volume_24h_usdt,sparkline,status,sampled_at,is_deleted
                ) VALUES('NEX_USDT','NEX / USDT','per NEX',?,?,0,CAST(? AS JSON),'ACTIVE',?,0)
                """,
                priceUsdt.setScale(8, RoundingMode.HALF_UP),
                deltaPercent.setScale(4, RoundingMode.HALF_UP),
                sparklineJson,
                sampledAt);
    }
}
