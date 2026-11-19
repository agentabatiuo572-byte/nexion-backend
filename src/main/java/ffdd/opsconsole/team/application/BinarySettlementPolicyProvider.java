package ffdd.opsconsole.team.application;

import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Locks and validates every money-amplifying input used by one F3 settlement. */
@Component
@RequiredArgsConstructor
public class BinarySettlementPolicyProvider {
    static final String THRESHOLD_KEY = "team.ui.F.binary.threshold";
    static final String MATCH_RATE_KEY = "team.ui.F.binary.matchRate";
    static final String PAUSED_KEY = "team.ui.F.binary.paused";
    static final String AUTO_SETTLE_KEY = "team.ui.F.binary.autoSettle";
    static final String AUTO_ENABLED_AT_UTC_KEY = "team.binary.autoEnabledAtUtc";
    static final String PLATFORM_CAP_PCT_KEY = "team.ui.F.binary.platformCapPct";
    static final String DEFAULT_PLATFORM_CAP_PCT = "2%";
    static final String SPILLOVER_KEY = "team.ui.F.binary.spillover";
    static final String SETTLE_PERIOD_KEY = "team.ui.F.binary.settlePeriod";
    static final String RESIDUAL_POLICY_KEY = "team.ui.F.binary.residualPolicy";
    static final String GV_RESET_KEY = "team.ui.F.binary.gvResetCron";
    static final String H1_TOTAL_MONTHS_KEY = "H1.rhythm.totalMonths";
    static final String H1_CURRENT_MONTH_KEY = "H1.rhythm.currentMonth";
    static final String H1_PHASE_KEY = "growth.phase.current";

    private final PlatformConfigFacade configFacade;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final TreasuryCoverageFacade coverageFacade;

    public BinarySettlementPolicy lockPolicy() {
        return lockPolicy(true);
    }

    BinarySettlementPolicy lockPolicyForApproval() {
        return lockPolicy(false);
    }

    private BinarySettlementPolicy lockPolicy(boolean checkCurrentCoverage) {
        BigDecimal threshold;
        try {
            threshold = normalizeThreshold(require(THRESHOLD_KEY, "F3_THRESHOLD_MISSING"));
        } catch (IllegalArgumentException invalid) {
            throw new PolicyBlocked("F3_THRESHOLD_INVALID");
        }
        BigDecimal matchRate = rate(require(MATCH_RATE_KEY, "F3_MATCH_RATE_MISSING"));
        boolean paused = strictBoolean(require(PAUSED_KEY, "F3_PAUSED_CONFIG_MISSING"));
        boolean spilloverEnabled = strictEnabled(require(SPILLOVER_KEY, "F3_SPILLOVER_CONFIG_MISSING"));
        String settlePeriod = oneOf(
                require(SETTLE_PERIOD_KEY, "F3_SETTLE_PERIOD_MISSING"),
                "F3_SETTLE_PERIOD_INVALID", "每日", "每周", "每月");
        String residualPolicy = oneOf(
                require(RESIDUAL_POLICY_KEY, "F3_RESIDUAL_POLICY_MISSING"),
                "F3_RESIDUAL_POLICY_INVALID", "每月清零", "每次对碰清零", "转结");
        String gvResetCron = require(GV_RESET_KEY, "F3_GV_RESET_MISSING");

        int totalMonths = positiveInteger(require(H1_TOTAL_MONTHS_KEY, "H1_TOTAL_MONTHS_MISSING"),
                "H1_TOTAL_MONTHS_INVALID");
        int currentMonth = positiveInteger(require(H1_CURRENT_MONTH_KEY, "H1_CURRENT_MONTH_MISSING"),
                "H1_CURRENT_MONTH_INVALID");
        if (currentMonth > totalMonths) throw new PolicyBlocked("H1_CURRENT_MONTH_INVALID");
        require(H1_PHASE_KEY, "H1_PHASE_MISSING");
        String capKey = "growth.phase.month." + currentMonth + ".binaryDailyCap";
        BigDecimal lockedCap = positiveMoney(require(capKey, "H1_BINARY_DAILY_CAP_MISSING"),
                "H1_BINARY_DAILY_CAP_INVALID");

        GrowthRhythmSnapshot rhythm = GrowthRhythmSnapshot.from(configFacade, readTimeSeedPolicy);
        if (rhythm.totalMonths() != totalMonths || rhythm.currentMonth() != currentMonth
                || !StringUtils.hasText(rhythm.currentPhase())
                || rhythm.binaryDailyCap() == null || rhythm.binaryDailyCap().signum() <= 0
                || rhythm.binaryDailyCap().compareTo(lockedCap) != 0) {
            throw new PolicyBlocked("H1_GROWTH_RHYTHM_UNAVAILABLE");
        }

        TreasuryCoverageSnapshot coverage = null;
        if (checkCurrentCoverage) {
            coverage = coverageFacade.snapshot();
            String block = currentCoverageBlock(coverage);
            if (block != null) throw new PolicyBlocked(block);
        }
        return new BinarySettlementPolicy(
                threshold, matchRate, rhythm.binaryDailyCap(), paused,
                spilloverEnabled, settlePeriod, residualPolicy, gvResetCron,
                coverage == null ? null : coverage.coverageRatio(),
                coverage == null ? null : coverage.redlinePct());
    }

    BigDecimal platformCapPctForUpdate() {
        BigDecimal cap = parsePlatformCapPct(configFacade.activeValueForUpdate(PLATFORM_CAP_PCT_KEY)
                .orElse(DEFAULT_PLATFORM_CAP_PCT));
        if (cap == null) throw new PolicyBlocked("F3_PLATFORM_CAP_PCT_INVALID");
        return cap;
    }

    String currentCoverageBlock() {
        return currentCoverageBlock(coverageFacade.snapshot());
    }

    static String currentCoverageBlock(TreasuryCoverageSnapshot coverage) {
        if (coverage == null || !coverage.reliable()
                || coverage.coverageRatio() == null || coverage.redlinePct() == null
                || coverage.redlinePct().signum() <= 0) {
            return "B1_COVERAGE_UNRELIABLE";
        }
        return coverage.redlineBreached() || coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0
                ? "COVERAGE_BELOW_REDLINE" : null;
    }

    String projectedCoverageBlock(BigDecimal amountUsdt) {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        if (coverage == null || !coverage.reliable() || coverage.redlinePct() == null
                || coverage.redlinePct().signum() <= 0
                || coverage.ratioReserveUsd() == null || coverage.ratioLiabilitiesUsd() == null) {
            return "B1_COVERAGE_UNRELIABLE";
        }
        // B1 already sums pending commission ledger rows. This payout has yet to post.
        BigDecimal projectedLiabilities = coverage.ratioLiabilitiesUsd().add(amountUsdt);
        return coverage.ratioReserveUsd().multiply(BigDecimal.valueOf(100))
                .compareTo(projectedLiabilities.multiply(coverage.redlinePct())) < 0
                ? "COVERAGE_BELOW_REDLINE" : null;
    }

    private String require(String key, String code) {
        return configFacade.activeValueForUpdate(key)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .orElseThrow(() -> new PolicyBlocked(code));
    }

    private BigDecimal positiveMoney(String raw, String code) {
        try {
            BigDecimal value = new BigDecimal(raw.replace("$", "").replace(",", "").trim());
            if (value.signum() <= 0) throw new PolicyBlocked(code);
            return value.setScale(6, RoundingMode.UNNECESSARY);
        } catch (PolicyBlocked ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PolicyBlocked(code);
        }
    }

    private BigDecimal rate(String raw) {
        try {
            return normalizeMatchRate(raw);
        } catch (IllegalArgumentException ex) {
            throw new PolicyBlocked("F3_MATCH_RATE_INVALID");
        }
    }

    public static BigDecimal normalizeThreshold(String raw) {
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            if (value.signum() <= 0 || value.compareTo(new BigDecimal("5000")) > 0 || value.scale() > 6) {
                throw new IllegalArgumentException("F3_THRESHOLD_INVALID");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("F3_THRESHOLD_INVALID");
        }
    }

    public static BigDecimal normalizeMatchRate(String raw) {
        String value = raw.trim();
        if (value.endsWith("%")) value = value.substring(0, value.length() - 1).trim();
        try {
            BigDecimal percent = new BigDecimal(value);
            if (percent.signum() <= 0 || percent.compareTo(new BigDecimal("20")) > 0 || percent.scale() > 10) {
                throw new IllegalArgumentException("F3_MATCH_RATE_INVALID");
            }
            return percent.movePointLeft(2).stripTrailingZeros();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("F3_MATCH_RATE_INVALID");
        }
    }

    static BigDecimal parsePlatformCapPct(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        String value = raw.trim();
        if (value.endsWith("%")) value = value.substring(0, value.length() - 1).trim();
        try {
            BigDecimal percent = new BigDecimal(value);
            if (percent.signum() < 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0) return null;
            BigDecimal exactPercent = percent.stripTrailingZeros();
            return exactPercent.scale() <= 10 ? exactPercent : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int positiveInteger(String raw, String code) {
        try {
            int value = new BigDecimal(raw).intValueExact();
            if (value <= 0) throw new PolicyBlocked(code);
            return value;
        } catch (PolicyBlocked ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PolicyBlocked(code);
        }
    }

    /**
     * The one F3 pause parser used by settlement, admin reads and writes.
     * Unknown/missing input stays null; callers decide whether that is an error.
     */
    public static Boolean parsePaused(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "on" -> true;
            case "false", "0", "off" -> false;
            default -> null;
        };
    }

    private boolean strictBoolean(String raw) {
        Boolean parsed = parsePaused(raw);
        if (parsed == null) throw new PolicyBlocked("F3_PAUSED_CONFIG_INVALID");
        return parsed;
    }

    private boolean strictEnabled(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "on", "已启用" -> true;
            case "false", "0", "off", "已关闭" -> false;
            default -> throw new PolicyBlocked("F3_SPILLOVER_CONFIG_INVALID");
        };
    }

    private String oneOf(String raw, String code, String... allowed) {
        String value = raw.trim();
        for (String candidate : allowed) {
            if (candidate.equals(value)) return value;
        }
        throw new PolicyBlocked(code);
    }

    public record BinarySettlementPolicy(
            BigDecimal threshold,
            BigDecimal matchRate,
            BigDecimal dailyCap,
            boolean paused,
            boolean spilloverEnabled,
            String settlePeriod,
            String residualPolicy,
            String gvResetCron,
            BigDecimal coverageRatio,
            BigDecimal coverageRedline) { }

    public static final class PolicyBlocked extends RuntimeException {
        public PolicyBlocked(String code) {
            super(code);
        }
    }
}
