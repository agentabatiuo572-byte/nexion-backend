package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.domain.TopupWalletSnapshot;
import ffdd.opsconsole.finance.mapper.E4OrderRefundMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class E4OrderRefundSettlementFacadeAdapterTest {
    private final E4OrderRefundMapper mapper = mock(E4OrderRefundMapper.class);
    private final E4OrderRefundSettlementFacadeAdapter facade = new E4OrderRefundSettlementFacadeAdapter(mapper);

    @Test
    void walletRefundUpdatesBalanceCumulativeDepositLedgerBillAndPayment() {
        when(mapper.lockWallet(7L)).thenReturn(new TopupWalletSnapshot(
                7L, new BigDecimal("100.000000"), new BigDecimal("80.000000"), 3L));
        when(mapper.updateWallet(7L, new BigDecimal("130.000000"), new BigDecimal("50.000000"), 3L))
                .thenReturn(1);
        when(mapper.insertLedger(7L, "E4-REFUND-OD-7", new BigDecimal("30.000000"),
                new BigDecimal("130.000000"),
                "E4 order refund | orderNo=OD-7 | operator=admin | reason=customer refund approved | key=idem-7"))
                .thenReturn(1);
        when(mapper.insertBill(7L, "E4-BILL-OD-7", new BigDecimal("30.000000"))).thenReturn(1);

        var result = facade.settle("OD-7", 7L, new BigDecimal("30"), "WALLET",
                "customer refund approved", "admin", "idem-7");

        assertThat(result.walletAfter()).isEqualByComparingTo("130");
        assertThat(result.cumulativeDepositAfter()).isEqualByComparingTo("50");
        verify(mapper).markPaymentRefunded("OD-7", 7L);
    }

    @Test
    void originalPaymentFailsClosedUntilPspRefundAdapterExists() {
        assertThatThrownBy(() -> facade.settle("OD-7", 7L, BigDecimal.ONE, "ORIGINAL_PAYMENT",
                "customer refund approved", "admin", "idem-7"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("ORDER_REFUND_PSP_NOT_AVAILABLE");
    }
}
