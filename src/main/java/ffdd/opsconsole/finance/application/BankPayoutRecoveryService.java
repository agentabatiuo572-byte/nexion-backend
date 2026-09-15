package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.hdpay.HdPayPayoutDigest;
import ffdd.opsconsole.finance.hdpay.HdPayPayoutGateway;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Query only: this command has no access to create/dispatch and cannot override conflicting evidence. */
@Service @RequiredArgsConstructor
public class BankPayoutRecoveryService {
    private final HdPayPayoutGateway gateway;
    private final HdPayPayoutTransactions transactions;
    private final AdminIdempotencyService idempotency;
    private final ffdd.opsconsole.finance.mapper.BankWithdrawalMapper bank;

    public String recover(String orderNo, String key, Long expectedVersion, String reason) {
        if (orderNo == null || !orderNo.matches("WD-[A-Za-z0-9]{1,60}") || expectedVersion == null || expectedVersion < 0
                || key == null || key.isBlank() || key.length() > 128 || reason == null
                || reason.trim().length() < 10 || reason.length() > 300)
            throw new BizException(422, "BANK_PAYOUT_RECOVERY_REQUEST_INVALID");
        String actor = AdminActorResolver.resolve(null);
        String hash = HdPayPayoutDigest.sha(orderNo + "|" + expectedVersion + "|" + reason.trim());
        String scope = "BANK_REQUERY:" + actor;
        var receipt = idempotency.recoveryResult(scope, key, hash, String.class);
        if (receipt.status() == AdminIdempotencyService.RecoveryStatus.SUCCEEDED) return receipt.response();
        if (receipt.status() == AdminIdempotencyService.RecoveryStatus.MISMATCH
                || receipt.status() == AdminIdempotencyService.RecoveryStatus.PROCESSING
                || receipt.status() == AdminIdempotencyService.RecoveryStatus.UNKNOWN)
            throw new BizException(409, "BANK_PAYOUT_RECOVERY_IN_PROGRESS_OR_CONFLICT");
        var order = bank.order(orderNo);
        if (order == null) throw new BizException(404, "BANK_WITHDRAWAL_NOT_FOUND");
        if (!"MANUAL_REVIEW".equals(order.state()) || !expectedVersion.equals(bank.version(orderNo)))
            throw new BizException(409, "BANK_PAYOUT_RECOVERY_STALE");
        // Bounded read-only HTTP is outside the business transaction. Even repeats only query the original order.
        var result = gateway.query(orderNo);
        return idempotency.executeRetained(scope, key, hash, String.class,
                () -> transactions.recover(orderNo, expectedVersion, result, actor, reason.trim()));
    }
}
