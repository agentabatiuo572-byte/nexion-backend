package ffdd.opsconsole.auth.dto;

public record UserRegistrationRequest(
        String countryCode,
        String phone,
        String challengeNo,
        String code,
        String password,
        String sponsorCode) {
}
