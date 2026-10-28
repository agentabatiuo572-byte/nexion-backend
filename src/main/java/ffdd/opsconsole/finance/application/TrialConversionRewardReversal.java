package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.mapper.E4OrderRefundMapper;
import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FEAT-TRIAL05 refund leg. An order converted under FULL_TERM_ON_CONVERSION paid its trial rewards
 * up front, so a refund also takes those rewards back, through the same conservative recovery
 * primitive promotions use: only the part the wallet can prove unspent is debited, never into a
 * negative balance and never from other income.
 *
 * <p>The cash refund never waits on that recovery. Inside the refund transaction this only writes a
 * durable PENDING audit; each reward is recovered after the refund commits, in its own transaction
 * and fund-journal operation (recovery opens its own capture, which must not nest inside the
 * refund's). Anything not recovered, including a recovery that fails, ends as a MANUAL_REVIEW audit
 * with the outstanding amounts. A PENDING audit without a terminal one marks an interrupted run.
 * Orders converted under the accrued rule keep the refund behaviour they were sold with.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrialConversionRewardReversal {
    /** Snapshot token written by AppTrialLifecycleService for FULL_TERM_POLICY conversions. */
    static final String FULL_TERM_POLICY = "settlementPolicy=FULL_TERM_ON_CONVERSION";
    private static final String ACTOR = "E4_ORDER_REFUND";
    private static final List<String[]> SOURCES = List.of(
            new String[] {"H2_TRIAL_REMAINDER", ":REMAINDER", "USDT"},
            new String[] {"H2_TRIAL_BONUS", ":NEX", "UVT"});

    private final E4OrderRefundMapper mapper;
    private final EarningsReleaseMapper earningsMapper;
    private final EarningsReleaseService earnings;
    private final TreasuryLedgerPostingFacade ledger;
    private final AuditLogService audit;
    private final PlatformTransactionManager transactionManager;

    /** Called inside the refund transaction: records the obligation and schedules the recovery. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void reverse(String orderNo, Long userId, String refundLedgerBizNo) {
        E4OrderRefundMapper.TrialConversion trial = mapper.lockTrialConversion(orderNo, userId);
        if (trial == null || trial.settlementSnapshot() == null
                || !List.of(trial.settlementSnapshot().split(",")).contains(FULL_TERM_POLICY)) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orderNo", orderNo);
        detail.put("claimNo", trial.claimNo());
        detail.put("refundLedgerBizNo", refundLedgerBizNo);
        record("H2_TRIAL_REFUND_REWARD_PENDING", "PENDING", trial.claimNo(), orderNo, userId, detail);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                recoverAfterRefund(orderNo, userId, trial.claimNo(), refundLedgerBizNo);
            }
        });
    }

    void recoverAfterRefund(String orderNo, Long userId, String claimNo, String refundLedgerBizNo) {
        List<Map<String, Object>> rewards = new ArrayList<>();
        boolean manual = false;
        for (String[] source : SOURCES) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("asset", source[2]);
            try {
                Map<String, Object> result = newTransaction().execute(status ->
                        recoverOne(orderNo, userId, claimNo, refundLedgerBizNo, source));
                if (result == null) continue;
                row.putAll(result);
                manual |= ((BigDecimal) result.get("outstanding")).signum() > 0;
            } catch (RuntimeException failure) {
                log.error("Trial reward recovery failed after refund {} ({})", orderNo, source[2], failure);
                row.put("error", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
                manual = true;
            }
            rewards.add(row);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orderNo", orderNo);
        detail.put("claimNo", claimNo);
        detail.put("refundLedgerBizNo", refundLedgerBizNo);
        detail.put("rewards", rewards);
        detail.put("disposition", manual ? "MANUAL_REVIEW" : "REVERSED");
        boolean needsReview = manual;
        try {
            newTransaction().executeWithoutResult(status -> record(
                    needsReview ? "H2_TRIAL_REFUND_REWARD_MANUAL_REVIEW" : "H2_TRIAL_REFUND_REWARD_REVERSED",
                    needsReview ? "MANUAL_REVIEW" : "SUCCESS", claimNo, orderNo, userId, detail));
        } catch (RuntimeException failure) {
            // The PENDING audit without a terminal record still flags this refund for review.
            log.error("Trial reward recovery outcome for refund {} could not be audited: {}", orderNo, detail, failure);
        }
    }

    /** One reward entry in one transaction: recovery receipt, its fund operation and the reversal posting. */
    private Map<String, Object> recoverOne(
            String orderNo, Long userId, String claimNo, String refundLedgerBizNo, String[] source) {
        String sourceRef = claimNo + source[1];
        var entry = earningsMapper.findBySource(source[0], sourceRef, userId);
        if (entry == null) return null;
        var receipt = earnings.recoverReward(new EarningsReleaseService.RewardRecoveryRequest(
                userId, entry.entryNo(), source[0], sourceRef, source[2], "PRODUCTION", entry.amount(),
                "Trial early-conversion reward reversed with order refund " + orderNo, ACTOR),
                "TRIAL-REFUND-RECOVER:" + orderNo + ":" + ("USDT".equals(source[2]) ? "USDT" : "NEX"));
        if (receipt.recovered().signum() > 0) {
            ledger.postLedgerEntry(sourceRef + ":REVERSAL", userId, "TRIAL_BONUS_REVERSAL", source[2], "OUT",
                    receipt.recovered(), "SUCCESS", "Trial reward reversed with refund " + refundLedgerBizNo);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entryNo", entry.entryNo());
        row.put("recoveryNo", receipt.recoveryNo());
        row.put("issued", entry.amount());
        row.put("recovered", receipt.recovered());
        row.put("outstanding", receipt.outstanding());
        return row;
    }

    /** afterCommit still sees the finished transaction's resources; work there must open its own. */
    private TransactionTemplate newTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private void record(String action, String result, String claimNo, String orderNo, Long userId,
                        Map<String, Object> detail) {
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder()
                .action(action).resourceType("TRIAL_CLAIM").resourceId(claimNo).bizNo(orderNo)
                .userId(userId).actorUsername(ACTOR).result(result).riskLevel("HIGH").detail(detail).build());
    }
}
