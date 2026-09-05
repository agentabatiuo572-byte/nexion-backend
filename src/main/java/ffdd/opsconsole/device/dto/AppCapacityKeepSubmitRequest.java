package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record AppCapacityKeepSubmitRequest(
        String targetProductNo,
        BigDecimal expectedPayableUsdt) {
}
