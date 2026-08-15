package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.domain.DeviceCatalogRepository;
import ffdd.opsconsole.device.domain.DeviceGenerationGateView;
import ffdd.opsconsole.device.domain.DevicePhaseView;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StorefrontProductReleasePolicyTest {
    private final DeviceCatalogRepository catalog = mock(DeviceCatalogRepository.class);
    private final GrowthRhythmFacade rhythm = mock(GrowthRhythmFacade.class);
    private final StorefrontProductReleasePolicy policy = new StorefrontProductReleasePolicy(catalog, rhythm);

    @BeforeEach
    void setUp() {
        when(catalog.listPhases("E1", false)).thenReturn(List.of(
                phase("52", 10), phase("77", 20), phase("103", 30), phase("104", 40)));
        when(rhythm.snapshot()).thenReturn(snapshotAtMonth(3));
    }

    @Test
    void comparesConfiguredPhaseOrderInsteadOfTreatingDatabaseIdsAsH1Codes() {
        assertThat(policy.evaluate("box-now", "52").available()).isTrue();
        assertThat(policy.evaluate("box-future", "77")).satisfies(decision -> {
            assertThat(decision.available()).isFalse();
            assertThat(decision.reason()).isEqualTo("E1_PHASE_NOT_REACHED");
        });
    }

    @Test
    void failsClosedForAnUnknownDatabasePhaseId() {
        assertThat(policy.evaluate("box-unknown", "999")).satisfies(decision -> {
            assertThat(decision.available()).isFalse();
            assertThat(decision.reason()).isEqualTo("E1_UNLOCK_PHASE_INVALID");
        });
    }

    @Test
    void activeGenerationGateRequiresEligibilityPhaseAndReleaseMonth() {
        when(catalog.findGenerationGate("box-gen2")).thenReturn(Optional.of(new DeviceGenerationGateView(
                "box-gen2", "Gen2", 7, "52", BigDecimal.ZERO, true, 0, false, "active", null, null)));

        assertThat(policy.evaluate("box-gen2", "52")).satisfies(decision -> {
            assertThat(decision.available()).isFalse();
            assertThat(decision.reason()).isEqualTo("E1_GENERATION_RELEASE_MONTH_NOT_REACHED");
        });
    }

    private DevicePhaseView phase(String id, int sortOrder) {
        return new DevicePhaseView(id, "phase-" + id, "", "", sortOrder, "active", null, null);
    }

    private GrowthRhythmSnapshot snapshotAtMonth(int month) {
        return new GrowthRhythmSnapshot(
                12, month, "P2", 0,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, 1, new BigDecimal("100"), BigDecimal.ONE,
                false, List.of("test"));
    }
}
