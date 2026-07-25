package ffdd.opsconsole.finance.dto;

import java.math.BigDecimal;

public record FxQuoteUpdateRequest(
        BigDecimal baseRateVndPerUsdt,
        BigDecimal buySpreadPct,
        Integer lockWindowMinutes,
        Long expectedVersion,
        String reason,
        String operator) {
}
