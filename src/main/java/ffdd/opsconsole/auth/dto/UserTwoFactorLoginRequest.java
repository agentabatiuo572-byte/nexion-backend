package ffdd.opsconsole.auth.dto;

public record UserTwoFactorLoginRequest(
        String countryCode,
        String phone,
        String password,
        String challengeNo,
        String code,
        String deviceName) {
    public UserTwoFactorLoginRequest(String countryCode, String phone, String password, String challengeNo,
            String code) {
        this(countryCode, phone, password, challengeNo, code, null);
    }
}
