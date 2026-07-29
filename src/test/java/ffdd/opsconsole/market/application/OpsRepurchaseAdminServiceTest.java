package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.market.mapper.AppRepurchaseMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsRepurchaseAdminServiceTest {
    private final AppRepurchaseMapper mapper = mock(AppRepurchaseMapper.class);
    private final TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final OpsNexMarketService marketOverview = mock(OpsNexMarketService.class);
    private final OpsRepurchaseAdminService service = new OpsRepurchaseAdminService(
            mapper, coverage, idempotency, outbox, audit, marketOverview);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void successfulWriteReturnsCanonicalOverviewWithMutationReceipt() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("99376", null, List.of());
        authentication.setDetails(Map.of("username", "g-maker"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(mapper.lockProduct()).thenReturn(new AppRepurchaseMapper.ProductRow(
                1L, "REPURCHASE_90D", "Repurchase", "USDT", 90,
                new BigDecimal("1000"), new BigDecimal("500"), new BigDecimal("10"),
                BigDecimal.ONE, 1, "100 / 500", "ACTIVE"));
        when(mapper.updateApyBps(new BigDecimal("900"))).thenReturn(1);
        when(mapper.issuedTicketsThisMonth()).thenReturn(2L);
        when(mapper.configValue("G.genesis.lottery.monthlyCapacity")).thenReturn("100000");
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("120"), new BigDecimal("100")));
        when(outbox.publish(eq("REPURCHASE_CONFIG"), eq("apy"),
                eq("admin.repurchase_config_changed"), any())).thenReturn("receipt-g7");
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("domain", "G7");
        canonical.put("stats", Map.of("lockDays", 90));
        canonical.put("params", java.util.List.of());
        canonical.put("serverCanonical", true);
        canonical.put("sources", java.util.List.of("nx_staking_product:repurchase"));
        when(marketOverview.repurchaseOverview()).thenReturn(ApiResult.ok(canonical));

        ApiResult<Map<String, Object>> result = service.updateInternal(
                "apy",
                "g7-contract-1",
                new OpsRepurchaseAdminService.UpdateRequest(
                        "9", "approved acceptance update", "g-maker", ""));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("domain", "G7")
                .containsEntry("serverCanonical", true)
                .containsKeys("stats", "params", "sources");
        @SuppressWarnings("unchecked")
        Map<String, Object> updated = (Map<String, Object>) result.getData().get("updated");
        assertThat(updated)
                .containsEntry("key", "apy")
                .containsEntry("oldValue", "10")
                .containsEntry("newValue", "9")
                .containsEntry("receiptId", "receipt-g7");
        verify(marketOverview).repurchaseOverview();
        ArgumentCaptor<AuditLogWriteRequest> auditRequest =
                ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(audit).recordRequiredForTrustedActor(auditRequest.capture());
        assertThat(auditRequest.getValue().getActorId()).isEqualTo(99376L);
        assertThat(auditRequest.getValue().getActorUsername()).isEqualTo("g-maker");
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> eventPayload = ArgumentCaptor.forClass(Map.class);
        verify(outbox).publish(eq("REPURCHASE_CONFIG"), eq("apy"),
                eq("admin.repurchase_config_changed"), eventPayload.capture());
        assertThat(eventPayload.getValue()).containsEntry("operator", "g-maker");
    }
}
