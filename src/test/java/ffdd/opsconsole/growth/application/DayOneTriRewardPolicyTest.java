package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ffdd.opsconsole.shared.exception.BizException;
import org.junit.jupiter.api.Test;

class DayOneTriRewardPolicyTest {
    @Test
    void normalizesThePcOwnedThreePhaseLadder() {
        assertThat(DayOneTriRewardPolicy.normalizePolicy("500/200/0 UVT"))
                .isEqualTo("500 / 200 / 0 UVT");
    }

    @Test
    void rejectsSingleValueIncreasingExpiredOrFourPhasePolicies() {
        for (String value : new String[] {"60", "100 / 50 / 0 NEX", "tier 100 / 50 / 0 UVT", "100 / 50 / 0 USDT",
                "100 / 200 / 0 UVT", "100 / 50 / 1 UVT", "100 / 50 / 0 / 0 UVT"}) {
            assertThatThrownBy(() -> DayOneTriRewardPolicy.normalizePolicy(value))
                    .isInstanceOf(BizException.class)
                    .hasMessage("H3_DAY_ONE_REWARD_POLICY_UNAVAILABLE");
        }
    }

    @Test
    void usesThePersistedFullRewardWindowInsteadOfAnImplicitCurrentPolicy() {
        assertThat(DayOneTriRewardPolicy.effectiveDayOneReward(
                "500 / 200 / 0 UVT", 30, 72, 36)).isEqualByComparingTo("500");
        assertThat(DayOneTriRewardPolicy.effectiveDayOneReward(
                "500 / 200 / 0 UVT", 36, 72, 36)).isEqualByComparingTo("200");
    }
}
