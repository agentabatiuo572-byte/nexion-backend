package ffdd.opsconsole.shared.capacity;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Single server-side implementation of the E3 capacity curve.
 *
 * <p>The three monthly deltas compound. {@code cycleMonths} is deliberately
 * absent: it is the admin chart horizon, while band 3 remains open-ended until
 * the configured floor is reached.</p>
 */
public final class E3CapacityCurve {
    private static final MathContext MATH = new MathContext(24, RoundingMode.HALF_UP);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private E3CapacityCurve() {
    }

    public static BigDecimal capacityPct(int ageMonths, Map<String, String> config) {
        int month = Math.max(0, ageMonths);
        int earlyEnd = integer(config, "stageEarlyEnd", "3");
        int midEnd = integer(config, "stageMidEnd", "8");
        int band1Months = Math.min(month, earlyEnd);
        int band2Months = Math.max(Math.min(month, midEnd) - earlyEnd, 0);
        int band3Months = Math.max(month - midEnd, 0);

        BigDecimal factor = monthlyFactor(config, "capacityBand1DeltaPct", "-3").pow(band1Months, MATH)
                .multiply(monthlyFactor(config, "capacityBand2DeltaPct", "-6").pow(band2Months, MATH), MATH)
                .multiply(monthlyFactor(config, "capacityBand3DeltaPct", "-23.7").pow(band3Months, MATH), MATH);
        BigDecimal floor = decimal(config, "capacityFloorPct", "22");
        return ONE_HUNDRED.multiply(factor, MATH)
                .max(floor)
                .setScale(6, RoundingMode.HALF_UP);
    }

    /** Returns true when the candidate curve pays more in any meaningful future segment. */
    public static boolean amplifiesAtAnyFutureMonth(
            Map<String, String> before, Map<String, String> candidate) {
        return futureProbeMonths(before, candidate).stream().anyMatch(month ->
                capacityPct(month, candidate).compareTo(capacityPct(month, before)) > 0);
    }

    /** Compares effective payout when an SKU enters or leaves the E3 curve. */
    public static boolean participationChangeAmplifies(
            Map<String, String> before,
            Map<String, String> candidate,
            boolean beforeParticipates,
            boolean candidateParticipates) {
        return futureProbeMonths(before, candidate).stream().anyMatch(month -> {
            BigDecimal beforePct = beforeParticipates ? capacityPct(month, before) : ONE_HUNDRED;
            BigDecimal candidatePct = candidateParticipates ? capacityPct(month, candidate) : ONE_HUNDRED;
            return candidatePct.compareTo(beforePct) > 0;
        });
    }

    private static Set<Integer> futureProbeMonths(
            Map<String, String> before, Map<String, String> candidate) {
        Set<Integer> probes = new TreeSet<>();
        probes.add(1);
        addBoundaryProbes(probes, integer(before, "stageEarlyEnd", "3"));
        addBoundaryProbes(probes, integer(before, "stageMidEnd", "8"));
        addBoundaryProbes(probes, integer(candidate, "stageEarlyEnd", "3"));
        addBoundaryProbes(probes, integer(candidate, "stageMidEnd", "8"));
        int lastBoundary = probes.stream().mapToInt(Integer::intValue).max().orElse(1);
        addProbe(probes, (long) lastBoundary + 12L);
        addProbe(probes, (long) lastBoundary + 120L);
        return probes;
    }

    private static BigDecimal monthlyFactor(Map<String, String> config, String key, String fallback) {
        return BigDecimal.ONE.add(decimal(config, key, fallback).movePointLeft(2), MATH);
    }

    private static BigDecimal decimal(Map<String, String> config, String key, String fallback) {
        return new BigDecimal(config.getOrDefault(key, fallback));
    }

    private static int integer(Map<String, String> config, String key, String fallback) {
        return decimal(config, key, fallback).intValueExact();
    }

    private static void addBoundaryProbes(Set<Integer> probes, int boundary) {
        addProbe(probes, (long) boundary - 1L);
        addProbe(probes, boundary);
        addProbe(probes, (long) boundary + 1L);
    }

    private static void addProbe(Set<Integer> probes, long month) {
        if (month > 0 && month <= Integer.MAX_VALUE) probes.add((int) month);
    }
}
