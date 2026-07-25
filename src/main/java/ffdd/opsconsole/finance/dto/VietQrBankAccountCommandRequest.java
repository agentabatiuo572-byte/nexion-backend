package ffdd.opsconsole.finance.dto;

import java.math.BigDecimal;

public record VietQrBankAccountCommandRequest(
        String action,
        BigDecimal dailyCapVnd,
        Long expectedVersion,
        String reason,
        String operator) {
}
