package ffdd.opsconsole.auth.dto;

public record UserPasswordResetCompleteRequest(
        String countryCode,
        String phone,
        String currentPassword,
        String newPassword) {
}
