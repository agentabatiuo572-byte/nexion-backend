package ffdd.opsconsole.commerce.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class CommerceAcceptanceSandboxProfileConditionTest {
    @Test
    void callbackSurfaceExistsOnlyForExactlyOneIsolatedProfile() {
        assertThat(CommerceAcceptanceSandboxProfileCondition.isStrictIsolatedProfile("dev")).isFalse();
        assertThat(CommerceAcceptanceSandboxProfileCondition.isStrictIsolatedProfile("test")).isTrue();
        assertThat(CommerceAcceptanceSandboxProfileCondition.isStrictIsolatedProfile("prod")).isFalse();
        assertThat(CommerceAcceptanceSandboxProfileCondition.isStrictIsolatedProfile("dev", "dev")).isFalse();
        assertThat(CommerceAcceptanceSandboxProfileCondition.isEnabled("LOCAL_SANDBOX", "dev")).isFalse();
        assertThat(CommerceAcceptanceSandboxProfileCondition.isEnabled("LOCAL_SANDBOX", "test")).isTrue();
        assertThat(CommerceAcceptanceSandboxProfileCondition.isEnabled("DISABLED", "dev")).isFalse();
        assertThat(CommerceAcceptanceSandboxProfileCondition.isEnabled("LOCAL_SANDBOX", "dev", "dev")).isFalse();
    }
}
