package ffdd.opsconsole.risk.dto;

public record RiskClusterReviewRequest(String reason, String operator, Long expectedVersion) {
    public RiskClusterReviewRequest(String reason, String operator) {
        this(reason, operator, null);
    }
}
