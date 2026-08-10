package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CoverageRedlineAlertGateTest {

    @Test
    void emitsOneBlockedAlertUntilThatClaimRecovers() {
        CoverageRedlineAlertGate gate = new CoverageRedlineAlertGate();

        assertThat(gate.firstBlocked("TRIAL-7")).isTrue();
        assertThat(gate.firstBlocked("TRIAL-7")).isFalse();
        assertThat(gate.clearOnNonBlocked("TRIAL-7")).isTrue();
        assertThat(gate.firstBlocked("TRIAL-7")).isTrue();
    }

    @Test
    void keepsDifferentClaimsIndependentlyActionable() {
        CoverageRedlineAlertGate gate = new CoverageRedlineAlertGate();

        assertThat(gate.firstBlocked("TRIAL-7")).isTrue();
        assertThat(gate.firstBlocked("TRIAL-8")).isTrue();
        assertThat(gate.clearOnNonBlocked("TRIAL-9")).isFalse();
    }
}
