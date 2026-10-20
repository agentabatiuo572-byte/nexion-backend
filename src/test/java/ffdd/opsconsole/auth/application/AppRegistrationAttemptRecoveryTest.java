package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.auth.captcha.CaptchaOtpGate;
import ffdd.opsconsole.auth.dto.UserLoginResponse;
import ffdd.opsconsole.auth.dto.UserRegistrationRequest;
import ffdd.opsconsole.auth.mapper.AppUserRegistrationMapper;
import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import ffdd.opsconsole.content.facade.SupportRegistrationFacade;
import ffdd.opsconsole.growth.application.OpsReferralRewardService;
import ffdd.opsconsole.growth.facade.DayOneInstanceFacade;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AppRegistrationAttemptRecoveryTest {
    @Test void loserRecoversCommittedAttemptBeforeRecordingInvalidOtpOrCreatingAnotherAccount() {
        var mapper = mock(AppUserRegistrationMapper.class);
        var users = mock(UserOpsMapper.class);
        var auth = mock(AppUserAuthService.class);
        var accounts = mock(AuthNewAccountCreator.class);
        var executor = mock(AppUserRegistrationTransactionExecutor.class);
        when(executor.execute(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        var environment = new MockEnvironment(); environment.setActiveProfiles("prod");
        var service = new AppUserRegistrationService(mapper, users, mock(PasswordEncoder.class),
                mock(UserOtpDeliveryService.class), auth, mock(EventOutboxService.class), executor, environment,
                mock(OpsReferralRewardService.class), mock(PlatformConfigFacade.class), mock(UserLoginGuardMapper.class),
                mock(CaptchaOtpGate.class), mock(DayOneInstanceFacade.class), mock(SupportRegistrationFacade.class), accounts);
        var context = new AppLoginContext(AppSessionSurface.APP, "installation-A", true,
                "attempt-registration-123", "r".repeat(43), "REGISTRATION", "REG-" + "a".repeat(32));
        var receipt = ApiResult.ok(new UserLoginResponse("original-access", "Bearer",
                new UserLoginResponse.UserSession(42L, "+84", "987654321", "name"), "original-refresh"));
        when(auth.recoverCompletedLogin(context)).thenReturn(null, receipt);

        var result = service.register(new UserRegistrationRequest("+84", "987654321",
                "REG-" + "a".repeat(32), "123456", "CorrectPass2026!", null), "127.0.0.1", context);

        assertThat(result).isSameAs(receipt);
        verify(mapper).consumeValidChallengeInEnvironment(anyString(), eq("+84"), eq("987654321"),
                eq("PRODUCTION"), eq("123456"), eq(5));
        verify(mapper, never()).recordInvalidAttemptInEnvironment(any(), any(), any(), any(), anyInt());
        verifyNoInteractions(users, accounts);
        verify(auth, never()).issueRegisteredSession(any(), any(), any(AppLoginContext.class));
    }

    @Test void registrationTransactionUsesFreshCommittedReadsAfterOtpLockWait() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        String result = new AppUserRegistrationTransactionExecutor(manager).execute(() -> "done");
        assertThat(result).isEqualTo("done");
        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(definition.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
}
