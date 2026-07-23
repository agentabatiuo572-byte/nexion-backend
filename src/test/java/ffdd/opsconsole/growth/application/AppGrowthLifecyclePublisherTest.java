package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher.UserAttribution;
import ffdd.opsconsole.growth.application.AppGrowthLifecyclePublisher.VoucherRedemption;
import ffdd.opsconsole.growth.mapper.AppGrowthLifecycleMapper;
import ffdd.opsconsole.growth.mapper.AppGrowthLifecycleMapper.VoucherRedemptionRow;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AppGrowthLifecyclePublisherTest {
    private final AppGrowthLifecycleMapper mapper = mock(AppGrowthLifecycleMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AppGrowthLifecyclePublisher service = new AppGrowthLifecyclePublisher(mapper, audit, outbox);
    private final UserAttribution attribution = new UserAttribution("P3", 5, "2026-W30");

    @Test
    void percentageVoucherUsesCapAndIsConsumedBeforeAuditAndOutbox() {
        when(mapper.lockAvailableVoucher(42L, "V-20", "SKU-1", 0L)).thenReturn(null);
        when(mapper.lockAvailableVoucher(eq(42L), eq("V-20"), eq("SKU-1"), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new VoucherRedemptionRow(
                        "G-1", "V-20", "PERCENT", BigDecimal.ZERO, new BigDecimal("20"),
                        new BigDecimal("100"), new BigDecimal("15")));
        when(mapper.markVoucherUsed("G-1", 42L, "ORD-1")).thenReturn(1);

        VoucherRedemption redemption = service.prepareVoucher(42L, "V-20", "SKU-1", new BigDecimal("200"));
        service.redeemVoucher(42L, redemption, "ORD-1", "SKU-1", attribution);

        assertThat(redemption.discountUsdt()).isEqualByComparingTo("15.000000");
        var ordered = inOrder(mapper, audit, outbox);
        ordered.verify(mapper).markVoucherUsed("G-1", 42L, "ORD-1");
        ordered.verify(audit).recordRequired(any());
        ordered.verify(outbox).publishUserEvent(
                eq("ORDER"), eq("ORD-1"), eq("voucher.redeemed"), eq(42L),
                eq("P3"), eq(5), eq("2026-W30"), any());
    }

    @Test
    void outboxFailurePropagatesSoCallerTransactionRollsBackVoucherAndOrder() {
        when(mapper.markVoucherUsed("G-1", 42L, "ORD-1")).thenReturn(1);
        when(outbox.publishUserEvent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("outbox unavailable"));

        assertThatThrownBy(() -> service.redeemVoucher(
                42L, new VoucherRedemption("G-1", "V-1", BigDecimal.ONE),
                "ORD-1", "SKU-1", attribution))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");
        verify(audit).recordRequired(any());
    }

    @Test
    void rejectedVoucherDoesNotMutateGrant() {
        when(mapper.lockAvailableVoucher(eq(42L), eq("V-1"), eq("SKU-X"), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(null);

        assertThatThrownBy(() -> service.prepareVoucher(42L, "V-1", "SKU-X", BigDecimal.TEN))
                .hasMessage("VOUCHER_NOT_AVAILABLE_OR_NOT_APPLICABLE");
        verify(mapper, never()).markVoucherUsed(any(), any(), any());
    }

    @Test
    void trialAttemptRequiresAuditThenGovernedOutbox() {
        service.trialChargeAttempted(
                42L, "TRIAL-1", "FAILED", new BigDecimal("90"), "INSUFFICIENT_FUNDS", attribution);

        var ordered = inOrder(audit, outbox);
        ordered.verify(audit).recordRequired(any());
        ordered.verify(outbox).publishUserEvent(
                eq("TRIAL_CLAIM"), eq("TRIAL-1"), eq("trial.charge_attempted"), eq(42L),
                eq("P3"), eq(5), eq("2026-W30"), any());
    }
}
