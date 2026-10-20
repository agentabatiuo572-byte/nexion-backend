package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.auth.captcha.CaptchaOtpGate;
import ffdd.opsconsole.auth.dto.UserRefreshRequest;
import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import ffdd.opsconsole.content.application.SupportActivityService;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.*;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

class AppRefreshEndedReasonTest {
    private final AuthSessionMapper sessions = mock(AuthSessionMapper.class);
    private final UserOpsMapper users = mock(UserOpsMapper.class);
    private final JwtTokenProvider tokens = mock(JwtTokenProvider.class);
    private final UserSessionRevocationService revocations = mock(UserSessionRevocationService.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final AppUserAuthService auth = new AppUserAuthService(users, sessions, mock(UserLoginGuardMapper.class),
            mock(PasswordEncoder.class), tokens, new JwtProperties(), mock(UserAccountBlocklistVerifier.class),
            mock(PlatformConfigFacade.class), mock(CaptchaOtpGate.class), mock(UserOtpDeliveryService.class),
            mock(EventOutboxService.class), environment, mock(SupportActivityService.class),
            mock(AppSingleSessionService.class), revocations);

    AppRefreshEndedReasonTest() { environment.setActiveProfiles("dev"); }

    @ParameterizedTest
    @ValueSource(strings = {"APP_REPLACED", "CURRENT_LOGOUT", "SECURITY_REVOKED", "SESSION_EXPIRED"})
    void verifiedEndedRefreshProvidesOnlyTheReason(String reason) {
        known("APP", reason, 0);
        var result = auth.refresh(new UserRefreshRequest("owned-refresh-secret"));
        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getMessage()).isEqualTo("SESSION_CONTEXT_REAUTH_REQUIRED");
        assertThat(result.getData()).isEqualTo(ffdd.opsconsole.auth.dto.UserLoginResponse.ended(reason));
        verifyNoInteractions(tokens, revocations);
        verify(sessions, never()).insert(any(UserSessionEntity.class));
    }

    @Test void unknownTokenRevealsNoSessionState() {
        var result = auth.refresh(new UserRefreshRequest("unknown-secret"));
        assertThat(result.getMessage()).isEqualTo("USER_REFRESH_TOKEN_INVALID");
        assertThat(result.getData()).isNull();
        verifyNoInteractions(users, tokens, revocations);
        verify(sessions, never()).findRefreshForUpdate(any());
    }

    @Test void anotherEnvironmentRevealsNoSessionState() {
        known("APP", "APP_REPLACED", 1);
        assertThat(auth.refresh(new UserRefreshRequest("other-environment-secret")).getData()).isNull();
        verifyNoInteractions(tokens, revocations);
    }

    @Test void unknownLegacyContextNeverClaimsAnAppReplacement() {
        known("UNKNOWN", "APP_REPLACED", 0);
        assertThat(auth.refresh(new UserRefreshRequest("legacy-secret")).getData().sessionEndedReason())
                .isEqualTo("REAUTH_REQUIRED");
        verifyNoInteractions(tokens, revocations);
    }

    @Test void normalH5ExpirationRemainsExpiration() {
        var session = known("H5", null, 0);
        session.setExpiresAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE).minusSeconds(1));
        session.setLastActiveAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE));
        when(sessions.countFixedContext(any(), eq(42L))).thenReturn(1);
        var result = auth.refresh(new UserRefreshRequest("expired-h5-secret"));
        assertThat(result.getMessage()).isEqualTo("USER_REFRESH_TOKEN_EXPIRED");
        assertThat(result.getData().sessionEndedReason()).isEqualTo("SESSION_EXPIRED");
        verify(revocations).expireOwned(42L, "session-id");
        verifyNoInteractions(tokens);
    }

    private UserSessionEntity known(String group, String reason, int sandbox) {
        var session = new UserSessionEntity(); session.setUserId(42L); session.setRefreshTokenId("session-id");
        session.setSessionGroup(group); session.setSessionEndedReason(reason);
        session.setInstallationId("private-installation"); session.setSessionChainId("private-chain");
        when(sessions.findSession(any())).thenReturn(session);
        when(sessions.findRefreshForUpdate(any())).thenReturn(session);
        when(sessions.lockAccount(42L)).thenReturn(42L);
        var user = new UserEntity(); user.setId(42L); user.setSandbox(sandbox);
        when(users.findLoginUserForUpdate(42L)).thenReturn(user);
        return session;
    }
}
