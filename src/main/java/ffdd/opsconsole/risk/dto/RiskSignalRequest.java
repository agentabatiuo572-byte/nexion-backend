package ffdd.opsconsole.risk.dto;

public record RiskSignalRequest(
        Long userId,
        String signalType,
        String severity,
        String evidence,
        String reason,
        String operator) {
}
