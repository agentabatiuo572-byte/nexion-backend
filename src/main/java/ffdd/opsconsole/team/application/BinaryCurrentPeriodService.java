package ffdd.opsconsole.team.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.config.VietnamBusinessPeriod;
import ffdd.opsconsole.team.mapper.BinaryPlatformSettlementMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only current F3 cycle. It borrows the formal settlement cadence, business calendar,
 * paid-order filter and frozen platform period; it never derives the cycle from settlement rows.
 */
@Service
@RequiredArgsConstructor
public class BinaryCurrentPeriodService {
    private static final String SETTLE_PERIOD_KEY = BinarySettlementPolicyProvider.SETTLE_PERIOD_KEY;
    private static final String PLATFORM_CAP_PCT_KEY = BinarySettlementPolicyProvider.PLATFORM_CAP_PCT_KEY;
    private static final String DEFAULT_PLATFORM_CAP_PCT = BinarySettlementPolicyProvider.DEFAULT_PLATFORM_CAP_PCT;

    private final BinaryPlatformSettlementMapper periods;
    private final PlatformConfigFacade config;
    private final Clock clock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> currentPeriod() {
        Instant now = clock.instant();
        Set<String> unavailable = new LinkedHashSet<>();
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("asOfUtc", now.toString());

        LocalDate today = null;
        try {
            today = VietnamBusinessPeriod.dateAt(now);
        } catch (RuntimeException ex) {
            unavailable.add("F3_BUSINESS_PERIOD_UNAVAILABLE");
        }

        String rawCadence = config.activeValue(SETTLE_PERIOD_KEY).map(String::trim).orElse(null);
        String cadence = null;
        if (rawCadence == null || rawCadence.isEmpty()) {
            unavailable.add("F3_SETTLE_PERIOD_MISSING");
        } else if (List.of("每日", "每周", "每月").contains(rawCadence)) {
            cadence = rawCadence;
            period.put("cadence", cadence);
        } else {
            unavailable.add("F3_SETTLE_PERIOD_INVALID");
        }

        if (today != null && cadence != null) {
            LocalDate settlementDate = settlementDate(today, cadence);
            int days = periodDays(settlementDate, cadence);
            LocalDate start = settlementDate.minusDays(days - 1L);
            LocalDate previousEnd = periods.previousEnd(settlementDate);
            if (previousEnd != null && !previousEnd.isBefore(start)) start = previousEnd.plusDays(1);

            period.put("settlementDate", settlementDate.toString());
            period.put("periodStart", start.toString());
            period.put("periodEnd", settlementDate.toString());
            period.put("periodDays", days);
            period.put("businessZone", VietnamBusinessPeriod.ZONE.getId());
            period.put("storageZone", VietnamBusinessPeriod.STORAGE_ZONE.getId());
            putUtcBoundaries(period, start, settlementDate, unavailable);

            var frozen = periods.period(settlementDate);
            period.put("frozen", frozen != null);
            period.put("freezeStatus", frozen == null ? "NOT_FROZEN" : "FROZEN");
            if (frozen == null) {
                unavailable.add("F3_PLATFORM_PERIOD_NOT_FROZEN");
                period.put("platformCapReason", "F3_PLATFORM_PERIOD_NOT_FROZEN");
                period.put("platformUsedReason", "F3_PLATFORM_PERIOD_NOT_FROZEN");
                period.put("planReason", "F3_PLATFORM_PERIOD_NOT_FROZEN");
                period.put("paidSalesUsdt", periods.paidSales(
                        VietnamBusinessPeriod.storageBoundary(start),
                        VietnamBusinessPeriod.storageBoundary(today.plusDays(1))));
                period.put("paidSalesAsOfUtc", now.toString());
                period.put("paidSalesComplete", false);
                period.put("paidSalesBasis", "PAID_ORDERS_TO_DATE_NOT_FROZEN");
                var capPctValue = config.activeValue(PLATFORM_CAP_PCT_KEY);
                BigDecimal capPct = BinarySettlementPolicyProvider.parsePlatformCapPct(
                        capPctValue.orElse(DEFAULT_PLATFORM_CAP_PCT));
                if (capPct == null) {
                    unavailable.add("F3_PLATFORM_CAP_PCT_INVALID");
                    period.put("platformCapPctReason", "F3_PLATFORM_CAP_PCT_INVALID");
                }
                period.put("platformCapPct", capPct);
                period.put("platformCapPctUnit", "PERCENT");
                period.put("platformCapPctBasis", capPct == null ? null
                        : (capPctValue.isPresent() ? "CURRENT_CONFIG_NOT_FROZEN" : "DEFAULT_CONFIG_NOT_FROZEN"));
                period.put("platformCapUsdt", null);
                period.put("platformUsedUsdt", null);
                period.put("settledPayoutUsdt", null);
                period.put("automaticSettledUsdt", null);
                period.put("manualSettledUsdt", null);
                period.put("settledSettlementCount", null);
                period.put("plannedRawTotalUsdt", null);
                period.put("platformRemainingUsdt", null);
            } else {
                period.put("periodStart", frozen.periodStart().toString());
                period.put("periodDays", frozen.periodDays());
                putUtcBoundaries(period, frozen.periodStart(), frozen.settlementDate(), unavailable);
                period.put("paidSalesUsdt", frozen.paidSales());
                period.put("paidSalesAsOfUtc", null);
                period.put("paidSalesComplete", true);
                period.put("paidSalesBasis", "FROZEN_PERIOD");
                period.put("platformCapPct", frozen.platformCapPct());
                period.put("platformCapPctUnit", "PERCENT");
                period.put("platformCapPctBasis", "FROZEN_PERIOD");
                period.put("platformCapUsdt", frozen.capAmount());
                period.put("plannedRawTotalUsdt", frozen.rawTotal());
                period.put("plannedRawTotalBasis", "FROZEN_PERIOD_RAW_TOTAL_NOT_PAID");
                var totals = periods.settledTotals(settlementDate);
                period.put("platformUsedUsdt", totals.settledPayoutUsdt());
                period.put("settledPayoutUsdt", totals.settledPayoutUsdt());
                period.put("automaticSettledUsdt", totals.automaticSettledUsdt());
                period.put("manualSettledUsdt", totals.manualSettledUsdt());
                period.put("settledSettlementCount", totals.settledSettlementCount());
                period.put("platformRemainingUsdt", frozen.capAmount().subtract(totals.settledPayoutUsdt()));
            }
        } else {
            period.put("frozen", null);
            period.put("freezeStatus", "UNKNOWN");
            period.put("paidSalesUsdt", null);
            period.put("paidSalesComplete", false);
            period.put("platformCapPct", null);
            period.put("platformCapPctUnit", "PERCENT");
            period.put("platformCapUsdt", null);
            period.put("platformUsedUsdt", null);
            period.put("settledPayoutUsdt", null);
            period.put("automaticSettledUsdt", null);
            period.put("manualSettledUsdt", null);
            period.put("settledSettlementCount", null);
            period.put("plannedRawTotalUsdt", null);
            period.put("platformRemainingUsdt", null);
        }

        Map<String, Object> availability = new LinkedHashMap<>();
        availability.put("dates", period.containsKey("settlementDate"));
        availability.put("paidSales", period.get("paidSalesUsdt") != null);
        availability.put("platformCap", period.get("platformCapUsdt") != null);
        availability.put("platformUsed", period.get("platformUsedUsdt") != null);
        period.put("availability", availability);
        period.put("available", Boolean.TRUE.equals(availability.get("dates"))
                && Boolean.TRUE.equals(availability.get("paidSales"))
                && Boolean.TRUE.equals(availability.get("platformCap"))
                && Boolean.TRUE.equals(availability.get("platformUsed")));
        period.put("unavailableReasons", List.copyOf(unavailable));
        period.put("basis", basis());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "F3");
        response.put("currentPeriod", period);
        response.put("sources", List.of(
                "nx_binary_platform_period",
                "nx_binary_platform_allocation",
                "nx_binary_commission_settlement",
                "nx_order",
                "nx_config_item:team.ui.F.binary.settlePeriod",
                "nx_config_item:team.ui.F.binary.platformCapPct"));
        return response;
    }

    private static LocalDate settlementDate(LocalDate today, String cadence) {
        return switch (cadence) {
            case "每日" -> today;
            case "每周" -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
            case "每月" -> today.with(TemporalAdjusters.lastDayOfMonth());
            default -> throw new IllegalArgumentException("F3_SETTLE_PERIOD_INVALID");
        };
    }

    private static int periodDays(LocalDate date, String cadence) {
        return switch (cadence) {
            case "每日" -> 1;
            case "每周" -> 7;
            case "每月" -> date.lengthOfMonth();
            default -> throw new IllegalArgumentException("F3_SETTLE_PERIOD_INVALID");
        };
    }

    private static void putUtcBoundaries(Map<String, Object> period, LocalDate start, LocalDate end,
                                         Set<String> unavailable) {
        try {
            period.put("periodStartAtUtc", utc(start));
            period.put("periodEndExclusiveAtUtc", utc(end.plusDays(1)));
        } catch (RuntimeException ex) {
            unavailable.add("F3_BUSINESS_PERIOD_UNAVAILABLE");
        }
    }

    private static String utc(LocalDate date) {
        LocalDateTime storageBoundary = VietnamBusinessPeriod.storageBoundary(date);
        return storageBoundary.atZone(VietnamBusinessPeriod.STORAGE_ZONE).toInstant().toString();
    }

    private static Map<String, Object> basis() {
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("period", "team.ui.F.binary.settlePeriod + VietnamBusinessPeriod; same cadence and boundaries as F3 settlement");
        basis.put("paidSales", "nx_order paid statuses PAID/CONFIRMED/SUCCESS, refunded/cancelled/failed excluded, paid_at in the period; frozen value when the period is frozen");
        basis.put("platformCap", "nx_binary_platform_period.platform_cap_pct/cap_amount_usdt; frozen at the first settlement of the period");
        basis.put("platformUsed", "SUM(nx_binary_commission_settlement.amount_usdt) for platform allocations with settlement_mode AUTOMATIC or MANUAL");
        basis.put("plannedRawTotal", "nx_binary_platform_period.raw_total_usdt; projected raw total, not paid");
        basis.put("notCountedAsPaid", "platform allocations without settlement_mode are projections and are excluded from settled/used amounts");
        return basis;
    }
}
