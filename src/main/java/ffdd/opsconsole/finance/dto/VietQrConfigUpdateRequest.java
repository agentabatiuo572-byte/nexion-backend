package ffdd.opsconsole.finance.dto;

import java.math.BigDecimal;

public record VietQrConfigUpdateRequest(
        BigDecimal toleranceVnd,
        Integer graceMinutes,
        BigDecimal perTxLimitUsd,
        Integer trc20Confirmations,
        Integer erc20Confirmations,
        Integer bep20Confirmations,
        String rotationStrategy,
        Long expectedVersion,
        String reason,
        String operator) {
}
