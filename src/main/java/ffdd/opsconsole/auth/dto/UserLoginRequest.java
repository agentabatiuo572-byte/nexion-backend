package ffdd.opsconsole.auth.dto;

public record UserLoginRequest(String countryCode, String phone, String password, String captchaTicket,
        String deviceName) {
    public UserLoginRequest(String countryCode, String phone, String password, String captchaTicket) {
        this(countryCode, phone, password, captchaTicket, null);
    }

    public UserLoginRequest(String countryCode, String phone, String password) {
        this(countryCode, phone, password, null, null);
    }
}
