package ffdd.opsconsole.treasury.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;

/** Threshold configuration shared by B5 and the J1 R1 trigger. */
public final class BankRunThresholdPolicy {
    public static final String YELLOW_CONFIG_KEY = "risk.bankrun-yellow-pct";
    public static final String REDLINE_CONFIG_KEY = "risk.bankrun-red-pct";
    public static final String APPROVAL_REQUIRED = "B5_BANKRUN_THRESHOLDS_APPROVAL_REQUIRED";
    private static final String VERSION_CONFIG_KEY = "risk.bankrun-threshold-version";
    private static final String VERSION_REMARK = "B5 optimistic concurrency version";
    public static final BigDecimal DEFAULT_YELLOW_PCT = new BigDecimal("20");
    public static final BigDecimal DEFAULT_REDLINE_PCT = new BigDecimal("40");
    public static final BigDecimal MIN_YELLOW_PCT = new BigDecimal("5");
    public static final BigDecimal MAX_YELLOW_PCT = new BigDecimal("50");
    public static final BigDecimal MIN_REDLINE_PCT = new BigDecimal("10");
    public static final BigDecimal MAX_REDLINE_PCT = new BigDecimal("80");

    private BankRunThresholdPolicy() {
    }

    public static Bands resolve(PlatformConfigFacade configFacade) {
        BigDecimal yellow = bounded(
                configFacade.activeValue(YELLOW_CONFIG_KEY).orElse(null),
                DEFAULT_YELLOW_PCT,
                MIN_YELLOW_PCT,
                MAX_YELLOW_PCT);
        BigDecimal redline = bounded(
                configFacade.activeValue(REDLINE_CONFIG_KEY).orElse(null),
                DEFAULT_REDLINE_PCT,
                MIN_REDLINE_PCT,
                MAX_REDLINE_PCT);
        if (redline.compareTo(yellow) <= 0) {
            return new Bands(DEFAULT_YELLOW_PCT, DEFAULT_REDLINE_PCT);
        }
        return new Bands(yellow.stripTrailingZeros(), redline.stripTrailingZeros());
    }

    public static long version(PlatformConfigFacade configFacade) {
        return parseVersion(configFacade.activeValue(VERSION_CONFIG_KEY).orElse("0"));
    }

    public static Change previewApproval(
            PlatformConfigFacade configFacade, BigDecimal yellow, BigDecimal redline, long expectedVersion) {
        Change change = proposedChange(configFacade, yellow, redline, expectedVersion, version(configFacade));
        requireAmplification(change);
        return change;
    }

    /** The caller owns the transaction. Legacy PATCH has no expected version. */
    public static Change applyDirect(
            PlatformConfigFacade configFacade, BigDecimal yellow, BigDecimal redline, Long expectedVersion) {
        return apply(configFacade, yellow, redline, expectedVersion, false);
    }

    /** The caller must already be executing a checked A2 replay. */
    public static Change applyApproved(
            PlatformConfigFacade configFacade, BigDecimal yellow, BigDecimal redline, long expectedVersion) {
        return apply(configFacade, yellow, redline, expectedVersion, true);
    }

    private static Change apply(
            PlatformConfigFacade configFacade, BigDecimal yellow, BigDecimal redline, Long expectedVersion,
            boolean approved) {
        if (configFacade.activeValue(VERSION_CONFIG_KEY).isEmpty()) {
            // A fresh database has no version row for SELECT FOR UPDATE to lock.
            configFacade.insertAdminValueIfMissing(VERSION_CONFIG_KEY, "0", "NUMBER", "risk", VERSION_REMARK);
        }
        long version = parseVersion(configFacade.activeValueForUpdate(VERSION_CONFIG_KEY)
                .orElseThrow(() -> new BizException(500, "B5_CONFIG_VERSION_INVALID")));
        Change change = proposedChange(configFacade, yellow, redline, expectedVersion, version);
        if (approved) {
            requireAmplification(change);
        } else if (change.amplifies()) {
            throw new BizException(409, APPROVAL_REQUIRED);
        }
        // Persist the effective pair. A partial write could unmask an invalid legacy pair
        // and change the effective redline without having checked that direction.
        configFacade.upsertAdminValue(YELLOW_CONFIG_KEY, plain(change.after().yellowPct()),
                "NUMBER", "risk", "B5 bank-run warning threshold");
        configFacade.upsertAdminValue(REDLINE_CONFIG_KEY, plain(change.after().redlinePct()),
                "NUMBER", "risk", "B5/J1 R1 shared bank-run redline");
        configFacade.upsertAdminValue(VERSION_CONFIG_KEY, String.valueOf(change.newVersion()),
                "NUMBER", "risk", VERSION_REMARK);
        return change;
    }

    private static Change proposedChange(
            PlatformConfigFacade configFacade, BigDecimal yellow, BigDecimal redline, Long expectedVersion,
            long version) {
        if (expectedVersion != null && expectedVersion != version) {
            throw new BizException(409, "B5_THRESHOLD_VERSION_CONFLICT");
        }
        Bands before = resolve(configFacade);
        Bands after = new Bands(yellow == null ? before.yellowPct() : yellow,
                redline == null ? before.redlinePct() : redline);
        if (after.redlinePct().compareTo(after.yellowPct()) <= 0) {
            throw new BizException(400, "BANKRUN_REDLINE_MUST_EXCEED_YELLOW");
        }
        return new Change(before, after, version + 1);
    }

    private static void requireAmplification(Change change) {
        if (!change.amplifies()) {
            throw new BizException(422, "B5_BANKRUN_THRESHOLDS_AMPLIFICATION_REQUIRED");
        }
    }

    private static long parseVersion(String raw) {
        try {
            long version = Long.parseLong(raw);
            if (version < 0) throw new NumberFormatException();
            return version;
        } catch (NumberFormatException ex) {
            throw new BizException(500, "B5_CONFIG_VERSION_INVALID");
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal bounded(
            String value, BigDecimal fallback, BigDecimal minInclusive, BigDecimal maxInclusive) {
        try {
            BigDecimal parsed = new BigDecimal(value == null ? "" : value.trim());
            return parsed.compareTo(minInclusive) >= 0 && parsed.compareTo(maxInclusive) <= 0
                    ? parsed
                    : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    public record Bands(BigDecimal yellowPct, BigDecimal redlinePct) {
    }

    public record Change(Bands before, Bands after, long newVersion) {
        private boolean amplifies() {
            return after.redlinePct().compareTo(before.redlinePct()) > 0;
        }
    }
}
