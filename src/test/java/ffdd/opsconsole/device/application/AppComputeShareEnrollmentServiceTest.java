package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.mapper.AppComputeShareEnrollmentMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppComputeShareEnrollmentServiceTest {
    private final AppComputeShareEnrollmentMapper mapper = org.mockito.Mockito.mock(AppComputeShareEnrollmentMapper.class);
    private final PlatformConfigFacade config = org.mockito.Mockito.mock(PlatformConfigFacade.class);
    private final AdminIdempotencyService idempotency = org.mockito.Mockito.mock(AdminIdempotencyService.class);
    private final AuditLogService audit = org.mockito.Mockito.mock(AuditLogService.class);
    private final EventOutboxService outbox = org.mockito.Mockito.mock(EventOutboxService.class);
    private final MockEnvironment environment = new MockEnvironment().withProperty("spring.profiles.active", "prod");
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC);
    private final AppComputeShareEnrollmentService service = new AppComputeShareEnrollmentService(
            mapper, config, idempotency, audit, outbox, environment, clock);

    @BeforeEach
    void setUp() {
        environment.setActiveProfiles("prod");
        when(mapper.isProductionUser(42L)).thenReturn(1);
        when(mapper.lockProductionUser(42L)).thenReturn(42L);
        when(config.activeValue("E.compute.computeShareEnabled")).thenReturn(Optional.of("on"));
        when(mapper.activeDeviceCount(42L)).thenReturn(1);
        when(mapper.activeEnrollmentCount(42L, clock.instant())).thenReturn(0);
        when(mapper.deviceSlotCap()).thenReturn(6);
        when(mapper.insertEnrollment(any())).thenReturn(1);
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
    }

    @Test
    void createsServerOwnedPendingPairingWithoutCreatingADevice() {
        ApiResult<?> result = service.create(42L, "NVIDIA RTX 4070", "pair-key-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isInstanceOf(java.util.Map.class);
        assertThat((java.util.Map) result.getData())
                .containsEntry("status", "PENDING")
                .containsEntry("source", "server")
                .containsKey("pairingCode")
                .containsKey("enrollmentNo");
        verify(mapper).insertEnrollment(any());
        verify(mapper, never()).insertCanonicalDevice(any());
    }

    @Test
    void sandboxOrDisabledProfileCannotTouchProductionEnrollmentTables() {
        environment.setActiveProfiles("dev");
        assertThat(service.create(42L, "NVIDIA RTX 4070", "pair-key-2").getMessage())
                .isEqualTo("COMPUTE_SHARE_PRODUCTION_ENROLLMENT_FORBIDDEN");

        verify(mapper, never()).insertEnrollment(any());
        verify(audit, never()).recordRequiredForTrustedActor(any());
        verify(outbox, never()).publishUserEvent(anyString(), anyString(), anyString(), any(), anyString(), any(), anyString(), any());
    }

    @Test
    void trustedClaimCreatesCanonicalDeviceExactlyOnce() {
        var row = new AppComputeShareEnrollmentMapper.EnrollmentRow(
                9L, "CSE-1", 42L, "NVIDIA RTX 4070", AppComputeShareEnrollmentService.hashPairingCode("CSE-1", "731904"),
                "PENDING", null, clock.instant().plusSeconds(600), 0L);
        when(mapper.lockEnrollment("CSE-1")).thenReturn(row);
        when(mapper.findCanonicalDevice("JANUS-PC-1")).thenReturn(
                null, new AppComputeShareEnrollmentMapper.CanonicalDeviceRow(77L, 42L, "JANUS-PC-1"));
        when(mapper.insertCanonicalDevice(any())).thenReturn(1);
        when(mapper.completeEnrollment(9L, 0L, "JANUS-PC-1", 77L)).thenReturn(1);

        ApiResult<?> result = service.claim(42L, "CSE-1", "731904", "JANUS-PC-1",
                "NVIDIA RTX 4070", 12, 240);

        assertThat(result.getCode()).isZero();
        assertThat((java.util.Map) result.getData())
                .containsEntry("status", "CONNECTED")
                .containsEntry("deviceId", 77L);
        verify(mapper).insertCanonicalDevice(any());
        verify(mapper).completeEnrollment(9L, 0L, "JANUS-PC-1", 77L);
    }

    @Test
    void wrongPairingCodeCannotCreateDevice() {
        var row = new AppComputeShareEnrollmentMapper.EnrollmentRow(
                9L, "CSE-1", 42L, "NVIDIA RTX 4070", AppComputeShareEnrollmentService.hashPairingCode("CSE-1", "731904"),
                "PENDING", null, clock.instant().plusSeconds(600), 0L);
        when(mapper.lockEnrollment("CSE-1")).thenReturn(row);

        assertThat(service.claim(42L, "CSE-1", "000000", "JANUS-PC-1",
                "NVIDIA RTX 4070", 12, 240).getMessage()).isEqualTo("COMPUTE_SHARE_PAIRING_CODE_INVALID");
        verify(mapper, never()).insertCanonicalDevice(any());
    }
}
