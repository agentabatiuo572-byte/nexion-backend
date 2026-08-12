package ffdd.opsconsole.commerce.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class CommerceAcceptanceSandboxProfileConditionTest {
    @Test
    void callbackSurfaceExistsOnlyForExactlyOneIsolatedProfile() {
        assertThat(CommerceAcceptanceSandboxProfileCondition.isStrictIsolatedProfile("acceptance")).isTrue();
        assertThat(CommerceAcceptanceSandboxProfileCondition.isStrictIsolatedProfile("test")).isTrue();
        assertThat(CommerceAcceptanceSandboxProfileCondition.isStrictIsolatedProfile("production")).isFalse();
        assertThat(CommerceAcceptanceSandboxProfileCondition.isStrictIsolatedProfile("acceptance", "dev")).isFalse();
        assertThat(CommerceAcceptanceSandboxProfileCondition.isEnabled("LOCAL_SANDBOX", "acceptance")).isTrue();
        assertThat(CommerceAcceptanceSandboxProfileCondition.isEnabled("DISABLED", "acceptance")).isFalse();
        assertThat(CommerceAcceptanceSandboxProfileCondition.isEnabled("LOCAL_SANDBOX", "acceptance", "dev")).isFalse();
    }
}
