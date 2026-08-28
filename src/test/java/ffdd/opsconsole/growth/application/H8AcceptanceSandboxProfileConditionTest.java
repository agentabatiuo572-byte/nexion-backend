package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class H8AcceptanceSandboxProfileConditionTest {
    @Test
    void registersOnlyForOneExplicitlyIsolatedProfile() {
        assertThat(H8AcceptanceSandboxProfileCondition.isStrictIsolatedProfile("test")).isTrue();
    }

    @Test
    void failsClosedForEmptyUnknownOrMixedProfiles() {
        assertThat(H8AcceptanceSandboxProfileCondition.isStrictIsolatedProfile()).isFalse();
        assertThat(H8AcceptanceSandboxProfileCondition.isStrictIsolatedProfile("dev")).isFalse();
        assertThat(H8AcceptanceSandboxProfileCondition.isStrictIsolatedProfile("prod")).isFalse();
        assertThat(H8AcceptanceSandboxProfileCondition.isStrictIsolatedProfile("dev", "prod")).isFalse();
        assertThat(H8AcceptanceSandboxProfileCondition.isStrictIsolatedProfile("test", "dev")).isFalse();
        assertThat(H8AcceptanceSandboxProfileCondition.isStrictIsolatedProfile((String[]) null)).isFalse();
    }
}
