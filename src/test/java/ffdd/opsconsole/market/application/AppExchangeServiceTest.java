package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.market.mapper.AppExchangeMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.facade.KycReviewTriggerResult;
import ffdd.opsconsole.risk.facade.RiskKycReviewFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class AppExchangeServiceTest {
    private final AppExchangeMapper mapper = mock(AppExchangeMapper.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final RiskKycReviewFacade riskKycReviewFacade = mock(RiskKycReviewFacade.class);
    private AppExchangeService service;

    @BeforeEach
    void setUp() {
        service = new AppExchangeService(mapper, config, idempotency, outbox, audit,
                riskKycReviewFacade, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-22T10:00:00Z"), ZoneOffset.UTC));
        doAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        when(config.activeValue("wallet.exchange.kyc_threshold_usdt")).thenReturn(Optional.of("100"));
        when(mapper.currentPrice()).thenReturn(BigDecimal.ONE);
        when(mapper.lockActiveUserNo(7L)).thenReturn("U00000007");
        when(mapper.lockWalletGate(7L)).thenReturn(
                new AppExchangeMapper.WalletGateRow(new BigDecimal("500"), new BigDecimal("500"), "PENDING", "SG"));
        when(mapper.geoBlocked("SG")).thenReturn(0);
        when(mapper.userTodayUsdt(7L)).thenReturn(BigDecimal.ZERO);
        when(mapper.platformTodayUsdt()).thenReturn(BigDecimal.ZERO);
        when(mapper.userOrders(7L)).thenReturn(List.of());
        when(mapper.insertOrder(any())).thenReturn(1);
    }

    @Test
    void cumulativeKycGateCreatesK5TicketAndCanonicalRiskEventInSameSwapCommand() {
        when(mapper.userLifetimeUsdt(7L)).thenReturn(new BigDecimal("90"));
        when(riskKycReviewFacade.triggerCumulativeExchangeReview(
                eq("U00000007"), eq(new BigDecimal("20.000000")), eq(new BigDecimal("110.000000")),
                eq(new BigDecimal("100")), eq("PENDING"), anyString(), eq("system:g2"), anyString()))
                .thenReturn(new KycReviewTriggerResult(true, true, "KR-G2-CUM-1", "K5_CUMULATIVE_EXCHANGE_REVIEW_REQUIRED"));
        when(outbox.publish(anyString(), anyString(), anyString(), any())).thenReturn("receipt");

        var result = service.swap(7L, "idem-g2-cumulative",
                new AppExchangeService.SwapRequest("USDT_TO_NEX", new BigDecimal("20"), false));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("gate", "KYC_REQUIRED");
        verify(riskKycReviewFacade).triggerCumulativeExchangeReview(
                eq("U00000007"), eq(new BigDecimal("20.000000")), eq(new BigDecimal("110.000000")),
                eq(new BigDecimal("100")), eq("PENDING"), anyString(), eq("system:g2"),
                contains("G2 cumulative exchange threshold reached"));
        verify(outbox).publish(eq("RISK_KYC_REVIEW_TICKET"), eq("KR-G2-CUM-1"),
                eq("risk.kyc_review_triggered"), any());
    }

    @Test
    void belowCumulativeThresholdCompletesSwapWithoutCallingK5() {
        when(mapper.userLifetimeUsdt(7L)).thenReturn(new BigDecimal("10"));
        when(mapper.applyWalletDelta(eq(7L), any(), any())).thenReturn(1);
        when(mapper.insertLedger(any())).thenReturn(1);
        when(mapper.userAttribution(7L)).thenReturn(new AppExchangeMapper.UserAttribution("P1", 3, "2026-W30"));
        when(outbox.publishUserEvent(anyString(), anyString(), anyString(), eq(7L), anyString(), any(), anyString(), any()))
                .thenReturn("receipt");

        var result = service.swap(7L, "idem-g2-below",
                new AppExchangeService.SwapRequest("USDT_TO_NEX", new BigDecimal("20"), false));

        assertThat(result.getCode()).isZero();
        assertThat(((java.util.Map<?, ?>) result.getData().get("order")).get("status")).isEqualTo("COMPLETED");
        verify(riskKycReviewFacade, never()).triggerCumulativeExchangeReview(
                anyString(), any(), any(), any(), anyString(), anyString(), anyString(), anyString());
        verify(outbox).publishUserEvent(eq("EXCHANGE_ORDER"), anyString(), eq("exchange.swapped"),
                eq(7L), eq("P1"), eq(3), eq("2026-W30"), any());
    }

    @Test
    void k5FailureAbortsGatedSwapBeforeOutboxAuditAndReliesOnRollbackBoundary() throws Exception {
        when(mapper.userLifetimeUsdt(7L)).thenReturn(new BigDecimal("90"));
        when(riskKycReviewFacade.triggerCumulativeExchangeReview(
                anyString(), any(), any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("K5_G2_TRIGGER_FAILED"));

        assertThatThrownBy(() -> service.swap(7L, "idem-g2-failure",
                new AppExchangeService.SwapRequest("USDT_TO_NEX", new BigDecimal("20"), false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("K5_G2_TRIGGER_FAILED");

        assertThat(AppExchangeService.class.getMethod("swap", Long.class, String.class,
                AppExchangeService.SwapRequest.class).getAnnotation(Transactional.class).rollbackFor())
                .contains(Exception.class);
        verify(mapper).insertOrder(any());
        verify(outbox, never()).publish(anyString(), anyString(), anyString(), any());
        verify(audit, never()).recordRequiredForTrustedActor(any());
    }
}
