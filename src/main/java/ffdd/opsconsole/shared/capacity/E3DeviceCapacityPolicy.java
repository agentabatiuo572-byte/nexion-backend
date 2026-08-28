package ffdd.opsconsole.shared.capacity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canonical E3 device policy shared by projections and money settlement.
 *
 * <p>Callers provide the server clock and server-loaded configuration. This
 * keeps the displayed capacity, subsidy countdown and posted reward on one
 * deterministic contract instead of reimplementing the curve in each path.</p>
 */
public final class E3DeviceCapacityPolicy {
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100).setScale(6, RoundingMode.HALF_UP);
    private static final long DAY_MILLIS = Duration.ofDays(1).toMillis();
    private static final Set<String> REQUIRED_KEYS = Set.of(
            "capacityBand1DeltaPct", "capacityBand2DeltaPct", "capacityBand3DeltaPct",
            "stageEarlyEnd", "stageMidEnd", "cycleMonths", "capacityFloorPct", "capacitySubsidyDays",
            "taskLockS1", "taskLockPro", "taskLockRack",
            "capacityApplyToPhone", "capacityApplyToCloudShare", "capacityApplyToPcGpu",
            "capacityApplyToS1", "capacityApplyToPro", "capacityApplyToProV2",
            "capacityApplyToRackP1", "capacityApplyToRackP2");

    private E3DeviceCapacityPolicy() {
    }

    public static boolean validConfig(Map<String, String> config) {
        if (config == null || !config.keySet().containsAll(REQUIRED_KEYS)) return false;
        try {
            List<BigDecimal> deltas = List.of(
                    decimal(config, "capacityBand1DeltaPct"),
                    decimal(config, "capacityBand2DeltaPct"),
                    decimal(config, "capacityBand3DeltaPct"));
            if (deltas.stream().anyMatch(value -> value.compareTo(BigDecimal.valueOf(-100)) < 0
                    || value.compareTo(BigDecimal.valueOf(100)) > 0)) return false;
            int early = integer(config, "stageEarlyEnd");
            int mid = integer(config, "stageMidEnd");
            int cycle = integer(config, "cycleMonths");
            int subsidyDays = integer(config, "capacitySubsidyDays");
            List<Integer> taskLocks = List.of(
                    integer(config, "taskLockS1"),
                    integer(config, "taskLockPro"),
                    integer(config, "taskLockRack"));
            BigDecimal floor = decimal(config, "capacityFloorPct");
            if (early <= 0 || mid <= early || cycle <= mid || subsidyDays < 0 || floor.signum() < 0
                    || floor.compareTo(BigDecimal.valueOf(100)) > 0) return false;
            if (taskLocks.stream().anyMatch(value -> value < 0 || value > 10080)) return false;
            return REQUIRED_KEYS.stream().filter(key -> key.startsWith("capacityApplyTo"))
                    .allMatch(key -> "true".equalsIgnoreCase(config.get(key))
                            || "false".equalsIgnoreCase(config.get(key)));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public static Projection project(
            String productCode,
            String deviceType,
            LocalDateTime purchasedAt,
            LocalDateTime activatedAt,
            LocalDateTime now,
            Map<String, String> config) {
        if (now == null || !validConfig(config)) {
            throw new IllegalArgumentException("E3_CAPACITY_CONFIG_INVALID");
        }
        LocalDateTime lifecycleStartedAt = purchasedAt != null ? purchasedAt : activatedAt;
        if (lifecycleStartedAt == null) {
            throw new IllegalArgumentException("E3_DEVICE_LIFECYCLE_START_UNAVAILABLE");
        }
        if (lifecycleStartedAt.isAfter(now)) {
            throw new IllegalArgumentException("E3_DEVICE_LIFECYCLE_START_INVALID");
        }
        String configKey = classify(productCode, deviceType);
        if (configKey == null) {
            throw new IllegalArgumentException("E3_DEVICE_CAPACITY_CLASSIFICATION_MISSING");
        }
        int ageMonths = ageMonths(lifecycleStartedAt, now);
        BigDecimal capacityPct = Boolean.parseBoolean(config.get(configKey))
                ? E3CapacityCurve.capacityPct(ageMonths, config)
                : ONE_HUNDRED;
        int subsidyDays = integer(config, "capacitySubsidyDays");
        LocalDateTime subsidyEndsAt = lifecycleStartedAt.plusDays(subsidyDays);
        boolean subsidized = subsidyDays > 0 && subsidyEndsAt != null && now.isBefore(subsidyEndsAt);
        int remainingDays = subsidized ? remainingDays(now, subsidyEndsAt) : 0;
        return new Projection(
                configKey, ageMonths, capacityPct, subsidyDays,
                subsidized, remainingDays, subsidyEndsAt);
    }

    public static BigDecimal applyCapacity(BigDecimal amount, Projection projection) {
        if (amount == null || amount.signum() < 0 || projection == null) {
            throw new IllegalArgumentException("E3_CAPACITY_AMOUNT_INVALID");
        }
        return amount.multiply(projection.capacityPct().movePointLeft(2))
                .setScale(6, RoundingMode.HALF_UP);
    }

    public static String classify(String productCode, String deviceType) {
        String identity = (String.valueOf(productCode) + " " + String.valueOf(deviceType))
                .toLowerCase(Locale.ROOT).replace("_", "-");
        if (identity.contains("phone") || identity.contains("mobile")) return "capacityApplyToPhone";
        if (identity.contains("cloud")) return "capacityApplyToCloudShare";
        if (identity.contains("pc") || identity.contains("gpu")) return "capacityApplyToPcGpu";
        if (identity.contains("share")) return "capacityApplyToCloudShare";
        if (identity.contains("rack-p2") || identity.contains("rackp2")) return "capacityApplyToRackP2";
        if (identity.contains("rack-p1") || identity.contains("rackp1") || identity.contains("rack")) {
            return "capacityApplyToRackP1";
        }
        if (identity.contains("pro-v2") || identity.contains("prov2")) return "capacityApplyToProV2";
        if (identity.contains("pro")) return "capacityApplyToPro";
        if (identity.contains("s1") || identity.contains("box")) return "capacityApplyToS1";
        return null;
    }

    private static int remainingDays(LocalDateTime now, LocalDateTime endsAt) {
        long millis = Duration.between(now, endsAt).toMillis();
        if (millis <= 0) return 0;
        return Math.toIntExact((millis + DAY_MILLIS - 1) / DAY_MILLIS);
    }

    private static int ageMonths(LocalDateTime purchasedAt, LocalDateTime now) {
        if (purchasedAt == null || !now.isAfter(purchasedAt)) return 0;
        long months = (long) (now.getYear() - purchasedAt.getYear()) * 12L
                + now.getMonthValue() - purchasedAt.getMonthValue();
        if (months > 0 && purchasedAt.plusMonths(months).isAfter(now)) months--;
        return Math.max(0, Math.toIntExact(months));
    }

    private static BigDecimal decimal(Map<String, String> config, String key) {
        return new BigDecimal(config.get(key));
    }

    private static int integer(Map<String, String> config, String key) {
        return decimal(config, key).intValueExact();
    }

    public record Projection(
            String configKey,
            int ageMonths,
            BigDecimal capacityPct,
            int subsidyDays,
            boolean subsidized,
            int subsidyRemainingDays,
            LocalDateTime subsidyEndsAt) {
    }
}
