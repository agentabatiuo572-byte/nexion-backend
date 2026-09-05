package ffdd.opsconsole.finance.hdpay;

import com.fasterxml.jackson.databind.JsonNode;
import ffdd.opsconsole.shared.exception.BizException;
import org.springframework.stereotype.Service;

@Service
public class HdPayCallbackService {
    static final int PAID_STATUS = 3;

    private final HdPayGateway gateway;
    private final HdPayCallbackVerifier verifier;
    private final HdPayCallbackSettlementService settlement;

    public HdPayCallbackService(
            HdPayGateway gateway,
            HdPayCallbackVerifier verifier,
            HdPayCallbackSettlementService settlement) {
        this.gateway = gateway;
        this.verifier = verifier;
        this.settlement = settlement;
    }

    public String accept(JsonNode body) {
        HdPayCallbackVerifier.VerifiedCallback callback = verifier.verify(body);
        if (callback.orderStatus() != PAID_STATUS) {
            return settlement.observe(callback);
        }

        HdPayCallbackSettlementService.QueryClaim claim =
                settlement.claimForProviderQuery(callback);
        if (claim.disposition()
                == HdPayCallbackSettlementService.ClaimDisposition.ACKNOWLEDGED) {
            return "success";
        }
        if (claim.disposition()
                == HdPayCallbackSettlementService.ClaimDisposition.RETRY_LATER) {
            throw new BizException(503, "HDPAY_CALLBACK_QUERY_ALREADY_CLAIMED");
        }

        HdPayGateway.PayOrder confirmed;
        try {
            confirmed = gateway.queryPayOrder(callback.merchantOrderId());
        } catch (HdPayGatewayException ex) {
            settlement.releaseProviderQueryClaim(
                    claim.fact(), claim.claimToken(), null, "HDPAY_CALLBACK_QUERY_UNAVAILABLE");
            throw new BizException(503, "HDPAY_CALLBACK_QUERY_UNAVAILABLE");
        }
        if (confirmed.orderStatus() != PAID_STATUS) {
            settlement.releaseProviderQueryClaim(
                    claim.fact(), claim.claimToken(), confirmed.orderStatus(),
                    "HDPAY_CALLBACK_QUERY_NOT_PAID");
            throw new BizException(503, "HDPAY_CALLBACK_QUERY_NOT_PAID");
        }
        if (!callback.merchantOrderId().equals(confirmed.merchantOrderId())
                || !callback.orderId().equals(confirmed.providerOrderId())
                || callback.transAmt().compareTo(confirmed.transAmt()) != 0
                || !"BANKQR".equalsIgnoreCase(confirmed.payType())) {
            settlement.reviewProviderQueryClaim(
                    claim.fact(), claim.claimToken(), "HDPAY_CALLBACK_QUERY_MISMATCH");
            return "success";
        }
        try {
            return settlement.settleConfirmed(claim.fact(), claim.claimToken(), confirmed);
        } catch (RuntimeException ex) {
            settlement.releaseProviderQueryClaim(
                    claim.fact(), claim.claimToken(), confirmed.orderStatus(),
                    "HDPAY_SETTLEMENT_RETRY_REQUIRED");
            throw ex;
        }
    }
}
