package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.hdpay.*;
import ffdd.opsconsole.finance.mapper.*;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Short DB transactions only. Outbound HTTP lives in the scheduler, after durable dispatch intent. */
@Service @RequiredArgsConstructor
public class HdPayPayoutTransactions {
    private final BankWithdrawalMapper bank;
    private final AppWithdrawalMapper users;
    private final WithdrawalPayoutMapper canonical;
    private final WithdrawalPayoutFinalizer finalizer;
    private final FinanceSensitiveDataCipher cipher;
    private final HdPayProperties transport;
    private final HdPayPayoutProperties properties;
    private final PayoutVndConfigService config;
    private final OpsFinanceService finance;
    private final AuditLogService audit;
    private final EventOutboxService outbox;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public HdPayPayoutGateway.Request prepare(String orderNo) {
        var read = bank.order(orderNo);
        if (read == null || users.lockActiveUser(read.userId()) == null || Integer.valueOf(1).equals(users.isSandboxUser(read.userId()))) return null;
        var order = bank.lockOrder(orderNo);
        if (order == null || !"READY".equals(order.state()) || !properties.ready(transport)) return null;
        var gate = config.overview();
        if (gate.getCode() != 0
                || !Boolean.TRUE.equals(gate.getData().get("providerReady"))) return null;
        String block = finance.bankPayoutDispatchBlockReason(orderNo);
        if (block != null) {
            if (block.equals("BANK_PAYOUT_RISK_REVIEW_REQUIRED")) {
                if (bank.returnForReview(orderNo, LocalDateTime.now(clock), block) == 1)
                    record(order, "BANK_PAYOUT_RISK_REVIEW_REQUIRED", Map.of("reason", block));
            }
            return null;
        }
        var quote = bank.quote(order.quoteNo());
        if (!snapshotMatches(order, quote, canonical.payout(orderNo)))
            throw new BizException(409, "BANK_PAYOUT_SNAPSHOT_MISMATCH");
        var beneficiary = bank.lockBeneficiary(order.userId());
        LocalDateTime now = LocalDateTime.now(clock);
        if (BankWithdrawalEligibility.quoteBlock(beneficiary, quote) != null) return null;
        String[] recipient = recipient(quote);
        // The gateway uses deployment-owned egress IP; client_ip remains original user audit evidence.
        var request = new HdPayPayoutGateway.Request(orderNo, quote.amountVnd(), "", recipient[0], recipient[1]);
        if (bank.processing(orderNo, now) != 1 || bank.dispatch(orderNo, now) != 1) throw new BizException(409, "BANK_PAYOUT_CLAIM_CONFLICT");
        record(order, "BANK_PAYOUT_DISPATCH_INTENT", Map.of("amountVnd", quote.amountVnd(), "bankCode", "", "payType", HdPayPayoutProperties.PAY_TYPE,
                "serverIp", properties.serverIp(transport)));
        return request;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void reconcile(String orderNo, HdPayPayoutGateway.Order response) {
        var read = bank.order(orderNo);
        if (read == null) return;
        // Wallet mutex first, shared with withdrawal submit/binding. Reconciliation also works after user suspension.
        users.lockWallet(read.userId());
        var order = bank.lockOrder(orderNo);
        if (order == null || "READY".equals(order.state()) || "MANUAL_REVIEW".equals(order.state())) return;
        reconcileLocked(order, response, false);
    }

    /** Called only by the privileged query-only recovery command, never by a generic refund action. */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public String recover(String orderNo, long expectedVersion, HdPayPayoutGateway.Order response, String actor, String reason) {
        var read = bank.order(orderNo);
        if (read == null) throw new BizException(404, "BANK_WITHDRAWAL_NOT_FOUND");
        users.lockWallet(read.userId());
        var order = bank.lockOrder(orderNo);
        if (order == null || !"MANUAL_REVIEW".equals(order.state()) || !Long.valueOf(expectedVersion).equals(bank.version(orderNo)))
            throw new BizException(409, "BANK_PAYOUT_RECOVERY_STALE");
        reconcileLocked(order, response, true);
        String state = bank.order(orderNo).state();
        audit.recordRequired(AuditLogWriteRequest.builder().action("BANK_PAYOUT_QUERY_RECOVERY").resourceType("WITHDRAWAL")
                .resourceId(orderNo).bizNo(orderNo).userId(order.userId()).actorType("ADMIN").actorUsername(actor)
                .riskLevel("CRITICAL").result("SUCCESS").detail(Map.of("reason", reason, "expectedVersion", expectedVersion,
                        "providerStatus", response.status(), "resultState", state)).build());
        return state;
    }

    private void reconcileLocked(BankWithdrawalMapper.Order order, HdPayPayoutGateway.Order response, boolean recovering) {
        String orderNo = order.withdrawalNo();
        var quote = bank.quote(order.quoteNo());
        var withdrawal = canonical.payout(orderNo);
        if (!snapshotMatches(order, quote, withdrawal)) { hold(order, "BANK_PAYOUT_SNAPSHOT_MISMATCH"); return; }
        String[] recipient = recipient(quote);
        boolean mismatch = !orderNo.equals(response.merchantOrderId()) || quote.amountVnd().compareTo(response.amount()) != 0
                || !recipient[0].equals(response.account()) || !recipient[1].equals(response.holder()) || !"2".equals(response.type())
                || (order.providerOrderId() != null && order.providerOrderId() != response.providerOrderId())
                || bank.conflictingCallbacks(orderNo, response.providerOrderId(), response.amount(), response.status()) > 0;
        if (mismatch) { hold(order, "BANK_PAYOUT_EVIDENCE_CONFLICT"); return; }
        var outcome = HdPayPayoutOutcome.from(response.status());
        if (recovering) {
            if ("CONFIRMED".equals(withdrawal.status()) || "FAILED".equals(withdrawal.status())) {
                String settled = "CONFIRMED".equals(withdrawal.status()) ? "PAID" : "FAILED";
                if (settled.equals(outcome.name()) && "hdpay".equals(withdrawal.payoutSource())
                        && withdrawal.providerCid() != null && withdrawal.providerCid() == response.providerOrderId())
                    bank.progress(orderNo, settled, response.providerOrderId(), response.status(), null, LocalDateTime.now(clock));
                else hold(order, "BANK_PAYOUT_TERMINAL_CONFLICT");
                return; // Never change money already settled by a previous finalizer.
            }
            if (outcome == HdPayPayoutOutcome.PENDING) { hold(order, "BANK_PAYOUT_QUERY_STILL_PENDING"); return; }
            if (bank.resumeHeld(orderNo, LocalDateTime.now(clock)) != 1) {
                hold(order, "BANK_PAYOUT_CANONICAL_STATE_CONFLICT"); return;
            }
            withdrawal = canonical.payout(orderNo);
        }
        if ("PAID".equals(order.state()) || "FAILED".equals(order.state())) {
            if (!order.state().equals(outcome.name())) hold(order, "BANK_PAYOUT_TERMINAL_CONFLICT");
            return;
        }
        if (outcome == HdPayPayoutOutcome.MANUAL_REVIEW) { hold(order, "BANK_PAYOUT_RETURN_REVIEW_REQUIRED"); return; }
        if ("PROCESSING".equals(withdrawal.status())) {
            if (!finalizer.submitted(withdrawal, response.providerOrderId(), "hdpay")) throw new BizException(409, "BANK_PAYOUT_SUBMISSION_CONFLICT");
            withdrawal = canonical.payout(orderNo);
        }
        if (!"SENT".equals(withdrawal.status()) || !"hdpay".equals(withdrawal.payoutSource())
                || withdrawal.providerCid() == null || withdrawal.providerCid() != response.providerOrderId()) {
            hold(order, "BANK_PAYOUT_CANONICAL_STATE_CONFLICT"); return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (outcome == HdPayPayoutOutcome.PENDING) {
            bank.progress(orderNo, "PENDING", response.providerOrderId(), response.status(), null, now);
            return;
        }
        String status = outcome == HdPayPayoutOutcome.PAID ? "CONFIRMED" : "FAILED";
        String digest = HdPayPayoutDigest.sha(orderNo + "|" + response.providerOrderId() + "|" + status + "|" + quote.amountVnd());
        if (!finalizer.terminal(withdrawal, response.providerOrderId(), "hdpay", "HDP-" + digest.substring(0, 48), digest,
                status, null, outcome == HdPayPayoutOutcome.FAILED
                        ? response.status() == 4 ? "HDPAY_PAYOUT_RETURNED" : "HDPAY_PAYOUT_FAILED" : null)) throw new BizException(409, "BANK_PAYOUT_SETTLEMENT_CONFLICT");
        bank.progress(orderNo, outcome.name(), response.providerOrderId(), response.status(), null, now);
        outbox.publish("WITHDRAWAL", orderNo, outcome == HdPayPayoutOutcome.PAID ? "withdraw.confirmed" : "withdraw.refunded",
                BankWithdrawalService.map("withdrawal_id", orderNo, "user_id", order.userId(), "amount", quote.amountUsdt(),
                        "currency", "USDT", "amount_vnd", quote.amountVnd(), "state", status, "provider", "HDPAY"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public String accept(HdPayPayoutCallbackVerifier.Callback callback) {
        var order = bank.lockOrder(callback.merchantOrderId());
        if (order == null || "READY".equals(order.state())) throw new BizException(409, "BANK_PAYOUT_CALLBACK_ORDER_UNKNOWN");
        var quote = bank.quote(order.quoteNo());
        if (quote == null) throw new BizException(409, "BANK_PAYOUT_CALLBACK_SNAPSHOT_MISSING");
        bank.callback(callback);
        if (quote.amountVnd().compareTo(callback.amount()) != 0 ||
                (order.providerOrderId() != null && order.providerOrderId() != callback.providerOrderId())
                || ("PAID".equals(order.state()) && (callback.status() == 4 || callback.status() == 5))
                || ("FAILED".equals(order.state()) && callback.status() == 3)) {
            hold(order, "BANK_PAYOUT_CALLBACK_CONFLICT");
        } else if ("DISPATCHING".equals(order.state()) || "PENDING".equals(order.state())) {
            // Persist evidence, never settle directly from a callback. Query scheduler performs authoritative readback.
            bank.progress(order.withdrawalNo(), order.state(), null, order.providerStatus(), null, LocalDateTime.now(clock).minusMinutes(2));
        }
        return "success";
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void defer(String orderNo) {
        var order = bank.lockOrder(orderNo);
        if (order != null && ("DISPATCHING".equals(order.state()) || "PENDING".equals(order.state())))
            bank.progress(orderNo, order.state(), null, order.providerStatus(), "BANK_PAYOUT_QUERY_UNAVAILABLE", LocalDateTime.now(clock));
    }

    private void hold(BankWithdrawalMapper.Order order, String reason) {
        bank.progress(order.withdrawalNo(), "MANUAL_REVIEW", null, order.providerStatus(), reason, LocalDateTime.now(clock));
        bank.hold(order.withdrawalNo(), reason, LocalDateTime.now(clock));
        record(order, "BANK_PAYOUT_MANUAL_REVIEW_REQUIRED", Map.of("reason", reason));
    }
    private boolean snapshotMatches(BankWithdrawalMapper.Order order, BankWithdrawalMapper.Quote quote,
                                    WithdrawalPayoutMapper.PayoutRow withdrawal) {
        return quote != null && withdrawal != null && "BANK-VND".equals(withdrawal.chain())
                && order.withdrawalNo().equals(withdrawal.withdrawalNo()) && order.withdrawalNo().equals(quote.withdrawalNo())
                && order.quoteNo().equals(quote.quoteNo()) && order.userId().equals(quote.userId())
                && order.userId().equals(withdrawal.userId())
                && ("BANK-VND:" + quote.beneficiaryNo()).equals(withdrawal.targetAddress())
                && quote.amountUsdt().compareTo(withdrawal.amount()) == 0
                && quote.netUsdt().compareTo(withdrawal.netReceive()) == 0
                && (withdrawal.nexBurned() == null || withdrawal.nexBurned().signum() == 0)
                && quote.amountUsdt().compareTo(quote.netUsdt().add(quote.feeUsdt())) == 0
                && quote.amountVnd().compareTo(quote.netUsdt().multiply(quote.rateVnd()).setScale(0, java.math.RoundingMode.DOWN)) == 0;
    }
    private String[] recipient(BankWithdrawalMapper.Quote quote) {
        String[] recipient = cipher.decrypt(quote.recipientCipher(), BankWithdrawalService.quoteAad(quote.userId(), quote.quoteNo())).split("\n", -1);
        if (recipient.length != 2) throw new BizException(503, "BANK_PAYOUT_RECIPIENT_INVALID");
        HttpHdPayPayoutGateway.account(recipient[0]); HttpHdPayPayoutGateway.holder(recipient[1]);
        return recipient;
    }
    private void record(BankWithdrawalMapper.Order order, String action, Map<String, Object> detail) {
        audit.recordRequired(AuditLogWriteRequest.builder().action(action).resourceType("WITHDRAWAL").resourceId(order.withdrawalNo())
                .bizNo(order.withdrawalNo()).userId(order.userId()).actorType("SYSTEM").actorUsername("hdpay-payout")
                .riskLevel("CRITICAL").result("SUCCESS").detail(detail).build());
    }
}
