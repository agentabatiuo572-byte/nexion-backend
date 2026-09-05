package ffdd.opsconsole.finance.hdpay;

import java.time.Clock;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers verified paid callbacks whose synchronous provider query did not finish. */
@Slf4j
@Component
public class HdPayCallbackRecoveryScheduler {
    private static final int BATCH_SIZE = 20;
    private static final long CLAIM_STALE_SECONDS = 10;

    private final HdPayProperties properties;
    private final HdPayGateway gateway;
    private final HdPayCallbackSettlementService settlement;
    private final Clock clock;

    public HdPayCallbackRecoveryScheduler(
            HdPayProperties properties,
            HdPayGateway gateway,
            HdPayCallbackSettlementService settlement,
            Clock clock) {
        this.properties = properties;
        this.gateway = gateway;
        this.settlement = settlement;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${nexion.finance.hdpay.callback-recovery-delay-ms:5000}")
    public void recover() {
        if (!properties.providerMode() || !properties.ready()) return;
        LocalDateTime staleBefore = LocalDateTime.now(clock).minusSeconds(CLAIM_STALE_SECONDS);
        for (HdPayCallbackSettlementService.PaidCallbackFact fact
                : settlement.listRecoverablePaidCallbacks(staleBefore, BATCH_SIZE)) {
            String claimToken = settlement.claimStoredCallbackForRetry(fact.payloadHash(), staleBefore);
            if (claimToken == null) continue;
            recoverClaim(fact, claimToken);
        }
    }

    private void recoverClaim(
            HdPayCallbackSettlementService.PaidCallbackFact fact,
            String claimToken) {
        HdPayGateway.PayOrder confirmed;
        try {
            confirmed = gateway.queryPayOrder(fact.merchantOrderId());
        } catch (HdPayGatewayException ex) {
            release(fact, claimToken, null, "HDPAY_CALLBACK_QUERY_UNAVAILABLE");
            return;
        }
        if (confirmed.orderStatus() != HdPayCallbackService.PAID_STATUS) {
            release(fact, claimToken, confirmed.orderStatus(), "HDPAY_CALLBACK_QUERY_NOT_PAID");
            return;
        }
        if (!fact.merchantOrderId().equals(confirmed.merchantOrderId())
                || !fact.providerOrderId().equals(confirmed.providerOrderId())
                || fact.transAmt().compareTo(confirmed.transAmt()) != 0
                || !"BANKQR".equalsIgnoreCase(confirmed.payType())) {
            try {
                settlement.reviewProviderQueryClaim(
                        fact, claimToken, "HDPAY_CALLBACK_QUERY_MISMATCH");
            } catch (RuntimeException ex) {
                log.error("HDPay callback recovery review failed code=HDPAY_RECOVERY_REVIEW_FAILED");
            }
            return;
        }
        try {
            settlement.settleConfirmed(fact, claimToken, confirmed);
        } catch (RuntimeException ex) {
            release(fact, claimToken, confirmed.orderStatus(),
                    "HDPAY_SETTLEMENT_RETRY_REQUIRED");
        }
    }

    private void release(
            HdPayCallbackSettlementService.PaidCallbackFact fact,
            String claimToken,
            Integer providerStatus,
            String resultCode) {
        try {
            settlement.releaseProviderQueryClaim(fact, claimToken, providerStatus, resultCode);
        } catch (RuntimeException ex) {
            log.error("HDPay callback recovery release failed code=HDPAY_RECOVERY_RELEASE_FAILED");
        }
    }
}
