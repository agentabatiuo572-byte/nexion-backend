package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.domain.TopupWalletSnapshot;
import ffdd.opsconsole.finance.mapper.E4OrderRefundMapper;
import ffdd.opsconsole.platform.application.A2RuntimePolicy;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class E4OrderRefundSettlementFacadeAdapterTest {
    private static final String ORDER_NO = "ORD-8EB83D7802F0458DA6CD797F2D99A746";
    private static final String APPROVAL_KEY = "a2-approve-WO-261006182354500-171-1791311030910-1-2a2a5d84";
    private static final String REMARK_PREFIX = "E4 order refund | orderNo=" + ORDER_NO
            + " | operator=suadmin | reason=";
    private static final String REMARK_SUFFIX = " | key=" + APPROVAL_KEY;
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("legalApprovalReasons")
    void legalApprovalReasonProduces255CharacterLedgerSummaryWithoutChangingSettlement(
            String scenario, String reason, String expectedRemark) {
        A2RuntimePolicy.validateReason(reason, 8);
        when(mapper.lockWallet(7L)).thenReturn(new TopupWalletSnapshot(
                7L, new BigDecimal("0.000000"), new BigDecimal("1299.000000"), 2L));
        when(mapper.updateWallet(7L, new BigDecimal("1299.000000"), new BigDecimal("0.000000"), 2L))
                .thenReturn(1);
        when(mapper.insertLedger(eq(7L), eq("E4-REFUND-" + ORDER_NO), eq(new BigDecimal("1299.000000")),
                eq(new BigDecimal("1299.000000")), anyString())).thenReturn(1);
        when(mapper.insertBill(7L, "E4-BILL-" + ORDER_NO, new BigDecimal("1299.000000"))).thenReturn(1);

        var result = facade.settle(ORDER_NO, 7L, new BigDecimal("1299"), "WALLET",
                reason, "suadmin", APPROVAL_KEY);

        var remark = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertLedger(eq(7L), eq("E4-REFUND-" + ORDER_NO), eq(new BigDecimal("1299.000000")),
                eq(new BigDecimal("1299.000000")), remark.capture());
        String actualRemark = remark.getValue();
        assertThat(actualRemark.codePointCount(0, actualRemark.length()))
                .as("nx_wallet_ledger.remark VARCHAR(255) character limit")
                .isLessThanOrEqualTo(255);
        assertThat(actualRemark).isEqualTo(expectedRemark);
        assertThat(result.channel()).isEqualTo("WALLET");
        assertThat(result.ledgerBizNo()).isEqualTo("E4-REFUND-" + ORDER_NO);
        assertThat(result.billNo()).isEqualTo("E4-BILL-" + ORDER_NO);
        assertThat(result.walletBefore()).isEqualByComparingTo("0");
        assertThat(result.walletAfter()).isEqualByComparingTo("1299");
        assertThat(result.cumulativeDepositBefore()).isEqualByComparingTo("1299");
        assertThat(result.cumulativeDepositAfter()).isEqualByComparingTo("0");
        verify(mapper).lockWallet(7L);
        verify(mapper).updateWallet(7L, new BigDecimal("1299.000000"), new BigDecimal("0.000000"), 2L);
        verify(mapper).insertBill(7L, "E4-BILL-" + ORDER_NO, new BigDecimal("1299.000000"));
        verify(mapper).markPaymentRefunded(ORDER_NO, 7L);
        verifyNoMoreInteractions(mapper);
    }

    private static Stream<Arguments> legalApprovalReasons() {
        String actualReason = "UVEL TEST W84 R1788基线与B原单、1299 USDT、钱包渠道已核对；由suadmin审批superadmin工单，"
                + "执行一次后核验退款和Main直推64.95 USDT及523.790322 NEX追回，不走外部通道。";
        String shortUnicodeReason = "批准钱包退款🚀并保留完整审批理由";
        return Stream.of(
                Arguments.of("actual legal approval: 274 characters", actualReason,
                        REMARK_PREFIX + actualReason + " | key=" + APPROVAL_KEY.substring(0, 39)),
                Arguments.of("exact 255 BMP characters unchanged", "界".repeat(99),
                        REMARK_PREFIX + "界".repeat(99) + REMARK_SUFFIX),
                Arguments.of("exact 255 supplementary characters unchanged", "🚀".repeat(99),
                        REMARK_PREFIX + "🚀".repeat(99) + REMARK_SUFFIX),
                Arguments.of("supplementary character at cutoff remains intact", "界".repeat(163) + "🚀" + "尾".repeat(36),
                        REMARK_PREFIX + "界".repeat(163) + "🚀"),
                Arguments.of("maximum 200 visible characters remains legal", "r".repeat(200),
                        REMARK_PREFIX + "r".repeat(164)),
                Arguments.of("short Unicode summary unchanged", shortUnicodeReason,
                        REMARK_PREFIX + shortUnicodeReason + REMARK_SUFFIX));
    }
}
