package ffdd.opsconsole.finance.domain;

import java.math.BigDecimal;

public record DepositCardRiskParamView(
        String key,
        String name,
        String value,
        String note,
        BigDecimal numericValue,
        String unit,
        BigDecimal minValue,
        BigDecimal maxValue) {
}
