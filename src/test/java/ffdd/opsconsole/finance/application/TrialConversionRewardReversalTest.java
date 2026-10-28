package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.application.EarningsReleaseService.RewardRecoveryReceipt;
import ffdd.opsconsole.finance.application.EarningsReleaseService.RewardRecoveryRequest;
import ffdd.opsconsole.finance.mapper.E4OrderRefundMapper;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TrialConversionRewardReversalTest {
    private final E4OrderRefundMapper mapper = mock(E4OrderRefundMapper.class);
    private final EarningsReleaseMapper earningsMapper = mock(EarningsReleaseMapper.class);
    private final EarningsReleaseService earnings = mock(EarningsReleaseService.class);
    private final TreasuryLedgerPostingFacade ledger = mock(TreasuryLedgerPostingFacade.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final TrialConversionRewardReversal reversal =
            new TrialConversionRewardReversal(mapper, earningsMapper, earnings, ledger, audit, transactions);

    @BeforeEach
    void openRefundTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
    }

    @AfterEach
    void closeRefundTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void accruedRuleOrdersKeepTheirOriginalRefundBehaviour() {
        when(mapper.lockTrialConversion("TRC-1", 7L)).thenReturn(new E4OrderRefundMapper.TrialConversion(
                "TRIAL-1", "trigger=convert,settlementPolicy=ACCRUED_ON_CONVERSION"));
        reversal.reverse("TRC-1", 7L, "E4-REFUND-TRC-1");
        reversal.reverse("ORD-2", 7L, "E4-REFUND-ORD-2");
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verifyNoInteractions(earningsMapper, earnings, ledger, audit);
    }

    @Test
    void theRefundOnlyRecordsAPendingObligationAndRecoversAfterItCommits() {
        fullTermConversion();
        reversal.reverse("TRC-1", 7L, "E4-REFUND-TRC-1");

        assertThat(audited()).extracting(AuditLogWriteRequest::getAction).containsExactly("H2_TRIAL_REFUND_REWARD_PENDING");
        verifyNoInteractions(earnings, ledger);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    }

    @Test
    void unspentEarlyRewardsAreRecoveredInFullEachInItsOwnTransaction() {
        fullTermConversion();
        when(earnings.recoverReward(any(), anyString())).thenAnswer(invocation -> {
            RewardRecoveryRequest request = invocation.getArgument(0);
            return receipt(request.asset(), request.amount(), request.amount());
        });

        reverseAndCommit();

        verify(earnings).recoverReward(eq(new RewardRecoveryRequest(7L, "ER-USDT", "H2_TRIAL_REMAINDER",
                "TRIAL-1:REMAINDER", "USDT", "PRODUCTION", new BigDecimal("65.560000"),
                "Trial early-conversion reward reversed with order refund TRC-1", "E4_ORDER_REFUND")),
                eq("TRIAL-REFUND-RECOVER:TRC-1:USDT"));
        verify(ledger).postLedgerEntry("TRIAL-1:REMAINDER:REVERSAL", 7L, "TRIAL_BONUS_REVERSAL", "USDT", "OUT",
                new BigDecimal("65.560000"), "SUCCESS", "Trial reward reversed with refund E4-REFUND-TRC-1");
        verify(ledger).postLedgerEntry("TRIAL-1:NEX:REVERSAL", 7L, "TRIAL_BONUS_REVERSAL", "UVT", "OUT",
                new BigDecimal("30.000000"), "SUCCESS", "Trial reward reversed with refund E4-REFUND-TRC-1");
        // Two recoveries plus the outcome audit, each in a new transaction after the refund commit.
        verify(transactions, times(3)).commit(any());
        assertThat(audited()).extracting(AuditLogWriteRequest::getAction)
                .containsExactly("H2_TRIAL_REFUND_REWARD_PENDING", "H2_TRIAL_REFUND_REWARD_REVERSED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void spentRewardsAreNeverForcedNegativeAndGoToManualReview() {
        fullTermConversion();
        when(earnings.recoverReward(any(), eq("TRIAL-REFUND-RECOVER:TRC-1:USDT")))
                .thenReturn(receipt("USDT", new BigDecimal("65.560000"), new BigDecimal("20.000000")));
        when(earnings.recoverReward(any(), eq("TRIAL-REFUND-RECOVER:TRC-1:NEX")))
                .thenReturn(receipt("UVT", new BigDecimal("30.000000"), BigDecimal.ZERO.setScale(6)));

        reverseAndCommit();

        verify(ledger).postLedgerEntry(eq("TRIAL-1:REMAINDER:REVERSAL"), eq(7L), anyString(), eq("USDT"), eq("OUT"),
                eq(new BigDecimal("20.000000")), anyString(), anyString());
        verify(ledger, never()).postLedgerEntry(eq("TRIAL-1:NEX:REVERSAL"), anyLong(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString());
        AuditLogWriteRequest outcome = audited().get(1);
        assertThat(outcome.getAction()).isEqualTo("H2_TRIAL_REFUND_REWARD_MANUAL_REVIEW");
        assertThat(outcome.getResult()).isEqualTo("MANUAL_REVIEW");
        List<Map<String, Object>> rewards = (List<Map<String, Object>>) ((Map<String, Object>) outcome.getDetail())
                .get("rewards");
        assertThat(rewards).extracting(row -> row.get("outstanding"))
                .containsExactly(new BigDecimal("45.560000"), new BigDecimal("30.000000"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedRecoveryNeverReachesTheRefundAndTheOtherRewardStillReverses() {
        fullTermConversion();
        when(earnings.recoverReward(any(), eq("TRIAL-REFUND-RECOVER:TRC-1:USDT")))
                .thenThrow(new BizException(409, "EARNINGS_RECOVERY_ACCOUNT_MISSING"));
        when(earnings.recoverReward(any(), eq("TRIAL-REFUND-RECOVER:TRC-1:NEX")))
                .thenReturn(receipt("UVT", new BigDecimal("30.000000"), new BigDecimal("30.000000")));

        reverseAndCommit();

        verify(transactions).rollback(any());
        verify(ledger).postLedgerEntry(eq("TRIAL-1:NEX:REVERSAL"), eq(7L), anyString(), eq("UVT"), eq("OUT"),
                eq(new BigDecimal("30.000000")), anyString(), anyString());
        AuditLogWriteRequest outcome = audited().get(1);
        assertThat(outcome.getAction()).isEqualTo("H2_TRIAL_REFUND_REWARD_MANUAL_REVIEW");
        List<Map<String, Object>> rewards = (List<Map<String, Object>>) ((Map<String, Object>) outcome.getDetail())
                .get("rewards");
        assertThat(rewards.get(0)).containsEntry("asset", "USDT").containsEntry("error", "EARNINGS_RECOVERY_ACCOUNT_MISSING");
        assertThat(rewards.get(1)).containsEntry("asset", "UVT").containsEntry("outstanding", new BigDecimal("0.000000"));
    }

    private void reverseAndCommit() {
        reversal.reverse("TRC-1", 7L, "E4-REFUND-TRC-1");
        List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        callbacks.forEach(TransactionSynchronization::afterCommit);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void fullTermConversion() {
        when(mapper.lockTrialConversion("TRC-1", 7L)).thenReturn(new E4OrderRefundMapper.TrialConversion(
                "TRIAL-1", "trigger=convert,orderNo=TRC-1,settlementPolicy=FULL_TERM_ON_CONVERSION,quoteVersion=x"));
        when(earningsMapper.findBySource("H2_TRIAL_REMAINDER", "TRIAL-1:REMAINDER", 7L)).thenReturn(
                new EarningsReleaseMapper.ExistingEntry("ER-USDT", 7L, "H2_TRIAL_REMAINDER", "TRIAL-1:REMAINDER",
                        "USDT", new BigDecimal("65.560000"), "ACTIVE", "k1", "PRODUCTION", 0));
        when(earningsMapper.findBySource("H2_TRIAL_BONUS", "TRIAL-1:NEX", 7L)).thenReturn(
                new EarningsReleaseMapper.ExistingEntry("ER-NEX", 7L, "H2_TRIAL_BONUS", "TRIAL-1:NEX",
                        "UVT", new BigDecimal("30.000000"), "ACTIVE", "k2", "PRODUCTION", 0));
    }

    private static RewardRecoveryReceipt receipt(String asset, BigDecimal requested, BigDecimal recovered) {
        return new RewardRecoveryReceipt("ERC-" + asset, "ER-" + asset, 7L, asset, requested, recovered,
                requested.subtract(recovered), recovered, requested.subtract(recovered), "withdrawable",
                BigDecimal.ZERO, "TRACKED_CONSERVATIVE");
    }

    private List<AuditLogWriteRequest> audited() {
        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(audit, org.mockito.Mockito.atLeastOnce()).recordRequiredForTrustedActor(captor.capture());
        return captor.getAllValues();
    }
}
