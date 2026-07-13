package ffdd.opsconsole.auth.dto;

public record UserLoginRequest(String countryCode, String phone, String password) {
}
