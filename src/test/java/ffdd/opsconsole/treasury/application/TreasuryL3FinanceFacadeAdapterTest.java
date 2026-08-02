package ffdd.opsconsole.treasury.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class TreasuryL3FinanceFacadeAdapterTest {
    private final OpsTreasuryService treasuryService = mock(OpsTreasuryService.class);
    private final TreasuryL3FinanceFacadeAdapter adapter = new TreasuryL3FinanceFacadeAdapter(treasuryService);

    @Test
    void exposesOneServerControlledReadSnapshotWithoutChangingTreasuryHttpPermissions() {
        when(treasuryService.coverage()).thenReturn(ApiResult.ok(coverage()));
        when(treasuryService.liabilities(true)).thenReturn(ApiResult.ok(liabilities()));
        when(treasuryService.maturityForecast("7d")).thenReturn(ApiResult.ok(maturity("7d", 7)));
        when(treasuryService.maturityForecast("30d")).thenReturn(ApiResult.ok(maturity("30d", 30)));

        Map<String, Object> result = adapter.currentL3FinanceSnapshot();

        assertThat(result)
                .containsEntry("serverAuthoritative", true)
                .containsKeys("coverage", "liabilities", "maturity7", "maturity30");
        verify(treasuryService).coverage();
        verify(treasuryService).liabilities(true);
        verify(treasuryService).maturityForecast("7d");
        verify(treasuryService).maturityForecast("30d");
    }

    @Test
    void failsClosedWhenAnyCanonicalTreasurySectionIsUnavailable() {
        when(treasuryService.coverage()).thenReturn(ApiResult.fail(503, "source unavailable"));

        assertThatThrownBy(adapter::currentL3FinanceSnapshot)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("L3_TREASURY_SOURCE_INVALID");
    }

    @Test
    void failsClosedWhenAnHttp200CanonicalSectionIsStructurallyIncomplete() {
        when(treasuryService.coverage()).thenReturn(ApiResult.ok(Map.of()));

        assertThatThrownBy(adapter::currentL3FinanceSnapshot)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("L3_TREASURY_SOURCE_INVALID");
    }

    @Test
    void readsAllFourSectionsInsideOneRepeatableReadTransaction() throws Exception {
        Transactional transaction = TreasuryL3FinanceFacadeAdapter.class
                .getMethod("currentL3FinanceSnapshot")
                .getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    private Map<String, Object> coverage() {
        return Map.ofEntries(
                Map.entry("reserveTotalUsdt", 110), Map.entry("liabilityTotalUsdt", 90),
                Map.entry("coverageRatio", 122.22), Map.entry("netExposureUsdt", 20),
                Map.entry("redLine", 100), Map.entry("yellowLine", 110),
                Map.entry("series", List.of(Map.of("period", "a"), Map.of("period", "b"))),
                Map.entry("breaches", List.of()), Map.entry("source", "B1 双账本"));
    }

    private Map<String, Object> liabilities() {
        return Map.of(
                "totalUsdt", 90,
                "hardLiabilityCategoryCount", 9,
                "trialShadowIncluded", false,
                "breakdown", IntStream.range(0, 9).mapToObj(index -> Map.of("index", index)).toList());
    }

    private Map<String, Object> maturity(String window, int days) {
        return Map.of(
                "window", window,
                "daily", IntStream.range(0, days).mapToObj(index -> Map.of("index", index)).toList(),
                "reserveCoverDays", 12);
    }
}
