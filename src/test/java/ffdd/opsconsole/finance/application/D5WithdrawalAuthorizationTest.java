package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.finance.dto.WithdrawalParamUpdateRequest;
import ffdd.opsconsole.finance.dto.WithdrawalLimitsUpdateRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class D5WithdrawalAuthorizationTest {
    private final D5WithdrawalAuthorization authorization = new D5WithdrawalAuthorization();

    @Test
    void eachBodyKeyRequiresItsExactPermission() {
        assertThat(can("finance_d5_daily_limit_write", "dailyLimitCount")).isTrue();
        assertThat(can("finance_d5_daily_limit_write", "balanceMaxRatio")).isFalse();
        assertThat(can("finance_d5_daily_limit_write", "networkFee")).isFalse();
        assertThat(can("finance_d5_daily_limit_write", "nexFeeOffsetRate")).isFalse();

        assertThat(can("finance_d5_balance_max_write", "balanceMaxRatio")).isTrue();
        assertThat(can("finance_d5_fee_write", "networkFee")).isTrue();
        assertThat(can("finance_d5_fee_write", "nexFeeOffsetRate")).isTrue();
        assertThat(can("finance_d5_fee_write", "unknown")).isFalse();
    }

    @Test
    void canonicalAggregateRequiresEveryChangedFieldPermission() {
        var authentication = new UsernamePasswordAuthenticationToken(
                "daily-only", "n/a", List.of(new SimpleGrantedAuthority("finance_d5_daily_limit_write")));
        WithdrawalLimitsUpdateRequest request = new WithdrawalLimitsUpdateRequest();
        request.setDailyLimitCount(2);
        request.setNetworkFeeMin(new java.math.BigDecimal("1"));

        assertThat(authorization.canUpdateLimits(authentication, request)).isFalse();
    }

    @Test
    void phaseFieldReachesReadOnly422ForD5ReadersOnly() {
        WithdrawalLimitsUpdateRequest request = new WithdrawalLimitsUpdateRequest();
        request.setComplianceHoldEnabled(null);
        var reader = new UsernamePasswordAuthenticationToken(
                "risk", "n/a", List.of(new SimpleGrantedAuthority("finance_d5_read")));
        var outsider = new UsernamePasswordAuthenticationToken(
                "support", "n/a", List.of(new SimpleGrantedAuthority("finance_d2_read")));

        assertThat(authorization.canUpdateLimits(reader, request)).isTrue();
        assertThat(authorization.canUpdateLimits(outsider, request)).isFalse();
    }

    private boolean can(String authority, String key) {
        var authentication = new UsernamePasswordAuthenticationToken(
                "tester", "n/a", List.of(new SimpleGrantedAuthority(authority)));
        return authorization.canUpdate(authentication,
                new WithdrawalParamUpdateRequest(key, "1", "valid authorization reason", "tester"));
    }
}
