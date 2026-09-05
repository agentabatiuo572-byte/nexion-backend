package ffdd.opsconsole.auth.dto;

public record UserLoginRequest(String countryCode, String phone, String password, String captchaTicket) {
    public UserLoginRequest(String countryCode, String phone, String password) {
        this(countryCode, phone, password, null);
    }
}
