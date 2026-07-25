package ffdd.opsconsole.finance.dto;

public record VietQrReconciliationCommandRequest(
        Long expectedVersion,
        Long userId,
        String intentNo,
        String reason,
        String operator) {
}
