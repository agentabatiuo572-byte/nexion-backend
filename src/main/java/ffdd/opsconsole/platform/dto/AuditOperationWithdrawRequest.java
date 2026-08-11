package ffdd.opsconsole.platform.dto;

public record AuditOperationWithdrawRequest(String reason, String expectedStatus, String operator) {
}
