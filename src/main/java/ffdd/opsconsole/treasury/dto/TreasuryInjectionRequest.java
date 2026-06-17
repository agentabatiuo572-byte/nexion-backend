package ffdd.opsconsole.treasury.dto;

import java.math.BigDecimal;

public record TreasuryInjectionRequest(
        BigDecimal amount,
        String voucherNo,
        String reason,
        String operator) {
}
