package ffdd.opsconsole.auth.dto;

import java.math.BigDecimal;

public record UserLoginResponse(
        String accessToken,
        String tokenType,
        UserSession user,
        String challengeNo,
        String deliveryHint,
        String refreshToken,
        RegistrationReceipt registrationReceipt) {
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

    public static UserLoginResponse challenge(UserSession user, String challengeNo, String deliveryHint) {
        return new UserLoginResponse(null, "Challenge", user, challengeNo, deliveryHint, null, null);
    }

    public UserLoginResponse withRegistrationReceipt(RegistrationReceipt receipt) {
        return new UserLoginResponse(accessToken, tokenType, user, challengeNo, deliveryHint, refreshToken, receipt);
    }

    public record UserSession(Long userId, String countryCode, String phone, String nickname) {
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
