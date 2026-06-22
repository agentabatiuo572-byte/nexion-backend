package ffdd.opsconsole.content.dto;

import java.math.BigDecimal;

public record LearningRewardUpdateRequest(
        BigDecimal rewardNex,
        String operator,
        String reason) {
}
