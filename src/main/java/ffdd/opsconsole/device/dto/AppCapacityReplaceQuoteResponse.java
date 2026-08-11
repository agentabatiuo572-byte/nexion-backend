package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record AppCapacityReplaceQuoteResponse(
        String decision,
        Integer activeDevices,
        Integer maxActiveDevices,
        Long sourceDeviceId,
        String sourceDeviceName,
        Long targetProductId,
        String targetProductNo,
        String targetProductName,
        BigDecimal targetPriceUsdt,
        BigDecimal payableUsdt,
        BigDecimal walletBalanceUsdt,
        boolean sufficientFunds,
        String decisionSource) {
}
