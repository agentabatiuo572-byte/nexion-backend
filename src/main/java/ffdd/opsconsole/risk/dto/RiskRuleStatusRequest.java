package ffdd.opsconsole.risk.dto;

public record RiskRuleStatusRequest(
        String state,
        Long expectedVersion,
        String reason,
        String operator
) {
    public RiskRuleStatusRequest(String state, String reason, String operator) {
        this(state, 0L, reason, operator);
    }
}
