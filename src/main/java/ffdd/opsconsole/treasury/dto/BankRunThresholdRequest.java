package ffdd.opsconsole.treasury.dto;

public record BankRunThresholdRequest(
        String yellowPct,
        String redlinePct,
        String reason,
        String operator) {
}
