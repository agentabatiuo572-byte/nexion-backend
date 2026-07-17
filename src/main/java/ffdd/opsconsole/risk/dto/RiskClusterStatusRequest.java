package ffdd.opsconsole.risk.dto;

public record RiskClusterStatusRequest(
        String status,
        String reason,
        String operator,
        Long expectedVersion
) {
    public RiskClusterStatusRequest(String status, String reason, String operator) {
        this(status, reason, operator, null);
    }
}
