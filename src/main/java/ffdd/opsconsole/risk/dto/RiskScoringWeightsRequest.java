package ffdd.opsconsole.risk.dto;

import java.util.Map;

public record RiskScoringWeightsRequest(
        Map<String, Integer> weights,
        String reason,
        String operator
) {
}
