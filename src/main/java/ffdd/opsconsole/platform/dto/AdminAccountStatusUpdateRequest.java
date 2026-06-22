package ffdd.opsconsole.platform.dto;

public record AdminAccountStatusUpdateRequest(
        String status,
        String reason,
        String operator) {
}
