package ffdd.opsconsole.auth.dto;

public record UserOtpLoginRequest(String countryCode, String phone, String captchaTicket) {
    public UserOtpLoginRequest(String countryCode, String phone) { this(countryCode, phone, null); }
}
