package ffdd.opsconsole.risk.dto;

public record RiskDecisionRequest(
        String decision,
        String reason,
        String operator) {
}
