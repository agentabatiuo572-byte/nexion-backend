package ffdd.opsconsole.auth.dto;

public record UserLoginResponse(String accessToken, String tokenType, UserSession user) {
    public record UserSession(Long userId, String countryCode, String phone, String nickname) {
    }
}
