package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BehaviorAnalyticsAcceptanceProfileConditionTest {
    @Test
    void registersOnlyForOneIsolatedProfile() {
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.isStrictIsolatedProfile("dev")).isFalse();
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.isStrictIsolatedProfile("test")).isTrue();
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.isStrictIsolatedProfile("dev")).isFalse();
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.isStrictIsolatedProfile("prod")).isFalse();
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.isStrictIsolatedProfile("dev", "prod")).isFalse();
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.isStrictIsolatedProfile()).isFalse();
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.sourceEnvironmentFor("dev")).isEqualTo("PRODUCTION");
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.sourceEnvironmentFor("test")).isEqualTo("SANDBOX");
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.sourceEnvironmentFor("dev")).isEqualTo("PRODUCTION");
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.sourceEnvironmentFor("prod")).isEqualTo("PRODUCTION");
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.sourceEnvironmentFor("dev", "prod")).isNull();
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.sourceEnvironmentFor("unknown")).isNull();
        assertThat(BehaviorAnalyticsAcceptanceProfileCondition.sourceEnvironmentFor("prod", "test")).isNull();
    }
}
