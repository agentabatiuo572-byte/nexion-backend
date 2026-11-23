package ffdd.opsconsole.auth.dto;

public record UserOtpLoginVerifyRequest(
        String countryCode,
        String phone,
        String challengeNo,
        String code,
        String deviceName) {
    public UserOtpLoginVerifyRequest(String countryCode, String phone, String challengeNo, String code) {
        this(countryCode, phone, challengeNo, code, null);
    }
}
