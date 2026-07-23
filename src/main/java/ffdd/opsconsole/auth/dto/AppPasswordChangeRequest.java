package ffdd.opsconsole.auth.dto;

public record AppPasswordChangeRequest(String currentPassword, String newPassword) {
}
