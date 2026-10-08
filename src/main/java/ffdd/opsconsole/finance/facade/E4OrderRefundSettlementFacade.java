package ffdd.opsconsole.finance.facade;

import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import java.math.BigDecimal;

public interface E4OrderRefundSettlementFacade {
    Settlement settle(
            String orderNo,
            Long userId,
            BigDecimal amount,
            String refundChannel,
            String reason,
            String operator,
            String idempotencyKey,
            SupportPaymentAttributionFacade.Prepared prepared);

    record Settlement(
            String channel,
            String ledgerBizNo,
            String billNo,
            BigDecimal walletBefore,
            BigDecimal walletAfter,
            BigDecimal cumulativeDepositBefore,
            BigDecimal cumulativeDepositAfter) {
    }
}
