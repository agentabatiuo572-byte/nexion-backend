package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.dto.F5CommissionAnomalyConfigRequest;
import ffdd.opsconsole.team.mapper.F5CommissionMapper;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
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
    private F5CommissionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(F5CommissionMapper.class);
        configFacade = mock(PlatformConfigFacade.class);
        auditLogService = mock(AuditLogService.class);
        outboxService = mock(EventOutboxService.class);
        idempotencyService = mock(AdminIdempotencyService.class);
        when(configFacade.activeValue(anyString())).thenReturn(Optional.empty());
        when(idempotencyService.execute(
                anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> {
                    Supplier<?> action = invocation.getArgument(4);
                    return action.get();
                });
        service = new F5CommissionService(
                mapper,
                configFacade,
                mock(TreasuryCoverageFacade.class),
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
}
