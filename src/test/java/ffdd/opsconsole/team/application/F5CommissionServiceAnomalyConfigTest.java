package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.dto.F5CommissionAnomalyConfigRequest;
import ffdd.opsconsole.team.dto.F5CommissionQuery;
import ffdd.opsconsole.team.dto.F5CommissionReissueRequest;
import ffdd.opsconsole.team.dto.F5CommissionReverseRequest;
import ffdd.opsconsole.team.dto.F5CommissionSuspensionRequest;
import ffdd.opsconsole.team.mapper.F5CommissionMapper;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class F5CommissionServiceAnomalyConfigTest {
    private F5CommissionMapper mapper;
    private PlatformConfigFacade configFacade;
    private AuditLogService auditLogService;
    private EventOutboxService outboxService;
    private AdminIdempotencyService idempotencyService;
    private TreasuryCoverageFacade coverageFacade;
    private F5CommissionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(F5CommissionMapper.class);
        configFacade = mock(PlatformConfigFacade.class);
        auditLogService = mock(AuditLogService.class);
        outboxService = mock(EventOutboxService.class);
        idempotencyService = mock(AdminIdempotencyService.class);
        coverageFacade = mock(TreasuryCoverageFacade.class);
        when(configFacade.activeValue(anyString())).thenReturn(Optional.empty());
        when(idempotencyService.execute(
                anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> {
                    Supplier<?> action = invocation.getArgument(4);
                    return action.get();
                });
        when(coverageFacade.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("1.2"), new BigDecimal("0.85")));
        service = new F5CommissionService(
                mapper,
                configFacade,
                coverageFacade,
                mock(TreasuryLedgerPostingFacade.class),
                auditLogService,
                outboxService,
                idempotencyService);
    }

    @Test
    void updateAnomalyConfigPublishesTheExactCanonicalA4ProducerPayload() {
        ApiResult<Map<String, Object>> result = service.updateAnomalyConfig(
                "f5-config-idem-1",
                new F5CommissionAnomalyConfigRequest(
                        new BigDecimal("3.5"),
                        new BigDecimal("25"),
                        "tighten F5 anomaly thresholds",
                        "f5-operator"));
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        assertThat(result.getCode()).isZero();
        verify(outboxService).publish(
                eq("ADMIN_COMMISSION"),
                anyString(),
                eq("admin.commission_anomaly_config_changed"),
                payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload).containsOnlyKeys(
                "beforeCommissionAnomalySigma",
                "afterCommissionAnomalySigma",
                "beforeLayerRatioAnomalyPct",
                "afterLayerRatioAnomalyPct",
                "operator",
                "reason");
        assertThat((BigDecimal) payload.get("beforeCommissionAnomalySigma"))
                .isEqualByComparingTo("3");
        assertThat((BigDecimal) payload.get("afterCommissionAnomalySigma"))
                .isEqualByComparingTo("3.5");
        assertThat((BigDecimal) payload.get("beforeLayerRatioAnomalyPct"))
                .isEqualByComparingTo("20");
        assertThat((BigDecimal) payload.get("afterLayerRatioAnomalyPct"))
                .isEqualByComparingTo("25");
        assertThat(payload)
                .containsEntry("operator", "f5-operator")
                .containsEntry("reason", "tighten F5 anomaly thresholds");
    }

    @Test
    void directF5MutationsFailClosedWithoutAnApprovedA2Replay() {
        ApiResult<Map<String, Object>> reverse = service.reverse("CM-41", "same-key",
                new F5CommissionReverseRequest("refund-41", "reverse after verified refund", "operator"));
        ApiResult<Map<String, Object>> reissue = service.reissue("same-key",
                new F5CommissionReissueRequest(List.of("CM-41"), "reissue after remediation", "operator"));
        ApiResult<Map<String, Object>> suspension = service.suspend(41L, "same-key",
                new F5CommissionSuspensionRequest(List.of("network"), true, "suspend confirmed abuse", "operator"));

        assertThat(reverse).extracting(ApiResult::getCode, ApiResult::getMessage)
                .containsExactly(409, "A2_CONFIRMATION_REQUIRED");
        assertThat(reissue).extracting(ApiResult::getCode, ApiResult::getMessage)
                .containsExactly(409, "A2_CONFIRMATION_REQUIRED");
        assertThat(suspension).extracting(ApiResult::getCode, ApiResult::getMessage)
                .containsExactly(409, "A2_CONFIRMATION_REQUIRED");
        verify(idempotencyService, never()).execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any());
    }

    @Test
    void approvedReplayBindsAnOperationIdAndPassesDifferentPayloadHashesForTheSameKey() {
        when(idempotencyService.execute(
                anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenReturn(ApiResult.ok(Map.of("accepted", true)));
        A2ReplayContext.enterReplay("A2-F5-41");
        try {
            ApiResult<Map<String, Object>> first = service.reverse("CM-41", "same-key",
                    new F5CommissionReverseRequest("refund-41", "verified refund one", "operator"));
            ApiResult<Map<String, Object>> changedPayload = service.reverse("CM-41", "same-key",
                    new F5CommissionReverseRequest("refund-42", "verified refund two", "operator"));

            assertThat(first.getCode()).isZero();
            assertThat(changedPayload.getCode()).isZero();
        } finally {
            A2ReplayContext.exitReplay();
        }
        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(idempotencyService, org.mockito.Mockito.times(2)).execute(
                eq("F5_COMMISSION_REVERSE"), eq("same-key"), hashes.capture(), eq(ApiResult.class), any());
        assertThat(hashes.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void replayFlagWithoutDurableA2OperationIdStillFailsClosed() {
        A2ReplayContext.enterReplay();
        try {
            ApiResult<Map<String, Object>> result = service.reverse("CM-41", "same-key",
                    new F5CommissionReverseRequest("refund-41", "verified refund", "operator"));
            assertThat(result).extracting(ApiResult::getCode, ApiResult::getMessage)
                    .containsExactly(409, "A2_CONFIRMATION_REQUIRED");
        } finally {
            A2ReplayContext.exitReplay();
        }
        verify(idempotencyService, never()).execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void overviewUsesFullAggregatesForSixKindsAndAllFiveStatuses() {
        when(mapper.queryEvents(null, null, null, null, null, null, 21)).thenReturn(List.of());
        when(mapper.queryEvents(null, null, null, null, null, null, 200)).thenReturn(List.of());
        when(mapper.aggregateCommissionKinds()).thenReturn(List.of(
                Map.of("kind", "network", "currency", "USDT", "amount", new BigDecimal("10"), "count", 1L),
                Map.of("kind", "binary", "currency", "USDT", "amount", new BigDecimal("20"), "count", 2L),
                Map.of("kind", "peer", "currency", "NEX", "amount", new BigDecimal("30"), "count", 3L),
                Map.of("kind", "cultivation", "currency", "NEX", "amount", new BigDecimal("40"), "count", 4L),
                Map.of("kind", "leadership", "currency", "USDT", "amount", new BigDecimal("50"), "count", 5L),
                Map.of("kind", "genesis", "currency", "NEX", "amount", new BigDecimal("60"), "count", 6L)));
        when(mapper.aggregateCommissionStatuses()).thenReturn(List.of(
                Map.of("status", "unlocked", "currency", "USDT", "amount", BigDecimal.ONE, "count", 1L),
                Map.of("status", "cooling", "currency", "USDT", "amount", BigDecimal.ONE, "count", 2L),
                Map.of("status", "withdrawn", "currency", "USDT", "amount", BigDecimal.ONE, "count", 3L),
                Map.of("status", "reversed", "currency", "USDT", "amount", BigDecimal.ONE, "count", 4L),
                Map.of("status", "frozen", "currency", "USDT", "amount", BigDecimal.ONE, "count", 5L)));

        Map<String, Object> overview = service.overview(
                new F5CommissionQuery(null, null, null, null, null, null, 20)).getData();

        assertThat((List<Map<String, Object>>) overview.get("commissionKinds")).hasSize(6);
        assertThat((List<Map<String, Object>>) overview.get("statusDistribution"))
                .extracting(row -> row.get("name"))
                .containsExactly("已解锁可提", "冷却计提中", "已提现", "已撤销", "已冻结");
        assertThat(overview.get("summary").toString()).contains("USDT 80.00", "NEX 130.00");
        assertThat((Map<String, Object>) overview.get("summary"))
                .containsEntry("frozenCount", 5L)
                .doesNotContainKey("abnormalOrFrozenCount");
    }

    @Test
    void overviewFailsClosedInsteadOfRelabelingUnknownRawStatus() {
        when(mapper.queryEvents(null, null, null, null, null, null, 21)).thenReturn(List.of());
        when(mapper.queryEvents(null, null, null, null, null, null, 200)).thenReturn(List.of());
        when(mapper.aggregateCommissionKinds()).thenReturn(List.of());
        when(mapper.aggregateCommissionStatuses()).thenReturn(List.of(
                Map.of("status", "unknown", "currency", "USDT", "amount", BigDecimal.ONE, "count", 1L)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.overview(
                new F5CommissionQuery(null, null, null, null, null, null, 20)))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class)
                .hasMessageContaining("F5_COMMISSION_STATUS_UNKNOWN");
    }
}
