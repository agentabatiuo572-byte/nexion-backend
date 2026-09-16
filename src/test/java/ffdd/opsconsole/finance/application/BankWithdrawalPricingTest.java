package ffdd.opsconsole.finance.application;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankWithdrawalPricingTest {
    Map<String, Object> config = Map.of("minAmountUsd", 20, "maxAmountUsd", 5000,
            "baseRateVndPerUsdt", 26000, "sellSpreadPct", "1.5", "feeRatePct", 1, "feeMinUsd", 1, "feeMaxUsd", 25);
    @Test void quoteUsesD7SellRateAndClampedFee() {
        var p = BankWithdrawalPricing.calculate(new BigDecimal("100"), config);
        assertEquals(0, new BigDecimal("25610").compareTo(p.rate()));
        assertEquals(0, new BigDecimal("1").compareTo(p.fee()));
        assertEquals(0, new BigDecimal("2535390").compareTo(p.vnd()));
        assertEquals(0, p.amount().compareTo(p.net().add(p.fee())));
        assertEquals(0, new BigDecimal("25").compareTo(BankWithdrawalPricing.calculate(new BigDecimal("5000"), config).fee()));
    }
    @Test void rejectsOutOfRangeAndExcessPrecision() {
        for (String amount : new String[]{"0", "-1", "19.999999", "5001", "20.0000001"})
            assertThrows(RuntimeException.class, () -> BankWithdrawalPricing.calculate(new BigDecimal(amount), config));
    }
    @Test void vndRoundsDownWithoutIncreasingDebit() {
        var p = BankWithdrawalPricing.calculate(new BigDecimal("20.000001"), config);
        assertEquals(0, p.vnd().scale());
        assertTrue(p.vnd().compareTo(p.net().multiply(p.rate())) <= 0);
    }
    @Test void acceptsThePcConfiguredRangeInsteadOfAHardcodedTwentyUsdtFloor() {
        var changed = new java.util.HashMap<>(config);
        changed.put("minAmountUsd", "5"); changed.put("maxAmountUsd", "80");
        assertEquals(0, new BigDecimal("5").compareTo(BankWithdrawalPricing.calculate(new BigDecimal("5"), changed).amount()));
        for (String amount : new String[]{"4.999999", "80.000001"})
            assertThrows(RuntimeException.class, () -> BankWithdrawalPricing.calculate(new BigDecimal(amount), changed));
    }
}
