package ffdd.opsconsole.auth.dto;

public record AdminPasswordChangeRequest(
        String currentPassword,
        String newPassword) {
}
