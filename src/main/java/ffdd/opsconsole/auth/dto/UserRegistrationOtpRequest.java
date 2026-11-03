package ffdd.opsconsole.auth.dto;

public record UserRegistrationOtpRequest(String countryCode, String phone, String captchaTicket, String language) {
    public UserRegistrationOtpRequest(String countryCode, String phone, String captchaTicket) { this(countryCode, phone, captchaTicket, null); }
    public UserRegistrationOtpRequest(String countryCode, String phone) { this(countryCode, phone, null); }
}
