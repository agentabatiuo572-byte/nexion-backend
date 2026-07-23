package ffdd.opsconsole.auth.dto;

public record UserLoginResponse(
        String accessToken,
        String tokenType,
        UserSession user,
        String challengeNo,
        String deliveryHint,
        String refreshToken) {
    public UserLoginResponse(String accessToken, String tokenType, UserSession user) {
        this(accessToken, tokenType, user, null, null, null);
    }

    public UserLoginResponse(String accessToken, String tokenType, UserSession user, String refreshToken) {
        this(accessToken, tokenType, user, null, null, refreshToken);
    }

    public static UserLoginResponse challenge(UserSession user, String challengeNo, String deliveryHint) {
        return new UserLoginResponse(null, "Challenge", user, challengeNo, deliveryHint, null);
    }

    public record UserSession(Long userId, String countryCode, String phone, String nickname) {
    }
}
