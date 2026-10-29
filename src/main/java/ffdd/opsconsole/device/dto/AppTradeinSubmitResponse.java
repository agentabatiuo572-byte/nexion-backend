package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record AppTradeinSubmitResponse(
        String tradeinNo,
        String orderNo,
        Long sourceDeviceId,
        Long targetDeviceId,
        String applicationStatus,
        String orderStatus,
        BigDecimal discountUsdt,
        BigDecimal walletDebitUsdt,
        BigDecimal walletBalanceAfterUsdt,
        String targetDeviceStatus) {
    public AppTradeinSubmitResponse(String tradeinNo, String orderNo, Long sourceDeviceId, Long targetDeviceId,
                                    String applicationStatus, String orderStatus, BigDecimal discountUsdt,
                                    BigDecimal walletDebitUsdt, BigDecimal walletBalanceAfterUsdt) {
        this(tradeinNo, orderNo, sourceDeviceId, targetDeviceId, applicationStatus, orderStatus,
                discountUsdt, walletDebitUsdt, walletBalanceAfterUsdt, "ACTIVE");
    }
}
