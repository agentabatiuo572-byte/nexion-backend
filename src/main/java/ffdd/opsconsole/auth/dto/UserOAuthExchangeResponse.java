package ffdd.opsconsole.auth.dto;

public record UserOAuthExchangeResponse(
        String accessToken,
        String tokenType,
        UserLoginResponse.UserSession user,
        String refreshToken,
        String source,
        boolean sandbox,
        String sessionSyncKey,
        String sessionGroup, String installationId, Long appSessionVersion, String sessionChainId,
        String loginAttemptId, String sessionEndedReason, Boolean executionSupported, String loginAttemptStatus) {
    public UserOAuthExchangeResponse(String accessToken, String tokenType, UserLoginResponse.UserSession user,
            String refreshToken, String source, boolean sandbox, String sessionSyncKey) {
        this(accessToken, tokenType, user, refreshToken, source, sandbox, sessionSyncKey,
                null, null, null, null, null, null, null, null);
    }
    public UserOAuthExchangeResponse(String accessToken, String tokenType, UserLoginResponse.UserSession user,
                                     String refreshToken, String source, boolean sandbox) {
        this(accessToken, tokenType, user, refreshToken, source, sandbox, null);
    }
}
