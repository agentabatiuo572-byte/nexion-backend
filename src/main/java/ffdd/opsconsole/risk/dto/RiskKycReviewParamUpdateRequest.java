package ffdd.opsconsole.risk.dto;

public record RiskKycReviewParamUpdateRequest(
        String value,
        Long expectedVersion,
        String reason,
        String operator
) {
}
