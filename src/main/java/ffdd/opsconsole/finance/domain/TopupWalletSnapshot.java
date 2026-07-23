package ffdd.opsconsole.finance.domain;

import java.math.BigDecimal;

public record TopupWalletSnapshot(
        Long userId,
        BigDecimal usdtAvailable,
        BigDecimal cumulativeDepositUsdt,
        Long version) {
}
