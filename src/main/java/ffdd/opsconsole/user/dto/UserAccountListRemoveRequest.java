package ffdd.opsconsole.user.dto;

public record UserAccountListRemoveRequest(
        String reason,
        String operator) {
}
