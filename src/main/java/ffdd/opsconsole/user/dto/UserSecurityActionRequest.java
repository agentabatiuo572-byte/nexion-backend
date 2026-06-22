package ffdd.opsconsole.user.dto;

public record UserSecurityActionRequest(
        String reason,
        String operator) {
}
