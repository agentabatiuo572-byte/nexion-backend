package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade.Prepared;
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
    private final SupportPaymentAttributionFacade capture = paymentAttribution();
    private static final String ORDER_NO = "ORD-8EB83D7802F0458DA6CD797F2D99A746";
    private static final String APPROVAL_KEY = "a2-approve-WO-261006182354500-171-1791311030910-1-2a2a5d84";
    private static final String REMARK_PREFIX = "E4 order refund | orderNo=" + ORDER_NO
            + " | operator=suadmin | reason=";
    private static final String REMARK_SUFFIX = " | key=" + APPROVAL_KEY;
    private final E4OrderRefundMapper mapper = mock(E4OrderRefundMapper.class);
    private final E4OrderRefundSettlementFacadeAdapter facade = new E4OrderRefundSettlementFacadeAdapter(capture, mapper);

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void walletRefundUpdatesBalanceCumulativeDepositLedgerBillAndPayment(boolean failCapture) {
        when(mapper.lockWallet(7L)).thenReturn(new TopupWalletSnapshot(
                7L, new BigDecimal("100.000000"), new BigDecimal("80.000000"), 3L));
        when(mapper.updateWallet(7L, new BigDecimal("130.000000"), new BigDecimal("50.000000"), 3L))
                .thenReturn(1);

        when(mapper.insertBill(7L, "E4-BILL-OD-7", new BigDecimal("30.000000"))).thenReturn(1);

        if (failCapture) {
            org.mockito.Mockito.doThrow(new IllegalStateException("CAPTURE_WRITE_FAILED"))
                    .when(capture).record(org.mockito.ArgumentMatchers.any(Prepared.class));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> facade.settle("OD-7", 7L, new BigDecimal("30"), "WALLET",
                "customer refund approved", "admin", "idem-7", org.mockito.Mockito.mock(Prepared.class)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("CAPTURE_WRITE_FAILED");
        org.mockito.Mockito.verify(capture).record(org.mockito.ArgumentMatchers.any(Prepared.class));
            return;
        }

        var result = facade.settle("OD-7", 7L, new BigDecimal("30"), "WALLET",
                "customer refund approved", "admin", "idem-7", org.mockito.Mockito.mock(Prepared.class));

        assertThat(result.walletAfter()).isEqualByComparingTo("130");
        assertThat(result.cumulativeDepositAfter()).isEqualByComparingTo("50");
        org.mockito.Mockito.verify(mapper,org.mockito.Mockito.never()).insertLedger(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
        verify(mapper).markPaymentRefunded("OD-7", 7L);
        org.mockito.Mockito.verify(capture).record(org.mockito.ArgumentMatchers.any(Prepared.class));
        var sequence=org.mockito.Mockito.inOrder(mapper,capture);
        sequence.verify(mapper).updateWallet(7L,new BigDecimal("130.000000"),new BigDecimal("50.000000"),3L);
        sequence.verify(capture).insertLedger(org.mockito.ArgumentMatchers.any(Prepared.class),eq(new BigDecimal("30.000000")),eq(new BigDecimal("130.000000")),anyString());
        sequence.verify(mapper).insertBill(7L,"E4-BILL-OD-7",new BigDecimal("30.000000"));
        sequence.verify(capture).record(org.mockito.ArgumentMatchers.any(Prepared.class));
    }

    @Test
    void originalPaymentFailsClosedUntilPspRefundAdapterExists() {
        assertThatThrownBy(() -> facade.settle("OD-7", 7L, BigDecimal.ONE, "ORIGINAL_PAYMENT",
                "customer refund approved", "admin", "idem-7", org.mockito.Mockito.mock(Prepared.class)))
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

        when(mapper.insertBill(7L, "E4-BILL-" + ORDER_NO, new BigDecimal("1299.000000"))).thenReturn(1);

        var result = facade.settle(ORDER_NO, 7L, new BigDecimal("1299"), "WALLET",
                reason, "suadmin", APPROVAL_KEY, org.mockito.Mockito.mock(Prepared.class));

        var remark = ArgumentCaptor.forClass(String.class);
        verify(capture).insertLedger(org.mockito.ArgumentMatchers.any(Prepared.class), eq(new BigDecimal("1299.000000")), eq(new BigDecimal("1299.000000")), remark.capture());
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
        org.mockito.Mockito.verify(mapper,org.mockito.Mockito.never()).insertLedger(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void canonicalLedgerZeroOrFailureStopsBeforeRecord(boolean thrown) {
        org.mockito.Mockito.when(capture.insertLedger(org.mockito.ArgumentMatchers.any(Prepared.class),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> { if(thrown)throw new IllegalStateException("LEDGER_INSERT_FAILED"); return 0; });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> walletRefundUpdatesBalanceCumulativeDepositLedgerBillAndPayment(false))
                .hasMessage(thrown?"LEDGER_INSERT_FAILED":"ORDER_REFUND_LEDGER_WRITE_FAILED");
        org.mockito.Mockito.verify(capture,org.mockito.Mockito.never()).record(org.mockito.ArgumentMatchers.any());
    }

    private static SupportPaymentAttributionFacade paymentAttribution() {
        var capture = org.mockito.Mockito.mock(SupportPaymentAttributionFacade.class);
        org.mockito.Mockito.when(capture.prepare(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(org.mockito.Mockito.mock(
                        Prepared.class));
        org.mockito.Mockito.when(capture.insertLedger(org.mockito.ArgumentMatchers.any(Prepared.class),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        return capture;
    }
}
