package ffdd.opsconsole.finance.dto;

import java.math.BigDecimal;

public record PayoutVndConfigUpdateRequest(
        BigDecimal sellSpreadPct,
        Integer quoteTtlMinWithdraw,
        BigDecimal requoteTolerancePct,
        BigDecimal feeRatePct,
        BigDecimal feeMinUsd,
        BigDecimal feeMaxUsd,
        BigDecimal minAmountUsd,
        BigDecimal maxAmountUsd,
        Long expectedVersion,
        String reason,
        Boolean forceInverted) {
}
