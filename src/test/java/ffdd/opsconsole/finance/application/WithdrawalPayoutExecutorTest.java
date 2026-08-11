package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.cregis.CregisGateway;
import ffdd.opsconsole.finance.cregis.CregisGatewayRouter;
import ffdd.opsconsole.finance.cregis.CregisProperties;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class WithdrawalPayoutExecutorTest {

    @Test
    void sandboxTickNeverTouchesRealWithdrawalOrdersOrWallets() {
        WithdrawalPayoutMapper mapper = mock(WithdrawalPayoutMapper.class);
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        CregisGateway gateway = mock(CregisGateway.class);
        WithdrawalPayoutFinalizer finalizer = mock(WithdrawalPayoutFinalizer.class);
        var row = row("REVIEW_PASSED");
        when(mapper.claimable(any(LocalDateTime.class), anyInt())).thenReturn(List.of(row));
        when(mapper.claim(eq(row.withdrawalNo()), any(), any())).thenReturn(1);
        when(mapper.payout(row.withdrawalNo())).thenReturn(row);
        when(router.mode()).thenReturn(CregisProperties.Mode.LOCAL_SANDBOX);
        when(router.isolatedLocalSandbox()).thenReturn(gateway);
        when(gateway.createPayout(any())).thenReturn(new CregisGateway.PayoutSubmission(9001L, row.withdrawalNo()));
        when(finalizer.submitted(row, 9001L, "mock")).thenReturn(true);

        new WithdrawalPayoutExecutor(mapper, router, finalizer).process();

        verify(gateway, never()).createPayout(any());
        verify(mapper, never()).claim(any(), any(), any());
        verify(finalizer, never()).submitted(any(), anyLong(), any());
        verify(finalizer, never()).completeSandbox(any(), anyLong());
    }

    @Test
    void disabledProductionFailsClosedWithoutAutomaticSandboxFallback() {
        WithdrawalPayoutMapper mapper = mock(WithdrawalPayoutMapper.class);
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        WithdrawalPayoutFinalizer finalizer = mock(WithdrawalPayoutFinalizer.class);
        var row = row("REVIEW_PASSED");
        when(mapper.incompleteSandboxSubmissions(anyInt())).thenReturn(List.of());
        when(mapper.claimable(any(), anyInt())).thenReturn(List.of(row));
        when(mapper.claim(eq(row.withdrawalNo()), any(), any())).thenReturn(1);
        when(mapper.payout(row.withdrawalNo())).thenReturn(row);
        when(router.mode()).thenReturn(CregisProperties.Mode.DISABLED);

        new WithdrawalPayoutExecutor(mapper, router, finalizer).process();

        verify(router, never()).isolatedLocalSandbox();
        verify(router, never()).provider();
        verify(finalizer).retry(row, "CREGIS_PROVIDER_DISABLED");
        verify(finalizer, never()).completeSandbox(any(), anyLong());
    }

    @Test
    void unknownSubmissionTimeoutMovesToDurableManualReconciliationWithoutAutomaticResend() {
        WithdrawalPayoutMapper mapper = mock(WithdrawalPayoutMapper.class);
        CregisGatewayRouter router = mock(CregisGatewayRouter.class);
        CregisGateway gateway = mock(CregisGateway.class);
        WithdrawalPayoutFinalizer finalizer = mock(WithdrawalPayoutFinalizer.class);
        var row = row("REVIEW_PASSED");
        when(router.mode()).thenReturn(CregisProperties.Mode.PROVIDER);
        when(router.provider()).thenReturn(gateway);
        when(router.payoutCallbackUrl()).thenReturn("https://callback.invalid/payout");
        when(mapper.claimable(any(), anyInt())).thenReturn(List.of(row));
        when(mapper.claim(eq(row.withdrawalNo()), any(), any())).thenReturn(1);
        when(mapper.payout(row.withdrawalNo())).thenReturn(row);
        when(gateway.createPayout(any())).thenThrow(new RuntimeException("timeout after send"));

        new WithdrawalPayoutExecutor(mapper, router, finalizer).process();

        verify(gateway).createPayout(any());
        verify(finalizer).orphaned(row, null, "provider", "CREGIS_SUBMISSION_UNKNOWN");
        verify(finalizer, never()).retry(eq(row), any());
        verify(finalizer, never()).submitted(any(), anyLong(), any());
    }

    private WithdrawalPayoutMapper.PayoutRow row(String status) {
        return new WithdrawalPayoutMapper.PayoutRow(
                "WD-PAYOUT-1", 42L, "USDT-BEP20", "0x1111111111111111111111111111111111111111",
                new BigDecimal("10.000000"), new BigDecimal("9.000000"), BigDecimal.ZERO,
                status, LocalDateTime.now().plusHours(24), null, "WD-PAYOUT-1", null, 0);
    }
}
