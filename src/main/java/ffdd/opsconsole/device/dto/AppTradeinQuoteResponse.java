package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record AppTradeinQuoteResponse(
        Long sourceDeviceId,
        String sourceProductName,
        Long targetProductId,
        String targetProductNo,
        String targetProductName,
        BigDecimal sourceActualPaidUsdt,
        BigDecimal cumulativeOutputUsdt,
        BigDecimal outputRatioPct,
        BigDecimal creditRatePct,
        BigDecimal discountUsdt,
        BigDecimal targetPriceUsdt,
        BigDecimal payableUsdt,
        BigDecimal walletBalanceUsdt,
        BigDecimal walletShortfallUsdt,
        boolean sufficientFunds,
        boolean discountToWallet,
        String pricingSource) {
}
