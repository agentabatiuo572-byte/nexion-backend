package ffdd.opsconsole.auth.dto;

public record UserOtpLoginVerifyRequest(
        String countryCode,
        String phone,
        String challengeNo,
        String code) {
}
