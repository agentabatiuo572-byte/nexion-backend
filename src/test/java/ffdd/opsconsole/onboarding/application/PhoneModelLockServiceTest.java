package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

    @Test void releasingAHeldInstallationMarksItReleasedAndIsAudited() {
        when(mapper.lockUserSandbox(9L)).thenReturn(0);
        when(mapper.modelLocks(9L, "PRODUCTION", "")).thenReturn(List.of(row("ios", "iPhone 8", "MODEL_CHANGED", null)),
                List.of(row(null, null, "MODEL_CHANGED", "true")));
        when(mapper.releaseModelLock(9L, "install-a", "PRODUCTION", "", 4L)).thenReturn(1);
        Map<String, Object> view = service.release(account, "install-a", 4L, 17L, "用户从备份恢复到新手机", "key-release-01");
        assertThat(view).containsEntry("released", true).containsEntry("releasable", false).containsEntry("lockedModel", null);
        verify(audit).recordRequired(argThat(event -> "ADMIN.PHONE_MODEL_LOCK_RELEASED".equals(event.getAction())
                && "install-a".equals(event.getResourceId())));
    }

    @Test void aRowCalibratedBeforeLocksWereStoredIsReleasableByItsLastReport() {
        when(mapper.modelLocks(9L, "PRODUCTION", "")).thenReturn(List.of(row(null, null, null, null)));
        assertThat(service.locks(account).get(0)).containsEntry("lockedModel", "iPhone 16 Pro").containsEntry("releasable", true);
    }

    @Test void aRetryIsANoOpAndANewerCalibrationIsAConflict() {
        when(mapper.lockUserSandbox(9L)).thenReturn(0);
        when(mapper.modelLocks(9L, "PRODUCTION", "")).thenReturn(List.of(row(null, null, "MODEL_CHANGED", "true")));
        assertThat(service.release(account, "install-a", 4L, 17L, "用户从备份恢复到新手机", "key-release-01")).containsEntry("released", true);
        verify(mapper, never()).releaseModelLock(any(), any(), any(), any(), anyLong());
        verify(audit, never()).recordRequired(any());
        // The customer calibrated the new phone after support looked: the lock now belongs to it.
        when(mapper.modelLocks(9L, "PRODUCTION", "")).thenReturn(List.of(recalibrated(row("ios", "iPhone 16 Pro", null, null))));
        assertThatThrownBy(() -> service.release(account, "install-a", 4L, 17L, "用户从备份恢复到新手机", "key-release-01"))
                .hasMessage("PHONE_MODEL_LOCK_RELEASE_CONFLICT");
        assertThatThrownBy(() -> service.release(account, "missing", 4L, 17L, "用户从备份恢复到新手机", "key-release-02"))
                .hasMessage("PHONE_INSTALLATION_NOT_FOUND");
    }

    private static ModelLockRow row(String lockPlatform, String lockModel, String reason, String released) {
        return new ModelLockRow("install-a", "ios", "iPhone 16 Pro", lockPlatform, lockModel,
                reason == null ? "MATCHED" : "PENDING_VERIFICATION", reason, "CALIBRATED", 1_700_000_000_000L,
                4L, released);
    }

    private static ModelLockRow recalibrated(ModelLockRow row) {
        return new ModelLockRow(row.deviceId(), row.platform(), row.model(), row.lockPlatform(), row.lockModel(),
                row.calibrationStatus(), row.pendingReason(), row.activationStatus(), row.updatedAt(), row.rowVersion() + 1, row.released());
    }
}
