package ffdd.opsconsole.auth.dto;

import java.math.BigDecimal;

public record UserLoginResponse(
        String accessToken,
        String tokenType,
        UserSession user,
        String challengeNo,
        String deliveryHint,
        String refreshToken,
        RegistrationReceipt registrationReceipt,
        String sessionSyncKey,
        String sessionGroup, String installationId, Long appSessionVersion, String sessionChainId,
        String loginAttemptId, String sessionEndedReason, Boolean executionSupported, String loginAttemptStatus) {
    public UserLoginResponse(String accessToken, String tokenType, UserSession user, String challengeNo,
            String deliveryHint, String refreshToken, RegistrationReceipt registrationReceipt, String sessionSyncKey) {
        this(accessToken, tokenType, user, challengeNo, deliveryHint, refreshToken, registrationReceipt, sessionSyncKey,
                null, null, null, null, null, null, null, null);
    }
    public UserLoginResponse(String accessToken, String tokenType, UserSession user,
                             String challengeNo, String deliveryHint, String refreshToken,
                             RegistrationReceipt registrationReceipt) {
        this(accessToken, tokenType, user, challengeNo, deliveryHint, refreshToken, registrationReceipt, null);
    }
    public UserLoginResponse(String accessToken, String tokenType, UserSession user) {
        this(accessToken, tokenType, user, null, null, null, null);
    }

    public UserLoginResponse(String accessToken, String tokenType, UserSession user, String refreshToken) {
        this(accessToken, tokenType, user, null, null, refreshToken, null);
    }

    public UserLoginResponse(String accessToken, String tokenType, UserSession user,
                             String challengeNo, String deliveryHint, String refreshToken) {
        this(accessToken, tokenType, user, challengeNo, deliveryHint, refreshToken, null);
    }

    /** A failed refresh returns only its verified session's termination reason, never credentials or identity. */
    public static UserLoginResponse ended(String reason) {
        return new UserLoginResponse(null, null, null, null, null, null, null, null,
                null, null, null, null, null, reason, null, null);
    }

    public static UserLoginResponse challenge(UserSession user, String challengeNo, String deliveryHint) {
        return new UserLoginResponse(null, "Challenge", user, challengeNo, deliveryHint, null, null);
    }

    public UserLoginResponse withRegistrationReceipt(RegistrationReceipt receipt) {
        return new UserLoginResponse(accessToken, tokenType, user, challengeNo, deliveryHint, refreshToken, receipt,
                sessionSyncKey, sessionGroup, installationId, appSessionVersion, sessionChainId, loginAttemptId,
                sessionEndedReason, executionSupported, loginAttemptStatus);
    }

    public UserLoginResponse withContext(ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity session, String status) {
        return new UserLoginResponse(accessToken, tokenType, user, challengeNo, deliveryHint, refreshToken, registrationReceipt,
                sessionSyncKey, session.getSessionGroup(), session.getInstallationId(), session.getAppSessionVersion(),
                session.getSessionChainId(), session.getLoginAttemptId(), session.getSessionEndedReason(),
                session.getExecutionSupported(), status);
    }

    public record UserSession(Long userId, String countryCode, String phone, String nickname,
                              boolean onboardingComplete) {
        public UserSession(Long userId, String countryCode, String phone, String nickname) {
            this(userId, countryCode, phone, nickname, false);
        }
    }

    public record RegistrationReceipt(
            String sponsorCode,
            String sponsorDisplayName,
            String sourceEnvironment,
            String giftStatus,
            BigDecimal giftUsdt,
            BigDecimal giftNex) {
    }
}
