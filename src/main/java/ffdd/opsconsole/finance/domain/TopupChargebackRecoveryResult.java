package ffdd.opsconsole.finance.domain;

import java.math.BigDecimal;

public record TopupChargebackRecoveryResult(
        BigDecimal recoveredAmount,
        BigDecimal walletShortfall,
        BigDecimal feeBufferDeducted,
        BigDecimal feeBufferShortfall,
        String status,
        String ledgerBizNo,
        String riskSignalNo) {
}
