package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper;
import ffdd.opsconsole.growth.mapper.AppTrialLifecycleMapper.TrialRow;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrialConvertAndDeferredDeactivateTest {
    private final AppTrialLifecycleMapper mapper = mock(AppTrialLifecycleMapper.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AppTrialLifecycleService service = new AppTrialLifecycleService(
            mapper, mock(EarningsReleaseService.class), idempotency, mock(TreasuryCoverageFacade.class),
            mock(AuditLogService.class), mock(EventOutboxService.class));

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setup() {
        when(mapper.activeUser(7L)).thenReturn(7L);
        when(mapper.policies()).thenReturn(List.of(
                new AppTrialLifecycleMapper.PolicyRow("phaseOpen", "true"),
                new AppTrialLifecycleMapper.PolicyRow("trialProductId", "stellarbox-s1"),
                new AppTrialLifecycleMapper.PolicyRow("trialOffsetCapUSD", "50")));
        when(mapper.attribution(7L)).thenReturn(new AppTrialLifecycleMapper.Attribution("P1", 1, "2026-W30"));
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
    }

    @Test
    void convertLocksAuthoritativeProductCreatesOrderAndClosesActiveTrialAtomically() {
        when(mapper.lockTrial(7L)).thenReturn(activeTrial());
        when(mapper.lockConversionProduct("stellarbox-s1"))
                .thenReturn(new AppTrialLifecycleMapper.ConversionProduct(11L, "stellarbox-s1", "S1", new BigDecimal("1299"), 2, "P1"));
        when(mapper.decrementProductStock(11L)).thenReturn(1);
        when(mapper.insertConversionOrder(eq(7L), anyString(), eq(11L), any(), any(), any())).thenReturn(1);
        when(mapper.insertConversionOrderItem(anyString(), eq(11L), eq("stellarbox-s1"), eq("S1"), any())).thenReturn(1);
        when(mapper.markTrialConverted(eq(1L), eq(0L), anyString(), any(), anyString())).thenReturn(1);

        ApiResult<java.util.Map<String, Object>> result = service.convert(7L, "stellarbox-s1", "convert-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("orderStatus", "PENDING_PAYMENT");
        verify(mapper).lockConversionProduct("stellarbox-s1");
        verify(mapper).markTrialConverted(eq(1L), eq(0L), anyString(), any(), anyString());
    }

    @Test
    void convertRejectsDifferentProductOrUnavailableStockBeforeAnyMutation() {
        when(mapper.lockTrial(7L)).thenReturn(activeTrial());
        when(mapper.lockConversionProduct("other-product")).thenReturn(null);

        ApiResult<java.util.Map<String, Object>> result = service.convert(7L, "other-product", "convert-2");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("TRIAL_PRODUCT_NOT_ELIGIBLE");
        verify(mapper, never()).decrementProductStock(anyLong());
        verify(mapper, never()).markTrialConverted(anyLong(), anyLong(), anyString(), any(), anyString());
    }

    private TrialRow activeTrial() {
        LocalDateTime now = LocalDateTime.now();
        return new TrialRow(1L, 7L, "TRIAL-1", "ACTIVE", null, null, "NexGridBox S1", 3,
                new BigDecimal("40"), new BigDecimal("5"), new BigDecimal("50"), new BigDecimal("1299"),
                now.minusHours(1), now.plusDays(2), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, null, 0L);
    }
}
