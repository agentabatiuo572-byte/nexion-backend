package ffdd.opsconsole.auth.dto;

public record UserPasswordResetOtpCompleteRequest(
        String countryCode,
        String phone,
        String challengeNo,
        String code,
        String newPassword) {
}
