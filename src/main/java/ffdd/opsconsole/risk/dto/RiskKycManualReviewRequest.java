package ffdd.opsconsole.risk.dto;

public record RiskKycManualReviewRequest(
        String userNo,
        String reason,
        String operator
) {
}
