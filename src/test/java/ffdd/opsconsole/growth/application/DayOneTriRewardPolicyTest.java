package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.shared.exception.BizException;
import org.junit.jupiter.api.Test;

class DayOneTriRewardPolicyTest {
    @Test
    void normalizesThePcOwnedThreePhaseLadder() {
        assertThat(DayOneTriRewardPolicy.normalizePolicy("500/200/0 NEX"))
                .isEqualTo("500 / 200 / 0 NEX");
    }

    @Test
    void rejectsSingleValueIncreasingExpiredOrFourPhasePolicies() {
        for (String value : new String[] {"60", "tier 100 / 50 / 0 NEX", "100 / 50 / 0 USDT",
                "100 / 200 / 0 NEX", "100 / 50 / 1 NEX", "100 / 50 / 0 / 0 NEX"}) {
            assertThatThrownBy(() -> DayOneTriRewardPolicy.normalizePolicy(value))
                    .isInstanceOf(BizException.class)
                    .hasMessage("H3_DAY_ONE_REWARD_POLICY_UNAVAILABLE");
        }
    }
}
