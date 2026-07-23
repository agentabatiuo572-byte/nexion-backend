package ffdd.opsconsole.growth.dto;

import java.math.BigDecimal;
import java.util.Map;

public record GrowthWheelProbabilityBatchRequest(
        Map<String, BigDecimal> probabilities,
        String reason,
        String operator) {
}
