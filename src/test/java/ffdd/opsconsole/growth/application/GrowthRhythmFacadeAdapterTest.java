package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GrowthRhythmFacadeAdapterTest {

    @Test
    void exposesCanonicalPhaseMappingAndMonthSpecificSnapshotForB4() {
        PlatformConfigFacade configFacade = mock(PlatformConfigFacade.class);
        Map<String, String> values = Map.of(
                "H1.rhythm.totalMonths", "12",
                "H1.rhythm.currentMonth", "7",
                "H1.rhythm.phaseProgressPct", "58",
                "growth.phase.current", "P3");
        when(configFacade.activeValue(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(values.get(invocation.getArgument(0))));

        GrowthRhythmFacadeAdapter adapter = new GrowthRhythmFacadeAdapter(
                configFacade, OpsReadTimeSeedPolicy.disabledForDirectConstruction());

        assertThat(adapter.phaseForMonth(1)).isEqualTo("P1");
        assertThat(adapter.phaseForMonth(4)).isEqualTo("P2");
        assertThat(adapter.phaseForMonth(8)).isEqualTo("P4");
        assertThat(adapter.phaseForMonth(12)).isEqualTo("P6");

        GrowthRhythmSnapshot selected = adapter.snapshotAtMonth(8);
        assertThat(selected.totalMonths()).isEqualTo(12);
        assertThat(selected.currentMonth()).isEqualTo(8);
        assertThat(selected.currentPhase()).isEqualTo("P4");
        assertThat(selected.dials()).hasSize(8);
    }
}
