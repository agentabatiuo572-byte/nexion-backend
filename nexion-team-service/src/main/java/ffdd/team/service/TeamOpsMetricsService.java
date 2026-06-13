package ffdd.team.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TeamOpsMetricsService {
    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> overview() {
        BigDecimal monthlyCommissionUsdt = decimal("""
                SELECT COALESCE(SUM(amount_usdt), 0)
                  FROM nx_commission_event
                 WHERE is_deleted = 0
                   AND status <> 'REJECTED'
                   AND created_at >= DATE_FORMAT(CURRENT_DATE, '%Y-%m-01')
                """);
        BigDecimal coolingLockedUsdt = decimal("""
                SELECT COALESCE(SUM(amount_usdt), 0)
                  FROM nx_commission_event
                 WHERE is_deleted = 0
                   AND status IN ('PENDING', 'FROZEN')
                """);
        BigDecimal coolingLockedNex = decimal("""
                SELECT COALESCE(SUM(amount_nex), 0)
                  FROM nx_commission_event
                 WHERE is_deleted = 0
                   AND status IN ('PENDING', 'FROZEN')
                """);
        long monthCommissionEvents = count("""
                SELECT COUNT(*)
                  FROM nx_commission_event
                 WHERE is_deleted = 0
                   AND status <> 'REJECTED'
                   AND created_at >= DATE_FORMAT(CURRENT_DATE, '%Y-%m-01')
                """);
        long paidOrderCount = count("""
                SELECT COUNT(*)
                  FROM nx_order
                 WHERE is_deleted = 0
                   AND (payment_status IN ('PAID', 'SUCCESS') OR order_status IN ('PAID', 'ACTIVE', 'COMPLETED'))
                   AND COALESCE(paid_at, created_at) >= DATE_FORMAT(CURRENT_DATE, '%Y-%m-01')
                """);
        long triggeredOrderCount = count("""
                SELECT COUNT(DISTINCT order_no)
                  FROM nx_commission_event
                 WHERE is_deleted = 0
                   AND order_no IS NOT NULL
                   AND order_no <> ''
                   AND status <> 'REJECTED'
                   AND created_at >= DATE_FORMAT(CURRENT_DATE, '%Y-%m-01')
                """);
        long activeV3PlusUsers = count("""
                SELECT COUNT(*)
                  FROM nx_user
                 WHERE is_deleted = 0
                   AND status = 'ACTIVE'
                   AND CAST(SUBSTRING(COALESCE(v_rank, 'V0'), 2) AS UNSIGNED) >= 3
                """);
        BigDecimal triggerRatePct = paidOrderCount <= 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(triggeredOrderCount)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(paidOrderCount), 2, RoundingMode.HALF_UP);
        BigDecimal triggerTargetPct = configDecimal("team.commission.trigger_target_pct", BigDecimal.valueOf(80));
        BigDecimal leadershipPoolRate = decimal("""
                SELECT COALESCE(MAX(usdt_rate), 0)
                  FROM nx_commission_rule
                 WHERE commission_type = 'LEADERSHIP'
                   AND status = 1
                   AND is_deleted = 0
                """);
        BigDecimal leadershipPlatformVolume = decimal("""
                SELECT COALESCE(SUM(source.order_amount_usd), 0)
                  FROM (
                        SELECT order_no, MAX(order_amount_usd) AS order_amount_usd
                          FROM nx_commission_event
                         WHERE is_deleted = 0
                           AND status <> 'REJECTED'
                           AND order_no IS NOT NULL
                           AND order_no <> ''
                           AND order_amount_usd IS NOT NULL
                           AND created_at >= DATE_SUB(CURRENT_DATE, INTERVAL WEEKDAY(CURRENT_DATE) DAY)
                         GROUP BY order_no
                       ) source
                """);
        BigDecimal currentWeekPoolUsdt = leadershipPlatformVolume.multiply(leadershipPoolRate)
                .setScale(6, RoundingMode.HALF_UP);
        long leadershipParticipantCount = count("""
                SELECT COUNT(*)
                  FROM nx_user u
                  JOIN nx_v_rank_config cfg
                    ON cfg.rank_code = u.v_rank
                   AND cfg.status = 1
                   AND cfg.is_deleted = 0
                 WHERE u.is_deleted = 0
                   AND u.status = 'ACTIVE'
                   AND cfg.leadership_votes > 0
                """);
        long totalLeadershipVotes = count("""
                SELECT COALESCE(SUM(cfg.leadership_votes), 0)
                  FROM nx_user u
                  JOIN nx_v_rank_config cfg
                    ON cfg.rank_code = u.v_rank
                   AND cfg.status = 1
                   AND cfg.is_deleted = 0
                 WHERE u.is_deleted = 0
                   AND u.status = 'ACTIVE'
                   AND cfg.leadership_votes > 0
                """);
        int settlementWeekday = configDecimal("team.leadership.settlement_weekday_utc", BigDecimal.valueOf(7)).intValue();
        int settlementHour = configDecimal("team.leadership.settlement_hour_utc", BigDecimal.valueOf(23)).intValue();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("monthlyCommissionUsdt", monthlyCommissionUsdt);
        summary.put("activeV3PlusUsers", activeV3PlusUsers);
        summary.put("commissionTriggerRatePct", triggerRatePct);
        summary.put("commissionTriggerTargetPct", triggerTargetPct);
        summary.put("coolingLockedUsdt", coolingLockedUsdt);
        summary.put("coolingLockedNex", coolingLockedNex);
        summary.put("monthCommissionEvents", monthCommissionEvents);
        summary.put("paidOrderCount", paidOrderCount);
        summary.put("triggeredOrderCount", triggeredOrderCount);
        summary.put("leadershipPoolRatePct", leadershipPoolRate.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP));
        summary.put("leadershipPlatformVolumeUsdt", leadershipPlatformVolume);
        summary.put("currentWeekPoolUsdt", currentWeekPoolUsdt);
        summary.put("leadershipParticipantCount", leadershipParticipantCount);
        summary.put("totalLeadershipVotes", totalLeadershipVotes);
        summary.put("leadershipSettlementWeekdayUtc", settlementWeekday);
        summary.put("leadershipSettlementHourUtc", settlementHour);
        summary.put("leadershipSettlementLabel", settlementLabel(settlementWeekday, settlementHour));
        return summary;
    }

    private BigDecimal configDecimal(String key, BigDecimal fallback) {
        try {
            BigDecimal value = jdbcTemplate.queryForObject("""
                    SELECT CAST(config_value AS DECIMAL(18,6))
                      FROM nx_config_item
                     WHERE config_key = ?
                       AND status = 1
                       AND is_deleted = 0
                     LIMIT 1
                    """, BigDecimal.class, key);
            return value == null ? fallback : value;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private BigDecimal decimal(String sql) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private String settlementLabel(int weekday, int hour) {
        String day = switch (weekday) {
            case 1 -> "周一";
            case 2 -> "周二";
            case 3 -> "周三";
            case 4 -> "周四";
            case 5 -> "周五";
            case 6 -> "周六";
            default -> "周日";
        };
        return day + " " + String.format("%02d:59 UTC", Math.max(0, Math.min(23, hour)));
    }
}
