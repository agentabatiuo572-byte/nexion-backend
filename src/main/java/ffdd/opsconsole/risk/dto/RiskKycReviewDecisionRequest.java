package ffdd.opsconsole.risk.dto;

public record RiskKycReviewDecisionRequest(
        String decision,
        String reason,
        String operator
) {
}
