package ffdd.opsconsole.auth.dto;

public record UserOAuthExchangeResponse(
        String accessToken,
        String tokenType,
        UserLoginResponse.UserSession user,
        String refreshToken,
        String source,
        boolean sandbox,
        String sessionSyncKey) {
    public UserOAuthExchangeResponse(String accessToken, String tokenType, UserLoginResponse.UserSession user,
                                     String refreshToken, String source, boolean sandbox) {
        this(accessToken, tokenType, user, refreshToken, source, sandbox, null);
    }
}
