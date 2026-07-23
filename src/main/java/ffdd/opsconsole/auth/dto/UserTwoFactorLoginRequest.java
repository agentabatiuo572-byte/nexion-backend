package ffdd.opsconsole.auth.dto;

public record UserTwoFactorLoginRequest(
        String countryCode,
        String phone,
        String password,
        String challengeNo,
        String code) {
}
