package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BundleDiscountPolicyTest {
    @Test
    void readsDynamicMonotonicRatesAndSelectsTheMatchingTier() {
        Map<String, String> values = Map.of(
                BundleDiscountPolicy.TWO_ITEMS_KEY, "0.04",
                BundleDiscountPolicy.THREE_ITEMS_KEY, "0.07",
                BundleDiscountPolicy.FOUR_PLUS_ITEMS_KEY, "0.11");

        BundleDiscountPolicy policy = BundleDiscountPolicy.require(key -> Optional.ofNullable(values.get(key)));

        assertThat(policy.rateFor(1)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(policy.rateFor(2)).isEqualByComparingTo("0.04");
        assertThat(policy.rateFor(3)).isEqualByComparingTo("0.07");
        assertThat(policy.rateFor(8)).isEqualByComparingTo("0.11");
    }

    @Test
    void failsClosedWhenAnyRateIsMissingOrTheLadderDecreases() {
        assertThatThrownBy(() -> BundleDiscountPolicy.require(key -> Optional.empty()))
                .hasMessage("BUNDLE_DISCOUNT_POLICY_UNAVAILABLE");
        assertThatThrownBy(() -> new BundleDiscountPolicy(
                new BigDecimal("0.08"), new BigDecimal("0.07"), new BigDecimal("0.11")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("BUNDLE_DISCOUNT_POLICY_INVALID");
    }
}
