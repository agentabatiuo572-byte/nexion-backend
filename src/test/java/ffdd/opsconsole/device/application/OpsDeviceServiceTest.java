package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.device.domain.DeviceCatalogRepository;
import ffdd.opsconsole.device.domain.DeviceDatacenterView;
import ffdd.opsconsole.device.domain.DeviceGenerationGateView;
import ffdd.opsconsole.device.domain.DeviceOrderView;
import ffdd.opsconsole.device.domain.DeviceOpsRepository;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.device.domain.DevicePhaseView;
import ffdd.opsconsole.device.domain.DevicePhoneTierRewardView;
import ffdd.opsconsole.device.domain.DeviceReviewView;
import ffdd.opsconsole.device.domain.DeviceSkuView;
import ffdd.opsconsole.device.domain.DeviceTaskView;
import ffdd.opsconsole.device.domain.DeviceTradeinOverviewView;
import ffdd.opsconsole.device.domain.DeviceTradeinTxView;
import ffdd.opsconsole.device.dto.DatacenterOpsRequest;
import ffdd.opsconsole.device.dto.DeviceDatacenterUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceOrderActionRequest;
import ffdd.opsconsole.device.dto.DeviceOrderQueryRequest;
import ffdd.opsconsole.device.dto.DeviceOrderStateRequest;
import ffdd.opsconsole.device.dto.DevicePhaseArchiveRequest;
import ffdd.opsconsole.device.dto.DevicePhaseCurrentRequest;
import ffdd.opsconsole.device.dto.DevicePhaseUpsertRequest;
import ffdd.opsconsole.device.dto.DevicePhoneTierRewardUpdateRequest;
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
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
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
    private final FakeTreasuryLedgerPostingFacade ledgerPostingFacade = new FakeTreasuryLedgerPostingFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("UTC"));
    private final OpsDeviceService service = service();

    private OpsDeviceService service() {
        return service(ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction());
    }

    private OpsDeviceService service(OpsReadTimeSeedPolicy seedPolicy) {
        return new OpsDeviceService(
                deviceRepository,
                catalogRepository,
                configFacade,
                ledgerPostingFacade,
                auditLogService,
                clock,
                seedPolicy);
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
    void disabledReadTimeSeedsRequireConfiguredE3TradeinCliff() {
        OpsDeviceService realOnlyService = service(OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        ApiResult<DeviceTradeinOverviewView> result = realOnlyService.e3TradeinOverview();

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("E3_STAGE_MID_END_NOT_CONFIGURED");
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
    void updateE3ConfigAcceptsDottedPromoRouteString() {
        E3ConfigUpdateRequest request = new E3ConfigUpdateRequest("E.tradein.promo.routes", "/me/devices", "route scope", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateE3Config("idem-e3", request);

        assertThat(result.getCode()).isZero();
        assertThat(deviceRepository.config).containsEntry("promoRoutes", "/me/devices");
        assertThat(deviceRepository.lastConfigValueType).isEqualTo("STRING");
    }

    @Test
    void updateE3ConfigStoresPromoMaxPerSessionAsInteger() {
        E3ConfigUpdateRequest request = new E3ConfigUpdateRequest("E.tradein.promo.maxPerSession", "2.9", "session cap", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateE3Config("idem-e3", request);

        assertThat(result.getCode()).isZero();
        assertThat(deviceRepository.config).containsEntry("promoMaxPerSession", "2");
        assertThat(deviceRepository.lastConfigValueType).isEqualTo("NUMBER");
    }

    @Test
    void e1GenerationGatePersistsBusinessTableAndAudits() {
        configFacade.values.put("growth.phase.current_month", "4");
        configFacade.values.put("growth.phase.current", "P2");
        configFacade.values.put("device.e1.phaseOrder", "P1,P2,P3");
        configFacade.values.put("device.e1.phase.P1.meta", "L0+");
        configFacade.values.put("device.e1.phase.P1.skus", "Entry");
        configFacade.values.put("device.e1.phase.P2.meta", "L1+");
        configFacade.values.put("device.e1.phase.P2.skus", "Genesis");
        configFacade.values.put("device.e1.phase.P3.meta", "L2+");
        configFacade.values.put("device.e1.phase.P3.skus", "Pro v2");
        catalogRepository.generationGates.put(
                "stellarbox-pro-v2",
                gate("stellarbox-pro-v2", "NexionBox Pro v2", 5, "P3", new BigDecimal("300"), true, 0, false, "active"));
        E3ConfigUpdateRequest request =
                new E3ConfigUpdateRequest("E.gen.stellarbox-pro-v2.phaseOffset", "2", "stage next release", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateE1GenerationGate("idem-e1-gate", request);

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).doesNotContainKey("device.e1.generation.stellarbox-pro-v2.phaseOffset");
        assertThat(catalogRepository.generationGates.get("stellarbox-pro-v2").phaseOffset()).isEqualTo(2);
        assertThat(result.getData().get("configValues").toString())
                .contains("E.gen.stellarbox-pro-v2.phaseOffset=2");
        assertThat(result.getData())
                .containsEntry("platformMonth", 4)
                .containsEntry("phaseCurrent", "")
                .containsKeys("phaseOrder", "phases", "releases");
        assertThat((List<?>) result.getData().get("releases"))
                .anySatisfy(row -> {
                    Map<?, ?> release = (Map<?, ?>) row;
                    assertThat(release.get("id")).isEqualTo("stellarbox-pro-v2");
                    assertThat(release.get("phaseOffset")).isEqualTo(2);
                    assertThat(release.get("effectiveReleaseMonth")).isEqualTo(7);
                });

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E1_GENERATION_GATE_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-e1-gate");
    }

    @Test
    void e3TradeinOverviewReturnsAtomicTxStats() {
        deviceRepository.config.put("stageMidEnd", "8");

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
    void datacenterCrudRequiresCommandAndAudits() {
        DeviceDatacenterUpsertRequest createRequest = new DeviceDatacenterUpsertRequest(
                "us-east-2",
                "美国 · 弗吉尼亚",
                "active",
                10,
                "seed dc card",
                "superadmin");

        ApiResult<DeviceDatacenterView> created = service.createDatacenter("idem-dc-create", createRequest);
        ApiResult<DeviceDatacenterView> updated = service.updateDatacenter(
                "us-east-2",
                "idem-dc-update",
                new DeviceDatacenterUpsertRequest(
                        "us-east-2",
                        "美国 · 弗吉尼亚东区",
                        "maintenance",
                        11,
                        "rename dc card",
                        "superadmin"));
        ApiResult<Map<String, Object>> deleted = service.deleteDatacenter(
                "us-east-2",
                "idem-dc-delete",
                new DatacenterOpsRequest("remove dc card", "superadmin"));

        assertThat(created.getCode()).isZero();
        assertThat(created.getData().regionLabel()).isEqualTo("美国 · 弗吉尼亚");
        assertThat(updated.getCode()).isZero();
        assertThat(updated.getData().status()).isEqualTo("maintenance");
        assertThat(deleted.getData()).containsEntry("deleted", true);
        assertThat(deviceRepository.datacenters).doesNotContainKey("us-east-2");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, times(3)).record(captor.capture());
        assertThat(captor.getAllValues()).extracting(AuditLogWriteRequest::getAction)
                .containsExactly("E5_DATACENTER_CREATED", "E5_DATACENTER_UPDATED", "E5_DATACENTER_DELETED");
    }

    @Test
    void createDatacenterRejectsInvalidInput() {
        ApiResult<DeviceDatacenterView> missingKey = service.createDatacenter(
                "",
                new DeviceDatacenterUpsertRequest("us-east-2", "美国 · 弗吉尼亚", "active", 10, "seed", "superadmin"));
        ApiResult<DeviceDatacenterView> missingRegion = service.createDatacenter(
                "idem-dc",
                new DeviceDatacenterUpsertRequest("us-east-2", "", "active", 10, "seed", "superadmin"));
        ApiResult<DeviceDatacenterView> invalidStatus = service.createDatacenter(
                "idem-dc",
                new DeviceDatacenterUpsertRequest("us-east-2", "美国 · 弗吉尼亚", "online", 10, "seed", "superadmin"));

        assertThat(missingKey.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(missingRegion.getMessage()).isEqualTo("DATACENTER_REGION_LABEL_REQUIRED");
        assertThat(invalidStatus.getMessage()).isEqualTo("DATACENTER_STATUS_INVALID");
    }

    @Test
    void createSkuRequiresCommandAndAudits() {
        catalogRepository.phases.put("P1", phase("P1", "P1", 10));
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
        catalogRepository.phases.put("P1", phase("P1", "P1", 10));

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
    void updateSkuAllowsEncodedMediaAssetIdsLongerThanLegacyColumn() {
        catalogRepository.phases.put("P1", phase("P1", "P1", 10));
        catalogRepository.sku = sku("stellarrack-p1", "StellarRack P1", "on", "P1");
        String objectKey = "admin/e/sku-video/20260622/76979261-71a8-4276-82a2-b19b9823baef.mp4";
        String assetId = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(objectKey.getBytes(StandardCharsets.UTF_8));
        DeviceSkuUpsertRequest request = skuRequest(
                "stellarrack-p1",
                "StellarRack P1",
                "on",
                "Entry",
                "HK-1",
                1,
                "active",
                "P1",
                assetId,
                objectKey,
                "http://127.0.0.1:9000/nexion/" + objectKey + "?X-Amz-Signature=test");

        ApiResult<DeviceSkuView> result = service.updateSku("stellarrack-p1", "idem-sku-media", request);

        assertThat(assetId.length()).isGreaterThan(64);
        assertThat(result.getCode()).isZero();
        assertThat(catalogRepository.lastSkuRequest.imageAssetId()).isEqualTo(assetId);
        assertThat(catalogRepository.lastSkuRequest.imageObjectKey()).isEqualTo(objectKey);
    }

    @Test
    void createSkuRejectsPreviewUrlStoredAsImageAssetId() {
        catalogRepository.phases.put("P1", phase("P1", "P1", 10));
        DeviceSkuUpsertRequest request = skuRequest(
                "stellarbox-test",
                "NexionBox Test",
                "pending",
                "Entry",
                "HK-1",
                1,
                "active",
                "P1",
                "http://127.0.0.1:9000/nexion/admin/e/sku-video/20260622/asset.mp4",
                "admin/e/sku-video/20260622/asset.mp4",
                "http://127.0.0.1:9000/nexion/admin/e/sku-video/20260622/asset.mp4");

        ApiResult<DeviceSkuView> result = service.createSku("idem-sku-url-asset", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("SKU_IMAGE_ASSET_ID_INVALID");
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
    void skusReturnEmptyWhenCatalogIsEmpty() {
        ApiResult<PageResult<DeviceSkuView>> result =
                service.skus(new DeviceSkuQueryRequest(null, null, 1L, 100L));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getTotal()).isZero();
        assertThat(result.getData().getRecords()).isEmpty();
        assertThat(catalogRepository.phases).isEmpty();
        verify(auditLogService, never()).record(any());
    }

    @Test
    void skusDoNotSeedForFilteredEmptyQuery() {
        ApiResult<PageResult<DeviceSkuView>> result =
                service.skus(new DeviceSkuQueryRequest("on", "missing", 1L, 20L));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getTotal()).isZero();
        assertThat(catalogRepository.skus).isEmpty();
    }

    @Test
    void reviewsReturnEmptyWhenCatalogIsEmpty() {
        ApiResult<PageResult<DeviceReviewView>> result =
                service.reviews(new DeviceReviewQueryRequest(null, null, null, null, 1L, 100L));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getTotal()).isZero();
        assertThat(catalogRepository.skus).isEmpty();
        assertThat(result.getData().getRecords()).isEmpty();
        verify(auditLogService, never()).record(any());
    }

    @Test
    void e1PhaseCrudCreatesConfigAndAllowsSkuUnlockPhase() {
        DevicePhaseUpsertRequest phaseRequest =
                new DevicePhaseUpsertRequest(null, "代际第一代", "L0+", "Entry", 10, "active", "新增 Phase 配置", "superadmin");

        ApiResult<Map<String, Object>> phaseResult = service.createE1Phase("idem-phase", phaseRequest);
        assertThat(phaseResult.getCode()).isZero();
        String generatedPhaseId = catalogRepository.phases.keySet().iterator().next();
        ApiResult<DeviceSkuView> skuResult = service.createSku(
                "idem-sku-s1",
                skuRequest("stellarbox-s1", "NexionBox S1", "pending", "Entry", "HK-1", 1, "active", generatedPhaseId));

        assertThat(generatedPhaseId).matches("\\d+");
        assertThat(catalogRepository.phases.get(generatedPhaseId).label()).isEqualTo("代际第一代");
        assertThat(skuResult.getCode()).isZero();
        assertThat(skuResult.getData().unlockPhase()).isEqualTo(generatedPhaseId);

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, times(2)).record(captor.capture());
        assertThat(captor.getAllValues().stream().map(AuditLogWriteRequest::getAction))
                .contains("E1_PHASE_CREATED", "E1_SKU_CREATED");
    }

    @Test
    void e1PhaseRenameKeepsInternalReferencesStable() {
        catalogRepository.phases.put("1", phase("1", "P1", 10));
        catalogRepository.sku = sku("stellarbox-test", "NexionBox Test", "on", "1");
        catalogRepository.generationGates.put(
                "stellarbox-test",
                gate("stellarbox-test", "NexionBox Test", 1, "1", BigDecimal.ZERO, true, 0, false, "active"));
        configFacade.values.put("growth.phase.current", "1");

        ApiResult<Map<String, Object>> result = service.patchE1Phase(
                "1",
                "idem-phase-rename",
                new DevicePhaseUpsertRequest(null, "代际第一代", "L0+", "Entry", 10, "active", "改成业务命名", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(catalogRepository.phases).containsKey("1");
        assertThat(catalogRepository.phases.get("1").label()).isEqualTo("代际第一代");
        assertThat(catalogRepository.sku.unlockPhase()).isEqualTo("1");
        assertThat(catalogRepository.generationGates.get("stellarbox-test").phase()).isEqualTo("1");
        assertThat(configFacade.values).containsEntry("growth.phase.current", "1");
    }

    @Test
    void e1CurrentPhaseCanBeSetManually() {
        catalogRepository.phases.put("1", phase("1", "代际第一代", 10));
        catalogRepository.phases.put("2", phase("2", "代际第二代", 20));
        configFacade.values.put("growth.phase.current", "1");

        ApiResult<Map<String, Object>> result = service.setE1CurrentPhase(
                "2",
                "idem-phase-current",
                new DevicePhaseCurrentRequest("手动切换当前阶段", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values).containsEntry("growth.phase.current", "2");
        assertThat(result.getData()).containsEntry("phaseCurrent", "2");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E1_PHASE_CURRENT_CHANGED");
    }

    @Test
    void e1GenerationGatesReadH1RhythmForCurrentPhaseAndPacing() {
        catalogRepository.phases.put("P1", phase("P1", "拉新", 10));
        catalogRepository.phases.put("P2", phase("P2", "激活", 20));
        catalogRepository.phases.put("P3", phase("P3", "扩张", 30));
        catalogRepository.phases.put("P4", phase("P4", "深化", 40));
        catalogRepository.phases.put("P5", phase("P5", "收紧", 50));
        catalogRepository.phases.put("P6", phase("P6", "软退场", 60));
        configFacade.values.put("H1.rhythm.currentMonth", "10");
        configFacade.values.put("growth.phase.current", "P5");
        configFacade.values.put("growth.phase.device_release_pacing_pct", "0.35");

        ApiResult<Map<String, Object>> result = service.e1GenerationGates();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("platformMonth", 10)
                .containsEntry("phaseCurrent", "P5")
                .containsEntry("h1DeviceReleasePacingPct", new BigDecimal("35"));
        assertThat(result.getData().get("h1Rhythm"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("currentPhase", "P5")
                .containsEntry("currentMonth", 10);
    }

    @Test
    void e1GenerationGatesReturnEmptyWhenCatalogIsEmpty() {
        ApiResult<Map<String, Object>> result = service.e1GenerationGates();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("platformMonth", 0)
                .containsEntry("phaseCurrent", "");
        assertThat(result.getData().get("phaseOrder")).asList().isEmpty();
        assertThat(result.getData().get("phases")).asList().isEmpty();
        assertThat(result.getData().get("releases")).asList().isEmpty();
        assertThat(result.getData().get("configValues"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .isEmpty();
        assertThat(result.getData().toString())
                .doesNotContain("NexionBox")
                .doesNotContain("LLM 推理")
                .doesNotContain("入门档");
    }

    @Test
    void e1PhaseArchiveRejectsReferencedPhase() {
        catalogRepository.phases.put("1", phase("1", "P1", 10));
        catalogRepository.phases.put("2", phase("2", "代际第一代", 20));
        catalogRepository.sku = sku("stellarbox-test", "NexionBox Test", "on", "2");
        configFacade.values.put("growth.phase.current", "1");

        ApiResult<Map<String, Object>> result = service.archiveE1Phase(
                "2",
                "idem-phase-archive",
                new DevicePhaseArchiveRequest("仍被 SKU 使用", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("E1_PHASE_IN_USE");
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
    void tasksReturnEmptyWhenPrimaryListIsEmpty() {
        ApiResult<PageResult<DeviceTaskView>> result = service.tasks(new DeviceTaskQueryRequest(null, null, 1L, 20L));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getTotal()).isZero();
        assertThat(result.getData().getRecords()).isEmpty();
    }

    @Test
    void phoneTierRewardsReturnEmptyWhenPrimaryListIsEmpty() {
        ApiResult<List<DevicePhoneTierRewardView>> result = service.phoneTierRewards();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void phoneTierRewardUpdatePersistsAndAudits() {
        catalogRepository.createPhoneTierReward(
                3,
                "主流档",
                "真实配置",
                new BigDecimal("0.06"),
                new BigDecimal("10"),
                "active",
                LocalDateTime.now());

        ApiResult<DevicePhoneTierRewardView> result = service.updatePhoneTierReward(
                3,
                "idem-phone-tier",
                new DevicePhoneTierRewardUpdateRequest(new BigDecimal("0.07"), new BigDecimal("11"), "tier rebalance", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().tier()).isEqualTo(3);
        assertThat(result.getData().dailyUsdt()).isEqualByComparingTo("0.07");
        assertThat(result.getData().dailyNex()).isEqualByComparingTo("11");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E2_PHONE_TIER_REWARD_CHANGED");
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
    void updateTaskPersistsEditableFieldsAndAudits() {
        catalogRepository.task = task("TK-1", "LLM 推理 70B", new BigDecimal("0.46"), "active");

        ApiResult<DeviceTaskView> result = service.updateTask(
                "TK-1",
                "idem-task-update",
                taskRequest("手机端 Embedding", "/1k", "手机+"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().name()).isEqualTo("手机端 Embedding");
        assertThat(result.getData().unit()).isEqualTo("/1k");
        assertThat(result.getData().requirement()).isEqualTo("手机+");
        assertThat(result.getData().taskClass()).isEqualTo("embedding");
        assertThat(result.getData().model()).isEqualTo("BGE-M3");
        assertThat(result.getData().minReward()).isEqualByComparingTo("0.06");
        assertThat(result.getData().maxReward()).isEqualByComparingTo("0.22");
        assertThat(result.getData().minVram()).isEqualTo("8GB");
        assertThat(result.getData().killInit()).isEqualTo("派发中");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E2_TASK_UPDATED");
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
                taskRequest("LLM 推理 70B", "/1k", "手机+"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().taskId()).startsWith("TK-");
    }

    @Test
    void deleteTaskRejectsWhenSkuReferencesTaskPool() {
        catalogRepository.task = task("TK-5", "LoRA 微调", new BigDecimal("3.00"), "active");
        catalogRepository.tasks.put("TK-5", catalogRepository.task);
        catalogRepository.sku = sku("stellarbox-s1", "NexionBox S1", "on", "P1", "TK-5");

        ApiResult<Map<String, Object>> result = service.deleteTask(
                "TK-5",
                "idem-task-delete",
                new DeviceTaskStatusRequest("inactive", "retire task", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).contains("NexionBox S1").contains("stellarbox-s1");
        assertThat(catalogRepository.findTask("TK-5")).isPresent();
        verify(auditLogService, never()).record(any());
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
    void refundOrderPostsD4ReversalLedgerAndAudits() {
        catalogRepository.order = order("OD-1", "paid");

        ApiResult<DeviceOrderView> result = service.refundOrder(
                "OD-1",
                "idem-order-refund",
                new DeviceOrderActionRequest(null, "customer refund approved", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().state()).isEqualTo("refunded");
        assertThat(ledgerPostingFacade.entries).hasSize(1);
        Map<String, Object> entry = ledgerPostingFacade.entries.get(0);
        assertThat(entry)
                .containsEntry("bizNo", "E4-REFUND-OD-1")
                .containsEntry("userId", 1L)
                .containsEntry("bizType", "REFUND")
                .containsEntry("asset", "USDT")
                .containsEntry("direction", "IN")
                .containsEntry("status", "SUCCESS");
        assertThat((BigDecimal) entry.get("amount")).isEqualByComparingTo("1299");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E4_ORDER_REFUNDED");
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

    @Test
    void updateOrderStateAllowsAdjacentMainPathAndWritesAudit() {
        catalogRepository.order = order("OD-1", "paid");

        ApiResult<DeviceOrderView> result = service.updateOrderState(
                "OD-1",
                "idem-order-state",
                new DeviceOrderStateRequest("allocating", "manual advance", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().state()).isEqualTo("allocating");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E4_ORDER_STATE_CHANGED");
    }

    @Test
    void updateOrderStateAllowsFailedRetryToAllocating() {
        catalogRepository.order = order("OD-1", "failed");

        ApiResult<DeviceOrderView> result = service.updateOrderState(
                "OD-1",
                "idem-order-retry",
                new DeviceOrderStateRequest("allocating", "retry allocation", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().state()).isEqualTo("allocating");
    }

    @Test
    void updateOrderStateRejectsSkippingMainPath() {
        catalogRepository.order = order("OD-1", "created");

        ApiResult<DeviceOrderView> result = service.updateOrderState(
                "OD-1",
                "idem-order-skip",
                new DeviceOrderStateRequest("active", "skip flow", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        verify(auditLogService, never()).record(any());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    private static DeviceOpsView device(String status, Integer pendingDeactivate, LocalDateTime deactivatedAt) {
        return new DeviceOpsView(
                1L,
                1001L,
                "U00001001",
                "Test Miner",
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
                null,
                1L);
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
        return skuRequest(skuId, name, status, tier, datacenter, generation, lifecycle, unlockPhase, null, null, null);
    }

    private static DeviceSkuUpsertRequest skuRequest(
            String skuId,
            String name,
            String status,
            String tier,
            String datacenter,
            Integer generation,
            String lifecycle,
            String unlockPhase,
            String imageAssetId,
            String imageObjectKey,
            String imagePreviewUrl) {
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
                imageAssetId,
                imageObjectKey,
                imagePreviewUrl,
                "popular",
                status,
                "catalog update",
                "superadmin");
    }

    private static DevicePhaseView phase(String phaseId, String label, int sortOrder) {
        LocalDateTime now = LocalDateTime.of(2026, 6, 17, 0, 0);
        return new DevicePhaseView(phaseId, label, "", "", sortOrder, "active", now, now);
    }

    private static DeviceTaskUpsertRequest taskRequest(String name, String unit, String requirement) {
        return new DeviceTaskUpsertRequest(
                name,
                new BigDecimal("0.46"),
                unit,
                requirement,
                new BigDecimal("0.50"),
                "active",
                "embedding",
                "BGE-M3",
                new BigDecimal("0.06"),
                new BigDecimal("0.22"),
                "8GB",
                "派发中",
                "catalog update",
                "superadmin");
    }

    private static DeviceSkuView sku(String skuId, String name, String status) {
        return sku(skuId, name, status, "P1");
    }

    private static DeviceSkuView sku(String skuId, String name, String status, String unlockPhase) {
        return sku(skuId, name, status, unlockPhase, "LLM pool");
    }

    private static DeviceSkuView sku(String skuId, String name, String status, String unlockPhase, String aiUnlocks) {
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
                aiUnlocks,
                List.of("managed"),
                1,
                "active",
                "",
                BigDecimal.ZERO,
                unlockPhase,
                null,
                null,
                null,
                null,
                "popular",
                status,
                null,
                null);
    }

    private static DeviceGenerationGateView gate(
            String skuId,
            String name,
            Integer releaseMonth,
            String phase,
            BigDecimal discount,
            Boolean eligibility,
            Integer phaseOffset,
            Boolean forceUnlock,
            String status) {
        LocalDateTime now = LocalDateTime.of(2026, 6, 17, 0, 0);
        return new DeviceGenerationGateView(
                skuId,
                name,
                releaseMonth,
                phase,
                discount,
                eligibility,
                phaseOffset,
                forceUnlock,
                status,
                now,
                now);
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
                "embedding",
                "BGE-M3",
                new BigDecimal("0.06"),
                new BigDecimal("0.22"),
                "8GB",
                "派发中",
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
        private final Map<String, DeviceDatacenterView> datacenters = new LinkedHashMap<>();
        private String pausedDc;
        private String pauseReason;
        private String lastTradeinOperation;
        private LocalDateTime lastTradeinSince;
        private LocalDateTime lastTradeinMonthStart;
        private String lastConfigValueType;

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
            lastConfigValueType = valueType;
        }

        @Override
        public List<DeviceDatacenterView> datacenterSummaries() {
            return new ArrayList<>(datacenters.values());
        }

        @Override
        public Optional<DeviceDatacenterView> findDatacenter(String dcLocation) {
            return Optional.ofNullable(datacenters.get(dcLocation));
        }

        @Override
        public DeviceDatacenterView createDatacenter(DeviceDatacenterUpsertRequest request, String operator, LocalDateTime now) {
            DeviceDatacenterView view = dcView(request, now, now);
            datacenters.put(request.dcLocation(), view);
            return view;
        }

        @Override
        public Optional<DeviceDatacenterView> updateDatacenter(String dcLocation, DeviceDatacenterUpsertRequest request, String operator, LocalDateTime now) {
            DeviceDatacenterView before = datacenters.get(dcLocation);
            if (before == null) {
                return Optional.empty();
            }
            DeviceDatacenterView view = dcView(request, before.createdAt(), now);
            datacenters.put(dcLocation, view);
            return Optional.of(view);
        }

        @Override
        public boolean softDeleteDatacenter(String dcLocation, String operator, LocalDateTime now) {
            return datacenters.remove(dcLocation) != null;
        }

        @Override
        public void pauseDatacenter(String dcLocation, String reason, String operator, LocalDateTime now) {
            pausedDc = dcLocation;
            pauseReason = reason;
        }

        @Override
        public void resumeDatacenter(String dcLocation, String operator, LocalDateTime now) {
        }

        private static DeviceDatacenterView dcView(DeviceDatacenterUpsertRequest request, LocalDateTime createdAt, LocalDateTime updatedAt) {
            return new DeviceDatacenterView(
                    request.dcLocation(),
                    request.regionLabel(),
                    request.status(),
                    request.sortOrder(),
                    0L,
                    0L,
                    0L,
                    0L,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    false,
                    null,
                    null,
                    null,
                    createdAt,
                    updatedAt);
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
        private final Map<String, DeviceSkuView> skus = new LinkedHashMap<>();
        private final Map<String, DeviceReviewView> reviews = new LinkedHashMap<>();
        private final Map<String, DeviceTaskView> tasks = new LinkedHashMap<>();
        private final Map<Integer, DevicePhoneTierRewardView> phoneTierRewards = new LinkedHashMap<>();
        private DeviceOrderView order;
        private DeviceSkuUpsertRequest lastSkuRequest;
        private final Map<String, DevicePhaseView> phases = new LinkedHashMap<>();
        private final Map<String, DeviceGenerationGateView> generationGates = new LinkedHashMap<>();
        private long nextPhaseId = 1;

        @Override
        public PageResult<DeviceSkuView> pageSkus(DeviceSkuQueryRequest request) {
            List<DeviceSkuView> records = !skus.isEmpty()
                    ? new ArrayList<>(skus.values())
                    : (sku == null ? List.of() : List.of(sku));
            String status = request == null ? null : request.status();
            String keyword = request == null ? null : request.keyword();
            if (status != null && !status.isBlank()) {
                records = records.stream()
                        .filter(row -> status.trim().equalsIgnoreCase(row.status()))
                        .toList();
            }
            if (keyword != null && !keyword.isBlank()) {
                String normalized = keyword.trim().toLowerCase();
                records = records.stream()
                        .filter(row -> row.skuId().toLowerCase().contains(normalized)
                                || row.name().toLowerCase().contains(normalized))
                        .toList();
            }
            long pageNum = request == null || request.pageNum() == null ? 1 : request.pageNum();
            long pageSize = request == null || request.pageSize() == null ? 20 : request.pageSize();
            return new PageResult<>(records.size(), pageNum, pageSize, records);
        }

        @Override
        public Optional<DeviceSkuView> findSku(String skuId) {
            if (skus.containsKey(skuId)) {
                return Optional.of(skus.get(skuId));
            }
            return sku != null && sku.skuId().equals(skuId) ? Optional.of(sku) : Optional.empty();
        }

        @Override
        public List<DeviceSkuView> findSkusByAiUnlocks(String taskId) {
            if (!skus.isEmpty()) {
                return skus.values().stream()
                        .filter(row -> taskId.equals(row.aiUnlocks()))
                        .toList();
            }
            return sku != null && taskId.equals(sku.aiUnlocks()) ? List.of(sku) : List.of();
        }

        @Override
        public DeviceSkuView createSku(String skuId, DeviceSkuUpsertRequest request, LocalDateTime now) {
            lastSkuRequest = request;
            sku = sku(skuId, request.name(), request.status(), request.unlockPhase());
            skus.put(skuId, sku);
            return sku;
        }

        @Override
        public Optional<DeviceSkuView> updateSku(String skuId, DeviceSkuUpsertRequest request, LocalDateTime now) {
            DeviceSkuView current = skus.get(skuId);
            if (current == null && sku != null && sku.skuId().equals(skuId)) {
                current = sku;
            }
            if (current == null) {
                return Optional.empty();
            }
            lastSkuRequest = request;
            sku = sku(skuId, request.name(), request.status(), request.unlockPhase());
            skus.put(skuId, sku);
            return Optional.of(sku);
        }

        @Override
        public Optional<DeviceSkuView> updateSkuStatus(String skuId, String status, LocalDateTime now) {
            DeviceSkuView current = skus.get(skuId);
            if (current == null && sku != null && sku.skuId().equals(skuId)) {
                current = sku;
            }
            if (current == null) {
                return Optional.empty();
            }
            sku = sku(current.skuId(), current.name(), status, current.unlockPhase());
            skus.put(skuId, sku);
            return Optional.of(sku);
        }

        @Override
        public boolean softDeleteSku(String skuId, LocalDateTime now) {
            if (skus.remove(skuId) != null) {
                if (sku != null && sku.skuId().equals(skuId)) {
                    sku = null;
                }
                return true;
            }
            if (sku != null && sku.skuId().equals(skuId)) {
                sku = null;
                return true;
            }
            return false;
        }

        @Override
        public PageResult<DeviceReviewView> pageReviews(DeviceReviewQueryRequest request) {
            List<DeviceReviewView> records = !reviews.isEmpty()
                    ? new ArrayList<>(reviews.values())
                    : (review == null ? List.of() : List.of(review));
            String skuId = request == null ? null : request.skuId();
            String status = request == null ? null : request.status();
            Integer rating = request == null ? null : request.rating();
            String keyword = request == null ? null : request.keyword();
            if (skuId != null && !skuId.isBlank()) {
                records = records.stream()
                        .filter(row -> skuId.trim().equals(row.skuId()))
                        .toList();
            }
            if (status != null && !status.isBlank()) {
                records = records.stream()
                        .filter(row -> status.trim().equalsIgnoreCase(row.status()))
                        .toList();
            }
            if (rating != null) {
                records = records.stream()
                        .filter(row -> rating.equals(row.rating()))
                        .toList();
            }
            if (keyword != null && !keyword.isBlank()) {
                String normalized = keyword.trim().toLowerCase();
                records = records.stream()
                        .filter(row -> row.author().toLowerCase().contains(normalized)
                                || row.content().toLowerCase().contains(normalized))
                        .toList();
            }
            long pageNum = request == null || request.pageNum() == null ? 1 : request.pageNum();
            long pageSize = request == null || request.pageSize() == null ? 20 : request.pageSize();
            return new PageResult<>(records.size(), pageNum, pageSize, records);
        }

        @Override
        public Optional<DeviceReviewView> findReview(String reviewId) {
            if (reviews.containsKey(reviewId)) {
                return Optional.of(reviews.get(reviewId));
            }
            return review != null && review.reviewId().equals(reviewId) ? Optional.of(review) : Optional.empty();
        }

        @Override
        public DeviceReviewView createReview(String reviewId, DeviceReviewUpsertRequest request, LocalDateTime now) {
            String skuName = findSku(request.skuId()).map(DeviceSkuView::name).orElse(request.skuId());
            review = new DeviceReviewView(
                    reviewId,
                    request.skuId(),
                    skuName,
                    request.author(),
                    request.rating(),
                    request.content(),
                    request.dateText(),
                    request.status(),
                    now,
                    now);
            reviews.put(reviewId, review);
            return review;
        }

        @Override
        public Optional<DeviceReviewView> updateReview(String reviewId, DeviceReviewUpsertRequest request, LocalDateTime now) {
            DeviceReviewView current = reviews.get(reviewId);
            if (current == null && review != null && review.reviewId().equals(reviewId)) {
                current = review;
            }
            if (current == null) {
                return Optional.empty();
            }
            String skuName = findSku(request.skuId()).map(DeviceSkuView::name).orElse(request.skuId());
            review = new DeviceReviewView(
                    reviewId,
                    request.skuId(),
                    skuName,
                    request.author(),
                    request.rating(),
                    request.content(),
                    request.dateText(),
                    request.status(),
                    current.createdAt(),
                    now);
            reviews.put(reviewId, review);
            return Optional.of(review);
        }

        @Override
        public Optional<DeviceReviewView> updateReviewStatus(String reviewId, String status, LocalDateTime now) {
            DeviceReviewView current = reviews.get(reviewId);
            if (current == null && review != null && review.reviewId().equals(reviewId)) {
                current = review;
            }
            if (current == null) {
                return Optional.empty();
            }
            review = new DeviceReviewView(
                    current.reviewId(),
                    current.skuId(),
                    current.skuName(),
                    current.author(),
                    current.rating(),
                    current.content(),
                    current.dateText(),
                    status,
                    current.createdAt(),
                    now);
            reviews.put(reviewId, review);
            return Optional.of(review);
        }

        @Override
        public boolean softDeleteReview(String reviewId, LocalDateTime now) {
            if (reviews.remove(reviewId) != null) {
                if (review != null && review.reviewId().equals(reviewId)) {
                    review = null;
                }
                return true;
            }
            if (review != null && review.reviewId().equals(reviewId)) {
                review = null;
                return true;
            }
            return false;
        }

        @Override
        public List<DevicePhaseView> listPhases(String scope, boolean includeArchived) {
            return phases.values().stream()
                    .filter(phase -> includeArchived || "active".equals(phase.status()))
                    .sorted(java.util.Comparator.comparing(DevicePhaseView::sortOrder))
                    .toList();
        }

        @Override
        public Optional<DevicePhaseView> findPhase(String scope, String phaseId) {
            return Optional.ofNullable(phases.get(phaseId));
        }

        @Override
        public Optional<DevicePhaseView> findPhaseByLabel(String scope, String label) {
            return phases.values().stream()
                    .filter(phase -> label.equals(phase.label()))
                    .findFirst();
        }

        @Override
        public DevicePhaseView savePhase(
                String scope,
                String currentPhaseId,
                String label,
                String meta,
                String skus,
                Integer sortOrder,
                String status,
                LocalDateTime now) {
            String id = currentPhaseId == null || currentPhaseId.isBlank() ? nextPhaseId() : currentPhaseId;
            LocalDateTime createdAt = Optional.ofNullable(phases.get(id))
                    .map(DevicePhaseView::createdAt)
                    .orElse(now);
            DevicePhaseView phase = new DevicePhaseView(
                    id,
                    label,
                    meta,
                    skus,
                    sortOrder,
                    status,
                    createdAt,
                    now);
            phases.put(id, phase);
            return phase;
        }

        @Override
        public boolean archivePhase(String scope, String phaseId, LocalDateTime now) {
            DevicePhaseView before = phases.get(phaseId);
            if (before == null) {
                return false;
            }
            phases.put(phaseId, new DevicePhaseView(
                    before.p(),
                    before.label(),
                    before.meta(),
                    before.skus(),
                    before.sortOrder(),
                    "archived",
                    before.createdAt(),
                    now));
            return true;
        }

        @Override
        public void backfillPhaseReferences(String scope, LocalDateTime now) {
            Map<String, String> phaseLookup = new LinkedHashMap<>();
            phases.values().forEach(phase -> {
                phaseLookup.put(phase.p(), phase.p());
                phaseLookup.put(phase.label(), phase.p());
            });
            if (sku != null && phaseLookup.containsKey(sku.unlockPhase())) {
                sku = sku(sku.skuId(), sku.name(), sku.status(), phaseLookup.get(sku.unlockPhase()));
            }
            generationGates.replaceAll((id, gate) -> phaseLookup.containsKey(gate.phase())
                    ? new DeviceGenerationGateView(
                            gate.id(),
                            gate.name(),
                            gate.releaseMonth(),
                            phaseLookup.get(gate.phase()),
                            gate.discount(),
                            gate.eligibility(),
                            gate.phaseOffset(),
                            gate.forceUnlock(),
                            gate.status(),
                            gate.createdAt(),
                            now)
                    : gate);
        }

        @Override
        public int countSkusByUnlockPhase(String phaseId) {
            return sku != null && phaseId.equals(sku.unlockPhase()) ? 1 : 0;
        }

        @Override
        public int countGenerationGatesByPhase(String phaseId) {
            return (int) generationGates.values().stream()
                    .filter(gate -> phaseId.equals(gate.phase()) && "active".equals(gate.status()))
                    .count();
        }

        @Override
        public List<DeviceGenerationGateView> listGenerationGates(boolean includeArchived) {
            return generationGates.values().stream()
                    .filter(gate -> includeArchived || !"archived".equals(gate.status()))
                    .toList();
        }

        @Override
        public Optional<DeviceGenerationGateView> findGenerationGate(String skuId) {
            return Optional.ofNullable(generationGates.get(skuId));
        }

        @Override
        public DeviceGenerationGateView saveGenerationGate(
                String skuId,
                String name,
                Integer releaseMonth,
                String phase,
                BigDecimal discount,
                Boolean eligibility,
                Integer phaseOffset,
                Boolean forceUnlock,
                String status,
                LocalDateTime now) {
            LocalDateTime createdAt = Optional.ofNullable(generationGates.get(skuId))
                    .map(DeviceGenerationGateView::createdAt)
                    .orElse(now);
            DeviceGenerationGateView gate = new DeviceGenerationGateView(
                    skuId,
                    name,
                    releaseMonth,
                    phase,
                    discount,
                    eligibility,
                    phaseOffset,
                    forceUnlock,
                    status,
                    createdAt,
                    now);
            generationGates.put(skuId, gate);
            return gate;
        }

        @Override
        public boolean archiveGenerationGate(String skuId, LocalDateTime now) {
            DeviceGenerationGateView before = generationGates.get(skuId);
            if (before == null) {
                return false;
            }
            generationGates.put(skuId, new DeviceGenerationGateView(
                    before.id(),
                    before.name(),
                    before.releaseMonth(),
                    before.phase(),
                    before.discount(),
                    before.eligibility(),
                    before.phaseOffset(),
                    before.forceUnlock(),
                    "archived",
                    before.createdAt(),
                    now));
            return true;
        }

        private String nextPhaseId() {
            while (phases.containsKey(String.valueOf(nextPhaseId))) {
                nextPhaseId++;
            }
            return String.valueOf(nextPhaseId++);
        }

        @Override
        public PageResult<DeviceTaskView> pageTasks(DeviceTaskQueryRequest request) {
            if (!tasks.isEmpty()) {
                List<DeviceTaskView> records = tasks.values().stream()
                        .sorted((left, right) -> right.taskId().compareTo(left.taskId()))
                        .toList();
                return new PageResult<>(records.size(), 1, 20, records);
            }
            return new PageResult<>(task == null ? 0 : 1, 1, 20, task == null ? List.of() : List.of(task));
        }

        @Override
        public Optional<DeviceTaskView> findTask(String taskId) {
            DeviceTaskView found = tasks.get(taskId);
            if (found != null) {
                return Optional.of(found);
            }
            return task != null && task.taskId().equals(taskId) ? Optional.of(task) : Optional.empty();
        }

        @Override
        public DeviceTaskView createTask(String taskId, DeviceTaskUpsertRequest request, LocalDateTime now) {
            task = new DeviceTaskView(
                    taskId,
                    request.name(),
                    request.price(),
                    request.unit(),
                    request.requirement(),
                    request.saturation(),
                    request.status(),
                    request.taskClass(),
                    request.model(),
                    request.minReward(),
                    request.maxReward(),
                    request.minVram(),
                    request.killInit(),
                    now,
                    now);
            tasks.put(taskId, task);
            return task;
        }

        @Override
        public Optional<DeviceTaskView> updateTask(String taskId, DeviceTaskUpsertRequest request, LocalDateTime now) {
            DeviceTaskView current = tasks.get(taskId);
            if (current == null && task != null && task.taskId().equals(taskId)) {
                current = task;
            }
            if (current == null) {
                return Optional.empty();
            }
            DeviceTaskView updated = new DeviceTaskView(
                    current.taskId(),
                    request.name(),
                    request.price(),
                    request.unit(),
                    request.requirement(),
                    request.saturation(),
                    request.status(),
                    request.taskClass(),
                    request.model(),
                    request.minReward(),
                    request.maxReward(),
                    request.minVram(),
                    request.killInit(),
                    current.createdAt(),
                    now);
            tasks.put(taskId, updated);
            task = updated;
            return Optional.of(updated);
        }

        @Override
        public Optional<DeviceTaskView> updateTaskPrice(String taskId, BigDecimal price, LocalDateTime now) {
            DeviceTaskView current = tasks.get(taskId);
            if (current != null) {
                DeviceTaskView updated = new DeviceTaskView(
                        current.taskId(),
                        current.name(),
                        price,
                        current.unit(),
                        current.requirement(),
                        current.saturation(),
                        current.status(),
                        current.taskClass(),
                        current.model(),
                        current.minReward(),
                        current.maxReward(),
                        current.minVram(),
                        current.killInit(),
                        current.createdAt(),
                        now);
                tasks.put(taskId, updated);
                task = updated;
                return Optional.of(updated);
            }
            if (task == null || !task.taskId().equals(taskId)) {
                return Optional.empty();
            }
            task = new DeviceTaskView(
                    task.taskId(),
                    task.name(),
                    price,
                    task.unit(),
                    task.requirement(),
                    task.saturation(),
                    task.status(),
                    task.taskClass(),
                    task.model(),
                    task.minReward(),
                    task.maxReward(),
                    task.minVram(),
                    task.killInit(),
                    task.createdAt(),
                    now);
            return Optional.of(task);
        }

        @Override
        public Optional<DeviceTaskView> updateTaskStatus(String taskId, String status, LocalDateTime now) {
            DeviceTaskView current = tasks.get(taskId);
            if (current != null) {
                DeviceTaskView updated = new DeviceTaskView(
                        current.taskId(),
                        current.name(),
                        current.price(),
                        current.unit(),
                        current.requirement(),
                        current.saturation(),
                        status,
                        current.taskClass(),
                        current.model(),
                        current.minReward(),
                        current.maxReward(),
                        current.minVram(),
                        current.killInit(),
                        current.createdAt(),
                        now);
                tasks.put(taskId, updated);
                task = updated;
                return Optional.of(updated);
            }
            if (task == null || !task.taskId().equals(taskId)) {
                return Optional.empty();
            }
            task = new DeviceTaskView(
                    task.taskId(),
                    task.name(),
                    task.price(),
                    task.unit(),
                    task.requirement(),
                    task.saturation(),
                    status,
                    task.taskClass(),
                    task.model(),
                    task.minReward(),
                    task.maxReward(),
                    task.minVram(),
                    task.killInit(),
                    task.createdAt(),
                    now);
            return Optional.of(task);
        }

        @Override
        public boolean softDeleteTask(String taskId, LocalDateTime now) {
            if (tasks.remove(taskId) != null) {
                if (task != null && task.taskId().equals(taskId)) {
                    task = null;
                }
                return true;
            }
            if (task != null && task.taskId().equals(taskId)) {
                task = null;
                return true;
            }
            return false;
        }

        @Override
        public List<DevicePhoneTierRewardView> listPhoneTierRewards() {
            return List.copyOf(phoneTierRewards.values());
        }

        @Override
        public Optional<DevicePhoneTierRewardView> findPhoneTierReward(Integer tier) {
            return Optional.ofNullable(phoneTierRewards.get(tier));
        }

        @Override
        public DevicePhoneTierRewardView createPhoneTierReward(
                Integer tier,
                String name,
                String note,
                BigDecimal dailyUsdt,
                BigDecimal dailyNex,
                String status,
                LocalDateTime now) {
            DevicePhoneTierRewardView reward = new DevicePhoneTierRewardView(
                    tier,
                    name,
                    note,
                    dailyUsdt,
                    dailyNex,
                    status,
                    now,
                    now);
            phoneTierRewards.put(tier, reward);
            return reward;
        }

        @Override
        public Optional<DevicePhoneTierRewardView> updatePhoneTierReward(
                Integer tier,
                DevicePhoneTierRewardUpdateRequest request,
                LocalDateTime now) {
            DevicePhoneTierRewardView current = phoneTierRewards.get(tier);
            if (current == null) {
                return Optional.empty();
            }
            DevicePhoneTierRewardView updated = new DevicePhoneTierRewardView(
                    current.tier(),
                    current.name(),
                    current.note(),
                    request.dailyUsdt() == null ? current.dailyUsdt() : request.dailyUsdt(),
                    request.dailyNex() == null ? current.dailyNex() : request.dailyNex(),
                    current.status(),
                    current.createdAt(),
                    now);
            phoneTierRewards.put(tier, updated);
            return Optional.of(updated);
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
                    if (key.startsWith("device.e1.")) {
                        matched.put(key, value);
                    }
                });
            }
            return matched;
        }
    }

    private static final class FakeTreasuryLedgerPostingFacade implements TreasuryLedgerPostingFacade {
        private final List<Map<String, Object>> entries = new ArrayList<>();

        @Override
        public void postLedgerEntry(String bizNo, Long userId, String bizType, String asset, String direction,
                                    BigDecimal amount, String status, String remark) {
            entries.add(Map.of(
                    "bizNo", bizNo,
                    "userId", userId,
                    "bizType", bizType,
                    "asset", asset,
                    "direction", direction,
                    "amount", amount,
                    "status", status,
                    "remark", remark));
        }
    }
}
