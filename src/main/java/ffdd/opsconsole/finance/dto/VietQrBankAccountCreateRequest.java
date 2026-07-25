package ffdd.opsconsole.finance.dto;

import java.math.BigDecimal;

public record VietQrBankAccountCreateRequest(
        String bankCode,
        String bankName,
        String accountHolder,
        String accountNumber,
        BigDecimal dailyCapVnd,
        String reason,
        String operator) {
}
