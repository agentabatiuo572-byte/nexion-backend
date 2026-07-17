package ffdd.opsconsole.emergency.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class KillSwitchStateTest {
    @Test
    void primaryStateAlwaysWinsOverConflictingLegacyState() {
        assertThat(KillSwitchState.enabled(Optional.of("disabled"), Optional.of("enabled"))).isFalse();
        assertThat(KillSwitchState.enabled(Optional.of("enabled"), Optional.of("disabled"))).isTrue();
    }

    @Test
    void legacyIsUsedOnlyWhenPrimaryIsMissingAndBothMissingDefaultToEnabled() {
        assertThat(KillSwitchState.enabled(Optional.empty(), Optional.of("disabled"))).isFalse();
        assertThat(KillSwitchState.enabled(Optional.empty(), Optional.empty())).isTrue();
    }
}
