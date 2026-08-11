package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record AppCapacityReplaceSubmitRequest(
        Long sourceDeviceId,
        String targetProductNo,
        BigDecimal expectedPayableUsdt) {
}
