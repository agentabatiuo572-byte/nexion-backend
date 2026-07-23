package ffdd.opsconsole.risk.dto;

public record B5BankRunThresholdRequest(
        String yellowPct,
        String redPct,
        Long expectedVersion,
        String reason,
        String operator) {
}
