package ffdd.opsconsole.risk.domain;

import java.time.LocalDateTime;

public record RiskRuleView(
        String ruleId,
        String dimension,
        String conditionText,
        String action,
        String state,
        Boolean builtIn,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
