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
    static final String H1_TOTAL_MONTHS_KEY = "H1.rhythm.totalMonths";
    static final String H1_CURRENT_MONTH_KEY = "H1.rhythm.currentMonth";
    static final String H1_PHASE_KEY = "growth.phase.current";

    private final PlatformConfigFacade configFacade;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final TreasuryCoverageFacade coverageFacade;

    public BinarySettlementPolicy lockPolicy() {
        BigDecimal threshold = positiveMoney(require(THRESHOLD_KEY, "F3_THRESHOLD_MISSING"),
                "F3_THRESHOLD_INVALID");
        BigDecimal matchRate = rate(require(MATCH_RATE_KEY, "F3_MATCH_RATE_MISSING"));
        boolean paused = strictBoolean(require(PAUSED_KEY, "F3_PAUSED_CONFIG_MISSING"));

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

        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        if (coverage == null || !coverage.reliable()
                || coverage.coverageRatio() == null || coverage.redlinePct() == null) {
            throw new PolicyBlocked("B1_COVERAGE_UNRELIABLE");
        }
        if (coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0) {
            throw new PolicyBlocked("COVERAGE_BELOW_REDLINE");
        }
        return new BinarySettlementPolicy(
                threshold, matchRate, rhythm.binaryDailyCap(), paused,
                coverage.coverageRatio(), coverage.redlinePct());
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
            String value = raw.trim();
            boolean percent = value.endsWith("%");
            if (percent) value = value.substring(0, value.length() - 1).trim();
            BigDecimal parsed = new BigDecimal(value);
            if (percent || parsed.compareTo(BigDecimal.ONE) > 0) {
                parsed = parsed.divide(new BigDecimal("100"), 12, RoundingMode.HALF_UP);
            }
            if (parsed.signum() <= 0 || parsed.compareTo(BigDecimal.ONE) > 0) {
                throw new PolicyBlocked("F3_MATCH_RATE_INVALID");
            }
            return parsed.stripTrailingZeros();
        } catch (PolicyBlocked ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PolicyBlocked("F3_MATCH_RATE_INVALID");
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

    private boolean strictBoolean(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "on" -> true;
            case "false", "0", "off" -> false;
            default -> throw new PolicyBlocked("F3_PAUSED_CONFIG_INVALID");
        };
    }

    public record BinarySettlementPolicy(
            BigDecimal threshold,
            BigDecimal matchRate,
            BigDecimal dailyCap,
            boolean paused,
            BigDecimal coverageRatio,
            BigDecimal coverageRedline) { }

    public static final class PolicyBlocked extends RuntimeException {
        public PolicyBlocked(String code) {
            super(code);
        }
    }
}
