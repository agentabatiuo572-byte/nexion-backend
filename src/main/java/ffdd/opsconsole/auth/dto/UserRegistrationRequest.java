package ffdd.opsconsole.auth.dto;

public record UserRegistrationRequest(
        String countryCode,
        String phone,
        String challengeNo,
        String code,
        String password,
        String sponsorCode,
        String language) {
    public UserRegistrationRequest(String countryCode, String phone, String challengeNo,
                                   String code, String password, String sponsorCode) {
        this(countryCode, phone, challengeNo, code, password, sponsorCode, null);
    }
}
