package ffdd.opsconsole.team.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Strict, versioned authority boundary for every value used by an F4 money settlement. */
@Service
@RequiredArgsConstructor
public class LeadershipPoolConfigGuard {
    static final BigDecimal MAX_SETTLEMENT_PERCENT = new BigDecimal("30");
    private static final String DECIMAL_PERCENT_PATTERN = "[0-9]+(?:\\.[0-9]+)?%?";
    static final String RATE_KEY = "team.ui.F.pool.ratio";
    static final String UNLOCK_KEY = "team.ui.F.pool.unlockVRank";
    static final String MONTHLY_CAP_KEY = "team.ui.F.pool.monthlyCap";
    static final String CRON_KEY = "team.ui.F.pool.settleCron";
    static final String VERSION_KEY = "team.ui.F.pool.configVersion";

    private final PlatformConfigFacade configFacade;

    public SettlementConfig requireValid() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put(VERSION_KEY, required(VERSION_KEY));
        raw.put(RATE_KEY, required(RATE_KEY));
        raw.put(UNLOCK_KEY, required(UNLOCK_KEY));
        raw.put(MONTHLY_CAP_KEY, required(MONTHLY_CAP_KEY));
        raw.put(CRON_KEY, required(CRON_KEY));

        long version = parseVersion(raw.get(VERSION_KEY));
        BigDecimal injectRate = parseRate(raw.get(RATE_KEY));
        int unlockRank = parseUnlockRank(raw.get(UNLOCK_KEY));
        BigDecimal monthlyCap = parseMoney(raw.get(MONTHLY_CAP_KEY), MONTHLY_CAP_KEY);
        String springCron;
        try {
            springCron = LeadershipPoolSettleScheduler.normalizeCron(raw.get(CRON_KEY));
        } catch (IllegalArgumentException ex) {
            throw invalid(CRON_KEY, "INVALID_CRON", raw.get(CRON_KEY));
        }
        String canonical = version + "|" + injectRate.stripTrailingZeros().toPlainString()
                + "|" + unlockRank + "|" + monthlyCap.stripTrailingZeros().toPlainString()
                + "|" + springCron;
        return new SettlementConfig(version, injectRate, unlockRank, monthlyCap, springCron, sha256(canonical));
    }

    private String required(String key) {
        return configFacade.activeValue(key)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> invalid(key, "MISSING", null));
    }

    private long parseVersion(String value) {
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) {
            // converted to a stable domain error below
        }
        throw invalid(VERSION_KEY, "INVALID_VERSION", value);
    }

    private BigDecimal parseRate(String value) {
        try {
            return parseConfiguredRate(value);
        } catch (IllegalArgumentException ignored) {
            // converted to a stable domain error below
        }
        throw invalid(RATE_KEY, "INVALID_RATE", value);
    }

    /** Shared write/read parser: F.pool.ratio is always percentage points, capped at 30%. */
    static BigDecimal parseConfiguredRate(String value) {
        if (value == null) throw new IllegalArgumentException("F4_POOL_RATE_INVALID");
        try {
            String normalized = value.trim();
            if (!normalized.matches(DECIMAL_PERCENT_PATTERN)) {
                throw new IllegalArgumentException("F4_POOL_RATE_INVALID");
            }
            boolean percentSuffix = normalized.endsWith("%");
            BigDecimal configuredPercent = new BigDecimal(percentSuffix
                    ? normalized.substring(0, normalized.length() - 1)
                    : normalized);
            // Bare values always mean percentage points: 1 = 1%, 0.05 = 0.05%, 30 = 30%.
            if (configuredPercent.signum() >= 0
                    && configuredPercent.compareTo(MAX_SETTLEMENT_PERCENT) <= 0) {
                return configuredPercent.movePointLeft(2);
            }
        } catch (NumberFormatException ignored) {
            // stable validation error below
        }
        throw new IllegalArgumentException("F4_POOL_RATE_INVALID");
    }

    static String canonicalConfiguredPercent(String value) {
        return parseConfiguredRate(value).movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }

    static boolean isConfiguredRateIncrease(String oldValue, String newValue) {
        BigDecimal newRate = parseConfiguredRate(newValue);
        try {
            return newRate.compareTo(parseConfiguredRate(oldValue)) > 0;
        } catch (IllegalArgumentException oldValueWasNotExecutable) {
            // A missing/sentinel/illegal old value could not settle money, so its safe payout
            // baseline is zero. Any first positive rate is therefore an increase and must pass B1.
            return newRate.signum() > 0;
        }
    }

    private int parseUnlockRank(String value) {
        try {
            int parsed = Integer.parseInt(value.trim().toUpperCase().replaceFirst("^V", ""));
            if (parsed >= 1 && parsed <= 12) return parsed;
        } catch (NumberFormatException ignored) {
            // converted to a stable domain error below
        }
        throw invalid(UNLOCK_KEY, "INVALID_THRESHOLD", value);
    }

    private BigDecimal parseMoney(String value, String key) {
        String normalized = value.trim().toUpperCase().replace("$", "").replace(",", "");
        BigDecimal multiplier = BigDecimal.ONE;
        if (normalized.endsWith("M")) {
            multiplier = new BigDecimal("1000000");
            normalized = normalized.substring(0, normalized.length() - 1);
        } else if (normalized.endsWith("K")) {
            multiplier = new BigDecimal("1000");
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            BigDecimal parsed = new BigDecimal(normalized.trim()).multiply(multiplier);
            if (parsed.signum() >= 0) return parsed;
        } catch (NumberFormatException ignored) {
            // converted to a stable domain error below
        }
        throw invalid(key, "INVALID_THRESHOLD", value);
    }

    private ConfigUnavailableException invalid(String key, String reason, String rawValue) {
        return new ConfigUnavailableException(key, reason, fingerprint(rawValue));
    }

    private String fingerprint(String rawValue) {
        return rawValue == null ? "absent" : sha256(rawValue).substring(0, 16);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record SettlementConfig(
            long version,
            BigDecimal injectRate,
            int unlockRank,
            BigDecimal monthlyCap,
            String springCron,
            String fingerprint) {
    }

    public static final class ConfigUnavailableException extends RuntimeException {
        private final ConfigProblem problem;

        ConfigUnavailableException(String key, String reason, String valueFingerprint) {
            super("F4_SETTLEMENT_CONFIG_UNAVAILABLE");
            this.problem = new ConfigProblem(key, reason, valueFingerprint);
        }

        public String key() { return problem.key(); }
        public String reason() { return problem.reason(); }
        public String valueFingerprint() { return problem.valueFingerprint(); }
        public String signature() { return key() + ":" + reason() + ":" + valueFingerprint(); }
    }

    private record ConfigProblem(String key, String reason, String valueFingerprint) { }
}
