package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record AppTradeinSubmitRequest(
        Long sourceDeviceId,
        Long targetProductId,
        String targetProductNo,
        BigDecimal expectedPayableUsdt,
        BigDecimal expectedDiscountUsdt,
        Long expectedSourceVersion) {
    public AppTradeinSubmitRequest(Long sourceDeviceId, Long targetProductId, String targetProductNo,
                                   BigDecimal expectedPayableUsdt, BigDecimal expectedDiscountUsdt) {
        this(sourceDeviceId, targetProductId, targetProductNo, expectedPayableUsdt, expectedDiscountUsdt, 0L);
    }
    public AppTradeinSubmitRequest(Long sourceDeviceId, Long targetProductId) {
        this(sourceDeviceId, targetProductId, null, null, null);
    }

    public AppTradeinSubmitRequest(Long sourceDeviceId, Long targetProductId, String targetProductNo) {
        this(sourceDeviceId, targetProductId, targetProductNo, null, null);
    }
}
