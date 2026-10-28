package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.common.boundary.PhoneReplacementContract.Account;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper.ModelLockRow;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PhoneModelLockServiceTest {
    private final OnboardingCalibrationMapper mapper = mock(OnboardingCalibrationMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final PhoneModelLockService service = new PhoneModelLockService(mapper, mock(PlatformConfigFacade.class), audit,
            Clock.systemUTC());
    private final Account account = new Account(9L, "PRODUCTION", "");

    @Test void releasingAHeldInstallationRemovesTheLockAndIsAudited() {
        when(mapper.lockUserSandbox(9L)).thenReturn(0);
        when(mapper.modelLocks(9L, "PRODUCTION", "")).thenReturn(List.of(row("ios", "iPhone 8", "MODEL_CHANGED")),
                List.of(row(null, null, "MODEL_CHANGED")));
        when(mapper.releaseModelLock(9L, "install-a", "PRODUCTION", "")).thenReturn(1);
        Map<String, Object> view = service.release(account, "install-a", 17L, "用户从备份恢复到新手机", "key-release-01");
        assertThat(view).containsEntry("releasable", false).containsEntry("lockedModel", null);
        verify(audit).recordRequired(argThat(event -> "ADMIN.PHONE_MODEL_LOCK_RELEASED".equals(event.getAction())
                && "install-a".equals(event.getResourceId())));
    }

    @Test void anInstallationWithoutAStoredLockIsReturnedUnchanged() {
        when(mapper.lockUserSandbox(9L)).thenReturn(0);
        when(mapper.modelLocks(9L, "PRODUCTION", "")).thenReturn(List.of(row(null, null, null)));
        assertThat(service.release(account, "install-a", 17L, "用户从备份恢复到新手机", "key-release-01")).containsEntry("releasable", false);
        verify(mapper, never()).releaseModelLock(any(), any(), any(), any());
        verify(audit, never()).recordRequired(any());
        assertThatThrownBy(() -> service.release(account, "missing", 17L, "用户从备份恢复到新手机", "key-release-02"))
                .hasMessage("PHONE_INSTALLATION_NOT_FOUND");
    }

    private static ModelLockRow row(String lockPlatform, String lockModel, String reason) {
        return new ModelLockRow("install-a", "ios", "iPhone 16 Pro", lockPlatform, lockModel,
                reason == null ? "MATCHED" : "PENDING_VERIFICATION", reason, "CALIBRATED", 1_700_000_000_000L);
    }
}
