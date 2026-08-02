package ffdd.opsconsole.bi.application;

import ffdd.opsconsole.bi.mapper.L3FinanceFactMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.treasury.facade.TreasuryL3FinanceFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class L3FinanceReportService {
    private static final Set<String> PERIODS = Set.of("day", "week", "month", "quarter", "custom");
    private final L3FinanceFactMapper mapper;
    private final Clock clock;
    private final TreasuryL3FinanceFacade treasuryL3FinanceFacade;

    public ApiResult<Map<String, Object>> treasurySnapshot() {
        return ApiResult.ok(treasuryL3FinanceFacade.currentL3FinanceSnapshot());
    }

    public ApiResult<Map<String, Object>> revenue(String period, String from, String to) {
        PeriodWindow window = periodWindow(period, from, to);
        List<RevenueFact> current = revenueFacts(window.from(), window.to());
        List<RevenueFact> previous = revenueFacts(window.previousFrom(), window.previousTo());
        BigDecimal total = current.stream().map(RevenueFact::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String, Object>> streams = new ArrayList<>();
        for (int index = 0; index < current.size(); index++) {
            RevenueFact fact = current.get(index);
            BigDecimal previousAmount = previous.get(index).amount();
            Map<String, Object> row = linked(
                    "stream", fact.key(),
                    "label", fact.label(),
                    "source", fact.source(),
                    "amountUsdt", money(fact.amount()),
                    "previousAmountUsdt", money(previousAmount));
            row.put("share", total.signum() == 0 ? null : pct(fact.amount(), total));
            row.put("momDelta", previousAmount.signum() == 0 ? null
                    : fact.amount().subtract(previousAmount).multiply(BigDecimal.valueOf(100))
                    .divide(previousAmount, 1, RoundingMode.HALF_UP));
            streams.add(row);
        }
        return ApiResult.ok(linked(
                "module", "L3",
                "period", window.view(),
                "totalUsdt", money(total),
                "streams", streams,
                "breakdown", "stream",
                "serverAuthoritative", true,
                "sources", List.of("设备订单", "团队佣金账本", "代币兑换订单", "算力服务费账本")));
    }

    public ApiResult<Map<String, Object>> redemption(String period, String from, String to, String cohort) {
        PeriodWindow window = periodWindow(period, from, to);
        String normalizedCohort = normalizeCohort(cohort);
        Map<String, Object> current = mapper.redemptionSummary(window.from(), window.to(), normalizedCohort);
        Map<String, Object> previous = mapper.redemptionSummary(window.previousFrom(), window.previousTo(), normalizedCohort);
        long submitted = whole(current, "submitted");
        long confirmed = whole(current, "confirmed");
        long previousSubmitted = whole(previous, "submitted");
        long previousConfirmed = whole(previous, "confirmed");
        BigDecimal averageLatency = decimalOrNull(current, "avgLatencyHours");
        long rejected = whole(current, "rejected");
        long delayed = whole(current, "delayedCount");
        long frozen = whole(current, "frozen");
        Map<String, Object> response = linked(
                "module", "L3",
                "period", window.view(),
                "cohort", normalizedCohort == null ? "全部" : normalizedCohort,
                "submitted", submitted,
                "confirmed", confirmed,
                "rejected", rejected,
                "delayed", delayed,
                "frozen", frozen,
                "averageLatencyHours", averageLatency == null ? null : averageLatency.setScale(2, RoundingMode.HALF_UP),
                "redemptionRate", rate(confirmed, submitted),
                "previousRate", rate(previousConfirmed, previousSubmitted),
                "previousLabel", window.previousLabel(),
                "serverAuthoritative", true,
                "source", "A4 服务端权威提现生命周期事件");
        return ApiResult.ok(response);
    }

    private List<RevenueFact> revenueFacts(LocalDateTime from, LocalDateTime to) {
        return List.of(
                new RevenueFact("device_sales", "设备销售 GMV", "设备订单",
                        requireRevenue(mapper.sumDeviceSalesGmv(from, to))),
                new RevenueFact("team_commission", "团队分润佣金", "团队佣金账本",
                        requireRevenue(mapper.sumTeamCommission(from, to))),
                new RevenueFact("token_economy", "代币经济", "代币兑换订单",
                        requireRevenue(mapper.sumTokenEconomyVolume(from, to))),
                new RevenueFact("compute_matching", "算力撮合服务费", "算力服务费账本",
                        requireRevenue(mapper.sumComputeMatchingFees(from, to))));
    }

    private PeriodWindow periodWindow(String rawPeriod, String rawFrom, String rawTo) {
        String period = StringUtils.hasText(rawPeriod) ? rawPeriod.trim().toLowerCase(Locale.ROOT) : "month";
        if (!PERIODS.contains(period)) throw new BizException(400, "L3_PERIOD_INVALID");
        LocalDate today = LocalDate.now(clock);
        LocalDate start;
        LocalDate endExclusive;
        switch (period) {
            case "day" -> {
                start = today;
                endExclusive = start.plusDays(1);
            }
            case "week" -> {
                start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                endExclusive = start.plusWeeks(1);
            }
            case "quarter" -> {
                int firstMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
                start = LocalDate.of(today.getYear(), firstMonth, 1);
                endExclusive = start.plusMonths(3);
            }
            case "custom" -> {
                start = parseDate(rawFrom, "L3_CUSTOM_FROM_REQUIRED");
                LocalDate inclusiveEnd = parseDate(rawTo, "L3_CUSTOM_TO_REQUIRED");
                if (inclusiveEnd.isBefore(start) || inclusiveEnd.isAfter(start.plusYears(1))) {
                    throw new BizException(400, "L3_CUSTOM_RANGE_INVALID");
                }
                endExclusive = inclusiveEnd.plusDays(1);
            }
            default -> {
                start = today.withDayOfMonth(1);
                endExclusive = start.plusMonths(1);
            }
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, endExclusive);
        LocalDate previousStart = switch (period) {
            case "day" -> start.minusDays(1);
            case "week" -> start.minusWeeks(1);
            case "month" -> start.minusMonths(1);
            case "quarter" -> start.minusMonths(3);
            default -> start.minusDays(days);
        };
        LocalDate previousEnd = start;
        String label = periodLabel(period, start, endExclusive.minusDays(1));
        String previousLabel = periodLabel(period, previousStart, previousEnd.minusDays(1));
        return new PeriodWindow(
                period, start.atStartOfDay(), endExclusive.atStartOfDay(),
                previousStart.atStartOfDay(), previousEnd.atStartOfDay(), label, previousLabel,
                clock.getZone().getId());
    }

    private String periodLabel(String period, LocalDate from, LocalDate to) {
        return switch (period) {
            case "day" -> from.toString();
            case "week" -> from + " 至 " + to;
            case "month" -> from.toString().substring(0, 7);
            case "quarter" -> from.getYear() + " Q" + (((from.getMonthValue() - 1) / 3) + 1);
            default -> from + " 至 " + to;
        };
    }

    private LocalDate parseDate(String value, String code) {
        if (!StringUtils.hasText(value)) throw new BizException(400, code);
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException invalid) {
            throw new BizException(400, code);
        }
    }

    private String normalizeCohort(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim();
        if (!normalized.matches("\\d{4}-(0[1-9]|1[0-2])")) throw new BizException(400, "L3_COHORT_INVALID");
        return normalized;
    }

    private BigDecimal rate(long numerator, long denominator) {
        if (denominator == 0) return null;
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
    }

    private long whole(Map<String, Object> values, String key) {
        Object value = value(values, key);
        if (!(value instanceof Number number)) {
            throw new BizException(503, "L3_REDEMPTION_SOURCE_INVALID");
        }
        try {
            BigDecimal parsed = new BigDecimal(String.valueOf(number));
            return parsed.longValueExact() < 0
                    ? invalidWhole()
                    : parsed.longValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new BizException(503, "L3_REDEMPTION_SOURCE_INVALID");
        }
    }

    private long invalidWhole() {
        throw new BizException(503, "L3_REDEMPTION_SOURCE_INVALID");
    }

    private BigDecimal decimalOrNull(Map<String, Object> values, String key) {
        Object value = value(values, key);
        if (value == null) return null;
        try {
            BigDecimal parsed = new BigDecimal(String.valueOf(value));
            if (parsed.signum() < 0) throw new NumberFormatException("negative");
            return parsed;
        } catch (RuntimeException ignored) {
            throw new BizException(503, "L3_REDEMPTION_SOURCE_INVALID");
        }
    }

    private Object value(Map<String, Object> values, String key) {
        if (values == null) return null;
        if (values.containsKey(key)) return values.get(key);
        return values.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private BigDecimal requireRevenue(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new BizException(503, "L3_REVENUE_SOURCE_INVALID");
        }
        return value;
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal pct(BigDecimal numerator, BigDecimal denominator) {
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 1, RoundingMode.HALF_UP);
    }

    private Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private record RevenueFact(String key, String label, String source, BigDecimal amount) {}

    private record PeriodWindow(
            String granularity,
            LocalDateTime from,
            LocalDateTime to,
            LocalDateTime previousFrom,
            LocalDateTime previousTo,
            String label,
            String previousLabel,
            String timeZone) {
        Map<String, Object> view() {
            return Map.of(
                    "granularity", granularity,
                    "from", from.toLocalDate(),
                    "to", to.toLocalDate().minusDays(1),
                    "label", label,
                    "timeZone", timeZone);
        }
    }
}
