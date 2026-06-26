package ffdd.opsconsole.risk.dto;

public record RiskClusterStatusRequest(
        String status,
        String reason,
        String operator
) {
}
