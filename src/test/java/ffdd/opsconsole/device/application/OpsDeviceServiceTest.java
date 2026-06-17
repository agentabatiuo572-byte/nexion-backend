package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.device.domain.DeviceOpsRepository;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.device.dto.DatacenterOpsRequest;
import ffdd.opsconsole.device.dto.DeviceOpsQueryRequest;
import ffdd.opsconsole.device.dto.DeviceRestoreRequest;
import ffdd.opsconsole.device.dto.E3ConfigUpdateRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsDeviceServiceTest {
    private final FakeDeviceOpsRepository deviceRepository = new FakeDeviceOpsRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("UTC"));
    private final OpsDeviceService service = new OpsDeviceService(deviceRepository, auditLogService, clock);

    @Test
    void restoreRecycledDeviceReturnsOfflineAndAudits() {
        deviceRepository.device = device("RECYCLED", 0, LocalDateTime.now(clock));
        DeviceRestoreRequest request = new DeviceRestoreRequest("mistaken recycle", "superadmin");

        ApiResult<DeviceOpsView> result = service.restoreDevice(1L, "idem-restore", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("OFFLINE");
        assertThat(result.getData().deactivatedAt()).isNull();

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E3B_DEVICE_RESTORE");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("fromStatus", "RECYCLED")
                .containsEntry("toStatus", "OFFLINE")
                .containsEntry("idempotencyKey", "idem-restore");
    }

    @Test
    void restoreOnlineDeviceReturns409() {
        deviceRepository.device = device("ONLINE", 0, null);

        ApiResult<DeviceOpsView> result = service.restoreDevice(
                1L,
                "idem-restore",
                new DeviceRestoreRequest("wrong target", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.name());
    }

    @Test
    void restoreRequiresIdempotencyAndReason() {
        ApiResult<DeviceOpsView> missingKey = service.restoreDevice(
                1L,
                null,
                new DeviceRestoreRequest("mistaken recycle", "superadmin"));
        ApiResult<DeviceOpsView> missingReason = service.restoreDevice(
                1L,
                "idem-restore",
                new DeviceRestoreRequest("", "superadmin"));

        assertThat(missingKey.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(missingReason.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
    }

    @Test
    void updateE3ConfigWritesScalarConfigAndAudit() {
        E3ConfigUpdateRequest request = new E3ConfigUpdateRequest("E.tradein.promoCooldownDays", "21", "holiday window", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateE3Config("idem-e3", request);

        assertThat(result.getCode()).isZero();
        assertThat(deviceRepository.config).containsEntry("promoCooldownDays", "21");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E3_CONFIG_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-e3");
    }

    @Test
    void pauseDatacenterRequiresReasonAndUsesRepository() {
        DatacenterOpsRequest request = new DatacenterOpsRequest("maintenance", "superadmin");

        ApiResult<Map<String, Object>> result = service.pauseDatacenter("HK-1", "idem-dc", request);

        assertThat(result.getCode()).isZero();
        assertThat(deviceRepository.pausedDc).isEqualTo("HK-1");
        assertThat(deviceRepository.pauseReason).isEqualTo("maintenance");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    private static DeviceOpsView device(String status, Integer pendingDeactivate, LocalDateTime deactivatedAt) {
        return new DeviceOpsView(
                1L,
                1001L,
                "NX-001",
                "NexionBox S1",
                "S1",
                "stellarbox-s1",
                status,
                "HK-1",
                new BigDecimal("10"),
                new BigDecimal("1.00"),
                new BigDecimal("2.00"),
                null,
                null,
                deactivatedAt,
                pendingDeactivate,
                status,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static final class FakeDeviceOpsRepository implements DeviceOpsRepository {
        private DeviceOpsView device;
        private final Map<String, String> config = new LinkedHashMap<>(Map.of("promoCooldownDays", "14"));
        private String pausedDc;
        private String pauseReason;

        @Override
        public Map<String, Object> overviewCounters() {
            return new LinkedHashMap<>(Map.of("totalDevices", 1L, "datacenters", List.of()));
        }

        @Override
        public PageResult<DeviceOpsView> pageDevices(DeviceOpsQueryRequest request) {
            return new PageResult<>(1, 1, 20, List.of(device));
        }

        @Override
        public Optional<DeviceOpsView> findDevice(Long deviceId) {
            return Optional.ofNullable(device);
        }

        @Override
        public Optional<DeviceOpsView> restoreDevice(Long deviceId, LocalDateTime restoredAt) {
            device = device("OFFLINE", 0, null);
            return Optional.of(device);
        }

        @Override
        public Map<String, String> e3Config() {
            return config;
        }

        @Override
        public void upsertE3Config(String key, String value, String valueType, String operator) {
            config.put(key, value);
        }

        @Override
        public List<Map<String, Object>> datacenterSummaries() {
            return List.of();
        }

        @Override
        public void pauseDatacenter(String dcLocation, String reason, String operator, LocalDateTime now) {
            pausedDc = dcLocation;
            pauseReason = reason;
        }

        @Override
        public void resumeDatacenter(String dcLocation, String operator, LocalDateTime now) {
        }
    }
}
