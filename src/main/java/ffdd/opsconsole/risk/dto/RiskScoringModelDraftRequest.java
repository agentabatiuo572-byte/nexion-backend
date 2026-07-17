package ffdd.opsconsole.risk.dto;

import java.util.Map;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

public record RiskScoringModelDraftRequest(
        Long expectedVersion,
        Map<String, ? extends Number> weights,
        Map<String, Boolean> inputSources,
        Map<String, Integer> scoreMappings,
        Integer lowMax,
        Integer highMin,
        Integer autoEscalateScore,
        String reason,
        String operator
) {
    public RiskScoringModelDraftRequest(
            Long expectedVersion, Map<String, ? extends Number> weights, Map<String, Boolean> inputSources,
            Integer lowMax, Integer highMin, Integer autoEscalateScore, String reason, String operator) {
        this(expectedVersion, weights, inputSources,
                ffdd.opsconsole.risk.application.K4RiskScorer.DEFAULT_MAPPINGS,
                lowMax, highMin, autoEscalateScore, reason, operator);
    }

    public RiskScoringModelDraftRequest withExpectedVersion(Long value) {
        return new RiskScoringModelDraftRequest(
                value, weights, inputSources, scoreMappings, lowMax, highMin, autoEscalateScore, reason, operator);
    }

    /**
     * Accepts the PRD ratio contract (sum 1.0 +/- 0.001) and legacy percentage payloads.
     * The current scoring engine stores whole percentage points, so arbitrary ratios are
     * deterministically canonicalized with the largest-remainder method instead of being rejected.
     */
    public Map<String, Integer> weightPercentages() {
        if (weights == null) return Map.of();
        BigDecimal sum = weights.values().stream()
                .filter(java.util.Objects::nonNull)
                .map(value -> new BigDecimal(value.toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean ratioContract = sum.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.001")) <= 0;
        boolean percentContract = sum.compareTo(new BigDecimal("100")) == 0;
        if (!ratioContract && !percentContract) return Map.of();
        Map<String, BigDecimal> exactPercentages = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Number> entry : weights.entrySet()) {
            if (entry.getValue() == null) return Map.of();
            BigDecimal value = new BigDecimal(entry.getValue().toString());
            if (value.signum() < 0 || (ratioContract && value.compareTo(BigDecimal.ONE) > 0)
                    || (percentContract && value.compareTo(new BigDecimal("100")) > 0)) {
                return Map.of();
            }
            BigDecimal percentage = ratioContract ? value.multiply(new BigDecimal("100")) : value;
            exactPercentages.put(entry.getKey(), percentage);
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        List<Map.Entry<String, BigDecimal>> remainders = new ArrayList<>();
        int assigned = 0;
        for (Map.Entry<String, BigDecimal> entry : exactPercentages.entrySet()) {
            int floor = entry.getValue().setScale(0, RoundingMode.FLOOR).intValueExact();
            result.put(entry.getKey(), floor);
            assigned += floor;
            remainders.add(Map.entry(entry.getKey(), entry.getValue().subtract(BigDecimal.valueOf(floor))));
        }
        remainders.sort(Comparator.<Map.Entry<String, BigDecimal>, BigDecimal>comparing(Map.Entry::getValue)
                .reversed().thenComparing(Map.Entry::getKey));
        int remaining = 100 - assigned;
        if (remaining < 0 || remaining > remainders.size()) return Map.of();
        for (int index = 0; index < remaining; index++) {
            String key = remainders.get(index).getKey();
            result.put(key, result.get(key) + 1);
        }
        return Map.copyOf(result);
    }
}
