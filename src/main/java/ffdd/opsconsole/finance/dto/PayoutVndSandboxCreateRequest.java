package ffdd.opsconsole.finance.dto;

import java.math.BigDecimal;

public record PayoutVndSandboxCreateRequest(
        Long userId, BigDecimal amountVnd, String bankCode, String accountNo, String accountName, String reason) { }
