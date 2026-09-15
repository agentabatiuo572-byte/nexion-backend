package ffdd.opsconsole.finance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import ffdd.opsconsole.shared.exception.BizException;

/** Locked quote: VND is rounded down, never more than the displayed USDT principal can purchase. */
public final class BankWithdrawalPricing {
    private BankWithdrawalPricing() {}
    public record Price(BigDecimal amount, BigDecimal fee, BigDecimal net, BigDecimal rate, BigDecimal vnd) {}
    public static Price calculate(BigDecimal requested, Map<String, Object> config) {
        try {
            if (requested == null || requested.signum() <= 0) throw new IllegalArgumentException();
            BigDecimal amount = requested.setScale(6, RoundingMode.UNNECESSARY);
            if (amount.compareTo(number(config, "minAmountUsd").max(new BigDecimal("20"))) < 0
                    || amount.compareTo(number(config, "maxAmountUsd")) > 0) throw new IllegalArgumentException();
            BigDecimal fee = amount.multiply(number(config, "feeRatePct")).movePointLeft(2)
                    .max(number(config, "feeMinUsd")).min(number(config, "feeMaxUsd")).setScale(6, RoundingMode.UP);
            BigDecimal net = amount.subtract(fee);
            BigDecimal rate = number(config, "baseRateVndPerUsdt")
                    .multiply(BigDecimal.ONE.subtract(number(config, "sellSpreadPct").movePointLeft(2))).setScale(6, RoundingMode.DOWN);
            BigDecimal vnd = net.multiply(rate).setScale(0, RoundingMode.DOWN);
            if (fee.signum() < 0 || net.signum() <= 0 || rate.signum() <= 0 || vnd.signum() <= 0) throw new IllegalArgumentException();
            return new Price(amount, fee, net, rate, vnd);
        } catch (RuntimeException ex) { throw new BizException(422, "BANK_WITHDRAWAL_AMOUNT_INVALID"); }
    }
    private static BigDecimal number(Map<String, Object> config, String field) { return new BigDecimal(String.valueOf(config.get(field))); }
}
