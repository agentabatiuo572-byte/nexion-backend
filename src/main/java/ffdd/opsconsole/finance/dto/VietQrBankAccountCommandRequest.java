package ffdd.opsconsole.finance.dto;

import java.math.BigDecimal;

/**
 * D1 收款账户命令。REPROVISION 是唯一需要重录账户明文的动作,其余动作只带
 * action/dailyCapVnd/expectedVersion/reason/operator。
 */
public record VietQrBankAccountCommandRequest(
        String action,
        BigDecimal dailyCapVnd,
        Long expectedVersion,
        String reason,
        String operator,
        String bankCode,
        String bankName,
        String accountHolder,
        String accountNumber) {

    public VietQrBankAccountCommandRequest(
            String action,
            BigDecimal dailyCapVnd,
            Long expectedVersion,
            String reason,
            String operator) {
        this(action, dailyCapVnd, expectedVersion, reason, operator, null, null, null, null);
    }
}
