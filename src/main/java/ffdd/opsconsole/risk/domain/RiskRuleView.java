package ffdd.opsconsole.risk.domain;

import java.time.LocalDateTime;

public record RiskRuleView(
        String ruleId,
        String dimension,
        String conditionText,
        String action,
        String state,
        Boolean builtIn,
        Integer priority,
        Long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public RiskRuleView(
            String ruleId, String dimension, String conditionText, String action,
            String state, Boolean builtIn, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(ruleId, dimension, conditionText, action, state, builtIn, 50, 0L, createdAt, updatedAt);
    }
}
