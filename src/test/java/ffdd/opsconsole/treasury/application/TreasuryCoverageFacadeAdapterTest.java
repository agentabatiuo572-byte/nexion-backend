package ffdd.opsconsole.treasury.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TreasuryCoverageFacadeAdapterTest {

    private final OpsTreasuryService treasuryService = mock(OpsTreasuryService.class);
    private final TreasuryCoverageFacadeAdapter adapter = new TreasuryCoverageFacadeAdapter(treasuryService);

    @Test
    void exposesReliableSnapshotOnlyWhenValuationAndRatiosArePresent() {
        when(treasuryService.dualLedger()).thenReturn(ApiResult.ok(Map.of("snapshot", Map.of(
                "coverageRatio", new BigDecimal("101.25"),
                "redlinePct", new BigDecimal("85"),
                "valuationReliable", true))));

        TreasuryCoverageSnapshot snapshot = adapter.snapshot();

        assertThat(snapshot.reliable()).isTrue();
        assertThat(snapshot.coverageRatio()).isEqualByComparingTo("101.25");
        assertThat(snapshot.redlinePct()).isEqualByComparingTo("85");
    }

    @Test
    void missingNexValuationFailsClosedEvenWhenRatioLooksHealthy() {
        when(treasuryService.dualLedger()).thenReturn(ApiResult.ok(Map.of("snapshot", Map.of(
                "coverageRatio", new BigDecimal("101.25"),
                "redlinePct", new BigDecimal("85"),
                "valuationReliable", false))));

        assertThat(adapter.snapshot().reliable()).isFalse();
    }

    @Test
    void malformedCoverageDataFailsClosed() {
        when(treasuryService.dualLedger()).thenReturn(ApiResult.ok(Map.of("snapshot", Map.of(
                "coverageRatio", "not-a-number",
                "redlinePct", new BigDecimal("85"),
                "valuationReliable", true))));

        TreasuryCoverageSnapshot snapshot = adapter.snapshot();

        assertThat(snapshot.reliable()).isFalse();
        assertThat(snapshot.coverageRatio()).isZero();
    }
}
