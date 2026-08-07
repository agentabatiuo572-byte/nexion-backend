package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.market.mapper.AppExchangeMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
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
    private final G2ExchangeFeeAllocationService feeAllocationService = mock(G2ExchangeFeeAllocationService.class);
    private AppExchangeService service;

    @BeforeEach
    void setUp() {
        service = new AppExchangeService(mapper, config, idempotency, outbox, audit,
                feeAllocationService, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-22T10:00:00Z"), ZoneOffset.UTC));
        doAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotency).execute(anyString(), anyString(), anyString(), any(), any());
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        when(mapper.currentPrice()).thenReturn(BigDecimal.ONE);
        when(mapper.lockActiveUserNo(7L)).thenReturn("U00000007");
        when(mapper.lockWalletGate(7L)).thenReturn(
                new AppExchangeMapper.WalletGateRow(new BigDecimal("500"), new BigDecimal("500"), "SG"));
        when(mapper.geoBlocked("SG")).thenReturn(0);
        when(mapper.userTodayUsdt(7L)).thenReturn(BigDecimal.ZERO);
        when(mapper.platformTodayUsdt()).thenReturn(BigDecimal.ZERO);
        when(mapper.insertOrder(any())).thenReturn(1);
        when(mapper.applyWalletDelta(eq(7L), any(), any())).thenReturn(1);
        when(mapper.insertLedger(any())).thenReturn(1);
        when(mapper.userAttribution(7L)).thenReturn(new AppExchangeMapper.UserAttribution("P1", 3, "2026-W30"));
        when(feeAllocationService.allocate(anyString(), any(BigDecimal.class), any(BigDecimal.class)))
                .thenAnswer(invocation -> {
                    BigDecimal totalFee = invocation.getArgument(1, BigDecimal.class).setScale(6);
                    BigDecimal burnPool = totalFee.multiply(new BigDecimal("0.30")).setScale(6);
                    return new G2ExchangeFeeAllocationService.Allocation(
                            totalFee, burnPool, totalFee.subtract(burnPool));
                });
    }

    @Test
    void completesSwapUsingBalanceCapAndRegionControlsOnly() {
        var result = service.swap(7L, "idem-g2-direct",
                new AppExchangeService.SwapRequest("USDT_TO_NEX", new BigDecimal("20"), false));

        assertThat(result.getCode()).isZero();
        assertThat(((java.util.Map<?, ?>) result.getData().get("order")).get("status"))
                .isEqualTo("COMPLETED");
    }
}
