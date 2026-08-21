package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.risk.facade.TamperDetectionPublisher;
import ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.commerce.mapper.CommerceAcceptanceSandboxMapper;
import ffdd.opsconsole.commerce.application.CommerceAcceptanceRun;
import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class DeferredDeactivateCommandTest {
    @Test
    void commandRecordsPendingStateWhenAnActiveTaskExists() {
        CanonicalStateMapper mapper = mock(CanonicalStateMapper.class);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        stubIdempotency(idempotency);
        when(mapper.lockUser(7L)).thenReturn(new CanonicalStateMapper.UserLock(7L, false));
        when(mapper.lockDeviceForUserCommand(9L)).thenReturn(
                new CanonicalStateMapper.UserDeviceCommandRow(9L, 7L, "DEV-9", "ACTIVE", "OWNED", 4L, false));
        when(mapper.hasActiveTask(7L, 9L)).thenReturn(true);
        when(mapper.markDevicePendingDeactivate(7L, 9L, 4L)).thenReturn(1);
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[0]);

        AppCanonicalBoundaryService service = new AppCanonicalBoundaryService(
                mapper, mock(TamperDetectionPublisher.class), idempotency,
                mock(EventOutboxService.class), mock(AppGrowthLifecyclePublisher.class), mock(GrowthRhythmFacade.class),
                mock(AuditLogService.class), mock(CommerceAcceptanceSandboxMapper.class),
                mock(FundsSandboxProfileGuard.class), new CommerceAcceptanceRun("test-run"),
                mock(StorefrontProductReleasePolicy.class), new StorefrontPurchaseGatePolicy(), environment);
        ApiResult<Map<String, Object>> result = service.deactivateAfterTask(7L, 9L, 4L, "defer-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("status", "PENDING_DEACTIVATE");
        verify(mapper).markDevicePendingDeactivate(7L, 9L, 4L);
        verify(mapper, never()).deactivateOwnedDeviceCas(anyLong(), anyLong(), anyLong());
    }

    @Test
    void commandImmediatelyDeactivatesWhenThereIsNoCurrentTask() {
        CanonicalStateMapper mapper = mock(CanonicalStateMapper.class);
        AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
        stubIdempotency(idempotency);
        when(mapper.lockUser(7L)).thenReturn(new CanonicalStateMapper.UserLock(7L, false));
        when(mapper.lockDeviceForUserCommand(9L)).thenReturn(
                new CanonicalStateMapper.UserDeviceCommandRow(9L, 7L, "DEV-9", "ACTIVE", "OWNED", 4L, false));
        when(mapper.hasActiveTask(7L, 9L)).thenReturn(false);
        when(mapper.userEventAttribution(7L)).thenReturn(new CanonicalStateMapper.UserEventAttribution("P1", 1, "2026-W30"));
        when(mapper.deactivateOwnedDeviceCas(7L, 9L, 4L)).thenReturn(1);
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[0]);

        AppCanonicalBoundaryService service = new AppCanonicalBoundaryService(
                mapper, mock(TamperDetectionPublisher.class), idempotency,
                mock(EventOutboxService.class), mock(AppGrowthLifecyclePublisher.class), mock(GrowthRhythmFacade.class),
                mock(AuditLogService.class), mock(CommerceAcceptanceSandboxMapper.class),
                mock(FundsSandboxProfileGuard.class), new CommerceAcceptanceRun("test-run"),
                mock(StorefrontProductReleasePolicy.class), new StorefrontPurchaseGatePolicy(), environment);
        ApiResult<Map<String, Object>> result = service.deactivateAfterTask(7L, 9L, 4L, "defer-now");

        assertThat(result.getData()).containsEntry("status", "DEACTIVATED");
        verify(mapper, never()).markDevicePendingDeactivate(anyLong(), anyLong(), anyLong());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubIdempotency(AdminIdempotencyService idempotency) {
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier) invocation.getArgument(4)).get());
    }
}
