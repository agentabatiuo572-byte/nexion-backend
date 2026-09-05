package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.domain.DeviceCatalogRepository;
import ffdd.opsconsole.device.domain.DeviceGenerationGateView;
import ffdd.opsconsole.device.domain.DevicePhaseView;
import ffdd.opsconsole.growth.facade.GrowthRhythmFacade;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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

    @Test
    void tradeinEarlyAccessOpensOnlyTheConfiguredWindowBeforeTheReleaseMonth() {
        when(rhythm.snapshot()).thenReturn(snapshotAtMonth(3, 50));
        when(catalog.findGenerationGate("box-gen2")).thenReturn(Optional.of(new DeviceGenerationGateView(
                "box-gen2", "Gen2", 4, "52", BigDecimal.ZERO, true, 0, false, "active", null, null)));

        assertThat(policy.evaluateTradein("box-gen2", "52", false, 30).reason())
                .isEqualTo("E1_GENERATION_RELEASE_MONTH_NOT_REACHED");
        assertThat(policy.evaluateTradein("box-gen2", "52", true, 14).available()).isFalse();
        assertThat(policy.evaluateTradein("box-gen2", "52", true, 30)).satisfies(decision -> {
            assertThat(decision.available()).isTrue();
            assertThat(decision.reason()).isEqualTo("TRADEIN_EARLY_ACCESS_AVAILABLE");
        });
    }

    @Test
    void tradeinEarlyAccessNeverBypassesEligibilityOrPhaseGates() {
        when(catalog.findGenerationGate("box-gen2")).thenReturn(Optional.of(new DeviceGenerationGateView(
                "box-gen2", "Gen2", 4, "77", BigDecimal.ZERO, false, 0, false, "active", null, null)));

        assertThat(policy.evaluateTradein("box-gen2", "77", true, 90).reason())
                .isEqualTo("E1_GENERATION_ELIGIBILITY_REQUIRED");
    }

    @Test
    void batchEvaluationReadsRhythmPhasesAndGenerationGatesOnlyOnce() {
        when(catalog.listGenerationGates(false)).thenReturn(List.of(new DeviceGenerationGateView(
                "box-gen2", "Gen2", 7, "52", BigDecimal.ZERO, true, 0, false, "active", null, null)));
        Map<String, String> candidates = new LinkedHashMap<>();
        candidates.put("box-now", "52");
        candidates.put("box-gen2", "52");

        Map<String, StorefrontProductReleasePolicy.Decision> decisions = policy.evaluateBatch(candidates);

        assertThat(decisions.get("box-now").available()).isTrue();
        assertThat(decisions.get("box-gen2").reason()).isEqualTo("E1_GENERATION_RELEASE_MONTH_NOT_REACHED");
        verify(rhythm, times(1)).snapshot();
        verify(catalog, times(1)).listPhases("E1", false);
        verify(catalog, times(1)).listGenerationGates(false);
        verify(catalog, never()).findGenerationGate(org.mockito.ArgumentMatchers.anyString());
    }

    private DevicePhaseView phase(String id, int sortOrder) {
        return new DevicePhaseView(id, "phase-" + id, "", "", sortOrder, "active", null, null);
    }

    private GrowthRhythmSnapshot snapshotAtMonth(int month) {
        return snapshotAtMonth(month, 0);
    }

    private GrowthRhythmSnapshot snapshotAtMonth(int month, int phaseProgressPct) {
        return new GrowthRhythmSnapshot(
                12, month, "P2", phaseProgressPct,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, 1, new BigDecimal("100"), BigDecimal.ONE,
                false, List.of("test"));
    }
}
