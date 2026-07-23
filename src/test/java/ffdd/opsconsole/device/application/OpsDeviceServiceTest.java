package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.mapper.AuditObjectLockMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.device.domain.ComputeConfigRegistry;
import ffdd.opsconsole.device.domain.ComputeConfigView;
import ffdd.opsconsole.device.domain.DeviceCatalogRepository;
import ffdd.opsconsole.device.domain.DatacenterReferenceCount;
import ffdd.opsconsole.device.domain.DeviceDatacenterView;
import ffdd.opsconsole.device.domain.DeviceGenerationGateView;
import ffdd.opsconsole.device.domain.DeviceOrderFacts;
import ffdd.opsconsole.device.domain.DeviceOrderFundingView;
import ffdd.opsconsole.device.domain.DeviceOrderHistoryView;
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
import ffdd.opsconsole.device.dto.ComputeConfigParamResponse;
import ffdd.opsconsole.device.dto.ComputeConfigParamUpdateRequest;
import ffdd.opsconsole.device.dto.ComputeConfigBatchResponse;
import ffdd.opsconsole.device.dto.ComputeConfigBatchUpdateRequest;
import ffdd.opsconsole.device.dto.DatacenterOpsRequest;
import ffdd.opsconsole.device.dto.DeviceDatacenterUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceE5ActionRequest;
import ffdd.opsconsole.device.dto.DeviceE5BatchRequest;
import ffdd.opsconsole.device.dto.DeviceEarlyAccessUpdateRequest;
import ffdd.opsconsole.device.dto.DeviceGenerationGatePatchRequest;
import ffdd.opsconsole.device.dto.DeviceGenerationGateUpsertRequest;
import ffdd.opsconsole.device.dto.DeviceOrderActionRequest;
import ffdd.opsconsole.device.dto.DeviceOrderQueryRequest;
import ffdd.opsconsole.device.dto.DeviceOrderStateRequest;
import ffdd.opsconsole.device.dto.DevicePhaseArchiveRequest;
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
import ffdd.opsconsole.device.dto.E2TaskPricingUpdateRequest;
import ffdd.opsconsole.device.dto.E3ConfigUpdateRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.finance.facade.E4OrderRefundSettlementFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
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
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsDeviceServiceTest {
    private final FakeDeviceOpsRepository deviceRepository = new FakeDeviceOpsRepository();
    private final FakeDeviceCatalogRepository catalogRepository = new FakeDeviceCatalogRepository();
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeTreasuryLedgerPostingFacade ledgerPostingFacade = new FakeTreasuryLedgerPostingFacade();
    private final FakeE4OrderRefundSettlementFacade refundSettlementFacade = new FakeE4OrderRefundSettlementFacade();
    private final TreasuryCoverageFacade coverageFacade = mock(TreasuryCoverageFacade.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final EventOutboxService outboxService = mock(EventOutboxService.class);
    private final AuditObjectLockMapper lockMapper = mock(AuditObjectLockMapper.class);
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
                refundSettlementFacade,
                coverageFacade,
                auditLogService,
                idempotencyService,
                outboxService,
                clock,
                seedPolicy,
                lockMapper);
    }

    @BeforeEach
    void stubLocksNoActive() {
        // A2 锁守卫默认放行:countActiveByTarget=0 表示无活跃锁,replay 与常规写方法直通
        when(lockMapper.countActiveByTarget(anyString(), anyString(), anyString())).thenReturn(0);
        when(idempotencyService.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        when(coverageFacade.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                BigDecimal.valueOf(200), BigDecimal.valueOf(100), true));
    }

    @AfterEach
    void clearSecurityContext() {
        A2ReplayContext.exitReplay();
        SecurityContextHolder.clearContext();
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
    void disabledReadTimeSeedsStillReadE3TradeinOverviewWhenCliffMissing() {
        OpsDeviceService realOnlyService = service(OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        ApiResult<DeviceTradeinOverviewView> result = realOnlyService.e3TradeinOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isNotNull();
        assertThat(deviceRepository.lastTradeinCliffMonth).isEqualTo(1);
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
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("admin.tradein_config_changed");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-e3");
    }

    @Test
    void updateE3ConfigAcceptsNegativeCapacityDelta() {
        E3ConfigUpdateRequest request = new E3ConfigUpdateRequest(
                "E.device.capacity.band3DeltaPct", "-23.7", "align lifecycle curve", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateE3Config("idem-e3", request);

        assertThat(result.getCode()).isZero();
        assertThat(deviceRepository.config).containsEntry("capacityBand3DeltaPct", "-23.7");
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
    void updateE3ConfigRejectsFractionalIntegerValue() {
        E3ConfigUpdateRequest request = new E3ConfigUpdateRequest("E.tradein.promo.maxPerSession", "2.9", "session cap", "superadmin");

        ApiResult<Map<String, Object>> result = service.updateE3Config("idem-e3", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(deviceRepository.config).doesNotContainKey("promoMaxPerSession");
    }

    @Test
    void updateE3ConfigAcceptsRhythmCapacityTradeinAndEarlyAccessKeys() {
        assertThat(service.updateE3Config(
                "idem-capacity",
                new E3ConfigUpdateRequest("E.device.capacity.band1DeltaPct", "-5", "capacity schedule", "superadmin")).getCode())
                .isZero();
        assertThat(service.updateE3Config(
                "idem-tradein",
                new E3ConfigUpdateRequest("E.tradein.enabled", "true", "trade-in ladder", "superadmin")).getCode())
                .isZero();
        assertThat(service.updateE3Config(
                "idem-early-access",
                new E3ConfigUpdateRequest("E.release.earlyAccess.leadDays", "7", "release early access", "superadmin")).getCode())
                .isZero();

        assertThat(deviceRepository.config)
                .containsEntry("capacityBand1DeltaPct", "-5")
                .containsEntry("tradeinEnabled", "true")
                .containsEntry("earlyAccessLeadDays", "7");
    }

    @Test
    void updateE3ConfigRejectsNonIncreasingTradeinLadderCut() {
        ApiResult<Map<String, Object>> result = service.updateE3Config(
                "idem-ladder-order",
                new E3ConfigUpdateRequest("E.tradein.ladder.cut1", "60", "invalid ladder", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(deviceRepository.config).doesNotContainKey("tradeinLadderCut1");
    }

    @Test
    void e1GenerationGatePersistsBusinessTableAndAudits() {
        catalogRepository.phases.put("P1", phase("P1", "P1", 10));
        catalogRepository.phases.put("P2", phase("P2", "P2", 20));
        catalogRepository.phases.put("P3", phase("P3", "P3", 30));
        configFacade.values.put("H1.rhythm.currentMonth", "4");
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
                .containsEntry("phaseCurrent", "P1")
                .containsKeys("phaseOrder", "phases", "releases");
        assertThat((List<?>) result.getData().get("releases"))
                .anySatisfy(row -> {
                    Map<?, ?> release = (Map<?, ?>) row;
                    assertThat(release.get("id")).isEqualTo("stellarbox-pro-v2");
                    assertThat(release.get("phaseOffset")).isEqualTo(2);
                    assertThat(release.get("effectiveReleaseMonth")).isEqualTo(7);
                });

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E1_GENERATION_GATE_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-e1-gate");
    }

    @Test
    void e5OverviewAlwaysReturnsHardSixDeviceLimit() {
        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("maxDevicesPerUser", 6);
        assertThat((List<?>) result.getData().get("sources"))
                .noneMatch(source -> String.valueOf(source).contains("maxDevicesPerUser"));
    }

    @Test
    void e5ManualActivateUsesDurableIdempotencyAndCanonicalEvent() {
        deviceRepository.device = device("INVENTORY", 0, null);

        ApiResult<DeviceOpsView> result = service.activateE5Device(
                1L, false, "idem-e5-activate",
                new DeviceE5ActionRequest("activate inventory device", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("OFFLINE");
        verify(idempotencyService).execute(eq("E5_DEVICE_ACTIVATE"), eq("idem-e5-activate"),
                anyString(), eq(ApiResult.class), any());
        verify(outboxService).publish(eq("E5_DEVICE"), eq("1"),
                eq("admin.device_activated"), argThat(payload -> payload instanceof Map<?, ?> map
                        && "manual".equals(map.get("mode"))));
        verify(auditLogService).recordRequired(argThat(audit ->
                "admin.device_activated".equals(audit.getAction())));
    }

    @Test
    void e5ForceActivateNeverBypassesHardDeviceCap() {
        deviceRepository.device = device("DEACTIVATED", 0, LocalDateTime.now(clock));
        deviceRepository.activeDevicesByUser = 6;

        ffdd.opsconsole.platform.application.A2ReplayContext.enterReplay();
        ApiResult<DeviceOpsView> result;
        try {
            result = service.activateE5Device(
                    1L, true, "idem-e5-force",
                    new DeviceE5ActionRequest("force activate after repair", "superadmin"));
        } finally {
            ffdd.opsconsole.platform.application.A2ReplayContext.exitReplay();
        }

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("MAX_DEVICES_PER_USER_EXCEEDED");
        verify(outboxService, never()).publish(eq("E5_DEVICE"), anyString(),
                eq("admin.device_activated"), any());
    }

    @Test
    void e5ForceActivateAndUnbindRequireA2ReplayEvenWithBusinessPermission() {
        deviceRepository.device = device("DEACTIVATED", 0, LocalDateTime.now(clock));
        ApiResult<DeviceOpsView> force = service.activateE5Device(
                1L, true, "idem-e5-force-direct",
                new DeviceE5ActionRequest("force activate after repair", "superadmin"));

        deviceRepository.device = device("ONLINE", 0, null);
        ApiResult<DeviceOpsView> unbind = service.deactivateE5Device(
                1L, true, "idem-e5-unbind-direct",
                new DeviceE5ActionRequest("unbind compromised device", "superadmin"));

        assertThat(force.getCode()).isEqualTo(409);
        assertThat(force.getMessage()).isEqualTo("A2_CONFIRMATION_REQUIRED");
        assertThat(unbind.getCode()).isEqualTo(409);
        assertThat(unbind.getMessage()).isEqualTo("A2_CONFIRMATION_REQUIRED");
        verify(idempotencyService, never()).execute(eq("E5_DEVICE_FORCE_ACTIVATE"), anyString(),
                anyString(), eq(ApiResult.class), any());
        verify(idempotencyService, never()).execute(eq("E5_DEVICE_UNBIND"), anyString(),
                anyString(), eq(ApiResult.class), any());
    }

    @Test
    void e5DeactivateUsesItsOwnCanonicalAdminEvent() {
        deviceRepository.device = device("ONLINE", 0, null);

        ApiResult<DeviceOpsView> result = service.deactivateE5Device(
                1L, false, "idem-e5-deactivate",
                new DeviceE5ActionRequest("deactivate device for maintenance", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("DEACTIVATED");
        verify(outboxService).publish(eq("E5_DEVICE"), eq("1"),
                eq("admin.device_deactivated"), argThat(payload -> payload instanceof Map<?, ?> map
                        && "manual".equals(map.get("mode"))));
        verify(auditLogService).recordRequired(argThat(audit ->
                "admin.device_deactivated".equals(audit.getAction())));
    }

    @Test
    void e5UnbindUsesDeactivatedEventAndAuditSemantics() {
        deviceRepository.device = device("ONLINE", 0, null);

        ffdd.opsconsole.platform.application.A2ReplayContext.enterReplay();
        ApiResult<DeviceOpsView> result;
        try {
            result = service.deactivateE5Device(
                    1L, true, "idem-e5-unbind",
                    new DeviceE5ActionRequest("unbind compromised device", "superadmin"));
        } finally {
            ffdd.opsconsole.platform.application.A2ReplayContext.exitReplay();
        }

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("UNBOUND");
        verify(outboxService).publish(eq("E5_DEVICE"), eq("1"),
                eq("admin.device_deactivated"), argThat(payload -> payload instanceof Map<?, ?> map
                        && "unbind".equals(map.get("mode"))));
        verify(auditLogService).recordRequired(argThat(audit ->
                "admin.device_deactivated".equals(audit.getAction())));
    }

    @Test
    void e5ReasonMustContainBetweenEightAndTwoHundredCharacters() {
        deviceRepository.device = device("INVENTORY", 0, null);
        String overlong = "x".repeat(201);

        ApiResult<DeviceOpsView> shortReason = service.activateE5Device(
                1L, false, "idem-short", new DeviceE5ActionRequest("short", "superadmin"));
        ApiResult<DeviceOpsView> longReason = service.activateE5Device(
                1L, false, "idem-long", new DeviceE5ActionRequest(overlong, "superadmin"));

        assertThat(shortReason.getMessage()).isEqualTo("REASON_LENGTH_INVALID");
        assertThat(longReason.getMessage()).isEqualTo("REASON_LENGTH_INVALID");
    }

    @Test
    void e5BatchPauseIsScopedByUserAndEmitsCanonicalEvent() {
        deviceRepository.device = device("OFFLINE", 0, null);
        ApiResult<Map<String, Object>> result = service.batchE5Devices(
                true, "idem-e5-pause",
                new DeviceE5BatchRequest(1001L, "pause user fleet for maintenance", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("userId", 1001L).containsEntry("changedCount", 1);
        assertThat(deviceRepository.pausedUserId).isEqualTo(1001L);
        verify(outboxService).publish(eq("E5_DEVICE_BATCH"), eq("1001"),
                eq("admin.device_paused"), argThat(payload ->
                        payload instanceof Map<?, ?> map
                                && Integer.valueOf(1).equals(map.get("changed_count"))
                                && List.of(1L).equals(map.get("device_ids"))));
    }

    @Test
    void updateE3ConfigRejectsRetiredLifecycleAndSalvageKeys() {
        for (String key : List.of(
                "E.device.degradeEarly", "E.device.degradeMid", "E.device.degradeLate",
                "E.device.minEfficiency", "E.tradein.salvagePct", "E.tradein.minHoldingMonths")) {
            ApiResult<Map<String, Object>> result = service.updateE3Config(
                    "idem-retired-" + key, new E3ConfigUpdateRequest(key, "1", "retired key guard", "superadmin"));
            assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
            assertThat(result.getMessage()).isEqualTo("E3_CONFIG_KEY_RETIRED");
        }
    }

    @Test
    void updateE3ConfigRejectsNoopWithoutAuditOrOutboxNoise() {
        deviceRepository.config.put("promoCooldownDays", "14");

        ApiResult<Map<String, Object>> result = service.updateE3Config(
                "idem-e3-noop",
                new E3ConfigUpdateRequest("E.tradein.promo.cooldownDays", "14", "same value check", "superadmin"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("E3_VALUE_UNCHANGED");
        verify(auditLogService, never()).recordRequired(any());
        verify(outboxService, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void updateE3ConfigUsesDurableIdempotencyRequiredAuditAndA4Outbox() {
        deviceRepository.config.put("promoCooldownDays", "14");

        ApiResult<Map<String, Object>> result = service.updateE3Config(
                "idem-e3-durable",
                new E3ConfigUpdateRequest("E.tradein.promo.cooldownDays", "21", "holiday rhythm", "forged"));

        assertThat(result.getCode()).isZero();
        verify(idempotencyService).execute(eq("E3_CONFIG_UPDATE"), eq("idem-e3-durable"), anyString(), eq(ApiResult.class), any());
        verify(auditLogService).recordRequired(argThat(audit ->
                "admin.tradein_config_changed".equals(audit.getAction())
                        && "superadmin".equals(audit.getActorUsername()) == false));
        verify(outboxService).publish(eq("E3_CONFIG"), eq("promoCooldownDays"),
                eq("admin.tradein_config_changed"), any());
    }

    @Test
    void updateE3ConfigBlocksAmplifyingChangeBelowB1CoverageRedline() {
        deviceRepository.config.put("promoMult", "1");
        when(coverageFacade.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                BigDecimal.valueOf(90), BigDecimal.valueOf(100), true));

        ApiResult<Map<String, Object>> result = service.updateE3Config(
                "idem-e3-b1",
                new E3ConfigUpdateRequest("E.tradein.promoMult", "1.5", "amplify promotion", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(deviceRepository.config).containsEntry("promoMult", "1");
        verify(auditLogService, never()).recordRequired(any());
        verify(outboxService, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void updateE3ConfigBlocksCapacityScheduleChangesThatIncreaseAnyFuturePayoutBelowB1Redline() {
        deviceRepository.config.put("capacityBand1DeltaPct", "-3");
        deviceRepository.config.put("capacityBand2DeltaPct", "-6");
        deviceRepository.config.put("capacityBand3DeltaPct", "-23.7");
        deviceRepository.config.put("capacityFloorPct", "22");
        deviceRepository.config.put("stageEarlyEnd", "3");
        deviceRepository.config.put("stageMidEnd", "8");
        deviceRepository.config.put("cycleMonths", "12");
        when(coverageFacade.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                BigDecimal.valueOf(90), BigDecimal.valueOf(100), true));

        for (var change : List.of(
                Map.entry("E.tradein.stageEarlyEnd", "4"),
                Map.entry("E.tradein.stageMidEnd", "9"))) {
            ApiResult<Map<String, Object>> result = service.updateE3Config(
                    "idem-e3-b1-" + change.getKey(),
                    new E3ConfigUpdateRequest(change.getKey(), change.getValue(), "capacity curve outflow", "superadmin"));

            assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        }
        assertThat(deviceRepository.config)
                .containsEntry("stageEarlyEnd", "3")
                .containsEntry("stageMidEnd", "8");
        verify(auditLogService, never()).recordRequired(any());
        verify(outboxService, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void updateE3ConfigAllowsPureChartHorizonChangeBelowB1Redline() {
        deviceRepository.config.put("stageEarlyEnd", "3");
        deviceRepository.config.put("stageMidEnd", "8");
        deviceRepository.config.put("cycleMonths", "12");
        when(coverageFacade.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                BigDecimal.valueOf(90), BigDecimal.valueOf(100), true));

        ApiResult<Map<String, Object>> result = service.updateE3Config(
                "idem-e3-chart-window",
                new E3ConfigUpdateRequest("E.tradein.cycleMonths", "13", "widen chart view", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(deviceRepository.config).containsEntry("cycleMonths", "13");
        verify(auditLogService).recordRequired(any());
        verify(outboxService).publish(eq("E3_CONFIG"), eq("cycleMonths"),
                eq("admin.tradein_config_changed"), any());
    }

    @Test
    void updateE3ConfigUsesActualCapacityPayoutWhenParticipationSwitchChangesBelowB1Redline() {
        deviceRepository.config.putAll(Map.of(
                "capacityBand1DeltaPct", "-3", "capacityBand2DeltaPct", "-6",
                "capacityBand3DeltaPct", "-23.7", "capacityFloorPct", "22",
                "stageEarlyEnd", "3", "stageMidEnd", "8", "cycleMonths", "12",
                "capacityApplyToS1", "true"));
        when(coverageFacade.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                BigDecimal.valueOf(90), BigDecimal.valueOf(100), true));

        ApiResult<Map<String, Object>> result = service.updateE3Config(
                "idem-e3-disable-decay",
                new E3ConfigUpdateRequest("E.device.capacity.applyTo.stellarbox-s1", "false", "exempt S1", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(deviceRepository.config).containsEntry("capacityApplyToS1", "true");
    }

    @Test
    void updateE3ConfigBlocksPositiveGrowthParticipationButAllowsExemptionBelowB1Redline() {
        deviceRepository.config.putAll(Map.of(
                "capacityBand1DeltaPct", "3", "capacityBand2DeltaPct", "6",
                "capacityBand3DeltaPct", "10", "capacityFloorPct", "22",
                "stageEarlyEnd", "3", "stageMidEnd", "8", "cycleMonths", "12",
                "capacityApplyToS1", "false"));
        when(coverageFacade.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                BigDecimal.valueOf(90), BigDecimal.valueOf(100), true));

        ApiResult<Map<String, Object>> blocked = service.updateE3Config(
                "idem-e3-enable-growth",
                new E3ConfigUpdateRequest("E.device.capacity.applyTo.stellarbox-s1", "true", "enable growth", "superadmin"));
        assertThat(blocked.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());

        deviceRepository.config.put("capacityApplyToS1", "true");
        ApiResult<Map<String, Object>> allowed = service.updateE3Config(
                "idem-e3-disable-growth",
                new E3ConfigUpdateRequest("E.device.capacity.applyTo.stellarbox-s1", "false", "disable growth", "superadmin"));
        assertThat(allowed.getCode()).as("message=%s", allowed.getMessage()).isZero();
    }

    @Test
    void updateE3ConfigBlocksLadderBoundaryExpansionBelowB1Redline() {
        deviceRepository.config.putAll(Map.of(
                "tradeinLadderCut1", "25", "tradeinLadderCut2", "50",
                "tradeinLadderCut3", "75", "tradeinLadderCut4", "100",
                "tradeinLadderCredit1", "75", "tradeinLadderCredit2", "60",
                "tradeinLadderCredit3", "45", "tradeinLadderCredit4", "30",
                "tradeinLadderCredit5", "15"));
        when(coverageFacade.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                BigDecimal.valueOf(90), BigDecimal.valueOf(100), true));

        ApiResult<Map<String, Object>> result = service.updateE3Config(
                "idem-e3-ladder-cut",
                new E3ConfigUpdateRequest("E.tradein.ladder.cut1", "30", "expand top credit band", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(deviceRepository.config).containsEntry("tradeinLadderCut1", "25");
    }

    @Test
    void e1GenerationGateRejectsLegacyDiscountWrites() {
        catalogRepository.phases.put("P1", phase("P1", "P1", 10));
        catalogRepository.generationGates.put(
                "stellarbox-test",
                gate("stellarbox-test", "NexionBox Test", 1, "P1", new BigDecimal("300"), true, 0, false, "active"));

        ApiResult<Map<String, Object>> result = service.updateE1GenerationGate(
                "idem-gate-discount",
                new E3ConfigUpdateRequest(
                        "E.gen.stellarbox-test.discount", "100", "禁止旧折扣字段写入", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("E1_GATE_KEY_INVALID");
        assertThat(service.e1GenerationGates().getData().toString())
                .doesNotContain("discount")
                .doesNotContain("tradeinDiscount");
        assertThat(catalogRepository.generationGates.get("stellarbox-test").discount()).isEqualByComparingTo("300");
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
        assertThat(deviceRepository.lastTradeinCliffMonth).isEqualTo(9);
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
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("admin.tradein_action_executed");
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
        DeviceDatacenterUpsertRequest seed = new DeviceDatacenterUpsertRequest(
                "HK-1", "Hong Kong", "active", 10,
                "seed data center", "superadmin", "Hong Kong", "Hong Kong DC");
        deviceRepository.datacenters.put("HK-1", FakeDeviceOpsRepository.dcView(
                seed, LocalDateTime.now(clock), LocalDateTime.now(clock)));
        DatacenterOpsRequest request = new DatacenterOpsRequest("maintenance", "superadmin");

        ApiResult<Map<String, Object>> result = service.pauseDatacenter("HK-1", "idem-dc", request);

        assertThat(result.getCode()).isZero();
        assertThat(deviceRepository.pausedDc).isEqualTo("HK-1");
        assertThat(deviceRepository.pauseReason).isEqualTo("maintenance");
    }

    @Test
    void datacenterCrudUsesDurableIdempotencyRequiredAuditAndCanonicalEvents() {
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
        verify(idempotencyService).execute(eq("E5_DATACENTER_CREATE"), eq("idem-dc-create"),
                anyString(), eq(ApiResult.class), any());
        verify(idempotencyService).execute(eq("E5_DATACENTER_UPDATE"), eq("idem-dc-update"),
                anyString(), eq(ApiResult.class), any());
        verify(idempotencyService).execute(eq("E5_DATACENTER_DELETE"), eq("idem-dc-delete"),
                anyString(), eq(ApiResult.class), any());
        verify(auditLogService, times(3)).recordRequired(captor.capture());
        assertThat(captor.getAllValues()).extracting(AuditLogWriteRequest::getAction)
                .containsExactly("admin.datacenter_created", "admin.datacenter_updated", "admin.datacenter_deleted");
        verify(outboxService).publish(eq("E5_DATACENTER"), eq("us-east-2"),
                eq("admin.datacenter_created"), argThat(payload -> payload instanceof Map<?, ?> map
                        && "美国 · 弗吉尼亚".equals(map.get("display_name"))));
        verify(outboxService).publish(eq("E5_DATACENTER"), eq("us-east-2"),
                eq("admin.datacenter_updated"), any());
        verify(outboxService).publish(eq("E5_DATACENTER"), eq("us-east-2"),
                eq("admin.datacenter_deleted"), any());
    }

    @Test
    void deleteDatacenterBlocksAndKeepsRowWhenDevicesReference() {
        seedDatacenter("HK-1");
        deviceRepository.referenceDevices = 2L;
        DatacenterOpsRequest request = new DatacenterOpsRequest("retire blocked dc", "superadmin");

        ApiResult<Map<String, Object>> result = service.deleteDatacenter("HK-1", "idem-dc-del-dev", request);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("DATACENTER_HAS_REFERENCES");
        assertThat(result.getData()).containsEntry("dcLocation", "HK-1");
        assertThat(result.getData()).containsEntry("deviceCount", 2L);
        assertThat(result.getData()).containsEntry("pendingOrderCount", 0L);
        assertThat(result.getData()).containsEntry("skuCount", 0L);
        // 未软删、未同步清理
        assertThat(deviceRepository.datacenters).containsKey("HK-1");
        assertThat(deviceRepository.detachedDc).isNull();
        assertThat(deviceRepository.opsStateSoftDeleted).isFalse();
        verify(auditLogService, never()).record(any(AuditLogWriteRequest.class));
    }

    @Test
    void deleteDatacenterBlocksWhenPendingOrdersReference() {
        seedDatacenter("HK-1");
        deviceRepository.referencePendingOrders = 1L;
        deviceRepository.referenceSkus = 3L;
        DatacenterOpsRequest request = new DatacenterOpsRequest("retire blocked dc", "superadmin");

        ApiResult<Map<String, Object>> result = service.deleteDatacenter("HK-1", "idem-dc-del-ord", request);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("DATACENTER_HAS_REFERENCES");
        assertThat(result.getData()).containsEntry("pendingOrderCount", 1L);
        assertThat(result.getData()).containsEntry("skuCount", 3L);
        assertThat(deviceRepository.datacenters).containsKey("HK-1");
    }

    @Test
    void deleteDatacenterSucceedsAndSyncsDeviceDcAndOpsStateWhenNoReferences() {
        seedDatacenter("HK-1");
        DatacenterOpsRequest request = new DatacenterOpsRequest("retire dc", "superadmin");

        ApiResult<Map<String, Object>> result = service.deleteDatacenter("HK-1", "idem-dc-del-ok", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("deleted", true);
        assertThat(deviceRepository.datacenters).doesNotContainKey("HK-1");
        // 软删成功后同步清理设备表 dc_location 与运营状态行
        assertThat(deviceRepository.detachedDc).isEqualTo("HK-1");
        assertThat(deviceRepository.opsStateSoftDeleted).isTrue();
    }

    private void seedDatacenter(String dcLocation) {
        DeviceDatacenterUpsertRequest seed = new DeviceDatacenterUpsertRequest(
                dcLocation, "Hong Kong", "active", 10,
                "seed data center", "superadmin", "Hong Kong", "Hong Kong DC");
        deviceRepository.datacenters.put(dcLocation, FakeDeviceOpsRepository.dcView(
                seed, LocalDateTime.now(clock), LocalDateTime.now(clock)));
    }

    @Test
    void createDatacenterRejectsInvalidInput() {
        ApiResult<DeviceDatacenterView> missingKey = service.createDatacenter(
                "",
                new DeviceDatacenterUpsertRequest("us-east-2", "美国 · 弗吉尼亚", "active", 10, "seed data center", "superadmin"));
        ApiResult<DeviceDatacenterView> missingRegion = service.createDatacenter(
                "idem-dc",
                new DeviceDatacenterUpsertRequest("us-east-2", "", "active", 10, "seed data center", "superadmin"));
        ApiResult<DeviceDatacenterView> invalidStatus = service.createDatacenter(
                "idem-dc",
                new DeviceDatacenterUpsertRequest("us-east-2", "美国 · 弗吉尼亚", "online", 10, "seed data center", "superadmin"));

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
        verify(auditLogService).recordRequired(captor.capture());
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
    void executeTradeinActionUsesDurableIdempotencyRequiredAuditAndA4Outbox() {
        deviceRepository.device = device("ONLINE", 0, null);

        ApiResult<DeviceOpsView> result = service.executeTradeinAction(
                "replace", "idem-tradein-durable",
                new DeviceTradeinActionRequest(1L, "ops replacement", "forged"));

        assertThat(result.getCode()).isZero();
        verify(idempotencyService).execute(eq("E3_TRADEIN_REPLACE"), eq("idem-tradein-durable"), anyString(), eq(ApiResult.class), any());
        verify(auditLogService).recordRequired(argThat(audit ->
                "admin.tradein_action_executed".equals(audit.getAction())
                        && "superadmin".equals(audit.getActorUsername()) == false));
        verify(outboxService).publish(eq("E3_TRADEIN"), eq("1"),
                eq("admin.tradein_action_executed"), any());
    }

    @Test
    void createSkuDefaultsMissingGenerationForReplayCompatibility() {
        catalogRepository.phases.put("P1", phase("P1", "P1", 10));

        ApiResult<DeviceSkuView> result = service.createSku(
                "idem-generation-default",
                skuRequest("stellarbox-no-generation", "NexionBox Compatible", "pending", "Entry", "HK-1", null, "active", "P1"));

        assertThat(result.getCode()).isZero();
        assertThat(catalogRepository.lastSkuRequest.generation()).isEqualTo(1);
    }

    @Test
    void skuListingRequiresActiveEligibleReachedAndReleasedGate() {
        catalogRepository.phases.put("1", phase("1", "种子期", 10));
        catalogRepository.phases.put("2", phase("2", "启动期", 20));
        configFacade.values.put("H1.rhythm.totalMonths", "12");
        configFacade.values.put("H1.rhythm.currentMonth", "1");
        catalogRepository.sku = sku("stellarbox-test", "NexionBox Test", "pending", "1");

        catalogRepository.generationGates.put(
                "stellarbox-test",
                gate("stellarbox-test", "NexionBox Test", 1, "1", BigDecimal.ZERO, false, 0, false, "active"));
        ApiResult<DeviceSkuView> ineligible = service.updateSkuStatus(
                "stellarbox-test", "idem-list-ineligible", new DeviceSkuStatusRequest("on", "发布商品门槛校验", "superadmin"));

        catalogRepository.generationGates.put(
                "stellarbox-test",
                gate("stellarbox-test", "NexionBox Test", 1, "2", BigDecimal.ZERO, true, 0, false, "active"));
        ApiResult<DeviceSkuView> phaseBlocked = service.updateSkuStatus(
                "stellarbox-test", "idem-list-phase", new DeviceSkuStatusRequest("on", "发布商品门槛校验", "superadmin"));

        catalogRepository.generationGates.put(
                "stellarbox-test",
                gate("stellarbox-test", "NexionBox Test", 2, "1", BigDecimal.ZERO, true, 0, false, "active"));
        ApiResult<DeviceSkuView> monthBlocked = service.updateSkuStatus(
                "stellarbox-test", "idem-list-month", new DeviceSkuStatusRequest("on", "发布商品门槛校验", "superadmin"));

        assertThat(ineligible.getMessage()).isEqualTo("E1_SKU_E5_ELIGIBILITY_REQUIRED");
        assertThat(phaseBlocked.getMessage()).isEqualTo("E1_SKU_H1_PHASE_NOT_REACHED");
        assertThat(monthBlocked.getMessage()).isEqualTo("E1_SKU_RELEASE_MONTH_NOT_REACHED");

        catalogRepository.generationGates.put(
                "stellarbox-test",
                gate("stellarbox-test", "NexionBox Test", 1, "1", BigDecimal.ZERO, true, 0, false, "active"));
        ApiResult<DeviceSkuView> listed = service.updateSkuStatus(
                "stellarbox-test", "idem-list-ok", new DeviceSkuStatusRequest("on", "发布商品门槛校验", "superadmin"));

        assertThat(listed.getCode()).isZero();
        verify(outboxService).publish("DEVICE_SKU", "stellarbox-test", "admin.product_listed", Map.of(
                "sku_key", "stellarbox-test",
                "before_status", "pending",
                "after_status", "on",
                "operator", "superadmin",
                "reason", "发布商品门槛校验"));
    }

    @Test
    void skuWithoutActiveGateCanBeListed() {
        catalogRepository.sku = sku("cloud-share", "Nexion Cloud Share", "pending", "");

        ApiResult<DeviceSkuView> listed = service.updateSkuStatus(
                "cloud-share", "idem-list-no-gate", new DeviceSkuStatusRequest("on", "无需阶段商品上架", "superadmin"));

        assertThat(listed.getCode()).isZero();
        assertThat(listed.getData().status()).isEqualTo("on");
        verify(outboxService).publish("DEVICE_SKU", "cloud-share", "admin.product_listed", Map.of(
                "sku_key", "cloud-share",
                "before_status", "pending",
                "after_status", "on",
                "operator", "superadmin",
                "reason", "无需阶段商品上架"));
    }

    @Test
    void skuWithoutActiveGateCannotListBeforeItsUnlockPhaseIsReached() {
        catalogRepository.phases.put("1", phase("1", "种子期", 10));
        catalogRepository.phases.put("2", phase("2", "启动期", 20));
        configFacade.values.put("H1.rhythm.totalMonths", "12");
        configFacade.values.put("H1.rhythm.currentMonth", "1");
        catalogRepository.sku = sku("future-box", "Future Box", "pending", "2");

        ApiResult<DeviceSkuView> result = service.updateSkuStatus(
                "future-box", "idem-list-future-no-gate",
                new DeviceSkuStatusRequest("on", "未来阶段商品不可提前上架", "superadmin"));

        assertThat(result.getMessage()).isEqualTo("E1_SKU_H1_PHASE_NOT_REACHED");
        assertThat(catalogRepository.sku.status()).isEqualTo("pending");
        verify(outboxService, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void skuWithoutActiveGateCanListAfterItsConfiguredUnlockPhaseIsReached() {
        catalogRepository.phases.put("1", phase("1", "种子期", 10));
        catalogRepository.phases.put("2", phase("2", "启动期", 20));
        configFacade.values.put("H1.rhythm.currentMonth", "1");
        catalogRepository.sku = sku("current-phase-box", "Current Phase Box", "pending", "1");

        ApiResult<DeviceSkuView> result = service.updateSkuStatus(
                "current-phase-box", "idem-list-current-phase-no-gate",
                new DeviceSkuStatusRequest("on", "当前阶段商品满足上架条件", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("on");
    }

    @Test
    void skuWithoutActiveGateRequiresAConfiguredUnlockPhaseWhenOneIsDeclared() {
        catalogRepository.phases.put("1", phase("1", "种子期", 10));
        configFacade.values.put("H1.rhythm.currentMonth", "1");
        catalogRepository.sku = sku("orphan-phase-box", "Orphan Phase Box", "pending", "unknown-phase");

        ApiResult<DeviceSkuView> result = service.updateSkuStatus(
                "orphan-phase-box", "idem-list-invalid-phase-no-gate",
                new DeviceSkuStatusRequest("on", "未配置阶段商品不可直接上架", "superadmin"));

        assertThat(result.getMessage()).isEqualTo("E1_SKU_UNLOCK_PHASE_INVALID");
        assertThat(catalogRepository.sku.status()).isEqualTo("pending");
    }

    @Test
    void archivedGateFallsBackToSkuUnlockPhaseInsteadOfBypassingH1() {
        catalogRepository.phases.put("1", phase("1", "种子期", 10));
        catalogRepository.phases.put("2", phase("2", "启动期", 20));
        configFacade.values.put("H1.rhythm.currentMonth", "1");
        catalogRepository.sku = sku("archived-gate-box", "Archived Gate Box", "pending", "2");
        catalogRepository.generationGates.put(
                "archived-gate-box",
                gate("archived-gate-box", "Archived Gate Box", 1, "2", BigDecimal.ZERO, true, 0, true, "archived"));

        ApiResult<DeviceSkuView> result = service.updateSkuStatus(
                "archived-gate-box", "idem-list-archived-gate",
                new DeviceSkuStatusRequest("on", "已归档门槛不能绕过阶段限制", "superadmin"));

        assertThat(result.getMessage()).isEqualTo("E1_SKU_H1_PHASE_NOT_REACHED");
        assertThat(catalogRepository.sku.status()).isEqualTo("pending");
    }

    @Test
    void activeGateCannotDivergeFromTheSkuUnlockPhase() {
        catalogRepository.phases.put("1", phase("1", "种子期", 10));
        catalogRepository.phases.put("2", phase("2", "启动期", 20));
        configFacade.values.put("H1.rhythm.currentMonth", "12");
        catalogRepository.sku = sku("split-phase-box", "Split Phase Box", "pending", "1");
        catalogRepository.generationGates.put(
                "split-phase-box",
                gate("split-phase-box", "Split Phase Box", 1, "2", BigDecimal.ZERO, true, 0, false, "active"));

        ApiResult<DeviceSkuView> result = service.updateSkuStatus(
                "split-phase-box", "idem-list-split-phase",
                new DeviceSkuStatusRequest("on", "商品阶段与门槛阶段必须保持一致", "superadmin"));

        assertThat(result.getMessage()).isEqualTo("E1_SKU_GATE_PHASE_MISMATCH");
        assertThat(catalogRepository.sku.status()).isEqualTo("pending");
    }

    @Test
    void skuPriceAndUnlistChangesUseExistingOutbox() {
        catalogRepository.phases.put("P1", phase("P1", "P1", 10));
        catalogRepository.sku = sku("stellarbox-test", "NexionBox Test", "on", "P1");
        BigDecimal beforePrice = catalogRepository.sku.price();
        DeviceSkuUpsertRequest repriced = withPriceAndStatus(
                skuRequest("stellarbox-test", "NexionBox Test", "on"), beforePrice.add(BigDecimal.ONE), "on");

        ApiResult<DeviceSkuView> priceResult = service.updateSku("stellarbox-test", "idem-price-change", repriced);
        ApiResult<DeviceSkuView> offResult = service.updateSkuStatus(
                "stellarbox-test", "idem-unlist", new DeviceSkuStatusRequest("off", "商品下架运营处理", "superadmin"));

        assertThat(priceResult.getCode()).isZero();
        assertThat(offResult.getCode()).isZero();
        verify(outboxService).publish(
                eq("DEVICE_SKU"), eq("stellarbox-test"), eq("admin.product_price_changed"), argThat(payload ->
                        payload instanceof Map<?, ?> event
                                && "stellarbox-test".equals(event.get("sku_key"))
                                && "price".equals(event.get("scope"))
                                && "price".equals(event.get("field"))
                                && beforePrice.equals(event.get("before"))
                                && beforePrice.add(BigDecimal.ONE).equals(event.get("after"))
                                && "2026-06-17T00:00".equals(event.get("effective_at"))
                                && "superadmin".equals(event.get("operator"))
                                && "catalog update".equals(event.get("reason"))));
        verify(outboxService).publish("DEVICE_SKU", "stellarbox-test", "admin.product_unlisted", Map.of(
                "sku_key", "stellarbox-test",
                "before_status", "on",
                "after_status", "off",
                "operator", "superadmin",
                "reason", "商品下架运营处理"));
    }

    @Test
    void authenticatedE1SkuWritesIgnoreForgedBodyOperatorEverywhere() {
        authenticateAs("superadmin");
        String skuId = "trusted-actor-share";
        DeviceSkuUpsertRequest forgedCreate = withOperator(
                skuRequest(skuId, "Trusted Actor Share", "on", "Share", "HK-1", 2, "active", ""),
                "forged-client");

        ApiResult<DeviceSkuView> created = service.createSku("idem-actor-create", forgedCreate);
        DeviceSkuUpsertRequest forgedPrice = withOperator(
                withPriceAndStatus(forgedCreate, forgedCreate.price().add(BigDecimal.ONE), "on"),
                "forged-client");
        ApiResult<DeviceSkuView> repriced = service.updateSku(skuId, "idem-actor-price", forgedPrice);
        ApiResult<DeviceSkuView> unlisted = service.updateSkuStatus(
                skuId, "idem-actor-unlist",
                new DeviceSkuStatusRequest("off", "可信主体执行商品下架", "forged-client"));
        ApiResult<DeviceSkuView> relisted = service.updateSkuStatus(
                skuId, "idem-actor-status",
                new DeviceSkuStatusRequest("on", "可信主体执行商品上架", "forged-client"));

        assertThat(created.getCode()).isZero();
        assertThat(repriced.getCode()).isZero();
        assertThat(unlisted.getCode()).isZero();
        assertThat(relisted.getCode()).isZero();
        assertThat(catalogRepository.lastSkuRequest.operator()).isEqualTo("superadmin");

        ArgumentCaptor<String> eventType = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(outboxService, times(4)).publish(
                eq("DEVICE_SKU"), eq(skuId), eventType.capture(), payload.capture());
        assertThat(eventType.getAllValues()).containsExactly(
                "admin.product_listed",
                "admin.product_price_changed",
                "admin.product_unlisted",
                "admin.product_listed");
        assertThat(payload.getAllValues())
                .allSatisfy(event -> assertThat(event)
                        .containsEntry("operator", "superadmin")
                        .doesNotContainValue("forged-client"));

        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, times(4)).recordRequired(audit.capture());
        assertThat(audit.getAllValues())
                .extracting(AuditLogWriteRequest::getActorUsername)
                .containsOnly("superadmin");
    }

    @Test
    void authenticatedA2ReplayUsesSecurityContextActorInsteadOfReplayFallback() {
        authenticateAs("superadmin");
        catalogRepository.sku = sku("a2-actor-box", "A2 Actor Box", "on");

        ApiResult<?> result = service.replay(
                new AuditReplayCommand("E", "e1_sku_status", Map.of(
                        "skuId", "a2-actor-box",
                        "status", "off")),
                new AuditReplayContext("stale-replay-actor", "A2 回放可信主体校验", "idem-a2-actor"));

        assertThat(result.getCode()).isZero();
        verify(outboxService).publish(
                eq("DEVICE_SKU"), eq("a2-actor-box"), eq("admin.product_unlisted"), argThat(payload ->
                        payload instanceof Map<?, ?> event
                                && "superadmin".equals(event.get("operator"))
                                && !event.containsValue("stale-replay-actor")));
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(audit.capture());
        assertThat(audit.getValue().getActorUsername()).isEqualTo("superadmin");
    }

    @Test
    void unauthenticatedInternalA2ReplayUsesItsTrustedActorFallback() {
        catalogRepository.sku = sku("a2-internal-box", "A2 Internal Box", "on");

        ApiResult<?> result = service.replay(
                new AuditReplayCommand("E", "e1_sku_status", Map.of(
                        "skuId", "a2-internal-box",
                        "status", "off")),
                new AuditReplayContext("trusted-a2-executor", "内部回放可信主体回退", "idem-a2-internal"));

        assertThat(result.getCode()).isZero();
        verify(outboxService).publish(
                eq("DEVICE_SKU"), eq("a2-internal-box"), eq("admin.product_unlisted"), argThat(payload ->
                        payload instanceof Map<?, ?> event
                                && "trusted-a2-executor".equals(event.get("operator"))));
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(audit.capture());
        assertThat(audit.getValue().getActorUsername()).isEqualTo("trusted-a2-executor");
    }

    @Test
    void authenticatedE1ConfigPersistenceIgnoresForgedBodyOperator() {
        authenticateAs("superadmin");

        ApiResult<Map<String, Object>> result = service.updateE1EarlyAccess(
                "idem-actor-persistence",
                new DeviceEarlyAccessUpdateRequest(true, 14, "可信主体写入提前购买配置", "forged-client"));

        assertThat(result.getCode()).isZero();
        assertThat(deviceRepository.lastConfigOperator).isEqualTo("superadmin");
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(audit.capture());
        assertThat(audit.getValue().getActorUsername()).isEqualTo("superadmin");
    }

    @Test
    void e1EarlyAccessUpdatesBothKeysAndSupportsDedicatedReplay() {
        DeviceEarlyAccessUpdateRequest request =
                new DeviceEarlyAccessUpdateRequest(true, 14, "开启提前购买配置", "superadmin");

        ApiResult<Map<String, Object>> direct = service.updateE1EarlyAccess("idem-early-direct", request);
        ApiResult<?> replay = service.replay(
                new AuditReplayCommand("E", "e1_early_access_update", Map.of("enabled", false, "leadDays", 30)),
                new AuditReplayContext("superadmin", "关闭提前购买配置", "idem-early-replay"));
        ApiResult<Map<String, Object>> invalid = service.updateE1EarlyAccess(
                "idem-early-invalid",
                new DeviceEarlyAccessUpdateRequest(true, 15, "错误提前购买配置", "superadmin"));

        assertThat(direct.getCode()).isZero();
        assertThat(replay.getCode()).isZero();
        assertThat(deviceRepository.config)
                .containsEntry("earlyAccessEnabled", "false")
                .containsEntry("earlyAccessLeadDays", "30");
        assertThat(invalid.getMessage()).isEqualTo("E1_EARLY_ACCESS_LEAD_DAYS_INVALID");
        verify(auditLogService, times(2)).recordRequired(any());
    }

    @Test
    void legacySetCurrentPhaseReplayIsDisabled() {
        configFacade.values.put("growth.phase.current", "3");

        ApiResult<?> result = service.replay(
                new AuditReplayCommand("E", "e1_phase_current", Map.of("phaseId", "2")),
                new AuditReplayContext("superadmin", "禁止手工切换阶段", "idem-phase-current-disabled"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("UNKNOWN_REPLAY_OP:e1_phase_current");
        assertThat(configFacade.values).containsEntry("growth.phase.current", "3");
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
        verify(auditLogService, times(2)).recordRequired(captor.capture());
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
                new DevicePhaseUpsertRequest(null, "代际第一代", "L0+", "Entry", 10, "active", "修改成为业务阶段命名", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(catalogRepository.phases).containsKey("1");
        assertThat(catalogRepository.phases.get("1").label()).isEqualTo("代际第一代");
        assertThat(catalogRepository.sku.unlockPhase()).isEqualTo("1");
        assertThat(catalogRepository.generationGates.get("stellarbox-test").phase()).isEqualTo("1");
        assertThat(configFacade.values).containsEntry("growth.phase.current", "1");
    }

    @Test
    void e1CurrentPhaseIgnoresLegacyOverrideAndFollowsH1Month() {
        catalogRepository.phases.put("1", phase("1", "种子期", 10));
        catalogRepository.phases.put("2", phase("2", "启动期", 20));
        catalogRepository.phases.put("3", phase("3", "增长期", 30));
        catalogRepository.phases.put("4", phase("4", "扩张期", 40));
        catalogRepository.phases.put("5", phase("5", "稳定期", 50));
        configFacade.values.put("H1.rhythm.totalMonths", "12");
        configFacade.values.put("H1.rhythm.currentMonth", "3");
        configFacade.values.put("growth.phase.current", "3");

        ApiResult<Map<String, Object>> result = service.e1GenerationGates();

        assertThat(result.getData()).containsEntry("phaseCurrent", "1");
        assertThat(result.getData().get("h1Rhythm"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("currentPhase", "1")
                .containsEntry("currentMonth", 3);
    }

    @Test
    void e1GenerationGatesReadCanonicalH1RhythmForCurrentPhase() {
        catalogRepository.phases.put("P1", phase("P1", "拉新", 10));
        catalogRepository.phases.put("P2", phase("P2", "激活", 20));
        catalogRepository.phases.put("P3", phase("P3", "扩张", 30));
        catalogRepository.phases.put("P4", phase("P4", "深化", 40));
        catalogRepository.phases.put("P5", phase("P5", "收紧", 50));
        catalogRepository.phases.put("P6", phase("P6", "软退场", 60));
        configFacade.values.put("H1.rhythm.currentMonth", "10");
        configFacade.values.put("H1.rhythm.phaseProgressPct", "35");
        configFacade.values.put("growth.phase.current", "P5");

        ApiResult<Map<String, Object>> result = service.e1GenerationGates();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("platformMonth", 10)
                .containsEntry("phaseCurrent", "P5");
        assertThat(result.getData().get("h1Rhythm"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("currentPhase", "P5")
                .containsEntry("currentMonth", 10)
                .containsEntry("phaseProgressPct", 35);
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
    void e1ReasonMustBeEightToTwoHundredCodePointsAfterTrim() {
        ApiResult<Map<String, Object>> tooShort = service.createE1Phase(
                "idem-short-reason",
                new DevicePhaseUpsertRequest(null, "P1", "", "", 10, "active", "  1234567  ", "superadmin"));
        ApiResult<Map<String, Object>> tooLong = service.createE1Phase(
                "idem-long-reason",
                new DevicePhaseUpsertRequest(null, "P2", "", "", 20, "active", "x".repeat(201), "superadmin"));

        assertThat(tooShort.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(tooShort.getMessage()).isEqualTo("REASON_LENGTH_INVALID");
        assertThat(tooLong.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(tooLong.getMessage()).isEqualTo("REASON_LENGTH_INVALID");
        assertThat(catalogRepository.phases).isEmpty();
        verify(idempotencyService, never()).execute(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void e1CommandUsesStablePayloadHashForDurableIdempotency() {
        DevicePhaseUpsertRequest request =
                new DevicePhaseUpsertRequest(null, "代际第一代", "L0+", "Entry", 10, "active", "新增阶段配置原因", "superadmin");

        service.createE1Phase("same-key", request);
        service.createE1Phase("same-key", request);
        service.createE1Phase(
                "same-key",
                new DevicePhaseUpsertRequest(null, "代际第二代", "L1+", "Pro", 20, "active", "新增阶段配置原因", "superadmin"));

        ArgumentCaptor<String> scope = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(idempotencyService, times(3)).execute(
                scope.capture(), anyString(), hashes.capture(), any(), any());
        assertThat(scope.getAllValues()).containsOnly("E1_PHASE_CREATE");
        assertThat(hashes.getAllValues().get(0)).isEqualTo(hashes.getAllValues().get(1));
        assertThat(hashes.getAllValues().get(2)).isNotEqualTo(hashes.getAllValues().get(0));
    }

    @Test
    void e1AuditFailureIsNotSwallowed() {
        catalogRepository.phases.put("P1", phase("P1", "P1", 10));
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable"))
                .when(auditLogService).recordRequired(any());

        assertThatThrownBy(() -> service.createSku(
                        "idem-required-audit",
                        skuRequest("stellarbox-audit", "NexionBox Audit", "pending")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
        verify(auditLogService).recordRequired(any());
    }

    @Test
    void patchPhaseCannotArchiveAReferencedPhase() {
        catalogRepository.phases.put("1", phase("1", "P1", 10));
        catalogRepository.phases.put("2", phase("2", "P2", 20));
        catalogRepository.generationGates.put(
                "stellarbox-test",
                gate("stellarbox-test", "NexionBox Test", 3, "2", BigDecimal.ZERO, true, 0, false, "active"));
        configFacade.values.put("growth.phase.current", "1");

        ApiResult<Map<String, Object>> result = service.patchE1Phase(
                "2",
                "idem-phase-status-archive",
                new DevicePhaseUpsertRequest(null, "P2", null, null, null, "archived", "归档阶段配置原因", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("E1_PHASE_IN_USE");
        assertThat(catalogRepository.phases.get("2").status()).isEqualTo("active");
    }

    @Test
    void forceUnlockRequiresEligibilityReachedPhaseAndFinePermission() {
        catalogRepository.phases.put("1", phase("1", "P1", 10));
        catalogRepository.phases.put("2", phase("2", "P2", 20));
        catalogRepository.sku = sku("stellarbox-test", "NexionBox Test", "on", "1");
        configFacade.values.put("H1.rhythm.totalMonths", "12");
        configFacade.values.put("H1.rhythm.currentMonth", "1");

        ApiResult<Map<String, Object>> ineligible = service.createE1GenerationGate(
                "idem-force-ineligible",
                gateRequest("stellarbox-test", "1", false, true));
        ApiResult<Map<String, Object>> phaseNotReached = service.createE1GenerationGate(
                "idem-force-phase",
                gateRequest("stellarbox-test", "2", true, true));
        ApiResult<Map<String, Object>> forbidden = service.createE1GenerationGate(
                "idem-force-forbidden",
                gateRequest("stellarbox-test", "1", true, true));

        assertThat(ineligible.getMessage()).isEqualTo("E1_FORCE_UNLOCK_ELIGIBILITY_REQUIRED");
        assertThat(phaseNotReached.getMessage()).isEqualTo("E1_FORCE_UNLOCK_PHASE_NOT_REACHED");
        assertThat(forbidden.getCode()).isEqualTo(403);
        assertThat(forbidden.getMessage()).isEqualTo("E1_FORCE_UNLOCK_FORBIDDEN");

        authenticate("device_e1_generation_gate_force_unlock");
        ApiResult<Map<String, Object>> allowed = service.createE1GenerationGate(
                "idem-force-allowed",
                gateRequest("stellarbox-test", "1", true, true));
        assertThat(allowed.getCode()).isZero();
        assertThat(catalogRepository.generationGates.get("stellarbox-test").forceUnlock()).isTrue();

        ApiResult<Map<String, Object>> lockForbidden = service.patchE1GenerationGate(
                "stellarbox-test",
                "idem-force-lock-forbidden",
                new DeviceGenerationGatePatchRequest(
                        null, null, null, null, null, false, null, "关闭强制解锁配置", "superadmin"));
        assertThat(lockForbidden.getCode()).isEqualTo(403);
        assertThat(lockForbidden.getMessage()).isEqualTo("E1_FORCE_LOCK_FORBIDDEN");

        authenticate("device_e1_generation_gate_force_lock");
        ApiResult<Map<String, Object>> locked = service.patchE1GenerationGate(
                "stellarbox-test",
                "idem-force-lock-allowed",
                new DeviceGenerationGatePatchRequest(
                        null, null, null, null, null, false, null, "关闭强制解锁配置", "superadmin"));
        assertThat(locked.getCode()).isZero();
        assertThat(catalogRepository.generationGates.get("stellarbox-test").forceUnlock()).isFalse();
    }

    @Test
    void patchGenerationGateRejectsExistingOrphanPhaseReference() {
        catalogRepository.phases.put("1", phase("1", "P1", 10));
        catalogRepository.generationGates.put(
                "stellarbox-test",
                gate("stellarbox-test", "NexionBox Test", 3, "missing", BigDecimal.ZERO, true, 0, false, "active"));

        ApiResult<Map<String, Object>> result = service.patchE1GenerationGate(
                "stellarbox-test",
                "idem-fix-orphan",
                new DeviceGenerationGatePatchRequest(
                        "NexionBox Test", null, null, null, null, null, null, "修复孤儿引用原因", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("E1_GATE_PHASE_INVALID");
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
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E1_REVIEW_STATUS_CHANGED");
    }

    @Test
    void tasksReturnEmptyWhenPrimaryListIsEmpty() {
        ApiResult<PageResult<DeviceTaskView>> result = service.tasks(new DeviceTaskQueryRequest(null, null, null, 1L, 20L));

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
        verify(auditLogService).recordRequired(captor.capture());
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
        verify(auditLogService).recordRequired(captor.capture());
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
        assertThat(result.getData().taskClass()).isEqualTo("EM");
        assertThat(result.getData().model()).isEqualTo("BGE-M3");
        assertThat(result.getData().minReward()).isEqualByComparingTo("0.06");
        assertThat(result.getData().maxReward()).isEqualByComparingTo("0.22");
        assertThat(result.getData().minVram()).isEqualTo("8GB");
        assertThat(result.getData().killInit()).isEqualTo("派发中");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
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
        assertThat(refundSettlementFacade.entries).hasSize(1);
        assertThat(refundSettlementFacade.entries.get(0))
                .containsEntry("orderNo", "OD-1")
                .containsEntry("userId", 1L)
                .containsEntry("channel", "WALLET");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E4_ORDER_REFUNDED");
        verify(outboxService).publish(eq("E4_ORDER"), eq("OD-1"), eq("order.refunded"),
                argThat(payload -> payload instanceof Map<?, ?> map
                        && "OD-1".equals(map.get("orderId"))
                        && "WALLET".equals(map.get("refundChannel"))
                        && new BigDecimal("1299").compareTo((BigDecimal) map.get("cumulativeDepositAdjusted")) == 0));
        verify(outboxService).publish(eq("E4_ORDER"), eq("OD-1"), eq("admin.order_refunded"),
                argThat(payload -> payload instanceof Map<?, ?> map
                        && "superadmin".equals(map.get("operator"))
                        && "customer refund approved".equals(map.get("reason"))));
    }

    @Test
    void refundOrderUsesCanonicalB1RedlineErrorContract() {
        catalogRepository.order = order("OD-1", "paid");
        when(coverageFacade.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                BigDecimal.valueOf(99), BigDecimal.valueOf(100), true));

        ApiResult<DeviceOrderView> result = service.refundOrder(
                "OD-1",
                "idem-order-refund-blocked",
                new DeviceOrderActionRequest(null, "coverage blocks refund", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        assertThat(refundSettlementFacade.entries).isEmpty();
        verify(outboxService, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void terminalOrderWritesAudit() {
        catalogRepository.order = order("OD-1", "provisioning");

        ApiResult<DeviceOrderView> result = service.terminalOrder(
                "OD-1",
                "idem-order",
                new DeviceOrderActionRequest("provisioning_failed", "dc timeout", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().state()).isEqualTo("provisioning_failed");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E4_ORDER_TERMINALIZED");
    }

    @Test
    void updateOrderStateAllowsAdjacentMainPathAndWritesAudit() {
        catalogRepository.order = order("OD-1", "paid");

        ApiResult<DeviceOrderView> result = service.updateOrderState(
                "OD-1",
                "idem-order-state",
                new DeviceOrderStateRequest("provisioning", "manual advance", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().state()).isEqualTo("provisioning");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E4_ORDER_STATE_CHANGED");
    }

    @Test
    void updateOrderStateRejectsFailedRetryToProvisioning() {
        catalogRepository.order = order("OD-1", "provisioning_failed");

        ApiResult<DeviceOrderView> result = service.updateOrderState(
                "OD-1",
                "idem-order-retry",
                new DeviceOrderStateRequest("provisioning", "retry allocation", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void updateOrderStateRejectsSkippingMainPath() {
        catalogRepository.order = order("OD-1", "placed");

        ApiResult<DeviceOrderView> result = service.updateOrderState(
                "OD-1",
                "idem-order-skip",
                new DeviceOrderStateRequest("activated", "skip flow", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        verify(auditLogService, never()).record(any());
    }

    @Test
    void computeConfigReturnsDefaultsWhenConfigEmpty() {
        ApiResult<ComputeConfigView> r = newServiceWithEmptyConfig().computeConfig();

        assertThat(r.getData().domain()).isEqualTo("E6");
        assertThat(r.getData().flags()).hasSize(1);
        assertThat(r.getData().flags().get(0).enabled()).isFalse();
        assertThat(r.getData().gpuTiers()).hasSize(6);
        assertThat(r.getData().gpuTiers().get(0).tops()).isEqualTo("40");
        assertThat(r.getData().gpuTiers().get(0).keywords().get(0))
                .extracting(
                        ComputeConfigView.KeywordView::slot,
                        ComputeConfigView.KeywordView::value)
                .containsExactly("keyword1", "gtx 1650");
        assertThat(r.getData().download().url()).isEmpty();
    }

    @Test
    void updateComputeConfigParamRejectsMissingReason() {
        ComputeConfigParamUpdateRequest request = new ComputeConfigParamUpdateRequest("on", "   ", "superadmin");

        ApiResult<ComputeConfigParamResponse> result = service.updateComputeConfigParam(
                ComputeConfigRegistry.flagKey("computeShareEnabled"), "idem-e6", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
        assertThat(configFacade.values).doesNotContainKey(ComputeConfigRegistry.flagKey("computeShareEnabled"));
        verify(auditLogService, never()).record(any());
    }

    @Test
    void updateComputeConfigParamRejectsNonComputeKey() {
        ComputeConfigParamUpdateRequest request = new ComputeConfigParamUpdateRequest("on", "enable share", "superadmin");

        ApiResult<ComputeConfigParamResponse> result = service.updateComputeConfigParam(
                "admin.system.other", "idem-e6", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COMPUTE_PARAM_KEY_INVALID");
        verify(auditLogService, never()).record(any());
    }

    @Test
    void updateComputeConfigParamRejectsUnknownPrefixedKeyAndZeroCoefficient() {
        ApiResult<ComputeConfigParamResponse> unknown = service.updateComputeConfigParam(
                "E.compute.gpuTier.G7.tops", "idem-e6-unknown",
                new ComputeConfigParamUpdateRequest("100", "reject unknown tier", "superadmin"));
        ApiResult<ComputeConfigParamResponse> zero = service.updateComputeConfigParam(
                ComputeConfigRegistry.coeffKey("h5BaseFactor"), "idem-e6-zero",
                new ComputeConfigParamUpdateRequest("0", "reject zero coefficient", "superadmin"));

        assertThat(unknown.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(unknown.getMessage()).isEqualTo("COMPUTE_PARAM_KEY_INVALID");
        assertThat(zero.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(zero.getMessage()).isEqualTo("COMPUTE_COEFF_INVALID");
    }

    @Test
    void directComputeConfigMutationRequiresA2Confirmation() {
        String paramKey = ComputeConfigRegistry.flagKey("computeShareEnabled");
        ComputeConfigParamUpdateRequest request = new ComputeConfigParamUpdateRequest("on", "enable share", "superadmin");

        ApiResult<ComputeConfigParamResponse> result = service.updateComputeConfigParam(paramKey, "idem-e6", request);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("A2_CONFIRMATION_REQUIRED");
        assertThat(configFacade.values).doesNotContainKey(paramKey);
        verify(auditLogService, never()).record(any());
        verify(auditLogService, never()).recordRequired(any());
    }

    @Test
    void replayComputeConfigFlagIsIdempotentAuditedAndPublished() {
        String paramKey = ComputeConfigRegistry.flagKey("computeShareEnabled");
        ComputeConfigParamUpdateRequest request = new ComputeConfigParamUpdateRequest("on", "enable share", "spoofed-client");
        authenticate("device_e6_flag_toggle");
        A2ReplayContext.enterReplay();

        ApiResult<ComputeConfigParamResponse> result = service.updateComputeConfigParam(paramKey, "idem-e6", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().paramKey()).isEqualTo(paramKey);
        assertThat(result.getData().value()).isEqualTo("on");
        assertThat(configFacade.values).containsEntry(paramKey, "on");
        verify(idempotencyService).execute(eq("E6_COMPUTE_CONFIG_UPDATE"), eq("idem-e6"), anyString(), eq(ApiResult.class), any());
        verify(outboxService).publish(eq("E6_COMPUTE_CONFIG"), eq(paramKey), eq("compute.flag_toggled"), any());

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("compute.flag_toggled");
        assertThat(captor.getValue().getResourceType()).isEqualTo("E6_COMPUTE_CONFIG");
        assertThat(captor.getValue().getResourceId()).isEqualTo(paramKey);
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("paramKey", paramKey)
                .containsEntry("before", "")
                .containsEntry("after", "on")
                .containsEntry("idempotencyKey", "idem-e6");
    }

    @Test
    void replayComputeConfigNonCoefficientUsesStringParamEventContract() {
        String paramKey = ComputeConfigRegistry.gpuTierKey("G3", "label");
        authenticate("device_e6_write");
        A2ReplayContext.enterReplay();

        ApiResult<ComputeConfigParamResponse> result = service.updateComputeConfigParam(
                paramKey, "idem-e6-param",
                new ComputeConfigParamUpdateRequest("专业显卡", "rename tier", "spoofed-client"));

        assertThat(result.getCode()).isZero();
        verify(outboxService).publish(eq("E6_COMPUTE_CONFIG"), eq(paramKey), eq("compute.param_changed"), any());
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("compute.param_changed");
    }

    @Test
    void replayComputeConfigBatchValidatesEverythingBeforeAtomicWrite() {
        String labelKey = ComputeConfigRegistry.gpuTierKey("G3", "label");
        String topsKey = ComputeConfigRegistry.gpuTierKey("G3", "tops");
        configFacade.values.put(labelKey, "进阶显卡");
        configFacade.values.put(topsKey, "160");
        A2ReplayContext.enterReplay();

        ApiResult<ComputeConfigBatchResponse> rejected = service.updateComputeConfigBatch(
                "idem-e6-batch-invalid",
                new ComputeConfigBatchUpdateRequest(
                        Map.of(labelKey, "专业显卡", topsKey, "0"),
                        "reject partial write", "superadmin"));

        assertThat(rejected.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(configFacade.values)
                .containsEntry(labelKey, "进阶显卡")
                .containsEntry(topsKey, "160");

        ApiResult<ComputeConfigBatchResponse> updated = service.updateComputeConfigBatch(
                "idem-e6-batch-valid",
                new ComputeConfigBatchUpdateRequest(
                        Map.of(labelKey, "专业显卡", topsKey, "180"),
                        "atomic tier update", "superadmin"));

        assertThat(updated.getCode()).isZero();
        assertThat(updated.getData().values()).containsEntry(labelKey, "专业显卡").containsEntry(topsKey, "180");
        assertThat(configFacade.values).containsEntry(labelKey, "专业显卡").containsEntry(topsKey, "180");
        verify(outboxService).publish(eq("E6_COMPUTE_CONFIG"), eq("batch"), eq("compute.config_changed"), any());
    }

    @Test
    void replayE1SkuStatusChangesStatusAndAudits() {
        catalogRepository.sku = sku("stellarbox-test", "NexionBox Test", "on");

        ApiResult<?> result = service.replay(
                new AuditReplayCommand("E", "e1_sku_status", Map.of(
                        "skuId", "stellarbox-test",
                        "status", "off")),
                new AuditReplayContext("superadmin", "e1 replay sku status", "idem-replay-e1-sku-status"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isInstanceOf(DeviceSkuView.class);
        assertThat(((DeviceSkuView) result.getData()).status()).isEqualTo("off");
        assertThat(catalogRepository.sku.status()).isEqualTo("off");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E1_SKU_STATUS_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("fromStatus", "on")
                .containsEntry("toStatus", "off")
                .containsEntry("idempotencyKey", "idem-replay-e1-sku-status");
    }

    @Test
    void replayE4OrderRefundPostsLedgerAndAudits() {
        catalogRepository.order = order("OD-1", "paid");

        ApiResult<?> result = service.replay(
                new AuditReplayCommand("E", "e4_order_refund", Map.of("orderNo", "OD-1")),
                new AuditReplayContext("superadmin", "e4 replay refund", "idem-replay-e4-refund"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isInstanceOf(DeviceOrderView.class);
        assertThat(((DeviceOrderView) result.getData()).state()).isEqualTo("refunded");
        assertThat(catalogRepository.order.state()).isEqualTo("refunded");
        assertThat(refundSettlementFacade.entries).hasSize(1);
        assertThat(refundSettlementFacade.entries.get(0)).containsEntry("orderNo", "OD-1");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("E4_ORDER_REFUNDED");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("idempotencyKey", "idem-replay-e4-refund");
    }

    @Test
    void e4OrderDetailOnlyAdvertisesExecutableRefundChannel() {
        catalogRepository.order = order("OD-1", "paid");

        var result = service.order("OD-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().refundAllowed()).isTrue();
        assertThat(result.getData().refundChannels()).containsExactly("WALLET");
    }

    @Test
    void replayE3ConfigBatchValidatesAndWritesTheWholeCandidateAtomically() {
        deviceRepository.config.putAll(Map.of(
                "tradeinLadderCut1", "25", "tradeinLadderCut2", "50",
                "tradeinLadderCut3", "75", "tradeinLadderCut4", "100"));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("E.tradein.ladder.cut1", "60");
        values.put("E.tradein.ladder.cut2", "70");
        values.put("E.tradein.ladder.cut3", "80");
        values.put("E.tradein.ladder.cut4", "90");
        values.put("E.device.capacity.applyTo.phone", "true");
        values.put("E.tradein.enabled", "false");
        values.put("E.release.earlyAccess.enabled", "true");

        ApiResult<?> result = service.replay(
                new AuditReplayCommand("E", "e3_config_batch", Map.of("values", values)),
                new AuditReplayContext("superadmin", "replace complete E3 ladder", "idem-replay-e3-batch"));

        assertThat(result.getCode()).isZero();
        assertThat(deviceRepository.config)
                .containsEntry("tradeinLadderCut1", "60")
                .containsEntry("tradeinLadderCut2", "70")
                .containsEntry("tradeinLadderCut3", "80")
                .containsEntry("tradeinLadderCut4", "90")
                .containsEntry("capacityApplyToPhone", "true")
                .containsEntry("tradeinEnabled", "false")
                .containsEntry("earlyAccessEnabled", "true");
    }

    @Test
    void replayE3ConfigBatchUsesEffectiveLadderAndAllowsNonAmplifyingBoundaryCreditCombination() {
        deviceRepository.config.putAll(Map.of(
                "tradeinLadderCut1", "25", "tradeinLadderCut2", "50",
                "tradeinLadderCut3", "75", "tradeinLadderCut4", "100",
                "tradeinLadderCredit1", "75", "tradeinLadderCredit2", "60",
                "tradeinLadderCredit3", "45", "tradeinLadderCredit4", "30",
                "tradeinLadderCredit5", "15"));
        when(coverageFacade.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                BigDecimal.valueOf(90), BigDecimal.valueOf(100), true));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("E.tradein.ladder.cut1", "30");
        values.put("E.tradein.ladder.credit1", "60");
        values.put("E.tradein.ladder.credit2", "50");
        values.put("E.tradein.ladder.credit3", "40");
        values.put("E.tradein.ladder.credit4", "25");
        values.put("E.tradein.ladder.credit5", "10");

        ApiResult<?> result = service.replay(
                new AuditReplayCommand("E", "e3_config_batch", Map.of("values", values)),
                new AuditReplayContext("superadmin", "reduce effective ladder", "idem-replay-e3-ladder-safe"));

        assertThat(result.getCode()).isZero();
        assertThat(deviceRepository.config)
                .containsEntry("tradeinLadderCut1", "30")
                .containsEntry("tradeinLadderCredit1", "60")
                .containsEntry("tradeinLadderCredit5", "10");
    }

    @Test
    void replayE3ConfigBatchRejectsInvalidCandidateWithoutPartialWrites() {
        deviceRepository.config.putAll(Map.of(
                "tradeinLadderCut1", "25", "tradeinLadderCut2", "50",
                "tradeinLadderCut3", "75", "tradeinLadderCut4", "100"));

        ApiResult<?> result = service.replay(
                new AuditReplayCommand("E", "e3_config_batch", Map.of("values", Map.of(
                        "E.tradein.ladder.cut1", "60",
                        "E.tradein.ladder.cut2", "55"))),
                new AuditReplayContext("superadmin", "invalid E3 ladder candidate", "idem-replay-e3-invalid"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(deviceRepository.config)
                .containsEntry("tradeinLadderCut1", "25")
                .containsEntry("tradeinLadderCut2", "50");
    }

    @Test
    void replayUnknownOpReturns422WithUnknownReplayOpMarker() {
        ApiResult<?> result = service.replay(
                new AuditReplayCommand("E", "e_unknown", Map.of()),
                new AuditReplayContext("superadmin", "unknown replay", "idem-replay-unknown"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("UNKNOWN_REPLAY_OP:e_unknown");
        verify(auditLogService, never()).record(any());
    }

    /** 构造一个 config 全空的 service:FakePlatformConfigFacade 默认 values 为空 map,
     *  activeValue() 返回 Optional.empty(),触发所有项回落到 ComputeConfigRegistry 默认值。 */
    private OpsDeviceService newServiceWithEmptyConfig() {
        return service();
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

    @Test
    void e2TaskPricingReturnsCanonicalSixClassesAndFiveTeaserTiers() {
        for (String taskClass : List.of("IG", "VG", "LL", "FT", "EM", "SP")) {
            DeviceTaskView row = taskWithClass("TK-" + taskClass, taskClass, 8);
            catalogRepository.tasks.put(row.taskId(), row);
        }
        configFacade.values.put("E.task.queueSaturation", "0.35");

        ApiResult<Map<String, Object>> result = service.e2TaskPricing();

        assertThat(result.getCode()).isZero();
        assertThat((List<?>) result.getData().get("taskClasses")).hasSize(6);
        assertThat((List<?>) result.getData().get("teaser")).hasSize(5);
        assertThat((BigDecimal) result.getData().get("queueSaturation")).isEqualByComparingTo("0.35");
    }

    @Test
    void e2TaskPricingUpdateRequiresEightCharacterReasonAndPublishesGovernedEvent() {
        DeviceTaskView row = taskWithClass("TK-IG", "IG", 12);
        catalogRepository.tasks.put(row.taskId(), row);
        ApiResult<Map<String, Object>> shortReason = service.updateE2TaskPricing(
                "idem-e2-short", new E2TaskPricingUpdateRequest("IG", null, new BigDecimal("0.05"),
                        null, null, null, "short", "forged"));
        assertThat(shortReason.getCode()).isEqualTo(422);
        assertThat(shortReason.getMessage()).isEqualTo("REASON_LENGTH_INVALID");

        authenticateAs("trusted-admin", "device_e2_write");
        ApiResult<Map<String, Object>> updated = service.updateE2TaskPricing(
                "idem-e2-price", new E2TaskPricingUpdateRequest("IG", null, new BigDecimal("0.05"),
                        null, null, null, "raise image pricing", "forged"));

        assertThat(updated.getCode()).isZero();
        assertThat(catalogRepository.tasks.get("TK-IG").maxReward()).isEqualByComparingTo("0.05");
        verify(outboxService).publish(eq("E2_TASK_PRICING"), eq("IG"),
                eq("admin.task_pricing_changed"), any());
        verify(auditLogService).recordRequired(argThat(audit ->
                "admin.task_pricing_changed".equals(audit.getAction())
                        && "forged".equals(audit.getActorUsername()) == false));
    }

    @Test
    void e2QueueSaturationRejectsOutOfRangeAndPersistsValidValue() {
        ApiResult<Map<String, Object>> invalid = service.updateE2TaskPricing(
                "idem-e2-sat-bad", new E2TaskPricingUpdateRequest(null, null, null, null, null,
                        new BigDecimal("1.01"), "calibrate gpu queue", "superadmin"));
        assertThat(invalid.getCode()).isEqualTo(400);

        ApiResult<Map<String, Object>> valid = service.updateE2TaskPricing(
                "idem-e2-sat", new E2TaskPricingUpdateRequest(null, null, null, null, null,
                        new BigDecimal("0.42"), "calibrate gpu queue", "superadmin"));
        assertThat(valid.getCode()).isZero();
        assertThat(configFacade.values.get("E.task.queueSaturation")).isEqualTo("0.42");
    }

    private static DeviceGenerationGateUpsertRequest gateRequest(
            String skuId, String phaseId, boolean eligibility, boolean forceUnlock) {
        return new DeviceGenerationGateUpsertRequest(
                skuId,
                "NexionBox Test",
                3,
                phaseId,
                eligibility,
                0,
                forceUnlock,
                "active",
                "调整代际解锁配置",
                "superadmin");
    }

    private static DeviceSkuUpsertRequest withPriceAndStatus(
            DeviceSkuUpsertRequest source, BigDecimal price, String status) {
        return new DeviceSkuUpsertRequest(
                source.skuId(), source.name(), source.tier(), source.tagline(), source.badge(), source.gpu(), source.vram(),
                source.hashRate(), source.power(), source.datacenter(), price, source.dailyEarn(), source.dailyEarnNex(),
                source.shareYieldMin(), source.shareYieldMax(), source.baseRate(), source.sold(), source.stock(), source.rating(),
                source.reviews(), source.aiImageGenPerMin(), source.aiLlmTokensPerSec(), source.aiVideoMinPerHour(),
                source.aiFineTuneMins(), source.aiUnlocks(), source.features(), source.generation(), source.lifecycle(),
                source.supersededBy(), source.tradeinDiscount(), source.unlockPhase(), source.purchaseGate(), source.imageAssetId(),
                source.imageObjectKey(), source.imagePreviewUrl(), source.tag(), status, source.reason(), source.operator());
    }

    private static DeviceSkuUpsertRequest withOperator(DeviceSkuUpsertRequest source, String operator) {
        return new DeviceSkuUpsertRequest(
                source.skuId(), source.name(), source.tier(), source.tagline(), source.badge(), source.gpu(), source.vram(),
                source.hashRate(), source.power(), source.datacenter(), source.price(), source.dailyEarn(), source.dailyEarnNex(),
                source.shareYieldMin(), source.shareYieldMax(), source.baseRate(), source.sold(), source.stock(), source.rating(),
                source.reviews(), source.aiImageGenPerMin(), source.aiLlmTokensPerSec(), source.aiVideoMinPerHour(),
                source.aiFineTuneMins(), source.aiUnlocks(), source.features(), source.generation(), source.lifecycle(),
                source.supersededBy(), source.tradeinDiscount(), source.unlockPhase(), source.purchaseGate(), source.imageAssetId(),
                source.imageObjectKey(), source.imagePreviewUrl(), source.tag(), source.status(), source.reason(), operator);
    }

    private static void authenticateAs(String username, String... authorities) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "admin-id", "n/a",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        authentication.setDetails(Map.of("subjectType", "ADMIN", "username", username));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "superadmin",
                "n/a",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
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
                "EM",
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
        return sku(skuId, name, status, unlockPhase, aiUnlocks, new BigDecimal("1299"));
    }

    private static DeviceSkuView sku(
            String skuId, String name, String status, String unlockPhase, String aiUnlocks, BigDecimal price) {
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
                price,
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
                "EM",
                "BGE-M3",
                new BigDecimal("0.06"),
                new BigDecimal("0.22"),
                "8GB",
                "派发中",
                null,
                null);
    }

    private static DeviceTaskView taskWithClass(String taskId, String taskClass, int minVram) {
        LocalDateTime now = LocalDateTime.of(2026, 6, 17, 0, 0);
        return new DeviceTaskView(taskId, taskClass + " task", new BigDecimal("0.10"), "/job", "手机+",
                new BigDecimal("0.35"), "active", taskClass, taskClass + " model",
                new BigDecimal("0.01"), new BigDecimal("0.04"), minVram + "GB", "派发中", now, now);
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
        private int lastTradeinCliffMonth;
        private String lastConfigValueType;
        private String lastConfigOperator;
        private long activeDevicesByUser;
        private Long pausedUserId;
        // 删除数据中心跨域引用计数(默认全零,测试按需覆写)
        private long referenceDevices;
        private long referencePendingOrders;
        private long referenceSkus;
        private String detachedDc;
        private boolean opsStateSoftDeleted;

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
            lastTradeinCliffMonth = cliffMonth;
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
            lastConfigOperator = operator;
        }

        @Override
        public long countActiveDevicesByUser(Long userId) {
            return activeDevicesByUser;
        }

        @Override
        public Optional<DeviceOpsView> activateDevice(Long deviceId, LocalDateTime activatedAt) {
            device = device("OFFLINE", 0, null);
            return Optional.of(device);
        }

        @Override
        public Optional<DeviceOpsView> deactivateE5Device(Long deviceId, boolean unbind, LocalDateTime now) {
            device = device(unbind ? "UNBOUND" : "DEACTIVATED", 0, now);
            return Optional.of(device);
        }

        @Override
        public List<Long> lockE5BatchCandidateIds(Long userId, boolean pause, int limit) {
            return device == null ? List.of() : List.of(device.id());
        }

        @Override
        public int pauseDevicesByUser(Long userId, String reason, LocalDateTime now) {
            pausedUserId = userId;
            // MySQL reports two affected rows for one ON DUPLICATE KEY UPDATE row.
            return 2;
        }

        @Override
        public int resumeDevicesByUser(Long userId, LocalDateTime now) {
            pausedUserId = null;
            return 1;
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
        public DatacenterReferenceCount countDatacenterReferences(String dcLocation) {
            return new DatacenterReferenceCount(referenceDevices, referencePendingOrders, referenceSkus);
        }

        @Override
        public void syncDatacenterReferencesOnDelete(String dcLocation, String operator, LocalDateTime now) {
            detachedDc = dcLocation;
            opsStateSoftDeleted = true;
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
            sku = sku(skuId, request.name(), request.status(), request.unlockPhase(), request.aiUnlocks(), request.price());
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
            sku = sku(skuId, request.name(), request.status(), request.unlockPhase(), request.aiUnlocks(), request.price());
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
            sku = sku(current.skuId(), current.name(), status, current.unlockPhase(), current.aiUnlocks(), current.price());
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
        public Optional<DeviceOrderFacts> findOrderFacts(String orderNo) {
            if (order == null || !order.orderNo().equals(orderNo)) {
                return Optional.empty();
            }
            return Optional.of(new DeviceOrderFacts(orderNo, 1L, 1, "SINGLE",
                    order.amount(), BigDecimal.ZERO, order.amount(), null, "USDT_WALLET",
                    "PAID", "PAID", "WAITING_PROVISIONING", 1L, order.skuId(), order.skuName(),
                    null, null, order.dcLocation(), null, null, null, null));
        }

        @Override
        public List<DeviceOrderHistoryView> listOrderHistory(String orderNo) {
            return List.of();
        }

        @Override
        public List<DeviceOrderFundingView> listOrderFunding(String orderNo) {
            return List.of();
        }

        @Override
        public Optional<DeviceOrderView> updateOrderState(
                String orderNo, String expectedState, String state, LocalDateTime now) {
            if (order == null || !order.orderNo().equals(orderNo)) {
                return Optional.empty();
            }
            if (!expectedState.equals(order.state())) {
                return Optional.empty();
            }
            order = order(order.orderNo(), state);
            return Optional.of(order);
        }

        @Override
        public void recordOrderHistory(String orderNo, String fromState, String toState, String reason,
                                       String operator, String idempotencyKey, LocalDateTime now) {
        }

        @Override
        public void rollbackOrderAssets(String orderNo, LocalDateTime now) {
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

    private static final class FakeE4OrderRefundSettlementFacade implements E4OrderRefundSettlementFacade {
        private final List<Map<String, Object>> entries = new ArrayList<>();

        @Override
        public Settlement settle(String orderNo, Long userId, BigDecimal amount, String refundChannel,
                                 String reason, String operator, String idempotencyKey) {
            String channel = refundChannel == null ? "WALLET" : refundChannel;
            entries.add(Map.of("orderNo", orderNo, "userId", userId, "amount", amount, "channel", channel));
            return new Settlement(channel, "E4-REFUND-" + orderNo, "E4-BILL-" + orderNo,
                    BigDecimal.ZERO, amount, amount, BigDecimal.ZERO);
        }
    }
}
