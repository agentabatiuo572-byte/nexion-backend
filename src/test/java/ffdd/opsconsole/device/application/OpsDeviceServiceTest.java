package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.device.domain.DeviceCatalogRepository;
import ffdd.opsconsole.device.domain.DeviceOrderView;
import ffdd.opsconsole.device.domain.DeviceOpsRepository;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.device.domain.DeviceReviewView;
import ffdd.opsconsole.device.domain.DeviceSkuView;
import ffdd.opsconsole.device.domain.DeviceTaskView;
import ffdd.opsconsole.device.domain.DeviceTradeinOverviewView;
import ffdd.opsconsole.device.domain.DeviceTradeinTxView;
import ffdd.opsconsole.device.dto.DatacenterOpsRequest;
import ffdd.opsconsole.device.dto.DeviceOrderActionRequest;
import ffdd.opsconsole.device.dto.DeviceOrderQueryRequest;
import ffdd.opsconsole.device.dto.DeviceOpsQueryRequest;
import ffdd.opsconsole.device.dto.DeviceReviewQueryRequest;
import ffdd.opsconsole.device.dto.DeviceReviewStatusRequest;
import ffdd.opsconsole.device.dto.DeviceReviewUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceRestoreRequest;
import ffdd.opsconsole.device.dto.DeviceSkuQueryRequest;
import ffdd.opsconsole.device.dto.DeviceSkuStatusRequest;
import ffdd.opsconsole.device.dto.DeviceSkuUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceTaskPriceRequest;
import ffdd.opsconsole.device.dto.DeviceTaskQueryRequest;
import ffdd.opsconsole.device.dto.DeviceTaskStatusRequest;
import ffdd.opsconsole.device.dto.DeviceTaskUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceTradeinActionRequest;
import ffdd.opsconsole.device.dto.E3ConfigUpdateRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
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
    private final FakeDeviceCatalogRepository catalogRepository = new FakeDeviceCatalogRepository();
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("UTC"));
    private final OpsDeviceService service = service();

    private OpsDeviceService service() {
        return new OpsDeviceService(deviceRepository, catalogRepository, configFacade, auditLogService, clock);
    }

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
    void updateE3ConfigAcceptsNegativeDegradeRate() {
        E3ConfigUpdateRequest request = new E3ConfigUpdateRequest("E.device.degradeLate", "-23.7", "align lifecycle curve", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateE3Config("idem-e3", request);

        assertThat(result.getCode()).isZero();
        assertThat(deviceRepository.config).containsEntry("degradeLate", "-23.7");
    }

    @Test
    void e1GenerationGatePersistsConfigAndAudits() {
        E3ConfigUpdateRequest request =
                new E3ConfigUpdateRequest("E.gen.stellarbox-pro-v2.phaseOffset", "2", "stage next release", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateE1GenerationGate("idem-e1-gate", request);

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("device.e1.generation.stellarbox-pro-v2.phaseOffset", "2");
        assertThat(result.getData().get("configValues").toString())
                .contains("E.gen.stellarbox-pro-v2.phaseOffset=2");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E1_GENERATION_GATE_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-e1-gate");
    }

    @Test
    void e3TradeinOverviewReturnsAtomicTxStats() {
        ApiResult<DeviceTradeinOverviewView> result = service.e3TradeinOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().txStats())
                .extracting(DeviceTradeinTxView::operation)
                .containsExactly("recycle", "replace", "deactivate");
        assertThat(deviceRepository.lastTradeinSince).isEqualTo(LocalDateTime.now(clock).minusHours(24));
        assertThat(deviceRepository.lastTradeinMonthStart).isEqualTo(LocalDateTime.of(2026, 6, 1, 0, 0));
    }

    @Test
    void executeTradeinActionWritesAudit() {
        deviceRepository.device = device("ONLINE", 0, null);

        ApiResult<DeviceOpsView> result = service.executeTradeinAction(
                "replace",
                "idem-tradein",
                new DeviceTradeinActionRequest(1L, "ops replacement", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("RECYCLED");
        assertThat(deviceRepository.lastTradeinOperation).isEqualTo("replace");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E3_TRADEIN_REPLACE");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("operation", "replace")
                .containsEntry("deviceId", 1L)
                .containsEntry("idempotencyKey", "idem-tradein");
    }

    @Test
    void executeTradeinActionRequiresIdempotencyAndReason() {
        ApiResult<DeviceOpsView> missingKey = service.executeTradeinAction(
                "recycle",
                null,
                new DeviceTradeinActionRequest(1L, "ops recycle", "superadmin"));
        ApiResult<DeviceOpsView> missingReason = service.executeTradeinAction(
                "deactivate",
                "idem-tradein",
                new DeviceTradeinActionRequest(1L, "", "superadmin"));
        ApiResult<DeviceOpsView> invalidOperation = service.executeTradeinAction(
                "unknown",
                "idem-tradein",
                new DeviceTradeinActionRequest(1L, "bad op", "superadmin"));

        assertThat(missingKey.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(missingReason.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
        assertThat(invalidOperation.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void executeTradeinActionReturns409WhenDeviceIsTerminal() {
        deviceRepository.device = device("RECYCLED", 0, LocalDateTime.now(clock));

        ApiResult<DeviceOpsView> result = service.executeTradeinAction(
                "deactivate",
                "idem-tradein",
                new DeviceTradeinActionRequest(1L, "already final", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void pauseDatacenterRequiresReasonAndUsesRepository() {
        DatacenterOpsRequest request = new DatacenterOpsRequest("maintenance", "superadmin");

        ApiResult<Map<String, Object>> result = service.pauseDatacenter("HK-1", "idem-dc", request);

        assertThat(result.getCode()).isZero();
        assertThat(deviceRepository.pausedDc).isEqualTo("HK-1");
        assertThat(deviceRepository.pauseReason).isEqualTo("maintenance");
    }

    @Test
    void createSkuRequiresCommandAndAudits() {
        DeviceSkuUpsertRequest request = skuRequest("stellarbox-test", "NexionBox Test", "pending");

        ApiResult<DeviceSkuView> result = service.createSku("idem-sku", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().skuId()).isEqualTo("stellarbox-test");
        assertThat(catalogRepository.sku.status()).isEqualTo("pending");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E1_SKU_CREATED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-sku");
    }

    @Test
    void createSkuRejectsValuesOutsideBackendOptions() {
        assertThat(service.createSku(
                        "idem-sku",
                        skuRequest("stellarbox-test", "NexionBox Test", "pending", "Enterprise", "HK-1", 1, "active", "P1"))
                .getMessage()).isEqualTo("SKU_TIER_INVALID");
        assertThat(service.createSku(
                        "idem-sku",
                        skuRequest("stellarbox-test", "NexionBox Test", "pending", "Entry", "", 1, "active", "P1"))
                .getMessage()).isEqualTo("SKU_DATACENTER_REQUIRED");
        assertThat(service.createSku(
                        "idem-sku",
                        skuRequest("stellarbox-test", "NexionBox Test", "pending", "Entry", "HK-1", 4, "active", "P1"))
                .getMessage()).isEqualTo("SKU_GENERATION_INVALID");
        assertThat(service.createSku(
                        "idem-sku",
                        skuRequest("stellarbox-test", "NexionBox Test", "pending", "Entry", "HK-1", 1, "sunset", "P1"))
                .getMessage()).isEqualTo("SKU_LIFECYCLE_INVALID");
        assertThat(service.createSku(
                        "idem-sku",
                        skuRequest("stellarbox-test", "NexionBox Test", "pending", "Entry", "HK-1", 1, "active", ""))
                .getMessage()).isEqualTo("SKU_UNLOCK_PHASE_INVALID");
    }

    @Test
    void createShareSkuAllowsBlankUnlockPhase() {
        DeviceSkuUpsertRequest request =
                skuRequest("shared-rack-test", "Nexion Shared Rack", "pending", " Share ", "HK-1", 2, "active", "");

        ApiResult<DeviceSkuView> result = service.createSku("idem-share-sku", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().skuId()).isEqualTo("shared-rack-test");
    }

    @Test
    void updateSkuStatusRejectsUnsupportedStatus() {
        catalogRepository.sku = sku("stellarbox-test", "NexionBox Test", "on");

        ApiResult<DeviceSkuView> result = service.updateSkuStatus(
                "stellarbox-test",
                "idem-sku",
                new DeviceSkuStatusRequest("deleted", "wrong status", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("SKU_STATUS_INVALID");
    }

    @Test
    void reviewStatusChangeWritesAudit() {
        catalogRepository.sku = sku("stellarbox-test", "NexionBox Test", "on");
        catalogRepository.review = review("rv-1", "published");

        ApiResult<DeviceReviewView> result = service.updateReviewStatus(
                "rv-1",
                "idem-review",
                new DeviceReviewStatusRequest("hidden", "content policy", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("hidden");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E1_REVIEW_STATUS_CHANGED");
    }

    @Test
    void taskPriceChangeRequiresPositivePriceAndAudits() {
        catalogRepository.task = task("TK-1", "LLM 推理 70B", new BigDecimal("0.46"), "active");

        ApiResult<DeviceTaskView> result = service.updateTaskPrice(
                "TK-1",
                "idem-task",
                new DeviceTaskPriceRequest(new BigDecimal("0.60"), "market rebalance", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().price()).isEqualByComparingTo("0.60");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E2_TASK_PRICE_CHANGED");
    }

    @Test
    void taskPriceRejectsInvalidPrice() {
        catalogRepository.task = task("TK-1", "LLM 推理 70B", new BigDecimal("0.46"), "active");

        ApiResult<DeviceTaskView> result = service.updateTaskPrice(
                "TK-1",
                "idem-task",
                new DeviceTaskPriceRequest(BigDecimal.ZERO, "bad price", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("TASK_PRICE_INVALID");
    }

    @Test
    void createTaskRejectsValuesOutsideBackendOptions() {
        ApiResult<DeviceTaskView> invalidUnit = service.createTask(
                "idem-task",
                taskRequest("LLM 推理 70B", "/token", "S1+"));
        ApiResult<DeviceTaskView> invalidRequirement = service.createTask(
                "idem-task",
                taskRequest("LLM 推理 70B", "/job", "手动输入机器"));

        assertThat(invalidUnit.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(invalidUnit.getMessage()).isEqualTo("TASK_UNIT_INVALID");
        assertThat(invalidRequirement.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(invalidRequirement.getMessage()).isEqualTo("TASK_REQUIREMENT_INVALID");
    }

    @Test
    void createTaskAcceptsBackendOptionValues() {
        ApiResult<DeviceTaskView> result = service.createTask(
                "idem-task",
                taskRequest("LLM 推理 70B", "/1k", "需 NexionBox Pro"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().taskId()).startsWith("TK-");
    }

    @Test
    void cancelOrderRejectsIllegalStateWith409() {
        catalogRepository.order = order("OD-1", "active");

        ApiResult<DeviceOrderView> result = service.cancelOrder(
                "OD-1",
                "idem-order",
                new DeviceOrderActionRequest(null, "customer request", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.name());
    }

    @Test
    void terminalOrderWritesAudit() {
        catalogRepository.order = order("OD-1", "failed");

        ApiResult<DeviceOrderView> result = service.terminalOrder(
                "OD-1",
                "idem-order",
                new DeviceOrderActionRequest("provisioning_failed", "dc timeout", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().state()).isEqualTo("provisioning_failed");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E4_ORDER_TERMINALIZED");
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

    private static DeviceSkuUpsertRequest skuRequest(String skuId, String name, String status) {
        return skuRequest(skuId, name, status, "Entry", "HK-1", 1, "active", "P1");
    }

    private static DeviceSkuUpsertRequest skuRequest(
            String skuId,
            String name,
            String status,
            String tier,
            String datacenter,
            Integer generation,
            String lifecycle,
            String unlockPhase) {
        return new DeviceSkuUpsertRequest(
                skuId,
                name,
                tier,
                "test sku",
                "New",
                "4x GPU",
                "96GB",
                "100 MH/s",
                "1200W",
                datacenter,
                new BigDecimal("1299"),
                new BigDecimal("12.3"),
                new BigDecimal("24"),
                null,
                null,
                "$12.30/d",
                1L,
                "10",
                new BigDecimal("4.8"),
                2L,
                100L,
                1000L,
                10L,
                5L,
                "LLM pool",
                List.of("managed"),
                generation,
                lifecycle,
                "",
                BigDecimal.ZERO,
                unlockPhase,
                null,
                null,
                null,
                "popular",
                status,
                "catalog update",
                "superadmin");
    }

    private static DeviceTaskUpsertRequest taskRequest(String name, String unit, String requirement) {
        return new DeviceTaskUpsertRequest(
                name,
                new BigDecimal("0.46"),
                unit,
                requirement,
                new BigDecimal("0.50"),
                "active",
                "catalog update",
                "superadmin");
    }

    private static DeviceSkuView sku(String skuId, String name, String status) {
        return new DeviceSkuView(
                skuId,
                name,
                "Entry",
                "test sku",
                "New",
                "4x GPU",
                "96GB",
                "100 MH/s",
                "1200W",
                "HK-1",
                new BigDecimal("1299"),
                new BigDecimal("12.3"),
                new BigDecimal("24"),
                null,
                null,
                "$12.30/d",
                1L,
                "10",
                new BigDecimal("4.8"),
                2L,
                100L,
                1000L,
                10L,
                5L,
                "LLM pool",
                List.of("managed"),
                1,
                "active",
                "",
                BigDecimal.ZERO,
                "P1",
                null,
                null,
                null,
                "popular",
                status,
                null,
                null);
    }

    private static DeviceReviewView review(String reviewId, String status) {
        return new DeviceReviewView(
                reviewId,
                "stellarbox-test",
                "NexionBox Test",
                "Maya",
                5,
                "Good yield",
                "刚刚",
                status,
                null,
                null);
    }

    private static DeviceTaskView task(String taskId, String name, BigDecimal price, String status) {
        return new DeviceTaskView(
                taskId,
                name,
                price,
                "/job",
                "S1+",
                new BigDecimal("0.50"),
                status,
                null,
                null);
    }

    private static DeviceOrderView order(String orderNo, String state) {
        return new DeviceOrderView(
                orderNo,
                "usr_1",
                "stellarbox-test",
                "NexionBox Test",
                new BigDecimal("1299"),
                state,
                "",
                "1m",
                null,
                null);
    }

    private static final class FakeDeviceOpsRepository implements DeviceOpsRepository {
        private DeviceOpsView device;
        private final Map<String, String> config = new LinkedHashMap<>(Map.of("promoCooldownDays", "14"));
        private String pausedDc;
        private String pauseReason;
        private String lastTradeinOperation;
        private LocalDateTime lastTradeinSince;
        private LocalDateTime lastTradeinMonthStart;

        @Override
        public Map<String, Object> overviewCounters() {
            return new LinkedHashMap<>(Map.of("totalDevices", 1L, "datacenters", List.of()));
        }

        @Override
        public PageResult<DeviceOpsView> pageDevices(DeviceOpsQueryRequest request) {
            return new PageResult<>(1, 1, 20, List.of(device));
        }

        @Override
        public List<DeviceOpsView> listUserDevices(Long userId, int limit) {
            return device == null ? List.of() : List.of(device);
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
        public DeviceTradeinOverviewView e3TradeinOverview(LocalDateTime since, LocalDateTime monthStart, int cliffMonth) {
            lastTradeinSince = since;
            lastTradeinMonthStart = monthStart;
            return new DeviceTradeinOverviewView(
                    new BigDecimal("5.2"),
                    3L,
                    4L,
                    new BigDecimal("284.00"),
                    1L,
                    List.of(
                            tx("recycle"),
                            tx("replace"),
                            tx("deactivate")));
        }

        @Override
        public Optional<DeviceOpsView> executeTradeinAction(String operation, Long deviceId, String tradeInNo, LocalDateTime now) {
            lastTradeinOperation = operation;
            if (device == null || "RECYCLED".equals(device.status())) {
                return Optional.empty();
            }
            device = device("deactivate".equals(operation) ? "DEACTIVATED" : "RECYCLED", 0, now);
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

        private static DeviceTradeinTxView tx(String operation) {
            return new DeviceTradeinTxView(
                    operation,
                    operation,
                    "/api/admin/devices/e3/tradein/" + operation,
                    1L,
                    0L,
                    0L,
                    "最新成功",
                    "ok",
                    null,
                    "ok",
                    1L,
                    "NX-001",
                    1001L);
        }
    }

    private static final class FakeDeviceCatalogRepository implements DeviceCatalogRepository {
        private DeviceSkuView sku;
        private DeviceReviewView review;
        private DeviceTaskView task;
        private DeviceOrderView order;

        @Override
        public PageResult<DeviceSkuView> pageSkus(DeviceSkuQueryRequest request) {
            return new PageResult<>(sku == null ? 0 : 1, 1, 20, sku == null ? List.of() : List.of(sku));
        }

        @Override
        public Optional<DeviceSkuView> findSku(String skuId) {
            return sku != null && sku.skuId().equals(skuId) ? Optional.of(sku) : Optional.empty();
        }

        @Override
        public DeviceSkuView createSku(String skuId, DeviceSkuUpsertRequest request, LocalDateTime now) {
            sku = sku(skuId, request.name(), request.status());
            return sku;
        }

        @Override
        public Optional<DeviceSkuView> updateSku(String skuId, DeviceSkuUpsertRequest request, LocalDateTime now) {
            if (sku == null || !sku.skuId().equals(skuId)) {
                return Optional.empty();
            }
            sku = sku(skuId, request.name(), request.status());
            return Optional.of(sku);
        }

        @Override
        public Optional<DeviceSkuView> updateSkuStatus(String skuId, String status, LocalDateTime now) {
            if (sku == null || !sku.skuId().equals(skuId)) {
                return Optional.empty();
            }
            sku = sku(sku.skuId(), sku.name(), status);
            return Optional.of(sku);
        }

        @Override
        public boolean softDeleteSku(String skuId, LocalDateTime now) {
            if (sku != null && sku.skuId().equals(skuId)) {
                sku = null;
                return true;
            }
            return false;
        }

        @Override
        public PageResult<DeviceReviewView> pageReviews(DeviceReviewQueryRequest request) {
            return new PageResult<>(review == null ? 0 : 1, 1, 20, review == null ? List.of() : List.of(review));
        }

        @Override
        public Optional<DeviceReviewView> findReview(String reviewId) {
            return review != null && review.reviewId().equals(reviewId) ? Optional.of(review) : Optional.empty();
        }

        @Override
        public DeviceReviewView createReview(String reviewId, DeviceReviewUpsertRequest request, LocalDateTime now) {
            review = review(reviewId, request.status());
            return review;
        }

        @Override
        public Optional<DeviceReviewView> updateReview(String reviewId, DeviceReviewUpsertRequest request, LocalDateTime now) {
            if (review == null || !review.reviewId().equals(reviewId)) {
                return Optional.empty();
            }
            review = review(reviewId, request.status());
            return Optional.of(review);
        }

        @Override
        public Optional<DeviceReviewView> updateReviewStatus(String reviewId, String status, LocalDateTime now) {
            if (review == null || !review.reviewId().equals(reviewId)) {
                return Optional.empty();
            }
            review = review(reviewId, status);
            return Optional.of(review);
        }

        @Override
        public boolean softDeleteReview(String reviewId, LocalDateTime now) {
            if (review != null && review.reviewId().equals(reviewId)) {
                review = null;
                return true;
            }
            return false;
        }

        @Override
        public PageResult<DeviceTaskView> pageTasks(DeviceTaskQueryRequest request) {
            return new PageResult<>(task == null ? 0 : 1, 1, 20, task == null ? List.of() : List.of(task));
        }

        @Override
        public Optional<DeviceTaskView> findTask(String taskId) {
            return task != null && task.taskId().equals(taskId) ? Optional.of(task) : Optional.empty();
        }

        @Override
        public DeviceTaskView createTask(String taskId, DeviceTaskUpsertRequest request, LocalDateTime now) {
            task = task(taskId, request.name(), request.price(), request.status());
            return task;
        }

        @Override
        public Optional<DeviceTaskView> updateTaskPrice(String taskId, BigDecimal price, LocalDateTime now) {
            if (task == null || !task.taskId().equals(taskId)) {
                return Optional.empty();
            }
            task = task(task.taskId(), task.name(), price, task.status());
            return Optional.of(task);
        }

        @Override
        public Optional<DeviceTaskView> updateTaskStatus(String taskId, String status, LocalDateTime now) {
            if (task == null || !task.taskId().equals(taskId)) {
                return Optional.empty();
            }
            task = task(task.taskId(), task.name(), task.price(), status);
            return Optional.of(task);
        }

        @Override
        public boolean softDeleteTask(String taskId, LocalDateTime now) {
            if (task != null && task.taskId().equals(taskId)) {
                task = null;
                return true;
            }
            return false;
        }

        @Override
        public PageResult<DeviceOrderView> pageOrders(DeviceOrderQueryRequest request) {
            return new PageResult<>(order == null ? 0 : 1, 1, 20, order == null ? List.of() : List.of(order));
        }

        @Override
        public Optional<DeviceOrderView> findOrder(String orderNo) {
            return order != null && order.orderNo().equals(orderNo) ? Optional.of(order) : Optional.empty();
        }

        @Override
        public Optional<DeviceOrderView> updateOrderState(String orderNo, String state, LocalDateTime now) {
            if (order == null || !order.orderNo().equals(orderNo)) {
                return Optional.empty();
            }
            order = order(order.orderNo(), state);
            return Optional.of(order);
        }
    }

    private static final class FakePlatformConfigFacade implements PlatformConfigFacade {
        private final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public Optional<String> activeValue(String configKey) {
            return Optional.ofNullable(values.get(configKey));
        }

        @Override
        public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
            values.put(configKey, configValue);
        }

        @Override
        public Map<String, String> activeValuesByGroup(String configGroup) {
            Map<String, String> matched = new LinkedHashMap<>();
            if ("device_e1_generation_gate".equals(configGroup)) {
                values.forEach((key, value) -> {
                    if (key.startsWith("device.e1.generation.")) {
                        matched.put(key, value);
                    }
                });
            }
            return matched;
        }
    }
}
