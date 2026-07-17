package ffdd.opsconsole.emergency.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.facade.ContentNotificationDispatchFacade;
import ffdd.opsconsole.content.facade.NotificationEmergencyDispatchResult;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.emergency.dto.GeoCountryStatusRequest;
import ffdd.opsconsole.emergency.dto.GeoCountryListRequest;
import ffdd.opsconsole.emergency.dto.GeoEdgeJudgeRequest;
import ffdd.opsconsole.emergency.dto.GeoEmergencyBlockRequest;
import ffdd.opsconsole.emergency.dto.GeoEndpointCountriesRequest;
import ffdd.opsconsole.emergency.dto.KillSwitchToggleRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookCreateRequest;
import ffdd.opsconsole.emergency.dto.SopPlaybookRunRequest;
import ffdd.opsconsole.emergency.dto.SopStepConfirmationRequest;
import ffdd.opsconsole.emergency.dto.TamperAlertConfigRequest;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.outbox.EventOutboxMessage;
import ffdd.opsconsole.shared.outbox.EventConsumerDelivery;
import ffdd.opsconsole.shared.outbox.EventConsumerDeliveryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsEmergencyControlServiceTest {
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final FakeNotificationDispatchFacade notificationDispatchFacade = new FakeNotificationDispatchFacade();
    private final FakeEmergencyControlRepository emergencyRepository = new FakeEmergencyControlRepository();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final ffdd.opsconsole.platform.mapper.AuditObjectLockMapper lockMapper =
            mock(ffdd.opsconsole.platform.mapper.AuditObjectLockMapper.class);
    private final OpsKillSwitchService killSwitchService = mock(OpsKillSwitchService.class);
    private final EventOutboxService outboxService = mock(EventOutboxService.class);
    private final EventConsumerDeliveryService consumerDeliveryService = mock(EventConsumerDeliveryService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final GeoEdgeHealthMonitor geoEdgeHealthMonitor = new GeoEdgeHealthMonitor(java.time.Clock.systemUTC());
    private final org.springframework.transaction.PlatformTransactionManager transactionManager =
            mock(org.springframework.transaction.PlatformTransactionManager.class);
    private final OpsEmergencyControlService service = new OpsEmergencyControlService(
            configFacade,
            notificationDispatchFacade,
            auditLogService,
            new ObjectMapper(),
            ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
            emergencyRepository,
            lockMapper,
            killSwitchService,
            outboxService,
            consumerDeliveryService,
            idempotencyService,
            geoEdgeHealthMonitor,
            transactionManager);

    @org.junit.jupiter.api.BeforeEach
    void stubLocksNoActive() {
        var authentication = new org.springframework.security.authentication.TestingAuthenticationToken(
                "superadmin",
                "",
                "emergency_j4_write",
                "emergency_j4_playbook_execute",
                "emergency_j1_gate_kill",
                "emergency_j1_gate_resume",
                "content_i3_write");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        doReturn("event-j2-test").when(outboxService).publish(anyString(), anyString(), anyString(), any());
        when(idempotencyService.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new org.springframework.transaction.support.SimpleTransactionStatus());
        org.mockito.Mockito.when(lockMapper.countActiveByTarget(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(0);
        org.mockito.Mockito.when(killSwitchService.restoreFromLinkedDomain(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(ApiResult.ok(Map.of()));
        org.mockito.Mockito.when(killSwitchService.changeFromLinkedDomain(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    String gateKey = invocation.getArgument(0);
                    boolean enabled = invocation.getArgument(1);
                    emergencyRepository.settings.put(
                            "killswitch." + gateKey, enabled ? "enabled" : "disabled");
                    return ApiResult.ok(Map.of());
                });
        org.mockito.Mockito.when(killSwitchService.changeFromJ4Execution(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    String gateKey = invocation.getArgument(0);
                    String executionId = invocation.getArgument(3);
                    emergencyRepository.settings.put("killswitch." + gateKey, "disabled");
                    return ApiResult.ok(Map.of("updated", Map.of(
                            "before", true,
                            "after", false,
                            "ownershipToken", "owner:" + executionId)));
                });
        org.mockito.Mockito.when(killSwitchService.restoreFromJ4Execution(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(ApiResult.ok(Map.of()));
    }

    @Test
    void geoCountryChangePersistsConfigAndAudits() {
        var result = service.updateGeoCountry(
                "VE",
                "idem-j2",
                new GeoCountryStatusRequest("blocked", "监管点名", "regulatory request", "risk-lead", "allowed"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.geoCountries)
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("cc", "VE")
                        .containsEntry("status", "blocked")
                        .containsEntry("reason", "regulatory request"));
        assertThat(configFacade.values).isEmpty();

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("J2_GEO_COUNTRY_STATUS_CHANGED");
        verify(outboxService).publish(eq("KILL_SWITCH"), eq("geo-block"), eq("ADMIN_KILLSWITCH_TOGGLED"), any());
    }

    @Test
    void geoCountryRetryReplaysBeforeCheckingTheNowChangedState() {
        emergencyRepository.upsertGeoCountryPolicy("AQ", "南极洲", "blocked", "already applied", "risk-lead");
        ApiResult<Map<String, Object>> replayed = ApiResult.ok(Map.of("replayed", true));
        doReturn(replayed).when(idempotencyService).execute(
                eq("J2_COUNTRY:AQ"), eq("idem-j2-country-retry"), anyString(), eq(ApiResult.class), any());

        var result = service.updateGeoCountry(
                "AQ",
                "idem-j2-country-retry",
                new GeoCountryStatusRequest("blocked", "安全事件", "retry the same country command", "risk-lead", "allowed"));

        assertThat(result).isSameAs(replayed);
    }

    @Test
    void geoCountryAuditsAnIdempotencyPayloadMismatchWithoutChangingBusinessData() {
        doThrow(new ffdd.opsconsole.shared.exception.BizException(
                OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH"))
                .when(idempotencyService).execute(
                        eq("J2_COUNTRY:AQ"), eq("idem-j2-country-mismatch"), anyString(), eq(ApiResult.class), any());

        var result = service.updateGeoCountry(
                "AQ",
                "idem-j2-country-mismatch",
                new GeoCountryStatusRequest("blocked", "安全事件", "mismatched retry payload", "risk-lead", "allowed"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        assertThat(emergencyRepository.geoCountries).isEmpty();
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "J2_GEO_COMMAND_REJECTED".equals(request.getAction())
                        && String.valueOf(request.getDetail()).contains("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH")
                        && String.valueOf(request.getDetail()).contains("businessDataChanged=false")));
    }

    @Test
    void tamperThresholdRejectsOutOfRangeValue() {
        var result = service.updateTamperAlertConfig(
                "idem-j3",
                new TamperAlertConfigRequest(101, true, "too sensitive", "risk-lead"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
    }

    @Test
    void tamperPreconditionRejectionsAreAuditedBeforeAnyBusinessWrite() {
        var missingKey = service.updateTamperAlertConfig(
                null,
                new TamperAlertConfigRequest(12, true, 10, true,
                        "change monitoring threshold", "risk-lead"));
        var invalidReason = service.createTamperReport(
                "idem-j3-invalid-reason",
                new ffdd.opsconsole.emergency.dto.TamperReportRequest("24h", "short", "risk-lead"));

        assertThat(missingKey.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(invalidReason.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService, times(2)).recordRequired(audit.capture());
        assertThat(audit.getAllValues())
                .extracting(AuditLogWriteRequest::getResult)
                .containsOnly("REJECTED");
        assertThat(audit.getAllValues())
                .extracting(row -> String.valueOf(((Map<?, ?>) row.getDetail()).get("rejectionCode")))
                .containsExactlyInAnyOrder("IDEMPOTENCY_KEY_REQUIRED", "REASON_LENGTH_INVALID");
        assertThat(emergencyRepository.tamperReports).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void tamperDefaultsFeedK4OnAndRejectsUnknownWindows() {
        var overview = service.tamperOverview("24h", 1, 5);
        var invalid = service.tamperOverview("90d", 1, 5);

        assertThat(overview.getCode()).isZero();
        assertThat((Map<String, Object>) overview.getData().get("alertConfig"))
                .containsEntry("threshold", 10)
                .containsEntry("feedK4", true);
        assertThat((Map<String, Object>) overview.getData().get("coverage"))
                .containsEntry("status", "complete")
                .containsEntry("registeredCount", 11)
                .containsEntry("activeCount", 11);
        assertThat(overview.getData()).containsEntry("window", "24h");
        assertThat(invalid.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(invalid.getMessage()).isEqualTo("TAMPER_WINDOW_INVALID");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tamperUsesTheSameSelectedWindowAndConfiguredThresholdForEverySelectedDataset() {
        var result = service.tamperOverview("7d", 1, 5);

        assertThat(emergencyRepository.tamperPathRanges).hasSize(2);
        assertThat(emergencyRepository.tamperAccountRanges).hasSize(1);
        var selectedPathRange = emergencyRepository.tamperPathRanges.get(0);
        var selectedAccountRange = emergencyRepository.tamperAccountRanges.get(0);
        assertThat(selectedPathRange.get(0)).isNotNull();
        assertThat(selectedPathRange.get(1)).isNotNull();
        assertThat(java.time.Duration.between(
                selectedPathRange.get(0),
                selectedPathRange.get(1))).isEqualTo(java.time.Duration.ofDays(7));
        assertThat(selectedAccountRange).containsExactlyElementsOf(selectedPathRange);
        assertThat(emergencyRepository.tamperThresholds).containsExactly(70);
        assertThat((Map<String, Object>) result.getData().get("alertConfig"))
                .containsEntry("threshold", 10)
                .containsEntry("effectiveThreshold", 70)
                .containsEntry("sevenDayAlertAccounts", 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tamperThresholdPreviewUsesOneRealSevenDayFrequencyDistribution() {
        emergencyRepository.tamperFrequencyDistribution.addAll(List.of(
                Map.of("eventCount", 7, "accountCount", 2),
                Map.of("eventCount", 69, "accountCount", 1),
                Map.of("eventCount", 70, "accountCount", 3)));

        var result = service.tamperOverview("24h", 1, 5);

        Map<String, Object> alert = (Map<String, Object>) result.getData().get("alertConfig");
        Map<String, Object> preview = (Map<String, Object>) alert.get("sevenDayPreviewByThreshold");
        assertThat(preview)
                .containsEntry("1", 6)
                .containsEntry("9", 4)
                .containsEntry("10", 3)
                .containsEntry("11", 0);
        assertThat(emergencyRepository.tamperFrequencyDistributionReads).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tamperOverviewKeepsDeltaUnknownWhenThePreviousWindowHasNoEvents() {
        emergencyRepository.tamperPathResponses.add(List.of(Map.of(
                "id", "free_trial_state",
                "name", "试用状态篡改",
                "count", 12,
                "accounts", 1)));
        emergencyRepository.tamperPathResponses.add(List.of());

        var result = service.tamperOverview("24h", 1, 5);

        assertThat(result.getCode()).isZero();
        assertThat((Map<String, Object>) result.getData().get("stats"))
                .containsEntry("totalBlocked", 12)
                .containsEntry("previousWindowBlocked", 0)
                .containsEntry("deltaPrevPct", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tamperConfigUsesAuthenticatedActorOptimisticSnapshotRequiredAuditAndIdempotency() {
        when(idempotencyService.execute(eq("J3_ALERT_CONFIG"), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> {
                    assertThat(emergencyRepository.tamperConfigLockCount)
                            .as("J3 must lock its authoritative config before creating the idempotency row")
                            .isEqualTo(1);
                    return ((Supplier<?>) invocation.getArgument(4)).get();
                });
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("real-admin", "n/a", List.of()));
        try {
            var result = service.updateTamperAlertConfig(
                    "idem-j3-closed-loop",
                    new TamperAlertConfigRequest(12, false, 10, true,
                            "change monitoring sensitivity", "spoofed-admin"));

            assertThat(result.getCode()).isZero();
            verify(idempotencyService).execute(
                    eq("J3_ALERT_CONFIG"), eq("idem-j3-closed-loop"), anyString(), eq(ApiResult.class), any());
            ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
            verify(auditLogService, times(2)).recordRequired(captor.capture());
            AuditLogWriteRequest configAudit = captor.getAllValues().stream()
                    .filter(row -> "J3_TAMPER_ALERT_CONFIG_CHANGED".equals(row.getAction()))
                    .findFirst()
                    .orElseThrow();
            assertThat(configAudit.getActorUsername()).isEqualTo("admin:real-admin");
            assertThat(configAudit.getDetail())
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsKeys("before", "after", "reason", "idempotencyKey", "adminAlertEventId");
            verify(outboxService).publish(eq("TAMPER_ALERT_CONFIG"), eq("default"),
                    eq("ADMIN_J3_TAMPER_CONFIG_CHANGED"), any());
            assertThat(result.getData()).containsEntry("adminAlertEventId", "event-j2-test");
            assertThat(emergencyRepository.tamperConfigLockCount).isEqualTo(1);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void tamperConfigRejectsAStaleSnapshotWithoutWritingSettings() {
        var result = service.updateTamperAlertConfig(
                "idem-j3-stale",
                new TamperAlertConfigRequest(12, false, 9, true,
                        "stale monitoring snapshot", "risk-lead"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TAMPER_ALERT_CONFIG_CONFLICT");
        assertThat(emergencyRepository.settings).isEmpty();
    }

    @Test
    void tamperConfigRequiresTheDisplayedSnapshotBeforeWritingSettings() {
        var result = service.updateTamperAlertConfig(
                "idem-j3-missing-snapshot",
                new TamperAlertConfigRequest(12, false, "change monitoring sensitivity", "risk-lead"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("TAMPER_ALERT_CONFIG_EXPECTED_STATE_REQUIRED");
        assertThat(emergencyRepository.settings).isEmpty();
    }

    @Test
    void tamperConfigRejectsAnUnchangedStateWithoutWritingFalseSuccessAudit() {
        var result = service.updateTamperAlertConfig(
                "idem-j3-unchanged",
                new TamperAlertConfigRequest(10, true, 10, true,
                        "keep current monitoring state", "risk-lead"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("TAMPER_ALERT_CONFIG_UNCHANGED");
        assertThat(emergencyRepository.settings).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void geoEdgeSourceWritesEmergencySettingTableNotConfigItem() {
        for (int i = 0; i < 20; i++) {
            geoEdgeHealthMonitor.record("cloudflare", true, 1_000_000L);
            geoEdgeHealthMonitor.record("nexion-gateway", true, 1_000_000L);
        }
        var result = service.updateGeoEdgeJudge(
                "idem-j2-edge",
                new GeoEdgeJudgeRequest("cloudflare", "switch edge source", "risk-lead", "nexion-gateway"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.settings)
                .containsEntry("emergency.geo.edgeJudgeSource", "cloudflare")
                .containsEntry("emergency.geo.edgeJudgeFallbackSource", "nexion-gateway");
        assertThat(configFacade.values).isEmpty();
        Map<String, Object> edge = (Map<String, Object>) result.getData().get("edge");
        assertThat(edge).containsEntry("source", "cloudflare");
    }

    @Test
    void geoEdgeSwitchFromAnUnregisteredSourceDoesNotCreateAFakeFallback() {
        emergencyRepository.settings.put("emergency.geo.edgeJudgeSource", "retired-edge-provider");
        for (int i = 0; i < 20; i++) {
            geoEdgeHealthMonitor.record("cloudflare", true, 1_000_000L);
        }

        var result = service.updateGeoEdgeJudge(
                "idem-j2-edge-invalid-current",
                new GeoEdgeJudgeRequest(
                        "cloudflare", "recover from retired edge source", "superadmin", "retired-edge-provider"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.settings)
                .containsEntry("emergency.geo.edgeJudgeSource", "cloudflare")
                .containsEntry("emergency.geo.edgeJudgeFallbackSource", "")
                .containsEntry("emergency.geo.edgeJudgeFallbackUntilEpochMs", "0");
    }

    @Test
    void geoEdgeSwitchFromAnUnhealthyRegisteredSourceDoesNotCreateAFakeFallback() {
        for (int i = 0; i < 20; i++) {
            geoEdgeHealthMonitor.record("cloudflare", true, 1_000_000L);
        }

        var result = service.updateGeoEdgeJudge(
                "idem-j2-edge-unhealthy-current",
                new GeoEdgeJudgeRequest(
                        "cloudflare", "recover from unhealthy edge source", "superadmin", "nexion-gateway"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.settings)
                .containsEntry("emergency.geo.edgeJudgeSource", "cloudflare")
                .containsEntry("emergency.geo.edgeJudgeFallbackSource", "")
                .containsEntry("emergency.geo.edgeJudgeFallbackUntilEpochMs", "0");
    }

    @Test
    void geoEdgeRetryReplaysBeforeCheckingTheNowChangedSource() {
        emergencyRepository.settings.put("emergency.geo.edgeJudgeSource", "cloudflare");
        ApiResult<Map<String, Object>> replayed = ApiResult.ok(Map.of("replayed", true));
        doReturn(replayed).when(idempotencyService).execute(
                eq("J2_EDGE_SOURCE"), eq("idem-j2-edge-retry"), anyString(), eq(ApiResult.class), any());

        var result = service.updateGeoEdgeJudge(
                "idem-j2-edge-retry",
                new GeoEdgeJudgeRequest("cloudflare", "retry the same edge source", "superadmin", "cloudflare"));

        assertThat(result).isSameAs(replayed);
    }

    @Test
    @SuppressWarnings("unchecked")
    void geoBlockAlertsExposeOnlyPublishedSuperadminEvents() throws Exception {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId("event-alert-j2");
        message.setEventType("ADMIN_KILLSWITCH_TOGGLED");
        message.setAggregateType("KILL_SWITCH");
        message.setAggregateId("geo-block");
        message.setStatus("PUBLISHED");
        message.setCreatedAt(LocalDateTime.now());
        message.setPayload(new ObjectMapper().writeValueAsString(Map.of(
                "audienceRole", "SUPER_ADMIN",
                "scope", "country-list",
                "target", "blocked",
                "operator", "superadmin",
                "reason", "alert feed contract verification",
                "occurredAt", LocalDateTime.now().toString())));
        EventConsumerDelivery delivery = new EventConsumerDelivery();
        delivery.setEventId("event-alert-j2");
        delivery.setConsumerGroup(GeoPolicyAdminAlertConsumer.CONSUMER_GROUP);
        delivery.setStatus("SUCCESS");
        when(consumerDeliveryService.listByAggregate("KILL_SWITCH", "geo-block", 100))
                .thenReturn(List.of(delivery));
        when(outboxService.listByAggregate("KILL_SWITCH", "geo-block", 20)).thenReturn(List.of(message));

        var result = service.geoBlockAlerts();

        assertThat(result.getCode()).isZero();
        List<Map<String, Object>> alerts = (List<Map<String, Object>>) result.getData().get("alerts");
        assertThat(alerts).singleElement().satisfies(alert -> assertThat(alert)
                .containsEntry("id", "J2-event-alert-j2")
                .containsEntry("domain", "J2")
                .containsEntry("level", "mid"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void geoBlockAlertsHidePublishedRowsWithoutSuccessfulConsumerReceipt() throws Exception {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId("event-without-receipt");
        message.setEventType("ADMIN_KILLSWITCH_TOGGLED");
        message.setAggregateType("KILL_SWITCH");
        message.setAggregateId("geo-block");
        message.setStatus("PUBLISHED");
        message.setCreatedAt(LocalDateTime.now());
        message.setPayload(new ObjectMapper().writeValueAsString(Map.of(
                "audienceRole", "SUPER_ADMIN", "scope", "endpoint", "target", "janus")));
        when(consumerDeliveryService.listByAggregate("KILL_SWITCH", "geo-block", 100))
                .thenReturn(List.of());
        when(outboxService.listByAggregate("KILL_SWITCH", "geo-block", 20)).thenReturn(List.of(message));

        List<Map<String, Object>> alerts =
                (List<Map<String, Object>>) service.geoBlockAlerts().getData().get("alerts");

        assertThat(alerts).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void tamperConfigAlertsExposeOnlyDeliveredSuperadminEvents() throws Exception {
        EventOutboxMessage message = new EventOutboxMessage();
        message.setEventId("event-alert-j3");
        message.setEventType("ADMIN_J3_TAMPER_CONFIG_CHANGED");
        message.setAggregateType("TAMPER_ALERT_CONFIG");
        message.setAggregateId("default");
        message.setStatus("PUBLISHED");
        message.setCreatedAt(LocalDateTime.now());
        message.setPayload(new ObjectMapper().writeValueAsString(Map.of(
                "audienceRole", "SUPER_ADMIN",
                "before", Map.of("threshold", 10, "feedK4", true),
                "after", Map.of("threshold", 12, "feedK4", false),
                "operator", "superadmin",
                "reason", "verify persistent J3 alert feed",
                "occurredAt", LocalDateTime.now().toString())));
        EventConsumerDelivery delivery = new EventConsumerDelivery();
        delivery.setEventId("event-alert-j3");
        delivery.setConsumerGroup(TamperConfigAdminAlertConsumer.CONSUMER_GROUP);
        delivery.setStatus("SUCCESS");
        when(consumerDeliveryService.listByAggregate("TAMPER_ALERT_CONFIG", "default", 100))
                .thenReturn(List.of(delivery));
        when(outboxService.listByAggregate("TAMPER_ALERT_CONFIG", "default", 20)).thenReturn(List.of(message));

        var result = service.tamperConfigAlerts();

        assertThat(result.getCode()).isZero();
        List<Map<String, Object>> alerts = (List<Map<String, Object>>) result.getData().get("alerts");
        assertThat(alerts).singleElement().satisfies(alert -> assertThat(alert)
                .containsEntry("id", "J3-event-alert-j3")
                .containsEntry("domain", "J3")
                .containsEntry("level", "mid")
                .hasEntrySatisfying("hint", hint -> assertThat(String.valueOf(hint))
                        .contains("K4 开启→关闭")
                        .doesNotContain("true", "false")));
    }

    @Test
    void geoCountryRejectsUnknownIsoCodeBeforeWriting() {
        var result = service.updateGeoCountry(
                "ZZ",
                "idem-j2-invalid-country",
                new GeoCountryStatusRequest("blocked", "监管点名", "invalid country code", "risk-lead", "allowed"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("COUNTRY_CODE_INVALID");
        assertThat(emergencyRepository.geoCountries).isEmpty();
        verify(outboxService, never()).publish(anyString(), anyString(), anyString(), any());
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "J2_GEO_COMMAND_REJECTED".equals(request.getAction())
                        && "REJECTED".equals(request.getResult())
                        && String.valueOf(request.getDetail()).contains("COUNTRY_CODE_INVALID")
                        && String.valueOf(request.getDetail()).contains("businessDataChanged=false")));
    }

    @Test
    void geoCountryRequiresExpectedStatusOutsideA2Replay() {
        var result = service.updateGeoCountry(
                "JP",
                "idem-j2-expected-required",
                new GeoCountryStatusRequest("blocked", "监管点名", "missing expected state", "risk-lead"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("GEO_COUNTRY_EXPECTED_STATE_REQUIRED");
        assertThat(emergencyRepository.geoCountries).isEmpty();
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "J2_GEO_COMMAND_REJECTED".equals(request.getAction())
                        && String.valueOf(request.getDetail()).contains("GEO_COUNTRY_EXPECTED_STATE_REQUIRED")));
    }

    @Test
    void geoCountryRejectsAStaleExpectedStateWithoutOverwritingNewerPolicy() {
        emergencyRepository.upsertGeoCountryPolicy("JP", "日本", "blocked", "newer restriction", "risk-lead");

        var result = service.updateGeoCountry(
                "JP",
                "idem-j2-country-stale",
                new GeoCountryStatusRequest("limited", "监管点名", "stale single-country edit", "risk-lead", "allowed"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("GEO_COUNTRY_STATE_CONFLICT");
        assertThat(emergencyRepository.geoCountries)
                .anySatisfy(row -> assertThat(row).containsEntry("cc", "JP").containsEntry("status", "blocked"));
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "J2_GEO_COMMAND_REJECTED".equals(request.getAction())
                        && String.valueOf(request.getDetail()).contains("GEO_COUNTRY_STATE_CONFLICT")));
    }

    @Test
    void geoCountryTighteningRequiresStructuredTriggerBasis() {
        var result = service.updateGeoCountry(
                "JP",
                "idem-j2-trigger-required",
                new GeoCountryStatusRequest("limited", null, "tighten country access", "risk-lead", "allowed"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("TRIGGER_BASIS_REQUIRED");
        assertThat(emergencyRepository.geoCountries).isEmpty();
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "J2_GEO_COMMAND_REJECTED".equals(request.getAction())
                        && String.valueOf(request.getDetail()).contains("TRIGGER_BASIS_REQUIRED")));
    }

    @Test
    void geoCountryListReplaceUsesExpectedSnapshotAndWritesTheWholeDiff() {
        emergencyRepository.upsertGeoCountryPolicy("US", "美国", "blocked", "existing", "risk-lead");

        var result = service.replaceGeoCountryList(
                "blocked",
                "idem-j2-list",
                new GeoCountryListRequest(
                        "blocked", List.of("AQ", "JP"), List.of("US"), "安全事件", "replace complete list", "risk-lead"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.geoCountries)
                .anySatisfy(row -> assertThat(row).containsEntry("cc", "AQ").containsEntry("status", "blocked"))
                .anySatisfy(row -> assertThat(row).containsEntry("cc", "JP").containsEntry("status", "blocked"))
                .anySatisfy(row -> assertThat(row).containsEntry("cc", "US").containsEntry("status", "allowed"));
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "J2_GEO_COUNTRY_LIST_CHANGED".equals(request.getAction())
                        && String.valueOf(request.getDetail()).contains("AQ")
                        && String.valueOf(request.getDetail()).contains("US")));
    }

    @Test
    void geoCountryListRejectsAStaleSnapshotWithoutOverwritingANewerEmergencyBlock() {
        emergencyRepository.upsertGeoCountryPolicy("US", "美国", "blocked", "newer emergency", "risk-lead");

        var result = service.replaceGeoCountryList(
                "blocked",
                "idem-j2-list-stale",
                new GeoCountryListRequest(
                        "blocked", List.of("AQ"), List.of(), "安全事件", "stale browser tab", "risk-lead"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("GEO_COUNTRY_LIST_CONFLICT");
        assertThat(emergencyRepository.geoCountries)
                .anySatisfy(row -> assertThat(row).containsEntry("cc", "US").containsEntry("status", "blocked"))
                .noneSatisfy(row -> assertThat(row).containsEntry("cc", "AQ"));
    }

    @Test
    void blockedCountryCannotBeDowngradedDirectlyToLimited() {
        emergencyRepository.upsertGeoCountryPolicy("US", "美国", "blocked", "existing", "risk-lead");

        var result = service.updateGeoCountry(
                "US",
                "idem-j2-downgrade",
                new GeoCountryStatusRequest("limited", "监管点名", "unsafe downgrade", "risk-lead", "blocked"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("GEO_BLOCKED_CANNOT_BECOME_LIMITED");
        assertThat(emergencyRepository.geoCountries)
                .anySatisfy(row -> assertThat(row).containsEntry("cc", "US").containsEntry("status", "blocked"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void geoEndpointDerivedModeClearsExplicitCountriesAndReturnsDerivedState() {
        emergencyRepository.geoEndpointCatalogs.add(new LinkedHashMap<>(Map.of(
                "endpointKey", "janus",
                "endpointPath", "/api/app/janus",
                "label", "Janus 会话",
                "biz", "设备",
                "domain", "K6",
                "status", "ACTIVE")));
        emergencyRepository.geoEndpoints.add(new LinkedHashMap<>(Map.of(
                "endpointKey", "janus",
                "countryCode", "KP",
                "source", "explicit")));

        var result = service.updateGeoEndpoint(
                "janus",
                "idem-j2-endpoint-derived",
                new GeoEndpointCountriesRequest(
                        List.of("KP"), "derived", "restore global inheritance", "risk-lead", "explicit", List.of("KP")));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.geoEndpoints).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> updated = (Map<String, Object>) result.getData().get("updated");
        assertThat(updated)
                .containsEntry("source", "derived")
                .containsEntry("configurable", true);
        ArgumentCaptor<AuditLogWriteRequest> auditCaptor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getResult()).isEqualTo("SUCCESS");
        Map<String, Object> auditDetail = (Map<String, Object>) auditCaptor.getValue().getDetail();
        assertThat(auditDetail.get("before")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("mode", "countries")
                .containsEntry("mode", "explicit");
        assertThat(auditDetail.get("after")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("mode", "countries")
                .containsEntry("mode", "derived");
    }

    @Test
    void geoEndpointRejectsStaleExpectedState() {
        emergencyRepository.geoEndpointCatalogs.add(new LinkedHashMap<>(Map.of(
                "endpointKey", "janus", "endpointPath", "/api/app/janus", "label", "Janus 会话",
                "biz", "设备", "domain", "K6", "status", "ACTIVE")));
        emergencyRepository.geoEndpoints.add(new LinkedHashMap<>(Map.of(
                "endpointKey", "janus", "countryCode", "US", "source", "explicit")));

        var result = service.updateGeoEndpoint(
                "janus", "idem-j2-endpoint-stale",
                new GeoEndpointCountriesRequest(
                        List.of("JP"), "explicit", "stale browser endpoint edit", "risk-lead",
                        "derived", List.of()));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("GEO_ENDPOINT_STATE_CONFLICT");
        assertThat(emergencyRepository.geoEndpoints)
                .anySatisfy(row -> assertThat(row).containsEntry("countryCode", "US"))
                .noneSatisfy(row -> assertThat(row).containsEntry("countryCode", "JP"));
    }

    @Test
    void geoEndpointRejectsUnchangedStateWithRejectedAuditAndWithoutNotification() {
        emergencyRepository.geoEndpointCatalogs.add(new LinkedHashMap<>(Map.of(
                "endpointKey", "janus", "endpointPath", "/api/app/janus", "label", "Janus 会话",
                "biz", "设备", "domain", "K6", "status", "ACTIVE")));
        emergencyRepository.geoEndpoints.add(new LinkedHashMap<>(Map.of(
                "endpointKey", "janus", "countryCode", "US", "source", "explicit")));

        var result = service.updateGeoEndpoint(
                "janus", "idem-j2-endpoint-unchanged",
                new GeoEndpointCountriesRequest(
                        List.of("US"), "explicit", "duplicate endpoint rule", "risk-lead",
                        "explicit", List.of("US")));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("GEO_ENDPOINT_STATE_UNCHANGED");
        verify(outboxService, never()).publish(anyString(), anyString(), anyString(), any());
        ArgumentCaptor<AuditLogWriteRequest> auditCaptor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getResult()).isEqualTo("REJECTED");
        assertThat(auditCaptor.getValue().getDetail()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("rejectionCode", "GEO_ENDPOINT_STATE_UNCHANGED")
                .containsEntry("businessDataChanged", false)
                .containsEntry("idempotencyKey", "idem-j2-endpoint-unchanged");
    }

    @Test
    void geoEdgeRejectsStaleExpectedSourceBeforeSwitching() {
        emergencyRepository.settings.put("emergency.geo.edgeJudgeSource", "cloudflare");
        for (int i = 0; i < 20; i++) {
            geoEdgeHealthMonitor.record("nexion-gateway", true, 1_000_000L);
        }

        var result = service.updateGeoEdgeJudge(
                "idem-j2-edge-stale",
                new GeoEdgeJudgeRequest(
                        "nexion-gateway", "stale browser edge source edit", "superadmin", "nexion-gateway"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("EDGE_JUDGE_SOURCE_CONFLICT");
        assertThat(emergencyRepository.settings)
                .containsEntry("emergency.geo.edgeJudgeSource", "cloudflare")
                .doesNotContainKey("emergency.geo.edgeJudgeFallbackSource");
        ArgumentCaptor<AuditLogWriteRequest> auditCaptor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getResult()).isEqualTo("REJECTED");
        assertThat(auditCaptor.getValue().getDetail()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("rejectionCode", "EDGE_JUDGE_SOURCE_CONFLICT")
                .containsEntry("businessDataChanged", false);
    }

    @Test
    void emergencyGeoBlockValidatesEntireBatchBeforeAnyCountryWrite() {
        emergencyRepository.upsertGeoCountryPolicy("US", "美国", "blocked", "existing restriction", "risk-lead");

        var result = service.emergencyGeoBlock(
                "idem-j2-batch-conflict",
                new GeoEmergencyBlockRequest(
                        List.of("JP", "US"), "安全事件", "batch must remain atomic", "risk-lead"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("GEO_EMERGENCY_BLOCK_CONFLICT");
        assertThat(emergencyRepository.geoCountries)
                .noneSatisfy(row -> assertThat(row).containsEntry("cc", "JP"));
        verify(outboxService, never()).publish(anyString(), anyString(), anyString(), any());
        ArgumentCaptor<AuditLogWriteRequest> auditCaptor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getResult()).isEqualTo("REJECTED");
        assertThat(auditCaptor.getValue().getDetail()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("rejectionCode", "GEO_EMERGENCY_BLOCK_CONFLICT")
                .containsEntry("businessDataChanged", false)
                .containsEntry("idempotencyKey", "idem-j2-batch-conflict");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tamperAlertConfigWritesEmergencySettingTableNotConfigItem() {
        var result = service.updateTamperAlertConfig(
                "idem-j3-alert",
                new TamperAlertConfigRequest(12, false, 10, true, "raise alert threshold", "risk-lead"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.settings)
                .containsEntry("emergency.tamper.alert.threshold", "12")
                .containsEntry("emergency.tamper.alert.feedK4", "false");
        assertThat(configFacade.values).isEmpty();
        Map<String, Object> alert = (Map<String, Object>) result.getData().get("alertConfig");
        assertThat(alert)
                .containsEntry("threshold", 12)
                .containsEntry("feedK4", false);
    }

    @Test
    void tamperReportPersistsBusinessReportFromTamperTables() {
        emergencyRepository.tamperPaths.add(new LinkedHashMap<>(Map.of(
                "id", "withdraw-path",
                "name", "=HYPERLINK(\"https://example.invalid\",\"x\")",
                "count", 3,
                "accounts", 2)));
        emergencyRepository.tamperAccounts.add(new LinkedHashMap<>(Map.of(
                "userCode", "USER1234",
                "count", 3,
                "alertState", "escalated")));

        var result = service.createTamperReport(
                "idem-j3-report",
                new ffdd.opsconsole.emergency.dto.TamperReportRequest("24h", "export tamper report", "risk-lead"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("source", "nx_emergency_tamper_report");
        assertThat(result.getData())
                .containsEntry("contentType", "text/csv;charset=UTF-8")
                .containsKeys("filename", "contentBase64");
        String csv = new String(java.util.Base64.getDecoder().decode(
                String.valueOf(result.getData().get("contentBase64"))), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("\"'=HYPERLINK").doesNotContain("\",=HYPERLINK");
        assertThat(csv).contains("\"已升级处置\"").doesNotContain("\"escalated\"");
        assertThat(emergencyRepository.tamperReports).hasSize(1);
        assertThat(emergencyRepository.tamperReports.get(0))
                .containsEntry("window", "24h")
                .containsEntry("status", "READY");
        assertThat(String.valueOf(emergencyRepository.tamperReports.get(0).get("payload")))
                .contains("nx_emergency_tamper_event")
                .contains("withdraw-path");
        assertThat(configFacade.values).isEmpty();
    }

    @Test
    void nonEmergencyPlaybookRejectsEmergencyExecution() {
        service.createPlaybook(
                "idem-j4-create-non-emergency",
                new SopPlaybookCreateRequest(
                        "全站技术演练",
                        "技术故障",
                        "合规审计",
                        "20 分钟",
                        false,
                        "J1·熔断提现通道",
                        "",
                        "",
                        "演练结束恢复",
                        false,
                        "create non emergency drill",
                        "superadmin"));

        var result = service.executePlaybook(
                "SOP-CUSTOM-1",
                "idem-j4",
                new SopPlaybookRunRequest(true, "incident", "tech-on-call"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void sopOverviewReadsH1RhythmSnapshot() {
        configFacade.values.put("H1.rhythm.currentMonth", "9");
        configFacade.values.put("growth.phase.current", "P5");

        var result = service.sopOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("h1Rhythm"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("currentMonth", 9)
                .containsEntry("currentPhase", "P5");
    }

    @Test
    void sopOverviewDoesNotSeedPlaybooksOrExecutionsWhenDatabaseIsEmpty() {
        var result = service.sopOverview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("playbooks").toString()).doesNotContain("SOP-01");
        assertThat(result.getData().get("executions").toString()).doesNotContain("SOP-06");
        assertThat(result.getData().get("actionOptions").toString())
                .contains("熔断提现通道")
                .contains("发送通知模板")
                .doesNotContain("I5", "D2", "B1", "C2", "K1", "J2");
        assertThat(result.getData()).doesNotContainKey("sla");
        assertThat(result.getData().get("rollbackOptions").toString())
                .contains("逐步恢复已关停入口")
                .doesNotContain("提现限流恢复");
        assertThat(configFacade.values).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void createPlaybookPersistsI3NotificationReference() {
        var result = service.createPlaybook(
                "idem-j4-create",
                new SopPlaybookCreateRequest(
                        "监管点名快速止血",
                        "监管点名",
                        "合规审计",
                        "15 分钟",
                        true,
                        "J1·熔断提现通道\nI3·发送通知模板",
                        "CMP-2617",
                        "SFC 风险披露重新确认",
                        "根因消除后常规轨恢复",
                        true,
                        "wire I3 campaign",
                        "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.playbooks.toString())
                .contains("CMP-2617")
                .contains("SFC 风险披露重新确认");
        List<Map<String, Object>> playbooks = (List<Map<String, Object>>) result.getData().get("playbooks");
        Map<String, Object> draft = playbooks.stream()
                .filter(row -> "监管点名快速止血".equals(row.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(draft)
                .containsEntry("notifyCampaignNo", "CMP-2617")
                .containsEntry("notifyTemplate", "SFC 风险披露重新确认")
                .containsEntry("draft", true);
        verify(idempotencyService).execute(
                eq("J4_PLAYBOOK_CREATE"), eq("idem-j4-create"), anyString(), eq(ApiResult.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void executePlaybookDispatchesRealI3CampaignAndRecordsRollbackHistory() {
        service.createPlaybook(
                "idem-j4-create",
                new SopPlaybookCreateRequest(
                        "监管点名快速止血",
                        "监管点名",
                        "合规审计",
                        "15 分钟",
                        true,
                        "J1·熔断提现通道\nI3·发送通知模板",
                        "CMP-2617",
                        "SFC 风险披露重新确认",
                        "根因消除后常规轨恢复",
                        false,
                        "wire I3 campaign",
                        "superadmin"));
        markPlaybookReady("SOP-CUSTOM-1");

        var result = service.executePlaybook(
                "SOP-CUSTOM-1",
                "idem-j4-execute",
                confirmedJ4RunRequest(
                        "SOP-CUSTOM-1", true, "regulator escalation", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(notificationDispatchFacade.calls).containsExactly("CMP-2617:SOP-CUSTOM-1");
        Map<String, Object> updated = (Map<String, Object>) result.getData().get("updated");
        Map<String, Object> dispatch = (Map<String, Object>) updated.get("notificationDispatch");
        assertThat(dispatch)
                .containsEntry("status", "DISPATCHED")
                .containsEntry("campaignNo", "CMP-2617")
                .containsEntry("notificationCount", 1);
        assertThat(updated).containsEntry("rollback", "根因消除后常规轨恢复");
        assertThat(emergencyRepository.executions.toString())
                .contains("CMP-2617")
                .contains("notificationDispatch")
                .contains("根因消除后常规轨恢复");
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "J4_SOP_PLAYBOOK_EXECUTED".equals(request.getAction())
                        && String.valueOf(request.getDetail()).contains("CMP-2617")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executePlaybookRunsOnlyTargetDomainActionsWithRealExecutors() {
        service.createPlaybook(
                "idem-j4-create-domain-actions",
                new SopPlaybookCreateRequest(
                        "资金对账缺口",
                        "资金异常",
                        "财务主管",
                        "15 分钟",
                        true,
                        "J1·熔断提现通道\nJ1·熔断 Genesis 交易",
                        "",
                        "",
                        "B1 覆盖率恢复后回滚",
                        false,
                        "wire real domain actions",
                        "superadmin"));
        markPlaybookReady("SOP-CUSTOM-1");

        var result = service.executePlaybook(
                "SOP-CUSTOM-1",
                "idem-j4-domain-actions",
                confirmedJ4RunRequest(
                        "SOP-CUSTOM-1", true, "ledger gap containment", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.settings)
                .containsEntry("killswitch.withdraw", "disabled")
                .containsEntry("killswitch.genesis", "disabled")
                .doesNotContainKeys("treasury.j4.coverage_check.required", "withdrawal.j4.batch_release.mode");
        assertThat(configFacade.values).isEmpty();
        Map<String, Object> updated = (Map<String, Object>) result.getData().get("updated");
        assertThat((List<Map<String, Object>>) updated.get("domainActions"))
                .extracting("domain")
                .containsOnly("J1");
        assertThat(emergencyRepository.executions.toString())
                .contains("domainActions")
                .contains("killswitch.withdraw")
                .contains("killswitch.genesis")
                .doesNotContain("withdrawal.j4.batch_release.mode");
        verify(killSwitchService).changeFromJ4Execution(
                eq("withdraw"), eq("admin:superadmin"), eq("ledger gap containment"),
                org.mockito.ArgumentMatchers.startsWith("SOP-CUSTOM-1-"));
    }

    @Test
    void executePlaybookRequiresV3TriggerBasisContextAndExactStepConfirmation() {
        service.createPlaybook(
                "idem-j4-v3-confirmation-create",
                new SopPlaybookCreateRequest(
                        "资金异常确认门", "资金异常", "财务主管", "15 分钟", true,
                        "J1·熔断提现通道·withdraw", "", "", "根因消除后逐步恢复",
                        true, "prepare v3 confirmation boundary", "superadmin"));
        markPlaybookReady("SOP-CUSTOM-1");
        List<SopStepConfirmationRequest> confirmed = List.of(
                new SopStepConfirmationRequest(1, "J1", "withdraw", true));

        var sceneAsBasis = service.executePlaybook(
                "SOP-CUSTOM-1", "idem-j4-v3-scene-basis",
                new SopPlaybookRunRequest(
                        true, "reject scene as trigger basis", "superadmin",
                        "资金异常", "ledger deficit requires immediate containment", confirmed));
        var missingContext = service.executePlaybook(
                "SOP-CUSTOM-1", "idem-j4-v3-missing-context",
                new SopPlaybookRunRequest(
                        true, "reject missing trigger context", "superadmin",
                        "挤兑风险", "", confirmed));
        var wrongRef = service.executePlaybook(
                "SOP-CUSTOM-1", "idem-j4-v3-wrong-ref",
                new SopPlaybookRunRequest(
                        true, "reject mismatched step contract", "superadmin",
                        "挤兑风险", "ledger deficit requires immediate containment",
                        List.of(new SopStepConfirmationRequest(1, "J1", "genesis", true))));
        var unconfirmed = service.executePlaybook(
                "SOP-CUSTOM-1", "idem-j4-v3-unconfirmed",
                new SopPlaybookRunRequest(
                        true, "reject unchecked execution step", "superadmin",
                        "挤兑风险", "ledger deficit requires immediate containment",
                        List.of(new SopStepConfirmationRequest(1, "J1", "withdraw", false))));

        assertThat(sceneAsBasis.getMessage()).isEqualTo("J4_TRIGGER_BASIS_INVALID");
        assertThat(missingContext.getMessage()).isEqualTo("J4_TRIGGER_CONTEXT_INVALID");
        assertThat(wrongRef.getMessage()).isEqualTo("J4_STEP_CONFIRMATION_INVALID:1");
        assertThat(unconfirmed.getMessage()).isEqualTo("J4_STEP_CONFIRMATION_INVALID:1");
        assertThat(emergencyRepository.executions).isEmpty();
        verify(killSwitchService, never()).changeFromJ4Execution(
                anyString(), anyString(), anyString(), anyString());

        var accepted = service.executePlaybook(
                "SOP-CUSTOM-1", "idem-j4-v3-confirmed",
                new SopPlaybookRunRequest(
                        true, "execute fully confirmed containment", "superadmin",
                        "挤兑风险", "ledger deficit requires immediate containment", confirmed));

        assertThat(accepted.getCode()).isZero();
        assertThat(emergencyRepository.executions).hasSize(1);
        verify(killSwitchService).changeFromJ4Execution(
                eq("withdraw"), eq("admin:superadmin"), eq("execute fully confirmed containment"),
                org.mockito.ArgumentMatchers.startsWith("SOP-CUSTOM-1-"));
    }

    @Test
    void tamperUnexpectedFailureUsesAnIndependentFailureAudit() {
        emergencyRepository.tamperPaths.add(new LinkedHashMap<>(Map.of(
                "key", "risk_disclosure_ack", "name", "风险披露验真", "count", 1)));
        emergencyRepository.createTamperReportFailure = new IllegalStateException("storage unavailable");

        assertThatThrownBy(() -> service.createTamperReport(
                "idem-j3-failure-audit",
                new ffdd.opsconsole.emergency.dto.TamperReportRequest(
                        "24h", "verify unexpected failure audit", "risk-lead")))
                .isSameAs(emergencyRepository.createTamperReportFailure);

        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequiredInNewTransaction(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("J3_TAMPER_REPORT_FAILED");
        assertThat(audit.getValue().getResult()).isEqualTo("FAILED");
        assertThat(audit.getValue().getDetail())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("failureCode", "J3_UNEXPECTED_FAILURE")
                .containsEntry("businessDataChanged", false);
    }

    @Test
    void tamperReportRejectsOversizedResultBeforeLoadingAllAccounts() {
        emergencyRepository.tamperPaths.add(new LinkedHashMap<>(Map.of(
                "id", "withdraw-path", "name", "提现参数篡改", "count", 1, "accounts", 1)));
        emergencyRepository.tamperAccountCountOverride = 10_001L;

        var result = service.createTamperReport(
                "idem-j3-report-too-large",
                new ffdd.opsconsole.emergency.dto.TamperReportRequest("24h", "bounded report export", "risk-lead"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("TAMPER_REPORT_TOO_LARGE");
        assertThat(emergencyRepository.tamperAccountFullReads).isZero();
        assertThat(emergencyRepository.tamperReports).isEmpty();
    }

    @Test
    void executePlaybookRejectsI3StepWithoutNotificationCampaignBeforeDomainWrites() {
        var result = service.createPlaybook(
                "idem-j4-create-missing-notify",
                new SopPlaybookCreateRequest(
                        "监管点名缺通知",
                        "监管点名",
                        "合规审计",
                        "15 分钟",
                        true,
                        "J1·熔断提现通道\nI3·发送通知模板",
                        "",
                        "",
                        "根因消除后常规轨恢复",
                        false,
                        "wire I3 campaign",
                        "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("J4_NOTIFY_CAMPAIGN_NO_REQUIRED");
        assertThat(configFacade.values)
                .doesNotContainKey("killswitch.withdraw")
                .doesNotContainKey("emergency.sop.executions");
        assertThat(emergencyRepository.executions).isEmpty();
    }

    @Test
    void executePlaybookRejectsOversizedActionTextBeforeDomainWrites() {
        var result = service.createPlaybook(
                "idem-j4-create-long-action",
                new SopPlaybookCreateRequest(
                        "监管点名动作过长",
                        "监管点名",
                        "合规审计",
                        "15 分钟",
                        true,
                        "J1·" + "withdraw ".repeat(80),
                        "",
                        "",
                        "根因消除后常规轨恢复",
                        false,
                        "wire long action",
                        "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("J4_ACTION_TEXT_TOO_LONG");
        assertThat(configFacade.values)
                .doesNotContainKey("killswitch.withdraw")
                .doesNotContainKey("emergency.sop.executions");
        assertThat(emergencyRepository.executions).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void executePlaybookPersistsCurrentExecutionToBusinessRepository() {
        service.createPlaybook(
                "idem-j4-create-trim-history",
                new SopPlaybookCreateRequest(
                        "资金对账缺口",
                        "资金异常",
                        "财务主管",
                        "15 分钟",
                        true,
                        "J1·熔断提现通道\nJ1·熔断 Genesis 交易",
                        "",
                        "",
                        "B1 覆盖率恢复后回滚",
                        false,
                        "wire real domain actions",
                        "superadmin"));
        markPlaybookReady("SOP-CUSTOM-1");

        var result = service.executePlaybook(
                "SOP-CUSTOM-1",
                "idem-j4-trim-history",
                confirmedJ4RunRequest(
                        "SOP-CUSTOM-1", true, "ledger gap containment", "superadmin"));

        assertThat(result.getCode()).isZero();
        String execId = String.valueOf(((Map<String, Object>) result.getData().get("updated")).get("executionId"));
        assertThat(emergencyRepository.executions)
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("executionId", execId)
                        .containsEntry("code", "SOP-CUSTOM-1"));
        assertThat(emergencyRepository.executions.toString()).contains("killswitch.withdraw");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executePlaybookReplaysSameIdempotencyKeyWithoutDuplicateSideEffects() {
        service.createPlaybook(
                "idem-j4-create",
                new SopPlaybookCreateRequest(
                        "监管点名快速止血",
                        "监管点名",
                        "合规审计",
                        "15 分钟",
                        true,
                        "J1·熔断提现通道\nI3·发送通知模板",
                        "CMP-2617",
                        "SFC 风险披露重新确认",
                        "根因消除后常规轨恢复",
                        false,
                        "wire I3 campaign",
                        "superadmin"));
        markPlaybookReady("SOP-CUSTOM-1");

        var first = service.executePlaybook(
                "SOP-CUSTOM-1",
                "idem-j4-replay",
                confirmedJ4RunRequest(
                        "SOP-CUSTOM-1", true, "regulator escalation", "superadmin"));
        String executionsAfterFirst = emergencyRepository.executions.toString();
        var second = service.executePlaybook(
                "SOP-CUSTOM-1",
                "idem-j4-replay",
                confirmedJ4RunRequest(
                        "SOP-CUSTOM-1", true, "regulator escalation", "superadmin"));

        assertThat(first.getCode()).isZero();
        assertThat(second.getCode()).isZero();
        assertThat(notificationDispatchFacade.calls).containsExactly("CMP-2617:SOP-CUSTOM-1");
        assertThat(emergencyRepository.executions.toString()).isEqualTo(executionsAfterFirst);
        assertThat(emergencyRepository.executions).hasSize(1);
        Map<String, Object> updated = (Map<String, Object>) second.getData().get("updated");
        assertThat(updated)
                .containsEntry("idempotentReplay", true)
                .containsEntry("code", "SOP-CUSTOM-1");
        verify(auditLogService, times(1)).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "J4_SOP_PLAYBOOK_EXECUTED".equals(request.getAction())));
        verify(auditLogService, never()).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "J4_SOP_PLAYBOOK_EXECUTED".equals(request.getAction())
                        && String.valueOf(request.getDetail()).contains("idempotentReplay=true")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rollbackUsesExecutionSnapshotWhenDraftPlaybookConfigWasReplaced() {
        service.createPlaybook(
                "idem-j4-create",
                new SopPlaybookCreateRequest(
                        "监管点名快速止血",
                        "监管点名",
                        "合规审计",
                        "15 分钟",
                        true,
                        "J1·熔断提现通道\nI3·发送通知模板",
                        "CMP-2617",
                        "SFC 风险披露重新确认",
                        "根因消除后常规轨恢复",
                        false,
                        "wire I3 campaign",
                        "superadmin"));
        markPlaybookReady("SOP-CUSTOM-1");
        var executed = service.executePlaybook(
                "SOP-CUSTOM-1",
                "idem-j4-run-before-replace",
                new SopPlaybookRunRequest(
                        true,
                        "regulator escalation",
                        "superadmin",
                        "监管点名",
                        "regulator requested immediate containment",
                        List.of(
                                new ffdd.opsconsole.emergency.dto.SopStepConfirmationRequest(
                                        1, "J1", "withdraw", true),
                                new ffdd.opsconsole.emergency.dto.SopStepConfirmationRequest(
                                        2, "I3", "campaign-notify", true))));
        String execId = String.valueOf(((Map<String, Object>) executed.getData().get("updated")).get("executionId"));
        emergencyRepository.playbooks.clear();

        var rolledBack = service.rollbackPlaybookExecution(
                "SOP-CUSTOM-1",
                execId,
                "idem-j4-rollback-after-replace",
                new SopPlaybookRunRequest(false, "restore production controls", "superadmin"));
        var replayedRollback = service.rollbackPlaybookExecution(
                "SOP-CUSTOM-1",
                execId,
                "idem-j4-rollback-after-replace",
                new SopPlaybookRunRequest(false, "restore production controls", "spoofed-operator"));

        assertThat(rolledBack.getCode()).isZero();
        assertThat(replayedRollback.getCode()).isZero();
        Map<String, Object> updated = (Map<String, Object>) rolledBack.getData().get("updated");
        assertThat(updated)
                .containsEntry("code", "SOP-CUSTOM-1")
                .containsEntry("rollbackStatus", "ROLLED_BACK")
                .containsEntry("playbookSnapshotMissing", true);
        assertThat(emergencyRepository.executions)
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("rollbackStatus", "ROLLED_BACK")
                        .containsKey("rollbackActions"));
        assertThat(configFacade.values).doesNotContainKey("emergency.sop.executions");
        verify(killSwitchService).restoreFromJ4Execution(
                eq("withdraw"),
                eq("admin:superadmin"),
                eq("restore production controls"),
                eq(execId),
                eq("owner:" + execId));
        verify(transactionManager, times(1)).commit(any());
    }

    @Test
    void rollbackRejectsAKillOnlyOperatorWithoutCallingTheJ1RestoreBoundary() {
        Map<String, Object> execution = reversibleExecution("EXEC-KILL-ONLY");
        emergencyRepository.executions.add(execution);
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.TestingAuthenticationToken(
                        "risk-lead",
                        "",
                        "emergency_j4_playbook_execute",
                        "emergency_j1_gate_kill"));
        try {
            var result = service.rollbackPlaybookExecution(
                    "SOP-MISSING", "EXEC-KILL-ONLY", "idem-j4-kill-only-rollback",
                    new SopPlaybookRunRequest(false, "attempt restore without resume authority", "spoofed-superadmin"));

            assertThat(result.getCode()).isEqualTo(403);
            assertThat(result.getMessage()).isEqualTo(
                    "J4_TARGET_AUTHORITY_REQUIRED:J1:emergency_j1_gate_resume");
            assertThat(execution).doesNotContainKey("rollbackStatus");
            verify(killSwitchService, never()).restoreFromJ4Execution(
                    anyString(), anyString(), anyString(), anyString(), anyString());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void rollbackWithResumeAuthorityDelegatesToTheJ1RestoreAndB1Boundary() {
        Map<String, Object> execution = reversibleExecution("EXEC-RESUME-AUTHORIZED");
        emergencyRepository.executions.add(execution);
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.TestingAuthenticationToken(
                        "superadmin",
                        "",
                        "emergency_j4_playbook_execute",
                        "emergency_j1_gate_resume"));
        try {
            var result = service.rollbackPlaybookExecution(
                    "SOP-MISSING", "EXEC-RESUME-AUTHORIZED", "idem-j4-resume-authorized",
                    new SopPlaybookRunRequest(false, "restore after the incident is cleared", "spoofed-operator"));

            assertThat(result.getCode()).isZero();
            verify(killSwitchService).restoreFromJ4Execution(
                    eq("withdraw"),
                    eq("admin:superadmin"),
                    eq("restore after the incident is cleared"),
                    eq("EXEC-RESUME-AUTHORIZED"),
                    eq("owner:EXEC-RESUME-AUTHORIZED"));
            verify(transactionManager).commit(any());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void rollbackConvertsBizExceptionAfterTransactionRollbackAndWritesRejectedAuditOutOfBand() {
        Map<String, Object> execution = reversibleExecution("EXEC-ROLLBACK-BIZ");
        emergencyRepository.executions.add(execution);
        doThrow(new ffdd.opsconsole.shared.exception.BizException(422, "J1_RESTORE_REJECTED"))
                .when(killSwitchService).restoreFromJ4Execution(
                        eq("withdraw"), eq("admin:superadmin"), anyString(),
                        eq("EXEC-ROLLBACK-BIZ"), eq("owner:EXEC-ROLLBACK-BIZ"));

        var result = service.rollbackPlaybookExecution(
                "SOP-MISSING", "EXEC-ROLLBACK-BIZ", "idem-j4-rollback-biz",
                new SopPlaybookRunRequest(false, "restore after failed containment", "superadmin"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("J1_RESTORE_REJECTED");
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequiredInNewTransaction(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("J4_SOP_PLAYBOOK_ROLLBACK_REJECTED");
        assertThat(audit.getValue().getResult()).isEqualTo("REJECTED");
        assertThat((Map<String, Object>) audit.getValue().getDetail())
                .containsEntry("result", "REJECTED")
                .containsEntry("errorCode", "J1_RESTORE_REJECTED")
                .containsEntry("businessDataChanged", false);
        verify(auditLogService, never()).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "J4_SOP_PLAYBOOK_ROLLED_BACK".equals(request.getAction())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentRollbackCompletionIsResolvedThroughIndependentCommittedRead() {
        Map<String, Object> execution = reversibleExecution("EXEC-CONCURRENT-ROLLBACK");
        execution.put("rollbackReason", "observe concurrent rollback completion");
        execution.put("rollbackActions", List.of(Map.of(
                "operator", "superadmin",
                "rollbackIdempotencyKey", "idem-j4-concurrent-rollback")));
        emergencyRepository.executions.add(execution);
        emergencyRepository.completeRollbackOnNextClaim = true;

        var result = service.rollbackPlaybookExecution(
                "SOP-MISSING", "EXEC-CONCURRENT-ROLLBACK", "idem-j4-concurrent-rollback",
                new SopPlaybookRunRequest(false, "observe concurrent rollback completion", "superadmin"));

        assertThat(result.getCode()).isZero();
        Map<String, Object> updated = (Map<String, Object>) result.getData().get("updated");
        assertThat(updated)
                .containsEntry("executionId", "EXEC-CONCURRENT-ROLLBACK")
                .containsEntry("rollbackStatus", "ROLLED_BACK")
                .containsEntry("idempotentReplay", true);
        assertThat(emergencyRepository.executionIndependentReads).isEqualTo(2);
        verify(transactionManager).rollback(any());
        verify(killSwitchService, never()).restoreFromJ4Execution(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void durableRollbackFactReplaysBeforeIdempotencyWhenItsFinalizationWasLost() {
        emergencyRepository.executions.add(reversibleExecution("EXEC-DURABLE-ROLLBACK"));
        SopPlaybookRunRequest request = new SopPlaybookRunRequest(
                false, "retry after idempotency finalization loss", "superadmin");

        var first = service.rollbackPlaybookExecution(
                "SOP-MISSING", "EXEC-DURABLE-ROLLBACK", "idem-j4-durable-rollback", request);
        var replay = service.rollbackPlaybookExecution(
                "SOP-MISSING", "EXEC-DURABLE-ROLLBACK", "idem-j4-durable-rollback", request);

        assertThat(first.getCode()).isZero();
        assertThat(replay.getCode()).isZero();
        assertThat((Map<String, Object>) replay.getData().get("updated"))
                .containsEntry("rollbackStatus", "ROLLED_BACK")
                .containsEntry("idempotentReplay", true);
        assertThat(emergencyRepository.executions.get(0).get("rollbackActions").toString())
                .contains("rollbackIdempotencyKey=idem-j4-durable-rollback")
                .contains("rollbackRequestHash=");
        verify(idempotencyService, times(1)).execute(
                eq("J4_PLAYBOOK_ROLLBACK:EXEC-DURABLE-ROLLBACK"),
                eq("idem-j4-durable-rollback"), anyString(), eq(ApiResult.class), any());
        verify(transactionManager, times(1)).commit(any());
        verify(killSwitchService, times(1)).restoreFromJ4Execution(
                eq("withdraw"), eq("admin:superadmin"), eq("retry after idempotency finalization loss"),
                eq("EXEC-DURABLE-ROLLBACK"), eq("owner:EXEC-DURABLE-ROLLBACK"));
        verify(auditLogService, times(1)).recordRequired(org.mockito.ArgumentMatchers.argThat(audit ->
                "J4_SOP_PLAYBOOK_ROLLED_BACK".equals(audit.getAction())));
    }

    @Test
    void durableRollbackFactRejectsADifferentIdempotencyKeyBeforeEnteringIdempotency() {
        Map<String, Object> execution = reversibleExecution("EXEC-DURABLE-MISMATCH");
        execution.put("rollbackStatus", "ROLLED_BACK");
        execution.put("rollbackReason", "original rollback request");
        execution.put("rollbackActions", List.of(Map.of(
                "operator", "superadmin",
                "rollbackIdempotencyKey", "idem-j4-original")));
        emergencyRepository.executions.add(execution);

        var result = service.rollbackPlaybookExecution(
                "SOP-MISSING", "EXEC-DURABLE-MISMATCH", "idem-j4-different",
                new SopPlaybookRunRequest(false, "original rollback request", "superadmin"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("J4_ROLLBACK_REPLAY_MISMATCH");
        verify(idempotencyService, never()).execute(
                anyString(), anyString(), anyString(), eq(ApiResult.class), any());
        verify(auditLogService).recordRequiredInNewTransaction(org.mockito.ArgumentMatchers.argThat(audit ->
                "J4_SOP_PLAYBOOK_ROLLBACK_REJECTED".equals(audit.getAction())
                        && "REJECTED".equals(audit.getResult())
                        && String.valueOf(audit.getDetail()).contains("J4_ROLLBACK_REPLAY_MISMATCH")));
    }

    private Map<String, Object> reversibleExecution(String executionId) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("executionId", executionId);
        execution.put("code", "SOP-MISSING");
        execution.put("mode", "emergency");
        execution.put("steps", List.of("done"));
        execution.put("notificationDispatch", Map.of("auditStatus", "AUDITED"));
        execution.put("domainActions", List.of(Map.of(
                "domain", "J1",
                "step", 1,
                "configKey", "killswitch.withdraw",
                "ownershipToken", "owner:" + executionId,
                "status", "DONE")));
        return execution;
    }

    @Test
    void geoAndTamperOverviewsDoNotSeedConfigWhenDatabaseIsEmpty() {
        var geo = service.geoBlockOverview();
        var tamper = service.tamperOverview();

        assertThat(geo.getCode()).isZero();
        assertThat(tamper.getCode()).isZero();
        assertThat(configFacade.values).isEmpty();
    }

    @Test
    void executePlaybookKeepsCompletedStopLossStepsAndRecordsTheFailurePoint() {
        service.createPlaybook(
                "idem-j4-partial-create",
                new SopPlaybookCreateRequest(
                        "通知失败部分执行", "监管点名", "合规审计", "15 分钟", true,
                        "J1·熔断提现通道\nI3·发送通知模板", "CMP-2617", "监管通知",
                        "根因消除后常规轨恢复", true, "create partial scenario", "superadmin"));
        markPlaybookReady("SOP-CUSTOM-1");
        notificationDispatchFacade.failDispatch = true;

        var result = service.executePlaybook(
                "SOP-CUSTOM-1", "idem-j4-partial-run",
                confirmedJ4RunRequest(
                        "SOP-CUSTOM-1", true, "notification outage containment", "superadmin"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).startsWith("J4_EXECUTION_PARTIAL:SOP-CUSTOM-1-");
        assertThat(emergencyRepository.settings).containsEntry("killswitch.withdraw", "disabled");
        assertThat(emergencyRepository.executions).singleElement().satisfies(row -> {
            assertThat(row.get("steps")).isEqualTo(List.of("done", "failed"));
            assertThat(row.get("domainActions").toString()).contains("ownershipToken");
        });
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "J4_SOP_PLAYBOOK_PARTIAL".equals(request.getAction())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executePlaybookMarksEveryUnattemptedStepAndDomainActionSkippedAfterAMiddleFailure() {
        service.createPlaybook(
                "idem-j4-middle-failure-create",
                new SopPlaybookCreateRequest(
                        "中间步骤失败追溯", "监管点名", "合规审计", "15 分钟", true,
                        "J1·熔断提现通道\nI3·发送通知模板\nJ1·熔断 Genesis 交易",
                        "CMP-2617", "监管通知", "根因消除后常规轨恢复", true,
                        "create middle failure scenario", "superadmin"));
        markPlaybookReady("SOP-CUSTOM-1");
        notificationDispatchFacade.failDispatch = true;

        var result = service.executePlaybook(
                "SOP-CUSTOM-1", "idem-j4-middle-failure-run",
                confirmedJ4RunRequest(
                        "SOP-CUSTOM-1", true, "middle notification dispatch failure", "superadmin"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(emergencyRepository.executions).singleElement().satisfies(row -> {
            assertThat(row.get("steps")).isEqualTo(List.of("done", "failed", "skipped"));
            assertThat((List<Map<String, Object>>) row.get("domainActions"))
                    .extracting(action -> action.get("status"))
                    .containsExactly("DONE", "FAILED", "SKIPPED");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleMiddleStepRecoveryMarksLaterUnattemptedStepsAndDomainActionsSkipped() {
        service.createPlaybook(
                "idem-j4-stale-middle-create",
                new SopPlaybookCreateRequest(
                        "中断恢复追溯", "监管点名", "合规审计", "15 分钟", true,
                        "J1·熔断提现通道\nI3·发送通知模板\nJ1·熔断 Genesis 交易",
                        "CMP-2617", "监管通知", "根因消除后常规轨恢复", true,
                        "create stale recovery scenario", "superadmin"));
        markPlaybookReady("SOP-CUSTOM-1");
        String executionId = "SOP-CUSTOM-1-STALE-MIDDLE";
        String requestReason = "reconcile interrupted notification step";
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("executionId", executionId);
        execution.put("code", "SOP-CUSTOM-1");
        execution.put("name", "中断恢复追溯");
        execution.put("trigger", requestReason);
        execution.put("mode", "emergency");
        execution.put("steps", new ArrayList<>(List.of("done", "running", "pending")));
        execution.put("operator", "superadmin");
        execution.put("roleGate", "合规审计");
        execution.put("idempotencyKey", "idem-j4-stale-middle-run");
        execution.put("notificationDispatch", new LinkedHashMap<>(Map.of(
                "required", true, "status", "PENDING", "auditStatus", "PENDING")));
        execution.put("domainActions", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of(
                        "domain", "J1", "step", 1, "action", "熔断提现通道",
                        "configKey", "killswitch.withdraw", "ownershipToken", "owner:" + executionId,
                        "status", "DONE")),
                new LinkedHashMap<>(Map.of(
                        "domain", "I3", "step", 2, "action", "发送通知模板",
                        "campaignNo", "CMP-2617", "status", "RUNNING")),
                new LinkedHashMap<>(Map.of(
                        "domain", "J1", "step", 3, "action", "熔断 Genesis 交易",
                        "configKey", "killswitch.genesis", "ownershipToken", "owner:" + executionId,
                        "status", "PENDING")))));
        emergencyRepository.executions.add(execution);

        var result = service.executePlaybook(
                "SOP-CUSTOM-1", "idem-j4-stale-middle-run",
                new SopPlaybookRunRequest(true, requestReason, "superadmin"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(execution.get("steps")).isEqualTo(List.of("done", "failed", "skipped"));
        assertThat((List<Map<String, Object>>) execution.get("domainActions"))
                .extracting(action -> action.get("status"))
                .containsExactly("DONE", "FAILED", "SKIPPED");
        verify(killSwitchService, never()).changeFromJ4Execution(
                eq("genesis"), anyString(), anyString(), anyString());
    }

    @Test
    void createPlaybookAcceptsCanonicalUiRefsAndRejectsDuplicateSideEffects() {
        var accepted = service.createPlaybook(
                "idem-j4-canonical-action",
                new SopPlaybookCreateRequest(
                        "规范动作协议", "监管点名", "合规审计", "15 分钟", true,
                        "J1·熔断提现通道·withdraw", "", "", "根因消除后逐步恢复", true,
                        "verify canonical action protocol", "superadmin"));
        var duplicated = service.createPlaybook(
                "idem-j4-duplicate-action",
                new SopPlaybookCreateRequest(
                        "重复通知动作", "监管点名", "合规审计", "15 分钟", true,
                        "I3·发送通知模板·campaign-notify\nI3·发送通知模板·campaign-notify",
                        "CMP-2617", "SFC 风险披露重新确认", "根因消除后逐步恢复", true,
                        "reject duplicate notification dispatch", "superadmin"));

        assertThat(accepted.getCode()).isZero();
        assertThat(duplicated.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(duplicated.getMessage()).isEqualTo("J4_ACTION_DUPLICATED:I3:campaign-notify");
        assertThat(emergencyRepository.playbooks).hasSize(1);
    }

    @Test
    void executePlaybookAnchorsAFailedExecutionBeforeAnySideEffectWhenRequiredAuditIsUnavailable() {
        service.createPlaybook(
                "idem-j4-audit-anchor-create",
                new SopPlaybookCreateRequest(
                        "审计锚点验证", "监管点名", "合规审计", "15 分钟", true,
                        "J1·熔断提现通道·withdraw", "", "", "根因消除后逐步恢复", true,
                        "create audit anchor candidate", "superadmin"));
        markPlaybookReady("SOP-CUSTOM-1");
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditLogService)
                .recordRequiredInNewTransaction(org.mockito.ArgumentMatchers.argThat(request ->
                        "J4_SOP_PLAYBOOK_EXECUTION_STARTED".equals(request.getAction())));

        assertThatThrownBy(() -> service.executePlaybook(
                "SOP-CUSTOM-1", "idem-j4-audit-anchor-run",
                confirmedJ4RunRequest(
                        "SOP-CUSTOM-1", true, "verify audit before effect", "superadmin")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");

        assertThat(emergencyRepository.executions)
                .singleElement()
                .satisfies(row -> assertThat(row.get("steps")).isEqualTo(List.of("failed")));
        verify(killSwitchService, never()).changeFromJ4Execution(
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void executePlaybookRetriesMissingOutcomeAuditWithoutRepeatingTheSideEffect() {
        service.createPlaybook(
                "idem-j4-outcome-audit-create",
                new SopPlaybookCreateRequest(
                        "完成审计补写", "监管点名", "合规审计", "15 分钟", true,
                        "J1·熔断提现通道·withdraw", "", "", "根因消除后逐步恢复", true,
                        "create outcome audit candidate", "superadmin"));
        markPlaybookReady("SOP-CUSTOM-1");
        doThrow(new IllegalStateException("audit temporarily unavailable"))
                .doNothing()
                .when(auditLogService)
                .recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                        "J4_SOP_PLAYBOOK_EXECUTED".equals(request.getAction())));

        assertThatThrownBy(() -> service.executePlaybook(
                "SOP-CUSTOM-1", "idem-j4-outcome-audit-run",
                confirmedJ4RunRequest(
                        "SOP-CUSTOM-1", true, "verify final audit retry", "superadmin")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit temporarily unavailable");
        var replay = service.executePlaybook(
                "SOP-CUSTOM-1", "idem-j4-outcome-audit-run",
                confirmedJ4RunRequest(
                        "SOP-CUSTOM-1", true, "verify final audit retry", "superadmin"));

        assertThat(replay.getCode()).isZero();
        Map<String, Object> execution = emergencyRepository.executions.get(0);
        assertThat((Map<String, Object>) execution.get("notificationDispatch"))
                .containsEntry("auditStatus", "AUDITED");
        verify(killSwitchService, times(1)).changeFromJ4Execution(
                eq("withdraw"), eq("admin:superadmin"), eq("verify final audit retry"), anyString());
        verify(auditLogService, times(2)).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "J4_SOP_PLAYBOOK_EXECUTED".equals(request.getAction())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleRunningJ1ExecutionReconcilesFromTheExactOwnershipToken() {
        service.createPlaybook(
                "idem-j4-reconcile-create",
                new SopPlaybookCreateRequest(
                        "超时执行对账", "监管点名", "合规审计", "15 分钟", true,
                        "J1·熔断提现通道·withdraw", "", "", "根因消除后逐步恢复", true,
                        "create reconcile candidate", "superadmin"));
        markPlaybookReady("SOP-CUSTOM-1");
        String executionId = "SOP-CUSTOM-1-RECOVER";
        String ownershipToken = "J4_OWNERSHIP:" + executionId + ":withdraw";
        emergencyRepository.settings.put("killswitch.withdraw", "disabled");
        emergencyRepository.settings.put("emergency.killswitch.withdraw.lastChange", ownershipToken);
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("executionId", executionId);
        execution.put("code", "SOP-CUSTOM-1");
        execution.put("name", "超时执行对账");
        execution.put("trigger", "reconcile stale execution");
        execution.put("mode", "emergency");
        execution.put("steps", new ArrayList<>(List.of("running")));
        execution.put("operator", "superadmin");
        execution.put("roleGate", "合规审计");
        execution.put("idempotencyKey", "idem-j4-reconcile-run");
        execution.put("notificationDispatch", new LinkedHashMap<>(Map.of(
                "required", false, "status", "SKIPPED", "auditStatus", "PENDING")));
        execution.put("domainActions", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of(
                "domain", "J1", "step", 1, "action", "熔断提现通道",
                "configKey", "killswitch.withdraw", "beforeValue", "enabled",
                "value", "disabled", "ownershipToken", ownershipToken, "status", "RUNNING")))));
        emergencyRepository.executions.add(execution);

        var result = service.executePlaybook(
                "SOP-CUSTOM-1", "idem-j4-reconcile-run",
                new SopPlaybookRunRequest(true, "reconcile stale execution", "superadmin"));

        assertThat(result.getCode()).isZero();
        assertThat(execution.get("steps")).isEqualTo(List.of("done"));
        assertThat((Map<String, Object>) execution.get("notificationDispatch"))
                .containsEntry("auditStatus", "AUDITED");
        verify(killSwitchService, never()).changeFromJ4Execution(
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void rollbackRejectsAnExecutionThatHasNotReachedATerminalState() {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("executionId", "EXEC-RUNNING");
        execution.put("code", "SOP-MISSING");
        execution.put("mode", "emergency");
        execution.put("steps", List.of("running"));
        execution.put("domainActions", List.of(Map.of(
                "domain", "J1", "step", 1, "configKey", "killswitch.withdraw",
                "ownershipToken", "J4_OWNERSHIP:EXEC-RUNNING:withdraw", "status", "RUNNING")));
        emergencyRepository.executions.add(execution);

        var result = service.rollbackPlaybookExecution(
                "SOP-MISSING", "EXEC-RUNNING", "idem-j4-running-rollback",
                new SopPlaybookRunRequest(false, "do not rollback running", "superadmin"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("J4_EXECUTION_IN_PROGRESS:EXEC-RUNNING");
        assertThat(execution).doesNotContainKey("rollbackStatus");
        verify(killSwitchService, never()).restoreFromJ4Execution(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void rollbackRejectsATerminalExecutionUntilItsOutcomeAuditIsDurable() {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("executionId", "EXEC-AUDIT-PENDING");
        execution.put("code", "SOP-MISSING");
        execution.put("mode", "emergency");
        execution.put("steps", List.of("done"));
        execution.put("notificationDispatch", Map.of("auditStatus", "PENDING"));
        execution.put("domainActions", List.of(Map.of(
                "domain", "J1", "step", 1, "configKey", "killswitch.withdraw",
                "ownershipToken", "J4_OWNERSHIP:EXEC-AUDIT-PENDING:withdraw", "status", "DONE")));
        emergencyRepository.executions.add(execution);

        var result = service.rollbackPlaybookExecution(
                "SOP-MISSING", "EXEC-AUDIT-PENDING", "idem-j4-audit-pending-rollback",
                new SopPlaybookRunRequest(false, "wait for outcome audit", "superadmin"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("J4_EXECUTION_AUDIT_PENDING:EXEC-AUDIT-PENDING");
        assertThat(execution).doesNotContainKey("rollbackStatus");
        verify(killSwitchService, never()).restoreFromJ4Execution(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void rollbackRejectsAFailedJ1AnchorThatNeverCompletedAnOwnedSideEffect() {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("executionId", "EXEC-J1-FAILED");
        execution.put("code", "SOP-MISSING");
        execution.put("mode", "emergency");
        execution.put("steps", List.of("failed"));
        execution.put("notificationDispatch", Map.of("auditStatus", "AUDITED"));
        execution.put("domainActions", List.of(Map.of(
                "domain", "J1", "step", 1, "configKey", "killswitch.withdraw",
                "ownershipToken", "J4_OWNERSHIP:EXEC-J1-FAILED:withdraw", "status", "FAILED")));
        emergencyRepository.executions.add(execution);

        var result = service.rollbackPlaybookExecution(
                "SOP-MISSING", "EXEC-J1-FAILED", "idem-j4-failed-anchor-rollback",
                new SopPlaybookRunRequest(false, "do not fake a restore", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("J4_EXECUTION_NOT_REVERSIBLE");
        assertThat(execution).doesNotContainKey("rollbackStatus");
        verify(killSwitchService, never()).restoreFromJ4Execution(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @SuppressWarnings("unchecked")
    private SopPlaybookRunRequest confirmedJ4RunRequest(
            String code, boolean emergency, String reason, String operator) {
        Map<String, Object> playbook = emergencyRepository.playbooks.stream()
                .filter(row -> code.equals(row.get("code")))
                .findFirst()
                .orElseThrow();
        List<Map<String, Object>> sequence = (List<Map<String, Object>>) playbook.get("sequence");
        List<SopStepConfirmationRequest> confirmations = new ArrayList<>();
        for (int index = 0; index < sequence.size(); index++) {
            Map<String, Object> step = sequence.get(index);
            Object ref = step.get("ref");
            confirmations.add(new SopStepConfirmationRequest(
                    index + 1,
                    String.valueOf(step.get("domain")),
                    ref == null ? "" : String.valueOf(ref),
                    true));
        }
        String triggerBasis = switch (String.valueOf(playbook.get("scene"))) {
            case "监管点名" -> "监管点名";
            case "舆情挤兑", "资金异常" -> "挤兑风险";
            default -> "安全事件";
        };
        return new SopPlaybookRunRequest(
                emergency,
                reason,
                operator,
                triggerBasis,
                reason,
                confirmations);
    }

    private void markPlaybookReady(String code) {
        emergencyRepository.playbooks.stream()
                .filter(row -> code.equals(row.get("code")))
                .findFirst()
                .orElseThrow()
                .put("state", "active");
        emergencyRepository.playbooks.stream()
                .filter(row -> code.equals(row.get("code")))
                .findFirst()
                .orElseThrow()
                .put("draft", false);
        emergencyRepository.playbooks.stream()
                .filter(row -> code.equals(row.get("code")))
                .findFirst()
                .orElseThrow()
                .put("lastDrill", LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
    }

    @Test
    void createPlaybookRejectsIncompleteOrNonExecutableDefinitionsBeforeWriting() {
        var incomplete = service.createPlaybook(
                "idem-j4-invalid-empty",
                new SopPlaybookCreateRequest(
                        "监管处置", "监管点名", "合规审计", "15 分钟", true,
                        "", "", "", "根因消除后逐步恢复", true,
                        "reject incomplete playbook", "spoofed-admin"));
        var unsupported = service.createPlaybook(
                "idem-j4-invalid-domain",
                new SopPlaybookCreateRequest(
                        "资金止血", "资金异常", "财务主管", "15 分钟", true,
                        "D2·提现限流 50%", "", "", "根因消除后逐步恢复", true,
                        "reject fake domain action", "spoofed-admin"));

        assertThat(incomplete.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(incomplete.getMessage()).isEqualTo("J4_ACTION_SEQUENCE_REQUIRED");
        assertThat(unsupported.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(unsupported.getMessage()).isEqualTo("J4_ACTION_NOT_EXECUTABLE:D2");
        assertThat(emergencyRepository.playbooks).isEmpty();
    }

    @Test
    void createPlaybookRejectsAnI3LabelThatDoesNotMatchTheCanonicalNotificationAction() {
        var result = service.createPlaybook(
                "idem-j4-fake-i3",
                new SopPlaybookCreateRequest(
                        "伪动作拒绝", "监管点名", "合规审计", "15 分钟", true,
                        "I3·删除用户", "CMP-2617", "监管通知", "根因消除后逐步恢复", true,
                        "reject fake I3 action", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("J4_ACTION_NOT_EXECUTABLE:I3");
        assertThat(emergencyRepository.playbooks).isEmpty();
    }

    @Test
    void createPlaybookRejectsDuplicateNameAndRecoveryDirection() {
        service.createPlaybook(
                "idem-j4-first-name",
                new SopPlaybookCreateRequest(
                        "监管止血", "监管点名", "合规审计", "15 分钟", true,
                        "J1·熔断提现通道", "", "", "根因消除后逐步恢复", false,
                        "create first playbook", "superadmin"));

        var duplicate = service.createPlaybook(
                "idem-j4-duplicate-name",
                new SopPlaybookCreateRequest(
                        "监管止血", "监管点名", "合规审计", "15 分钟", true,
                        "J1·熔断 Genesis 交易", "", "", "根因消除后逐步恢复", false,
                        "reject duplicate name", "superadmin"));
        var recovery = service.createPlaybook(
                "idem-j4-recovery-action",
                new SopPlaybookCreateRequest(
                        "恢复流程", "技术故障", "合规审计", "15 分钟", true,
                        "J1·恢复提现通道", "", "", "根因消除后逐步恢复", false,
                        "reject recovery direction", "superadmin"));

        assertThat(duplicate.getCode()).isEqualTo(409);
        assertThat(duplicate.getMessage()).isEqualTo("J4_PLAYBOOK_NAME_CONFLICT");
        assertThat(recovery.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(recovery.getMessage()).isEqualTo("J4_RECOVERY_ACTION_FORBIDDEN");
        assertThat(emergencyRepository.playbooks).hasSize(1);
        assertThat(emergencyRepository.playbookCatalogLockCount).isEqualTo(3);
    }

    @Test
    void executePlaybookRequiresACompletedDrillWhenTheDefinitionRequiresOne() {
        service.createPlaybook(
                "idem-j4-needs-drill",
                new SopPlaybookCreateRequest(
                        "监管演练后执行", "监管点名", "合规审计", "15 分钟", true,
                        "J1·熔断提现通道", "", "", "根因消除后逐步恢复", true,
                        "create guarded playbook", "superadmin"));

        var result = service.executePlaybook(
                "SOP-CUSTOM-1", "idem-j4-before-drill",
                new SopPlaybookRunRequest(true, "regulator containment", "superadmin"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("J4_PLAYBOOK_NOT_READY");
        assertThat(emergencyRepository.executions).isEmpty();
        verify(killSwitchService, never()).changeFromJ4Execution(
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulDrillStoresStepResultsAndPublishesTheDraftWithoutProductionWrites() {
        service.createPlaybook(
                "idem-j4-drill-create",
                new SopPlaybookCreateRequest(
                        "监管演练", "监管点名", "合规审计", "15 分钟", true,
                        "J1·熔断提现通道", "", "", "根因消除后逐步恢复", true,
                        "create drill candidate", "superadmin"));

        var result = service.drillPlaybook(
                "SOP-CUSTOM-1", "idem-j4-drill-run",
                new SopPlaybookRunRequest(false, "validate sandbox steps", "superadmin"));

        assertThat(result.getCode()).isZero();
        Map<String, Object> row = emergencyRepository.playbook("SOP-CUSTOM-1").orElseThrow();
        assertThat(row).containsEntry("state", "active").containsEntry("draft", false);
        assertThat(emergencyRepository.executions)
                .singleElement()
                .satisfies(execution -> assertThat(execution)
                        .containsEntry("mode", "drill")
                        .containsEntry("steps", List.of("done")));
        Map<String, Object> stats = (Map<String, Object>) result.getData().get("stats");
        assertThat(stats).containsEntry("readyCount", 1L).containsEntry("drill90d", 1L);
        verify(killSwitchService, never()).changeFromJ4Execution(
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void j4WritesUseTheAuthenticatedActorInsteadOfTheRequestOperator() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("real-admin", "n/a", List.of()));
        try {
            service.createPlaybook(
                    "idem-j4-authenticated-actor",
                    new SopPlaybookCreateRequest(
                            "认证操作员", "监管点名", "合规审计", "15 分钟", true,
                            "J1·熔断提现通道", "", "", "根因消除后逐步恢复", false,
                            "verify authenticated actor", "spoofed-admin"));

            assertThat(emergencyRepository.playbook("SOP-CUSTOM-1").orElseThrow())
                    .containsEntry("operator", "admin:real-admin");
            verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                    "J4_SOP_PLAYBOOK_CREATED".equals(request.getAction())
                            && "admin:real-admin".equals(request.getActorUsername())));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void executePlaybookRequiresEveryTargetDomainAuthority() {
        service.createPlaybook(
                "idem-j4-target-auth-create",
                new SopPlaybookCreateRequest(
                        "处置权限校验", "监管点名", "合规审计", "15 分钟", true,
                        "J1·熔断提现通道", "", "", "根因消除后逐步恢复", false,
                        "create target auth playbook", "superadmin"));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "j4-operator", "n/a",
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "emergency_j4_playbook_execute"))));
        try {
            var result = service.executePlaybook(
                    "SOP-CUSTOM-1", "idem-j4-target-auth-run",
                    new SopPlaybookRunRequest(true, "verify target permission", "spoofed-admin"));

            assertThat(result.getCode()).isEqualTo(403);
            assertThat(result.getMessage()).isEqualTo(
                    "J4_TARGET_AUTHORITY_REQUIRED:J1:emergency_j1_gate_kill");
            assertThat(emergencyRepository.executions).isEmpty();
            verify(killSwitchService, never()).changeFromJ4Execution(
                    anyString(), anyString(), anyString(), anyString());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void tamperReportRejectsAnEmptySelectedWindowWithoutCreatingAFalseSuccessRecord() {
        var result = service.createTamperReport(
                "idem-j3-empty-report",
                new ffdd.opsconsole.emergency.dto.TamperReportRequest("7d", "export selected window", "risk-lead"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("TAMPER_REPORT_EMPTY");
        assertThat(emergencyRepository.tamperReports).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void geoOverviewMarksAnUnregisteredConfiguredEdgeSourceAsInvalid() {
        emergencyRepository.settings.put("emergency.geo.edgeJudgeSource", "retired-edge-provider");

        var result = service.geoBlockOverview();

        Map<String, Object> edge = (Map<String, Object>) result.getData().get("edge");
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) edge.get("metrics");
        assertThat(edge)
                .containsEntry("sourceKnown", false)
                .containsEntry("healthy", false)
                .containsEntry("healthStatus", "invalid");
        assertThat(metrics).anySatisfy(metric -> assertThat(metric)
                .containsEntry("key", "地区解析状态")
                .containsEntry("value", "判定源未登记")
                .containsEntry("tone", "danger"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tamperAccountsArePagedAndDoNotExposeUid() {
        for (int i = 1; i <= 8; i++) {
            emergencyRepository.tamperAccounts.add(Map.of(
                    "userCode", "u-1000" + i,
                    "count", 9 + i,
                    "k4", "+" + (9 + i),
                    "last", "14:00:0" + i,
                    "paths", List.of("p" + i),
                    "cluster", "CL-1"));
        }

        var result = service.tamperOverview(2, 3);

        assertThat(result.getCode()).isZero();
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) result.getData().get("accounts");
        Map<String, Object> accountPage = (Map<String, Object>) result.getData().get("accountPage");
        Map<String, Object> firstAccount = accounts.get(0);
        assertThat(accounts).hasSize(3);
        assertThat(accountPage)
                .containsEntry("page", 2)
                .containsEntry("pageSize", 3)
                .containsEntry("total", 8)
                .containsEntry("pages", 3);
        assertThat(firstAccount)
                .containsKey("userCode")
                .containsKey("userNo")
                .doesNotContainKey("uid");
        assertThat(String.valueOf(firstAccount.get("userCode"))).startsWith("U");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tamperOverviewAndReportDoNotSilentlyTruncateAfterTwoHundredAccounts() {
        emergencyRepository.tamperPaths.add(Map.of(
                "id", "risk-disclosure-ack", "name", "风险披露确认篡改",
                "count", 205, "accounts", 205));
        for (int i = 1; i <= 205; i++) {
            emergencyRepository.tamperAccounts.add(Map.of(
                    "userCode", "U" + String.format("%08d", i),
                    "count", 10,
                    "last", "18:00:00",
                    "paths", List.of("risk-disclosure-ack")));
        }

        var overview = service.tamperOverview("24h", 5, 50);
        var report = service.createTamperReport(
                "idem-j3-report-over-200",
                new ffdd.opsconsole.emergency.dto.TamperReportRequest(
                        "24h", "verify complete report beyond legacy cap", "risk-lead"));

        Map<String, Object> accountPage = (Map<String, Object>) overview.getData().get("accountPage");
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) overview.getData().get("accounts");
        assertThat(accountPage)
                .containsEntry("total", 205)
                .containsEntry("pages", 5)
                .containsEntry("page", 5);
        assertThat(accounts).hasSize(5);
        assertThat(report.getCode()).isZero();
        assertThat(report.getData()).containsEntry("accountCount", 205);
    }

    @Test
    @SuppressWarnings("unchecked")
    void storedTamperUidIsConvertedToUserCode() {
        emergencyRepository.tamperAccounts.add(Map.of(
                "uid", "u-83271",
                "count", 24,
                "k4", "+42",
                "last", "14:18:32",
                "paths", List.of("local-balance"),
                "cluster", "CL-318"));

        var result = service.tamperOverview(1, 5);

        assertThat(result.getCode()).isZero();
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) result.getData().get("accounts");
        assertThat(accounts.get(0))
                .containsEntry("userCode", "U00083271")
                .doesNotContainKey("uid");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tamperAccountReadsMySqlNumericDeliveryFlagsTruthfully() {
        emergencyRepository.tamperAccounts.add(Map.of(
                "userCode", "U00000052",
                "count", 12,
                "k4", "+12",
                "fedToK4", 1,
                "b5Triggered", 1,
                "alertState", "escalated"));

        var result = service.tamperOverview(1, 5);

        List<Map<String, Object>> accounts = (List<Map<String, Object>>) result.getData().get("accounts");
        assertThat(accounts.get(0))
                .containsEntry("fedToK4", true)
                .containsEntry("b5Triggered", true)
                .containsEntry("alertState", "escalated");
    }

    @Test
    void replayJ1GateKillDelegatesToKillSwitchServiceToggle() {
        doReturn(ApiResult.ok(new java.util.LinkedHashMap<>(Map.of("domain", "J1"))))
                .when(killSwitchService).toggle(eq("withdraw"), eq("idem-replay-j1-kill"), any(KillSwitchToggleRequest.class));

        ApiResult<?> result = service.replay(
                new AuditReplayCommand("J", "j1_gate_kill", Map.of("gateKey", "withdraw")),
                new AuditReplayContext("superadmin", "j1 replay kill", "idem-replay-j1-kill"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<KillSwitchToggleRequest> captor = ArgumentCaptor.forClass(KillSwitchToggleRequest.class);
        verify(killSwitchService).toggle(eq("withdraw"), eq("idem-replay-j1-kill"), captor.capture());
        assertThat(captor.getValue().enabled()).isEqualTo("disabled");
        assertThat(captor.getValue().reason()).isEqualTo("j1 replay kill");
    }

    @Test
    void replayJ2CountryManageUpdatesGeoCountryAndAudit() {
        ApiResult<?> result = service.replay(
                new AuditReplayCommand("J", "j2_country_manage", Map.of("countryCode", "VE", "status", "blocked")),
                new AuditReplayContext("risk-lead", "j2 replay country block", "idem-replay-j2-country"));

        assertThat(result.getCode()).isZero();
        assertThat(emergencyRepository.geoCountries)
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("cc", "VE")
                        .containsEntry("status", "blocked"));
        verify(auditLogService).recordRequired(org.mockito.ArgumentMatchers.argThat(request ->
                "J2_GEO_COUNTRY_STATUS_CHANGED".equals(request.getAction())
                        && String.valueOf(request.getDetail()).contains("idem-replay-j2-country")));
    }

    @Test
    void replayJ4ExecutionRebuildsStructuredTriggerAndStepConfirmations() {
        service.createPlaybook(
                "idem-j4-replay-create",
                new SopPlaybookCreateRequest(
                        "监管回放止血", "监管点名", "风控", "15 分钟", true,
                        "J1·熔断提现通道", "", "", "根因消除后恢复",
                        true, "prepare auditable replay", "superadmin"));
        markPlaybookReady("SOP-CUSTOM-1");

        ApiResult<?> result = service.replay(
                new AuditReplayCommand("J", "j4_playbook_execute", Map.of(
                        "code", "SOP-CUSTOM-1",
                        "emergency", true,
                        "triggerBasis", "监管点名",
                        "triggerContext", "regulator ordered immediate containment",
                        "stepConfirmations", List.of(Map.of(
                                "step", 1, "domain", "J1", "ref", "withdraw", "confirmed", true)))),
                new AuditReplayContext(
                        "superadmin", "regulator ordered immediate containment", "idem-replay-j4-execute"));

        assertThat(result.getCode()).isZero();
        verify(killSwitchService).changeFromJ4Execution(
                eq("withdraw"), eq("admin:superadmin"), eq("regulator ordered immediate containment"),
                org.mockito.ArgumentMatchers.startsWith("SOP-CUSTOM-1-"));
    }

    @Test
    void replayUnknownOpReturns422WithUnknownReplayOpMarker() {
        ApiResult<?> result = service.replay(
                new AuditReplayCommand("J", "j_unknown_op", Map.of()),
                new AuditReplayContext("superadmin", "replay unknown op", "idem-replay-unknown"));

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("UNKNOWN_REPLAY_OP:j_unknown_op");
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
    }

    private static final class FakeEmergencyControlRepository implements EmergencyControlRepository {
        private final List<Map<String, Object>> geoCountries = new ArrayList<>();
        private final List<Map<String, Object>> geoEndpointCatalogs = new ArrayList<>();
        private final List<Map<String, Object>> geoEndpoints = new ArrayList<>();
        private final List<Map<String, Object>> geoHits = new ArrayList<>();
        private final Map<String, Integer> geoEndpointHits = new LinkedHashMap<>();
        private final List<Map<String, Object>> geoEdgeMetrics = new ArrayList<>();
        private final Map<String, String> settings = new LinkedHashMap<>();
        private final List<Map<String, Object>> tamperPaths = new ArrayList<>();
        private final List<Map<String, Object>> tamperAccounts = new ArrayList<>();
        private final List<Map<String, Object>> tamperReports = new ArrayList<>();
        private final java.util.Deque<List<Map<String, Object>>> tamperPathResponses = new java.util.ArrayDeque<>();
        private final List<List<LocalDateTime>> tamperPathRanges = new ArrayList<>();
        private final List<List<LocalDateTime>> tamperAccountRanges = new ArrayList<>();
        private final List<Integer> tamperThresholds = new ArrayList<>();
        private final List<Map<String, Object>> tamperFrequencyDistribution = new ArrayList<>();
        private int tamperFrequencyDistributionReads;
        private int tamperConfigLockCount;
        private Long tamperAccountCountOverride;
        private int tamperAccountFullReads;
        private RuntimeException createTamperReportFailure;
        private final List<Map<String, Object>> playbooks = new ArrayList<>();
        private final List<Map<String, Object>> executions = new ArrayList<>();
        private int playbookCatalogLockCount;
        private boolean completeRollbackOnNextClaim;
        private int executionIndependentReads;

        @Override
        public void ensureTables() {
        }

        @Override
        public Map<String, String> tamperConfigForUpdate() {
            tamperConfigLockCount++;
            return Map.copyOf(settings);
        }

        @Override
        public List<Map<String, Object>> geoCountryPolicies() {
            return geoCountries;
        }

        @Override
        public void upsertGeoCountryPolicy(String countryCode, String countryName, String status, String reason, String operator) {
            geoCountries.removeIf(row -> countryCode.equals(row.get("cc")));
            geoCountries.add(new LinkedHashMap<>(Map.of(
                    "cc", countryCode,
                    "name", countryName,
                    "status", status,
                    "reason", reason,
                    "operator", operator == null ? "" : operator)));
        }

        @Override
        public List<Map<String, Object>> geoEndpointCatalogs() {
            return geoEndpointCatalogs;
        }

        @Override
        public Optional<Map<String, Object>> geoEndpointCatalog(String endpointKey) {
            return geoEndpointCatalogs.stream()
                    .filter(row -> endpointKey.equals(row.get("endpointKey")))
                    .findFirst();
        }

        @Override
        public List<Map<String, Object>> geoEndpointPolicies() {
            return geoEndpoints;
        }

        @Override
        public void replaceGeoEndpointPolicies(String endpointKey, String endpointPath, String label, String biz, String domain,
                                               List<String> countryCodes, String source, String reason, String operator) {
            geoEndpoints.removeIf(row -> endpointKey.equals(row.get("endpointKey")));
            for (String countryCode : countryCodes) {
                geoEndpoints.add(new LinkedHashMap<>(Map.of(
                        "endpointKey", endpointKey,
                        "endpointPath", endpointPath,
                        "label", label,
                        "biz", biz,
                        "domain", domain,
                        "countryCode", countryCode,
                        "source", source,
                        "reason", reason)));
            }
        }

        @Override
        public List<Map<String, Object>> geoHits() {
            return geoHits;
        }

        @Override
        public Map<String, Integer> geoEndpointHits() {
            return geoEndpointHits;
        }

        @Override
        public List<Map<String, Object>> geoEdgeMetrics() {
            return geoEdgeMetrics;
        }

        @Override
        public Optional<String> settingValue(String settingKey) {
            return Optional.ofNullable(settings.get(settingKey));
        }

        @Override
        public void upsertSetting(
                String settingKey,
                String settingValue,
                String valueType,
                String groupCode,
                String remark,
                String operator) {
            settings.put(settingKey, settingValue);
        }

        @Override
        public Map<String, Object> tamperTrend(LocalDateTime now) {
            return Map.of(
                    "24h", Map.of("points", List.of(), "max", 0, "labels", List.of()),
                    "7d", Map.of("points", List.of(), "max", 0, "labels", List.of()),
                    "30d", Map.of("points", List.of(), "max", 0, "labels", List.of()));
        }

        @Override
        public List<Map<String, Object>> tamperPaths() {
            return tamperPaths;
        }

        @Override
        public List<Map<String, Object>> tamperPaths(LocalDateTime startAt, LocalDateTime endAt) {
            tamperPathRanges.add(List.of(startAt, endAt));
            return tamperPathResponses.isEmpty() ? tamperPaths : tamperPathResponses.removeFirst();
        }

        @Override
        public List<Map<String, Object>> tamperAccounts() {
            return tamperAccounts;
        }

        @Override
        public List<Map<String, Object>> tamperAccounts(LocalDateTime startAt, LocalDateTime endAt, int threshold) {
            tamperAccountFullReads++;
            tamperAccountRanges.add(List.of(startAt, endAt));
            tamperThresholds.add(threshold);
            return tamperAccounts;
        }

        @Override
        public long countTamperAccounts(LocalDateTime startAt, LocalDateTime endAt, int threshold) {
            tamperAccountRanges.add(List.of(startAt, endAt));
            tamperThresholds.add(threshold);
            return tamperAccountCountOverride == null ? tamperAccounts.size() : tamperAccountCountOverride;
        }

        @Override
        public List<Map<String, Object>> pageTamperAccounts(
                LocalDateTime startAt, LocalDateTime endAt, int threshold, int offset, int limit) {
            int from = Math.max(0, Math.min(offset, tamperAccounts.size()));
            int to = Math.max(from, Math.min(from + Math.max(0, limit), tamperAccounts.size()));
            return tamperAccounts.subList(from, to);
        }

        @Override
        public List<Map<String, Object>> tamperAccountFrequencyDistribution(
                LocalDateTime startAt, LocalDateTime endAt) {
            tamperFrequencyDistributionReads++;
            return tamperFrequencyDistribution;
        }

        @Override
        public void createTamperReport(String reportId, String window, boolean masked, String status,
                                       Map<String, Object> payload, String operator, String reason) {
            if (createTamperReportFailure != null) {
                throw createTamperReportFailure;
            }
            tamperReports.add(new LinkedHashMap<>(Map.of(
                    "reportId", reportId,
                    "window", window,
                    "masked", masked,
                    "status", status,
                    "payload", payload,
                    "operator", operator,
                    "reason", reason)));
        }

        @Override
        public List<Map<String, Object>> playbooks() {
            return playbooks;
        }

        @Override
        public void lockPlaybookCatalogMutations() {
            playbookCatalogLockCount++;
        }

        @Override
        public Optional<Map<String, Object>> playbook(String code) {
            return playbooks.stream()
                    .filter(row -> code.equals(row.get("code")))
                    .findFirst();
        }

        @Override
        public void createPlaybook(String code, String name, String scene, boolean emergency, String sla, String state,
                                   String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                                   boolean drillRequired, boolean draft, List<Map<String, Object>> sequence,
                                   String operator) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("name", name);
            row.put("scene", scene);
            row.put("emergency", emergency);
            row.put("sla", sla);
            row.put("state", state);
            row.put("owner", owner);
            row.put("lastDrill", "未演练");
            row.put("sequence", sequence);
            row.put("notifyCampaignNo", notifyCampaignNo);
            row.put("notifyTemplate", notifyTemplate);
            row.put("rollback", rollback);
            row.put("drillRequired", drillRequired);
            row.put("draft", draft);
            row.put("version", "2026-07-15 12:00:00");
            row.put("operator", operator);
            row.put("customSummary", "");
            row.put("lastExecution", "");
            playbooks.add(row);
        }

        @Override
        public boolean updatePlaybook(String code, String name, String scene, Boolean emergency, String sla, String state,
                                      String owner, String notifyCampaignNo, String notifyTemplate, String rollback,
                                      Boolean drillRequired, String summary, List<Map<String, Object>> sequence,
                                      boolean draft, String expectedVersion, String operator) {
            Map<String, Object> row = playbook(code).orElseThrow();
            if (!String.valueOf(row.get("version")).equals(expectedVersion)) return false;
            if (name != null && !name.isBlank()) row.put("name", name.trim());
            if (scene != null && !scene.isBlank()) row.put("scene", scene.trim());
            if (emergency != null) row.put("emergency", emergency);
            if (sla != null && !sla.isBlank()) row.put("sla", sla.trim());
            if (state != null && !state.isBlank()) row.put("state", state.trim());
            if (owner != null && !owner.isBlank()) row.put("owner", owner.trim());
            if (notifyCampaignNo != null) row.put("notifyCampaignNo", notifyCampaignNo.trim());
            if (notifyTemplate != null) row.put("notifyTemplate", notifyTemplate.trim());
            if (rollback != null && !rollback.isBlank()) row.put("rollback", rollback.trim());
            if (drillRequired != null) row.put("drillRequired", drillRequired);
            if (summary != null && !summary.isBlank()) row.put("customSummary", summary.trim());
            if (sequence != null) row.put("sequence", sequence);
            row.put("draft", draft);
            row.put("operator", operator);
            row.put("version", "2026-07-15 12:00:01");
            return true;
        }

        @Override
        public void markPlaybookDrilled(String code, LocalDateTime drillAt, String operator) {
            playbook(code).ifPresent(row -> {
                row.put("state", "active");
                row.put("lastDrill", drillAt.toString());
                row.put("draft", false);
            });
        }

        @Override
        public Optional<Map<String, Object>> executionByIdempotencyKey(String code, String idempotencyKey) {
            return executions.stream()
                    .filter(row -> code.equals(row.get("code")) && idempotencyKey.equals(row.get("idempotencyKey")))
                    .findFirst();
        }

        @Override
        public Optional<Map<String, Object>> execution(String executionId) {
            return executions.stream()
                    .filter(row -> executionId.equals(row.get("executionId")))
                    .findFirst();
        }

        @Override
        public Optional<Map<String, Object>> executionIndependent(String executionId) {
            executionIndependentReads++;
            return execution(executionId);
        }

        @Override
        public List<Map<String, Object>> executions(int limit) {
            return executions.stream().limit(limit).toList();
        }

        @Override
        public void createExecution(Map<String, Object> row) {
            String code = String.valueOf(row.get("code"));
            playbook(code).ifPresent(playbook -> playbook.put("lastExecution", row.get("executionId")));
            executions.add(0, new LinkedHashMap<>(row));
        }

        @Override
        public void updateExecutionProgressIndependent(
                String executionId,
                List<String> steps,
                Map<String, Object> notificationDispatch,
                List<Map<String, Object>> domainActions) {
            Map<String, Object> row = execution(executionId).orElseThrow();
            row.put("steps", new ArrayList<>(steps));
            row.put("notificationDispatch", new LinkedHashMap<>(notificationDispatch));
            row.put("domainActions", domainActions.stream().map(LinkedHashMap::new).toList());
        }

        @Override
        public void updateExecutionProgress(
                String executionId,
                List<String> steps,
                Map<String, Object> notificationDispatch,
                List<Map<String, Object>> domainActions) {
            updateExecutionProgressIndependent(executionId, steps, notificationDispatch, domainActions);
        }

        @Override
        public boolean claimExecutionRecovery(String executionId, LocalDateTime staleBefore) {
            return execution(executionId).isPresent();
        }

        @Override
        public synchronized boolean claimExecutionRollback(String executionId) {
            Optional<Map<String, Object>> found = execution(executionId);
            if (found.isEmpty()) {
                return false;
            }
            if (completeRollbackOnNextClaim) {
                completeRollbackOnNextClaim = false;
                found.get().put("rollbackStatus", "ROLLED_BACK");
                return false;
            }
            Object rollbackStatus = found.get().get("rollbackStatus");
            if (rollbackStatus != null && !String.valueOf(rollbackStatus).isBlank()) {
                return false;
            }
            Map<String, Object> notification = (Map<String, Object>) found.get().get("notificationDispatch");
            if (notification == null || !"AUDITED".equals(notification.get("auditStatus"))) {
                return false;
            }
            List<String> steps = (List<String>) found.get().getOrDefault("steps", List.of());
            if (steps.contains("pending") || steps.contains("running")) {
                return false;
            }
            found.get().put("rollbackStatus", "ROLLING_BACK");
            return true;
        }

        @Override
        public void lockPlaybook(String code) {
        }

        @Override
        public synchronized boolean completeExecutionRollback(
                String executionId, LocalDateTime rollbackAt, String reason,
                List<Map<String, Object>> rollbackActions) {
            Optional<Map<String, Object>> found = execution(executionId);
            if (found.isEmpty() || !"ROLLING_BACK".equals(found.get().get("rollbackStatus"))) {
                return false;
            }
            found.ifPresent(row -> {
                row.put("rollbackStatus", "ROLLED_BACK");
                row.put("rollbackAt", rollbackAt.toString());
                row.put("rollbackReason", reason);
                row.put("rollbackActions", rollbackActions);
            });
            return true;
        }
    }

    private static final class FakeNotificationDispatchFacade implements ContentNotificationDispatchFacade {
        private final List<String> calls = new ArrayList<>();
        private boolean failDispatch;

        @Override
        public Optional<NotificationEmergencyDispatchResult> inspectEmergencyCampaign(String campaignNo) {
            if (!"CMP-2617".equals(campaignNo)) {
                return Optional.empty();
            }
            return Optional.of(new NotificationEmergencyDispatchResult(
                    campaignNo, "SFC 风险披露重新确认", "critical", "全量", "scheduled", 0));
        }

        @Override
        public Optional<NotificationEmergencyDispatchResult> findEmergencyDispatch(
                String campaignNo, String executionId) {
            if (!calls.contains(campaignNo + ":SOP-CUSTOM-1")) {
                return Optional.empty();
            }
            return Optional.of(new NotificationEmergencyDispatchResult(
                    campaignNo, "SFC 风险披露重新确认", "critical", "全量", "sent", 1));
        }

        @Override
        public Optional<NotificationEmergencyDispatchResult> dispatchEmergencyCampaign(
                String campaignNo,
                String playbookCode,
                String executionId,
                String operator,
                String reason) {
            if (!"CMP-2617".equals(campaignNo)) {
                return Optional.empty();
            }
            if (failDispatch) {
                return Optional.empty();
            }
            calls.add(campaignNo + ":" + playbookCode);
            return Optional.of(new NotificationEmergencyDispatchResult(
                    campaignNo,
                    "SFC 风险披露重新确认",
                    "critical",
                    "全量",
                    "sending",
                    1));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingTamperAlertConfigUsesTheValidDocumentedDefault() {
        var result = service.tamperOverview();

        Map<String, Object> alert = (Map<String, Object>) result.getData().get("alertConfig");
        assertThat(alert)
                .containsEntry("threshold", 10)
                .containsEntry("label", "10 次 / 24h");
    }
}
