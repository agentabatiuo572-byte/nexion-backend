package ffdd.opsconsole.bi.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure L3 report calculation. B1/D3/B2 values enter as authoritative inputs;
 * this class only derives report presentation ratios and validates that the
 * cross-domain facts form one complete, non-ambiguous financial snapshot.
 */
public final class L3FinanceReportAssembler {
    public static final List<String> CANONICAL_REVENUE_STREAMS = List.of(
            "device_sales", "team_commission", "token_economy", "compute_matching");
    public static final List<String> CANONICAL_LIABILITY_CATEGORIES = List.of(
            "withdrawable_balance", "usdt_staking_principal", "staking_interest",
            "genesis_daily_emission", "nex_v2_future", "withdrawal_queue",
            "commission_cooling", "lock_other");
    public static final List<String> CANONICAL_LIABILITY_LABELS = List.of(
            "可提余额", "USDT staking 本金", "staking 应付利息", "Genesis 排放承诺",
            "NEX v2 未来兑付", "待提现 queue", "佣金冷却未解锁", "锁仓本息其他");
    private static final Set<String> PERIOD_GRANULARITIES = Set.of("day", "week", "month", "quarter", "custom");
    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");

    public Report assemble(Inputs input) {
        Objects.requireNonNull(input, "L3 inputs are required");
        validatePeriod(input.period());
        List<RevenueStream> revenue = orderedRevenue(input.revenueStreams());
        validateRedemption(input.redemption());
        validateCoverage(input.coverage());
        List<Liability> liabilities = orderedLiabilities(input.liabilities());
        validateMaturity(input.maturity7d(), 7, "maturity7d");
        validateMaturity(input.maturity30d(), 30, "maturity30d");
        if (input.reserveCoverDays() < 0) throw new IllegalArgumentException("reserveCoverDays must be non-negative");

        RevenueReport revenueReport = revenueReport(revenue);
        RedemptionReport redemptionReport = redemptionReport(input.redemption());
        MaturityWindow maturity7d = maturityWindow("7d", input.maturity7d());
        MaturityWindow maturity30d = maturityWindow("30d", input.maturity30d());
        BigDecimal liabilitySum = liabilities.stream()
                .map(Liability::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal authoritativeTotal = money(input.coverage().liabilitiesUsd());
        Quality quality = new Quality(
                liabilitySum.subtract(authoritativeTotal).abs().compareTo(ONE_CENT) <= 0,
                liabilitySum,
                authoritativeTotal,
                true,
                true);
        return new Report(
                input.period(), revenueReport, redemptionReport, input.coverage(), liabilities,
                maturity7d, maturity30d, input.reserveCoverDays(), quality);
    }

    private RevenueReport revenueReport(List<RevenueStream> streams) {
        BigDecimal total = streams.stream()
                .map(RevenueStream::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<RevenueRow> rows = streams.stream().map(stream -> {
            BigDecimal share = total.signum() == 0 ? null : pct(stream.amountUsd(), total);
            BigDecimal mom = stream.previousAmountUsd().signum() == 0 ? null
                    : stream.amountUsd().subtract(stream.previousAmountUsd())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(stream.previousAmountUsd(), 1, RoundingMode.HALF_UP);
            Boolean up = mom == null ? null : mom.signum() >= 0;
            return new RevenueRow(
                    stream.key(), stream.label(), stream.source(), money(stream.amountUsd()),
                    share, mom, up);
        }).toList();
        return new RevenueReport(money(total), rows);
    }

    private RedemptionReport redemptionReport(Redemption value) {
        BigDecimal rate = value.submitted() == 0 ? null
                : BigDecimal.valueOf(value.confirmed())
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(value.submitted()), 1, RoundingMode.HALF_UP);
        return new RedemptionReport(
                value.submitted(), value.confirmed(), value.rejected(), value.delayed(), value.frozen(),
                value.averageLatencyHours(), rate);
    }

    private MaturityWindow maturityWindow(String window, List<MaturityDay> days) {
        BigDecimal withdraw = sum(days, MaturityDay::withdrawDueUsd);
        BigDecimal interest = sum(days, MaturityDay::interestDueUsd);
        BigDecimal genesis = sum(days, MaturityDay::genesisDueUsd);
        return new MaturityWindow(window, money(withdraw), money(interest), money(genesis),
                money(withdraw.add(interest).add(genesis)), List.copyOf(days));
    }

    private BigDecimal sum(List<MaturityDay> days, java.util.function.Function<MaturityDay, BigDecimal> getter) {
        return days.stream().map(getter).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<RevenueStream> orderedRevenue(List<RevenueStream> values) {
        requireExactKeys(values, RevenueStream::key, CANONICAL_REVENUE_STREAMS,
                "four canonical revenue streams");
        List<RevenueStream> ordered = new ArrayList<>(values);
        ordered.sort(Comparator.comparingInt(value -> CANONICAL_REVENUE_STREAMS.indexOf(value.key())));
        ordered.forEach(value -> {
            requireText(value.label(), "revenue label");
            requireText(value.source(), "revenue source");
            requireNonNegative(value.amountUsd(), "revenue amount");
            requireNonNegative(value.previousAmountUsd(), "previous revenue amount");
        });
        return List.copyOf(ordered);
    }

    private List<Liability> orderedLiabilities(List<Liability> values) {
        requireExactKeys(values, Liability::category, CANONICAL_LIABILITY_CATEGORIES,
                "eight canonical liability categories");
        List<Liability> ordered = new ArrayList<>(values);
        ordered.sort(Comparator.comparingInt(value -> CANONICAL_LIABILITY_CATEGORIES.indexOf(value.category())));
        ordered.forEach(value -> {
            int index = CANONICAL_LIABILITY_CATEGORIES.indexOf(value.category());
            if (!CANONICAL_LIABILITY_LABELS.get(index).equals(value.label())) {
                throw new IllegalArgumentException("liability label must match its canonical category");
            }
            requireNonNegative(value.amountUsd(), "liability amount");
        });
        return List.copyOf(ordered);
    }

    private <T> void requireExactKeys(
            List<T> values,
            java.util.function.Function<T, String> key,
            List<String> expected,
            String label) {
        if (values == null || values.size() != expected.size()) {
            throw new IllegalArgumentException("L3 requires " + label);
        }
        Set<String> keys = new HashSet<>();
        for (T value : values) {
            if (value == null || !keys.add(key.apply(value))) {
                throw new IllegalArgumentException("L3 requires " + label);
            }
        }
        if (!keys.equals(Set.copyOf(expected))) {
            throw new IllegalArgumentException("L3 requires " + label);
        }
    }

    private void validatePeriod(Period value) {
        if (value == null || !PERIOD_GRANULARITIES.contains(value.granularity())
                || value.from() == null || value.to() == null || value.to().isBefore(value.from())) {
            throw new IllegalArgumentException("valid L3 period is required");
        }
        requireText(value.label(), "period label");
    }

    private void validateRedemption(Redemption value) {
        if (value == null || value.submitted() < 0 || value.confirmed() < 0 || value.rejected() < 0
                || value.delayed() < 0 || value.frozen() < 0
                || value.confirmed() + value.rejected() + value.delayed() + value.frozen() > value.submitted()) {
            throw new IllegalArgumentException("redemption counts must fit the submitted denominator");
        }
        if (value.averageLatencyHours() != null) requireNonNegative(value.averageLatencyHours(), "average latency");
    }

    private void validateCoverage(Coverage value) {
        if (value == null) throw new IllegalArgumentException("authoritative B1 coverage is required");
        requireNonNegative(value.reserveUsd(), "reserve");
        requireNonNegative(value.liabilitiesUsd(), "liabilities");
        requireNonNegative(value.coverageRatioPct(), "coverage ratio");
        requireNonNegative(value.redLinePct(), "coverage red line");
        requireNonNegative(value.yellowLinePct(), "coverage yellow line");
        if (value.yellowLinePct().compareTo(value.redLinePct()) <= 0) {
            throw new IllegalArgumentException("coverage yellow line must exceed red line");
        }
        if (value.series() == null || value.series().size() < 2) {
            throw new IllegalArgumentException("authoritative B1 coverage trend requires at least two points");
        }
        value.series().forEach(point -> {
            requireText(point.period(), "coverage period");
            requireNonNegative(point.coverageRatioPct(), "coverage point");
        });
    }

    private void validateMaturity(List<MaturityDay> values, int expectedDays, String field) {
        if (values == null || values.size() != expectedDays) {
            throw new IllegalArgumentException(field + " must contain exactly " + expectedDays + " days");
        }
        LocalDate previous = null;
        Set<LocalDate> seen = new HashSet<>();
        for (MaturityDay value : values) {
            if (value == null || value.date() == null || !seen.add(value.date())
                    || (previous != null && !value.date().equals(previous.plusDays(1)))) {
                throw new IllegalArgumentException(field + " dates must be unique and consecutive");
            }
            requireNonNegative(value.withdrawDueUsd(), field + " withdrawal");
            requireNonNegative(value.interestDueUsd(), field + " interest");
            requireNonNegative(value.genesisDueUsd(), field + " genesis emission");
            previous = value.date();
        }
    }

    private void requireNonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException(field + " must be non-negative");
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal pct(BigDecimal numerator, BigDecimal denominator) {
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 1, RoundingMode.HALF_UP);
    }

    public record Period(String granularity, LocalDate from, LocalDate to, String label) {}
    public record RevenueStream(
            String key, String label, String source, BigDecimal amountUsd, BigDecimal previousAmountUsd) {}
    public record Redemption(
            long submitted, long confirmed, long rejected, long delayed, long frozen,
            BigDecimal averageLatencyHours) {}
    public record Coverage(
            BigDecimal reserveUsd, BigDecimal liabilitiesUsd, BigDecimal coverageRatioPct,
            BigDecimal redLinePct, BigDecimal yellowLinePct, BigDecimal netExposureUsd,
            List<CoveragePoint> series, List<CoverageBreach> breaches) {}
    public record CoveragePoint(String period, BigDecimal coverageRatioPct) {}
    public record CoverageBreach(String period, String type, String label) {}
    public record Liability(String category, String label, BigDecimal amountUsd) {}
    public record MaturityDay(
            LocalDate date, BigDecimal withdrawDueUsd, BigDecimal interestDueUsd, BigDecimal genesisDueUsd) {}
    public record Inputs(
            Period period, List<RevenueStream> revenueStreams, Redemption redemption, Coverage coverage,
            List<Liability> liabilities, List<MaturityDay> maturity7d, List<MaturityDay> maturity30d,
            int reserveCoverDays) {}

    public record RevenueRow(
            String key, String label, String source, BigDecimal amountUsd,
            BigDecimal sharePct, BigDecimal momPct, Boolean up) {}
    public record RevenueReport(BigDecimal totalUsd, List<RevenueRow> streams) {}
    public record RedemptionReport(
            long submitted, long confirmed, long rejected, long delayed, long frozen,
            BigDecimal averageLatencyHours, BigDecimal ratePct) {}
    public record MaturityWindow(
            String window, BigDecimal withdrawDueUsd, BigDecimal interestDueUsd,
            BigDecimal genesisDueUsd, BigDecimal totalDueUsd, List<MaturityDay> days) {}
    public record Quality(
            boolean liabilityTotalMatches, BigDecimal liabilitySumUsd, BigDecimal authoritativeLiabilitiesUsd,
            boolean canonicalLiabilityCategoriesComplete, boolean maturityWindowsComplete) {}
    public record Report(
            Period period, RevenueReport revenue, RedemptionReport redemption, Coverage coverage,
            List<Liability> liabilities, MaturityWindow maturity7d, MaturityWindow maturity30d,
            int reserveCoverDays, Quality quality) {}
}
