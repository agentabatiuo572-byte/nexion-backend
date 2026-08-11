package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class H8AcceptanceSandboxProfileConditionTest {
    @Test
    void registersOnlyForOneExplicitlyIsolatedProfile() {
        assertThat(H8AcceptanceSandboxProfileCondition.isStrictIsolatedProfile("acceptance")).isTrue();
        assertThat(H8AcceptanceSandboxProfileCondition.isStrictIsolatedProfile("test")).isTrue();
        assertThat(H8AcceptanceSandboxProfileCondition.isStrictIsolatedProfile("local-sandbox")).isTrue();
    }

    @Test
    void failsClosedForEmptyUnknownOrMixedProfiles() {
        assertThat(H8AcceptanceSandboxProfileCondition.isStrictIsolatedProfile()).isFalse();
        assertThat(H8AcceptanceSandboxProfileCondition.isStrictIsolatedProfile("prod")).isFalse();
        assertThat(H8AcceptanceSandboxProfileCondition.isStrictIsolatedProfile("acceptance", "prod")).isFalse();
        assertThat(H8AcceptanceSandboxProfileCondition.isStrictIsolatedProfile("test", "acceptance")).isFalse();
        assertThat(H8AcceptanceSandboxProfileCondition.isStrictIsolatedProfile((String[]) null)).isFalse();
    }
}
