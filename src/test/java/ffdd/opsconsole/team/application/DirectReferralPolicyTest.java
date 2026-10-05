package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.team.domain.DirectReferralPolicy;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DirectReferralPolicyTest {
    private static BigDecimal d(String value) { return new BigDecimal(value); }

    @Test void splitsOneBudgetAndUsesBothPostedAssets() {
        var purchase = new DirectReferralPolicy.Rule(true, d("10"), d("60"), 0);
        var amount = purchase.calculate(d("1000"), d("0.01"));
        assertThat(amount.usdt()).isEqualByComparingTo("60");
        assertThat(amount.nex()).isEqualByComparingTo("4000");
        var earning = new DirectReferralPolicy.Rule(true, d("5"), d("60"), 0);
        var posted = earning.calculate(d("10").add(d("100").multiply(d("0.01"))), d("0.01"));
        assertThat(posted.usdt()).isEqualByComparingTo("0.33");
        assertThat(posted.nex()).isEqualByComparingTo("22");
    }

    @Test void floorsBothAssetsWithoutInflatingDust() {
        var amounts = new DirectReferralPolicy.Rule(true, d("0.000001"), d("60"), 365)
                .calculate(d("0.000001"), d("0.01"));
        assertThat(amounts.usdt()).isZero();
        assertThat(amounts.nex()).isZero();
        assertThat(amounts.payable()).isFalse();
    }

    @Test void rejectsOneAssetSplitsInvalidRatesAndMissingPrice() {
        for (String share : new String[] { "0", "100", "-1", "101" }) {
            assertThatThrownBy(() -> new DirectReferralPolicy.Rule(true, d("10"), d(share), 0).validate())
                    .hasMessage("DIRECT_REFERRAL_RULE_INVALID");
        }
        assertThatThrownBy(() -> new DirectReferralPolicy.Rule(true, d("0"), d("50"), 0).validate()).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new DirectReferralPolicy.Rule(true, d("10"), d("50"), 366).validate()).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new DirectReferralPolicy.Rule(true, d("10"), d("50"), 0).calculate(d("10"), BigDecimal.ZERO))
                .hasMessage("DIRECT_REFERRAL_PRICE_UNAVAILABLE");
    }

    @Test void amplificationComparesBothEffectiveAssetRatesAndCooling() {
        var before = new DirectReferralPolicy.Rule(true, d("10"), d("60"), 30);
        assertThat(new DirectReferralPolicy.Rule(false, d("10"), d("60"), 0).amplifies(before)).isFalse();
        assertThat(new DirectReferralPolicy.Rule(true, d("5"), d("60"), 30).amplifies(before)).isFalse();
        assertThat(new DirectReferralPolicy.Rule(true, d("10"), d("70"), 30).amplifies(before)).isTrue();
        assertThat(new DirectReferralPolicy.Rule(true, d("10"), d("50"), 30).amplifies(before)).isTrue();
        assertThat(new DirectReferralPolicy.Rule(true, d("10"), d("60"), 29).amplifies(before)).isTrue();
    }

    @Test void invalidAndUnrepresentableMoneyCannotProduceRewards() {
        var rule = new DirectReferralPolicy.Rule(true, d("10"), d("60"), 0);
        assertThatThrownBy(() -> rule.calculate(d("-1"), d("0.01"))).hasMessage("DIRECT_REFERRAL_BASIS_INVALID");
        assertThatThrownBy(() -> rule.calculate(d("1000000000000"), d("0.000001"))).hasMessage("DIRECT_REFERRAL_AMOUNT_OVERFLOW");
        assertThatThrownBy(() -> new DirectReferralPolicy.Rule(true, d("100.000001"), d("60"), 0).validate()).hasMessage("DIRECT_REFERRAL_RULE_INVALID");
        assertThatThrownBy(() -> new DirectReferralPolicy.Rule(true, d("0.0000001"), d("60"), 0).validate()).hasMessage("DIRECT_REFERRAL_RULE_INVALID");
    }
}
