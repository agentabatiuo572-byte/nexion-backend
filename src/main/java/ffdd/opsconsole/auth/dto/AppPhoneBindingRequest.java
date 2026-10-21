package ffdd.opsconsole.auth.dto;

public record AppPhoneBindingRequest(String countryCode, String phone, String challengeNo, String code) {
}
