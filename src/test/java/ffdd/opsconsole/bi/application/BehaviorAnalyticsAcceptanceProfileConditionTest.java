package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BehaviorAnalyticsAcceptanceProfileConditionTest {
    @Test
    void registersOnlyForOneIsolatedProfile() {
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.isStrictIsolatedProfile("acceptance")).isTrue();
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.isStrictIsolatedProfile("test")).isTrue();
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.isStrictIsolatedProfile("local-sandbox")).isTrue();
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.isStrictIsolatedProfile("production")).isFalse();
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.isStrictIsolatedProfile("acceptance", "production")).isFalse();
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.isStrictIsolatedProfile()).isFalse();
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.sourceEnvironmentFor("acceptance")).isEqualTo("SANDBOX");
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.sourceEnvironmentFor("test")).isEqualTo("SANDBOX");
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.sourceEnvironmentFor("local-sandbox")).isEqualTo("SANDBOX");
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.sourceEnvironmentFor("production")).isEqualTo("PRODUCTION");
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.sourceEnvironmentFor("acceptance", "production")).isNull();
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.sourceEnvironmentFor("unknown")).isNull();
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.sourceEnvironmentFor("production", "test")).isNull();
    }
}
