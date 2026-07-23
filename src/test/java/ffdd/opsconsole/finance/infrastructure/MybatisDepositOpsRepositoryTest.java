package ffdd.opsconsole.finance.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.domain.DepositChargebackView;
import ffdd.opsconsole.finance.domain.TopupChargebackRecoveryCommand;
import ffdd.opsconsole.finance.domain.TopupChargebackRecoveryResult;
import ffdd.opsconsole.finance.domain.TopupFeeBufferSnapshot;
import ffdd.opsconsole.finance.domain.TopupWalletSnapshot;
import ffdd.opsconsole.finance.mapper.D1FinanceClosureMapper;
import ffdd.opsconsole.finance.mapper.DepositOrderMapper;
import ffdd.opsconsole.finance.mapper.PaymentRecordMapper;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MybatisDepositOpsRepositoryTest {
    private final DepositOrderMapper depositOrderMapper = mock(DepositOrderMapper.class);
    private final PaymentRecordMapper paymentRecordMapper = mock(PaymentRecordMapper.class);
    private final D1FinanceClosureMapper closureMapper = mock(D1FinanceClosureMapper.class);
    private final TreasuryLedgerRepository treasuryLedgerRepository = mock(TreasuryLedgerRepository.class);
    private MybatisDepositOpsRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MybatisDepositOpsRepository(
                depositOrderMapper, paymentRecordMapper, closureMapper, treasuryLedgerRepository);
        when(paymentRecordMapper.findChargebackForUpdate("CB-100")).thenReturn(chargeback("已入账"));
        when(closureMapper.selectWalletForUpdate(42L)).thenReturn(
                new TopupWalletSnapshot(42L, new BigDecimal("150"), new BigDecimal("500"), 3L));
        when(closureMapper.selectFeeBufferForUpdate()).thenReturn(new TopupFeeBufferSnapshot(new BigDecimal("10"), 2L));
        when(closureMapper.updateWallet(any(), any(), any(), any())).thenReturn(1);
        when(closureMapper.updateFeeBuffer(any(), any())).thenReturn(1);
        when(closureMapper.updateChargebackStatus(any(), any(), any())).thenReturn(1);
    }

    @Test
    void recoverChargebackUpdatesWalletBufferLedgerStatusAndRecoveryAsOneClosure() {
        TopupChargebackRecoveryResult result = repository.recoverChargeback(command());

        assertThat(result.recoveredAmount()).isEqualByComparingTo("100.000000");
        assertThat(result.walletShortfall()).isZero();
        assertThat(result.feeBufferDeducted()).isEqualByComparingTo("3.500000");
        assertThat(result.feeBufferShortfall()).isZero();
        assertThat(result.status()).isEqualTo("RECOVERED");
        assertThat(result.ledgerBizNo()).isEqualTo("D1-CB-CB-100");
        assertThat(result.riskSignalNo()).isNull();

        verify(closureMapper).updateWallet(42L, new BigDecimal("50.000000"), new BigDecimal("400.000000"), 3L);
        verify(closureMapper).insertWalletLedger(
                eq(42L), eq("D1-CB-CB-100"), eq(new BigDecimal("100.000000")),
                eq(new BigDecimal("50.000000")), any());
        verify(closureMapper).updateFeeBuffer(new BigDecimal("6.500000"), 2L);
        verify(closureMapper).insertFeeBufferLedger(
                eq("FEE-CB-CB-100"), eq("D1-CB-CB-100"), eq(new BigDecimal("3.500000")),
                eq(new BigDecimal("6.500000")), any(), any(), any());
        verify(closureMapper).updateChargebackStatus("CB-100", "CHARGEBACK_RECOVERED", "approved recovery request");
        verify(closureMapper).resolveLegacyStatusOnlyChargeback("CB-100");
        verify(closureMapper, never()).insertRiskSignal(any(), any(), any(), any());
    }

    @Test
    void partialRecoveryFloorsBalancesAndCreatesRiskEvidence() {
        when(closureMapper.selectWalletForUpdate(42L)).thenReturn(
                new TopupWalletSnapshot(42L, new BigDecimal("40"), new BigDecimal("30"), 3L));
        when(closureMapper.selectFeeBufferForUpdate()).thenReturn(new TopupFeeBufferSnapshot(BigDecimal.ONE, 2L));

        TopupChargebackRecoveryResult result = repository.recoverChargeback(command());

        assertThat(result.recoveredAmount()).isEqualByComparingTo("40.000000");
        assertThat(result.walletShortfall()).isEqualByComparingTo("60.000000");
        assertThat(result.feeBufferDeducted()).isEqualByComparingTo("1.000000");
        assertThat(result.feeBufferShortfall()).isEqualByComparingTo("2.500000");
        assertThat(result.status()).isEqualTo("PARTIAL_ANOMALY");
        assertThat(result.riskSignalNo()).isEqualTo("RSK-CB-CB-100");
        verify(closureMapper).updateWallet(42L, new BigDecimal("0.000000"), new BigDecimal("0.000000"), 3L);
        verify(closureMapper).updateChargebackStatus("CB-100", "CHARGEBACK_PARTIAL", "approved recovery request");
        verify(closureMapper).insertRiskSignal(eq("RSK-CB-CB-100"), eq(42L), any(), eq("superadmin"));
    }

    @Test
    void zeroWalletProducesNoFictitiousD4LedgerReference() {
        when(closureMapper.selectWalletForUpdate(42L)).thenReturn(
                new TopupWalletSnapshot(42L, BigDecimal.ZERO, new BigDecimal("80"), 3L));

        TopupChargebackRecoveryResult result = repository.recoverChargeback(command());

        assertThat(result.ledgerBizNo()).isNull();
        verify(closureMapper, never()).insertWalletLedger(any(), any(), any(), any(), any());
        ArgumentCaptor<String> ledgerReference = ArgumentCaptor.forClass(String.class);
        verify(closureMapper).insertRecovery(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), ledgerReference.capture(), any());
        assertThat(ledgerReference.getValue()).isNull();
    }

    @Test
    void recoveryFailsClosedWhenOriginalCreditLedgerIsMissing() {
        when(paymentRecordMapper.findChargebackForUpdate("CB-100")).thenReturn(chargeback("未找到入账分录"));

        assertThatThrownBy(() -> repository.recoverChargeback(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHARGEBACK_LEDGER_ENTRY_NOT_FOUND");

        verify(closureMapper, never()).selectWalletForUpdate(any());
        verify(closureMapper, never()).updateWallet(any(), any(), any(), any());
    }

    @Test
    void recoveryFailsClosedOnOptimisticWalletConflict() {
        when(closureMapper.updateWallet(any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> repository.recoverChargeback(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHARGEBACK_WALLET_CONFLICT");

        verify(closureMapper, never()).insertWalletLedger(any(), any(), any(), any(), any());
        verify(closureMapper, never()).updateFeeBuffer(any(), any());
    }

    private TopupChargebackRecoveryCommand command() {
        return new TopupChargebackRecoveryCommand(
                "CB-100", 42L, new BigDecimal("100"), new BigDecimal("3.5"),
                "DISPUTE-PROOF-100", "approved recovery request", "superadmin", "idem-cb-100");
    }

    private DepositChargebackView chargeback(String enteredStatus) {
        return new DepositChargebackView(
                "CB-100", 42L, "usr_42", new BigDecimal("100"), new BigDecimal("3.5"), "fraud", enteredStatus,
                "CHARGEBACK", LocalDateTime.now().minusHours(1), LocalDateTime.now());
    }
}
