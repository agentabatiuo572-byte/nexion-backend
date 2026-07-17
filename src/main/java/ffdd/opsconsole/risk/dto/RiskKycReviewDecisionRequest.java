package ffdd.opsconsole.risk.dto;

public record RiskKycReviewDecisionRequest(
        String decision,
        Long expectedVersion,
        String reasonCode,
        String reason,
        String operator
) {
    public RiskKycReviewDecisionRequest(String decision, String reason, String operator) {
        this(decision, null, null, reason, operator);
    }
}
