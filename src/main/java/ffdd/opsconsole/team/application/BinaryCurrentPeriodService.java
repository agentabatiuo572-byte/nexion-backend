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
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
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
    private static final String ESTIMATE_BASIS = "PAID_ORDERS_TO_DATE_X_CURRENT_PCT_ESTIMATE";
    private static final String PAID_SALES_UNAVAILABLE = "F3_PAID_SALES_UNAVAILABLE";

    private final BinaryPlatformSettlementMapper periods;
    private final PlatformConfigFacade config;
    private final Clock clock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> currentPeriod() {
        Instant now = clock.instant();
        Set<String> unavailable = new LinkedHashSet<>();
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("asOfUtc", now.toString());
        putEstimate(period, null, null);

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
                BigDecimal paidSales = paidSalesToDate(start, now);
                period.put("paidSalesUsdt", paidSales);
                if (paidSales == null) {
                    unavailable.add(PAID_SALES_UNAVAILABLE);
                    period.put("paidSalesReason", PAID_SALES_UNAVAILABLE);
                }
                period.put("paidSalesAsOfUtc", now.toString());
                period.put("paidSalesComplete", false);
                period.put("paidSalesBasis", "PAID_ORDERS_TO_DATE_NOT_FROZEN");
                BigDecimal capPct = putCurrentCapPct(period, unavailable);
                String estimateReason = paidSales == null ? PAID_SALES_UNAVAILABLE
                        : (String) period.get("platformCapPctReason");
                putEstimate(period, estimateReason == null
                        ? BinaryPlatformSettlementPlan.capAmount(paidSales, capPct) : null, estimateReason);
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
            putEstimate(period, null, unavailable.iterator().next());
        }

        putAvailability(period);
        period.put("unavailableReasons", List.copyOf(unavailable));
        period.put("basis", basis());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "F3");
        response.put("currentPeriod", period);
        response.put("latestFrozenPeriod", latestFrozenPeriod(now, today));
        response.put("sources", List.of(
                "nx_binary_platform_period",
                "nx_binary_platform_allocation",
                "nx_binary_commission_settlement",
                "nx_order",
                "nx_config_item:team.ui.F.binary.settlePeriod",
                "nx_config_item:team.ui.F.binary.platformCapPct"));
        return response;
    }

    private BigDecimal paidSalesToDate(LocalDate start, Instant now) {
        try {
            return periods.paidSales(VietnamBusinessPeriod.storageBoundary(start),
                    LocalDateTime.ofInstant(now.truncatedTo(ChronoUnit.MICROS), VietnamBusinessPeriod.STORAGE_ZONE));
        } catch (DataAccessException ex) {
            return null;
        }
    }

    private BigDecimal putCurrentCapPct(Map<String, Object> period, Set<String> unavailable) {
        BigDecimal capPct = null;
        String basis = null;
        String reason = null;
        try {
            var value = config.activeValue(PLATFORM_CAP_PCT_KEY);
            capPct = BinarySettlementPolicyProvider.parsePlatformCapPct(value.orElse(DEFAULT_PLATFORM_CAP_PCT));
            if (capPct == null) reason = "F3_PLATFORM_CAP_PCT_INVALID";
            else basis = value.isPresent() ? "CURRENT_CONFIG_NOT_FROZEN" : "DEFAULT_CONFIG_NOT_FROZEN";
        } catch (DataAccessException ex) {
            reason = "F3_PLATFORM_CAP_PCT_UNAVAILABLE";
        }
        if (reason != null) {
            unavailable.add(reason);
            period.put("platformCapPctReason", reason);
        }
        period.put("platformCapPct", capPct);
        period.put("platformCapPctUnit", "PERCENT");
        period.put("platformCapPctBasis", basis);
        return capPct;
    }

    private static void putEstimate(Map<String, Object> period, BigDecimal amount, String reason) {
        period.put("platformCapEstimateUsdt", amount);
        period.put("platformCapEstimateBasis", amount == null ? null : ESTIMATE_BASIS);
        period.put("platformCapEstimateReason", reason);
    }

    /**
     * The current open cycle cannot be frozen before its closed date, so the latest frozen
     * cycle is read independently from the latest platform period before today.
     */
    private Map<String, Object> latestFrozenPeriod(Instant now, LocalDate today) {
        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put("asOfUtc", now.toString());
        putEstimate(latest, null, null);
        Set<String> unavailable = new LinkedHashSet<>();
        if (today == null) {
            unavailable.add("F3_BUSINESS_PERIOD_UNAVAILABLE");
            return unavailableLatest(latest, null, "UNKNOWN", unavailable);
        }
        LocalDate settlementDate = periods.previousEnd(today);
        if (settlementDate == null) {
            unavailable.add("F3_PLATFORM_PERIOD_NOT_FROZEN");
            return unavailableLatest(latest, false, "NOT_FROZEN", unavailable);
        }
        var frozen = periods.period(settlementDate);
        if (frozen == null) {
            unavailable.add("F3_PLATFORM_PERIOD_UNAVAILABLE");
            return unavailableLatest(latest, null, "UNKNOWN", unavailable);
        }

        latest.put("settlementDate", frozen.settlementDate().toString());
        latest.put("periodStart", frozen.periodStart().toString());
        latest.put("periodEnd", frozen.settlementDate().toString());
        latest.put("periodDays", frozen.periodDays());
        latest.put("businessZone", VietnamBusinessPeriod.ZONE.getId());
        latest.put("storageZone", VietnamBusinessPeriod.STORAGE_ZONE.getId());
        putUtcBoundaries(latest, frozen.periodStart(), frozen.settlementDate(), unavailable);
        latest.put("frozen", true);
        latest.put("freezeStatus", "FROZEN");
        latest.put("paidSalesUsdt", frozen.paidSales());
        latest.put("paidSalesAsOfUtc", null);
        latest.put("paidSalesComplete", true);
        latest.put("paidSalesBasis", "FROZEN_PERIOD");
        latest.put("platformCapPct", frozen.platformCapPct());
        latest.put("platformCapPctUnit", "PERCENT");
        latest.put("platformCapPctBasis", "FROZEN_PERIOD");
        latest.put("platformCapUsdt", frozen.capAmount());
        latest.put("plannedRawTotalUsdt", frozen.rawTotal());
        latest.put("plannedRawTotalBasis", "FROZEN_PERIOD_RAW_TOTAL_NOT_PAID");
        var totals = periods.settledTotals(settlementDate);
        latest.put("platformUsedUsdt", totals.settledPayoutUsdt());
        latest.put("settledPayoutUsdt", totals.settledPayoutUsdt());
        latest.put("automaticSettledUsdt", totals.automaticSettledUsdt());
        latest.put("manualSettledUsdt", totals.manualSettledUsdt());
        latest.put("settledSettlementCount", totals.settledSettlementCount());
        latest.put("platformRemainingUsdt", frozen.capAmount().subtract(totals.settledPayoutUsdt()));
        putAvailability(latest);
        latest.put("unavailableReasons", List.copyOf(unavailable));
        latest.put("basis", basis());
        return latest;
    }

    private static Map<String, Object> unavailableLatest(Map<String, Object> latest, Boolean frozen,
                                                         String freezeStatus, Set<String> unavailable) {
        latest.put("frozen", frozen);
        latest.put("freezeStatus", freezeStatus);
        latest.put("paidSalesUsdt", null);
        latest.put("paidSalesAsOfUtc", null);
        latest.put("paidSalesComplete", false);
        latest.put("platformCapPct", null);
        latest.put("platformCapPctUnit", "PERCENT");
        latest.put("platformCapUsdt", null);
        latest.put("platformUsedUsdt", null);
        latest.put("settledPayoutUsdt", null);
        latest.put("automaticSettledUsdt", null);
        latest.put("manualSettledUsdt", null);
        latest.put("settledSettlementCount", null);
        latest.put("plannedRawTotalUsdt", null);
        latest.put("platformRemainingUsdt", null);
        putAvailability(latest);
        latest.put("unavailableReasons", List.copyOf(unavailable));
        latest.put("basis", basis());
        return latest;
    }

    private static void putAvailability(Map<String, Object> view) {
        Map<String, Object> availability = new LinkedHashMap<>();
        availability.put("dates", view.containsKey("settlementDate"));
        availability.put("utc", view.get("periodStartAtUtc") != null && view.get("periodEndExclusiveAtUtc") != null);
        availability.put("paidSales", view.get("paidSalesUsdt") != null);
        availability.put("platformCap", view.get("platformCapUsdt") != null);
        availability.put("platformUsed", view.get("platformUsedUsdt") != null);
        view.put("availability", availability);
        view.put("available", Boolean.TRUE.equals(availability.get("dates"))
                && Boolean.TRUE.equals(availability.get("utc"))
                && Boolean.TRUE.equals(availability.get("paidSales"))
                && Boolean.TRUE.equals(availability.get("platformCap"))
                && Boolean.TRUE.equals(availability.get("platformUsed")));
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
        basis.put("platformCapEstimate", "Open period paidSalesUsdt × platformCapPct / 100, rounded down to six decimals; display only");
        basis.put("platformUsed", "SUM(nx_binary_commission_settlement.amount_usdt) for platform allocations with settlement_mode AUTOMATIC or MANUAL");
        basis.put("plannedRawTotal", "nx_binary_platform_period.raw_total_usdt; projected raw total, not paid");
        basis.put("notCountedAsPaid", "platform allocations without settlement_mode are projections and are excluded from settled/used amounts");
        return basis;
    }
}
