package ffdd.opsconsole.team.domain;

import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public record DirectReferralPolicy(long policyVersion, Instant effectiveAt, Rule purchase, Rule deviceEarning, int schemaVersion) {
    public DirectReferralPolicy(long version,Instant effective,Rule purchase,Rule earning){this(version,effective,purchase,earning,1);}
    public static final Rule DISABLED = new Rule(false, BigDecimal.ZERO, new BigDecimal("50"), 0);
    public record Amounts(BigDecimal usdt, BigDecimal nex) {
        public boolean payable() { return usdt.signum() > 0 && nex.signum() > 0; }
    }
    public record Rule(Boolean enabled, BigDecimal totalRatePct, BigDecimal usdtSharePct, Integer coolingDays) {
        private static final BigDecimal HUNDRED = new BigDecimal("100");
        public void validate() {
            if (enabled == null || totalRatePct == null || usdtSharePct == null || coolingDays == null
                    || totalRatePct.signum() < 0 || totalRatePct.compareTo(HUNDRED) > 0
                    || usdtSharePct.signum() < 0 || usdtSharePct.compareTo(HUNDRED) > 0
                    || totalRatePct.stripTrailingZeros().scale() > 6 || usdtSharePct.stripTrailingZeros().scale() > 6
                    || coolingDays < 0 || coolingDays > 365
                    || (enabled && (totalRatePct.signum() == 0 || usdtSharePct.signum() == 0 || usdtSharePct.compareTo(HUNDRED) == 0))) {
                throw new BizException(422, "DIRECT_REFERRAL_RULE_INVALID");
            }
        }
        public Amounts calculate(BigDecimal basis, BigDecimal price) {
            validate();
            if (price == null || price.signum() <= 0) throw new BizException(503, "DIRECT_REFERRAL_PRICE_UNAVAILABLE");
            if (basis == null || basis.signum() <= 0) throw new BizException(422, "DIRECT_REFERRAL_BASIS_INVALID");
            BigDecimal budget = basis.multiply(totalRatePct).movePointLeft(2);
            BigDecimal usdt = budget.multiply(usdtSharePct).movePointLeft(2).setScale(6, RoundingMode.DOWN);
            BigDecimal nex = budget.multiply(HUNDRED.subtract(usdtSharePct)).movePointLeft(2)
                    .divide(price, 6, RoundingMode.DOWN);
            if (usdt.precision() - usdt.scale() > 12 || nex.precision() - nex.scale() > 12) {
                throw new BizException(422, "DIRECT_REFERRAL_AMOUNT_OVERFLOW");
            }
            return new Amounts(usdt, nex);
        }
        public boolean amplifies(Rule before) {
            if (!Boolean.TRUE.equals(enabled)) return false;
            if (before == null || !Boolean.TRUE.equals(before.enabled)) return true;
            return totalRatePct.multiply(usdtSharePct).compareTo(before.totalRatePct.multiply(before.usdtSharePct)) > 0
                    || totalRatePct.multiply(HUNDRED.subtract(usdtSharePct))
                        .compareTo(before.totalRatePct.multiply(HUNDRED.subtract(before.usdtSharePct))) > 0
                    || coolingDays < before.coolingDays;
        }
    }
}
