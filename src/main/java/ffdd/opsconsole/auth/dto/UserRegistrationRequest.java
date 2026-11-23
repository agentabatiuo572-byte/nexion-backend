package ffdd.opsconsole.auth.dto;

public record UserRegistrationRequest(
        String countryCode,
        String phone,
        String challengeNo,
        String code,
        String password,
        String sponsorCode,
        String language,
        String deviceName) {
    public UserRegistrationRequest(String countryCode, String phone, String challengeNo,
                                   String code, String password, String sponsorCode, String language) {
        this(countryCode, phone, challengeNo, code, password, sponsorCode, language, null);
    }

    public UserRegistrationRequest(String countryCode, String phone, String challengeNo,
                                   String code, String password, String sponsorCode) {
        this(countryCode, phone, challengeNo, code, password, sponsorCode, null, null);
    }
}
