package ffdd.opsconsole.auth.dto;

public record UserRegistrationOtpRequest(String countryCode, String phone, String captchaTicket) {
    public UserRegistrationOtpRequest(String countryCode, String phone) { this(countryCode, phone, null); }
}
