package ffdd.opsconsole.user.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RegistrationRiskCaptchaRecoverySchedulerTest {

    @Test
    void expiredDeadlineIsPersistentlyRestoredAndRequiredAudited() {
        PlatformConfigFacade configFacade = mock(PlatformConfigFacade.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        when(configFacade.activeValue(RegistrationRiskCaptchaWindow.CONFIG_KEY))
                .thenReturn(Optional.of("2026-07-19T11:59:59Z"));
        when(configFacade.activeValueForUpdate(RegistrationRiskCaptchaWindow.VERSION_KEY))
                .thenReturn(Optional.of("7"));
        when(configFacade.activeValueForUpdate(RegistrationRiskCaptchaWindow.CONFIG_KEY))
                .thenReturn(Optional.of("2026-07-19T11:59:59Z"));
        RegistrationRiskCaptchaRecoveryScheduler scheduler = new RegistrationRiskCaptchaRecoveryScheduler(
                configFacade,
                auditLogService,
                Clock.fixed(Instant.parse("2026-07-19T12:00:00Z"), ZoneOffset.UTC));

        scheduler.restoreExpiredWindow();

        InOrder lockedWriteOrder = inOrder(configFacade);
        lockedWriteOrder.verify(configFacade).activeValueForUpdate(RegistrationRiskCaptchaWindow.VERSION_KEY);
        lockedWriteOrder.verify(configFacade).activeValueForUpdate(RegistrationRiskCaptchaWindow.CONFIG_KEY);
        lockedWriteOrder.verify(configFacade).upsertAdminValue(
                eq(RegistrationRiskCaptchaWindow.CONFIG_KEY), eq(""), eq("STRING"), eq("auth"), any());
        lockedWriteOrder.verify(configFacade).upsertAdminValue(
                eq(RegistrationRiskCaptchaWindow.VERSION_KEY), eq("8"), eq("NUMBER"), eq("auth"), any());
        verify(auditLogService).recordRequired(any());
    }
}
