package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record AppCapacityKeepSubmitResponse(
        String operationNo,
        String orderNo,
        Long targetDeviceId,
        String deviceStatus,
        String orderStatus,
        BigDecimal walletDebitUsdt,
        BigDecimal walletBalanceAfterUsdt) {
}
