package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.finance.domain.TopupWalletSnapshot;
import ffdd.opsconsole.finance.facade.E4OrderRefundSettlementFacade;
import ffdd.opsconsole.finance.mapper.E4OrderRefundMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class E4OrderRefundSettlementFacadeAdapter implements E4OrderRefundSettlementFacade {
    private static final Set<String> CHANNELS = Set.of("WALLET", "ORIGINAL_PAYMENT");
    private final E4OrderRefundMapper mapper;

    @Override
    public Settlement settle(
            String orderNo,
            Long userId,
            BigDecimal amount,
            String refundChannel,
            String reason,
            String operator,
            String idempotencyKey) {
        String channel = refundChannel == null ? "" : refundChannel.trim().toUpperCase(Locale.ROOT);
        if (!CHANNELS.contains(channel)) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "ORDER_REFUND_CHANNEL_INVALID");
        }
        if (userId == null || userId <= 0 || amount == null || amount.signum() <= 0) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "ORDER_REFUND_SETTLEMENT_INVALID");
        }
        if ("ORIGINAL_PAYMENT".equals(channel)) {
            throw new BizException(409, "ORDER_REFUND_PSP_NOT_AVAILABLE");
        }

        TopupWalletSnapshot wallet = mapper.lockWallet(userId);
        if (wallet == null) {
            throw new BizException(404, "USER_WALLET_NOT_FOUND");
        }
        BigDecimal normalizedAmount = amount.setScale(6, RoundingMode.HALF_UP);
        BigDecimal availableBefore = nz(wallet.usdtAvailable());
        BigDecimal cumulativeBefore = nz(wallet.cumulativeDepositUsdt());
        BigDecimal availableAfter = availableBefore.add(normalizedAmount);
        BigDecimal cumulativeAfter = cumulativeBefore.subtract(normalizedAmount).max(BigDecimal.ZERO);
        if (mapper.updateWallet(userId, availableAfter, cumulativeAfter, wallet.version()) != 1) {
            throw new BizException(409, "ORDER_REFUND_WALLET_CONFLICT");
        }

        String ledgerBizNo = "E4-REFUND-" + orderNo;
        String billNo = "E4-BILL-" + orderNo;
        String remark = "E4 order refund | orderNo=" + orderNo + " | operator=" + operator
                + " | reason=" + reason + " | key=" + idempotencyKey;
        if (mapper.insertLedger(userId, ledgerBizNo, normalizedAmount, availableAfter, remark) != 1
                || mapper.insertBill(userId, billNo, normalizedAmount) != 1) {
            throw new IllegalStateException("ORDER_REFUND_LEDGER_WRITE_FAILED");
        }
        mapper.markPaymentRefunded(orderNo, userId);
        return new Settlement(channel, ledgerBizNo, billNo, availableBefore, availableAfter,
                cumulativeBefore, cumulativeAfter);
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(6) : value;
    }
}
