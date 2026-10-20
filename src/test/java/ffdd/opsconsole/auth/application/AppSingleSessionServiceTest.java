package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.auth.mapper.AppLoginAttemptMapper;
import ffdd.opsconsole.common.boundary.PhoneExecutionSessionContract;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import ffdd.opsconsole.shared.security.UserAccountBlocklistVerifier;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

class AppSingleSessionServiceTest {
    final AuthSessionMapper sessions = mock(AuthSessionMapper.class);
    final AppLoginAttemptMapper attempts = mock(AppLoginAttemptMapper.class);
    final JwtTokenProvider tokens = mock(JwtTokenProvider.class);
    final PhoneExecutionSessionContract phone = mock(PhoneExecutionSessionContract.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final EventOutboxService outbox = mock(EventOutboxService.class);
    final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    final UserOpsMapper users = mock(UserOpsMapper.class);
    final UserAccountBlocklistVerifier blocklist = mock(UserAccountBlocklistVerifier.class);
    final MockEnvironment environment = new MockEnvironment();
    final AppSingleSessionService service = new AppSingleSessionService(sessions, attempts, tokens, phone,
            audit, outbox, config, users, blocklist, environment);
    UserEntity user;

    @BeforeEach void setup() {
        environment.setActiveProfiles("dev");
        user = new UserEntity(); user.setId(1L); user.setStatus("ACTIVE"); user.setIsDeleted(0); user.setSandbox(0);
        when(users.findLoginUserForUpdate(1L)).thenReturn(user);
        when(sessions.lockAccount(1)).thenReturn(1L);
        when(sessions.lockAppVersion(1)).thenReturn(4L);
        when(sessions.appInstallation(1)).thenReturn("phone-a");
        when(sessions.advanceAppState(eq(1L),eq(5L),anyString(),anyString(),anyBoolean())).thenReturn(1);
        when(sessions.insert(any(UserSessionEntity.class))).thenReturn(1);
        when(attempts.insert(any())).thenReturn(1);
        when(tokens.refreshSuccessor(anyString(),anyString())).thenReturn("fixed-refresh");
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
    }

    static AppLoginContext context(AppSessionSurface group, String installation, boolean execution) {
        return new AppLoginContext(group, installation, execution, "attempt_1234567890", "r".repeat(43), "OTP", "LOGIN-original");
    }

    @Test void appCommitsSequenceRevocationPhoneStopAndCanonicalEvidence() {
        var result = service.issue(1, context(AppSessionSurface.APP,"phone-b",true), "local",30);
        assertThat(result.session().getAppSessionVersion()).isEqualTo(5L);
        assertThat(result.session().getSessionGroup()).isEqualTo("APP");
        var order=inOrder(sessions,phone,attempts,audit,outbox);
        order.verify(sessions).lockAccount(1);
        order.verify(sessions).replaceAppSessions(1);
        order.verify(sessions).advanceAppState(eq(1L),eq(5L),anyString(),eq("phone-b"),eq(true));
        order.verify(sessions).insert(any(UserSessionEntity.class));
        order.verify(phone).authenticatedApp(1,"phone-b",true);
        order.verify(attempts).insert(any());
        verify(outbox).publish(eq("USER_SECURITY"),eq("1"),eq("account.app_session_replaced"),any());
        verify(audit,times(2)).recordRequired(any());
    }

    @Test void unsupportedNativeStillTakesOverButNeverGainsExecution() {
        var result=service.issue(1,context(AppSessionSurface.APP,"unsupported",false),"local",30);
        assertThat(result.session().getExecutionSupported()).isFalse();
        verify(phone).authenticatedApp(1,"unsupported",false);
        verify(sessions).replaceAppSessions(1);
    }

    @Test void h5NeverMutatesAppSequenceOrPhone() {
        var result=service.issue(1,context(AppSessionSurface.H5,"browser-a",false),"local",30);
        assertThat(result.session().getAppSessionVersion()).isNull();
        verify(sessions,never()).initializeAppState(anyLong());
        verifyNoInteractions(phone,outbox);
    }

    @Test void unknownAndInvalidCapabilityCannotIssue() {
        assertThatThrownBy(()->service.issue(1,AppLoginContext.unavailable(null),"local",30))
                .hasMessage("SESSION_CONTEXT_REAUTH_REQUIRED");
        assertThatThrownBy(()->service.issue(1,context(AppSessionSurface.H5,"browser",true),"local",30))
                .hasMessage("LOGIN_EXECUTION_CONTEXT_INVALID");
        verifyNoInteractions(sessions,phone,attempts);
    }

    AppSingleSessionService.Issued issuedAndRecoverable(AppLoginContext context) {
        var issued=service.issue(1,context,"local",30);
        var captured=ArgumentCaptor.forClass(AppLoginAttemptMapper.Attempt.class);
        verify(attempts).insert(captured.capture());
        when(attempts.find(context.loginAttemptId())).thenReturn(captured.getValue());
        when(attempts.findLocked(context.loginAttemptId())).thenReturn(captured.getValue());
        when(sessions.findRefreshForUpdate(issued.session().getRefreshTokenId())).thenReturn(issued.session());
        when(sessions.countFixedContext(issued.session().getRefreshTokenId(),1)).thenReturn(1);
        clearInvocations(phone,sessions,attempts,audit,outbox);
        return issued;
    }

    @Test void exactAttemptRecoversWithoutNewSequenceOrPhoneMutation() {
        var context=context(AppSessionSurface.APP,"phone-b",true);
        var original=issuedAndRecoverable(context);
        var replay=service.recover(context);
        assertThat(replay.original()).isTrue();
        assertThat(replay.refreshToken()).isEqualTo(original.refreshToken());
        verify(sessions,never()).replaceAppSessions(anyLong());
        verifyNoInteractions(phone,audit,outbox);
    }

    @Test void proofAndSemanticContextAreBothRequired() {
        var context=context(AppSessionSurface.APP,"phone-b",true); issuedAndRecoverable(context);
        assertThatThrownBy(()->service.recover(new AppLoginContext(context.group(),context.installationId(),true,
                context.loginAttemptId(),"x".repeat(43),context.authMethod(),context.authReference())))
                .hasMessage("LOGIN_ATTEMPT_RECOVERY_INVALID");
        assertThatThrownBy(()->service.recover(new AppLoginContext(context.group(),"phone-c",true,
                context.loginAttemptId(),context.recoveryProof(),context.authMethod(),context.authReference())))
                .hasMessage("LOGIN_ATTEMPT_CONTEXT_MISMATCH");
        verifyNoInteractions(phone,outbox);
    }

    @Test void replacedAttemptReturnsFactWithoutUsableCredential() {
        var context=context(AppSessionSurface.APP,"phone-b",true); var issued=issuedAndRecoverable(context);
        issued.session().setSessionEndedReason("APP_REPLACED");
        when(sessions.countFixedContext(issued.session().getRefreshTokenId(),1)).thenReturn(0);
        var result=service.recover(context);
        assertThat(result.refreshToken()).isNull(); assertThat(result.status()).isEqualTo("APP_REPLACED");
        verifyNoInteractions(phone,outbox);
    }

    @Test void recoveryCannotBypassIdleOrRotation() {
        var context=context(AppSessionSurface.APP,"phone-b",true); var issued=issuedAndRecoverable(context);
        issued.session().setLastActiveAt(LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE).minusDays(31));
        assertThat(service.recover(context).refreshToken()).isNull();
        issued.session().setRotationRedeemedAt(LocalDateTime.now());
        assertThat(service.recover(context).status()).isEqualTo("SESSION_ROTATED");
    }

    @Test void recoveryRechecksBlocklistPasswordResetAndEnvironment() {
        var context=context(AppSessionSurface.APP,"phone-b",true); issuedAndRecoverable(context);
        when(blocklist.isBlocked(1L)).thenReturn(true);
        assertThatThrownBy(()->service.recover(context)).hasMessage("USER_REFRESH_NOT_ALLOWED");
        when(blocklist.isBlocked(1L)).thenReturn(false); when(users.isPasswordResetRequired(1L)).thenReturn(true);
        assertThatThrownBy(()->service.recover(context)).hasMessage("USER_REFRESH_NOT_ALLOWED");
        when(users.isPasswordResetRequired(1L)).thenReturn(false); environment.setActiveProfiles("sandbox");
        assertThatThrownBy(()->service.recover(context)).hasMessage("USER_AUTH_ENVIRONMENT_FORBIDDEN");
    }

    @Test void lateLogoutNeverStopsNewOwner() {
        UserSessionEntity old=new UserSessionEntity();old.setUserId(1L);old.setSessionGroup("APP");
        old.setRefreshTokenId("old");old.setSessionChainId("old-chain");
        service.logout(old);
        verifyNoInteractions(phone);
        verify(sessions).endChain("old-chain","CURRENT_LOGOUT");
    }

    @Test void evidenceFailureEscapesForTransactionRollback() {
        doThrow(new IllegalStateException("OUTBOX_FAILED")).when(outbox).publish(anyString(),anyString(),anyString(),any());
        assertThatThrownBy(()->service.issue(1,context(AppSessionSurface.APP,"phone-b",true),"local",30)).hasMessage("OUTBOX_FAILED");
    }
}
