package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BinarySettlementPolicyProviderTest {
    private PlatformConfigFacade config;
    private TreasuryCoverageFacade coverage;
    private BinarySettlementPolicyProvider provider;

    @BeforeEach
    void setUp() {
        config = mock(PlatformConfigFacade.class);
        coverage = mock(TreasuryCoverageFacade.class);
        provider = new BinarySettlementPolicyProvider(config, mock(OpsReadTimeSeedPolicy.class), coverage);
        seed("team.ui.F.binary.threshold", "1000");
        seed("team.ui.F.binary.matchRate", "13%");
        seed("team.ui.F.binary.paused", "false");
        seed("H1.rhythm.totalMonths", "12");
        seed("H1.rhythm.currentMonth", "1");
        seed("growth.phase.current", "P1");
        seed("growth.phase.month.1.binaryDailyCap", "5000");
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("150"), new BigDecimal("120"), true));
    }

    @Test
    void locksAndParsesExactF3AndH1Policy() {
        BinarySettlementPolicyProvider.BinarySettlementPolicy policy = provider.lockPolicy();

        assertThat(policy.threshold()).isEqualByComparingTo("1000");
        assertThat(policy.matchRate()).isEqualByComparingTo("0.13");
        assertThat(policy.dailyCap()).isEqualByComparingTo("5000");
        assertThat(policy.paused()).isFalse();
    }

    @Test
    void missingOrZeroRateFailsClosed() {
        when(config.activeValueForUpdate("team.ui.F.binary.matchRate")).thenReturn(Optional.empty());
        assertThatThrownBy(provider::lockPolicy)
                .isInstanceOf(BinarySettlementPolicyProvider.PolicyBlocked.class)
                .hasMessage("F3_MATCH_RATE_MISSING");

        seed("team.ui.F.binary.matchRate", "0");
        assertThatThrownBy(provider::lockPolicy)
                .isInstanceOf(BinarySettlementPolicyProvider.PolicyBlocked.class)
                .hasMessage("F3_MATCH_RATE_INVALID");
    }

    @Test
    void unreliableOrBelowRedlineB1FailsClosed() {
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                BigDecimal.ZERO, BigDecimal.ZERO, false));
        assertThatThrownBy(provider::lockPolicy).hasMessage("B1_COVERAGE_UNRELIABLE");

        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("119.99"), new BigDecimal("120"), true));
        assertThatThrownBy(provider::lockPolicy).hasMessage("COVERAGE_BELOW_REDLINE");
    }

    @Test
    void missingH1DialFailsClosed() {
        when(config.activeValueForUpdate("growth.phase.month.1.binaryDailyCap"))
                .thenReturn(Optional.empty());
        assertThatThrownBy(provider::lockPolicy).hasMessage("H1_BINARY_DAILY_CAP_MISSING");
    }

    private void seed(String key, String value) {
        when(config.activeValueForUpdate(key)).thenReturn(Optional.of(value));
        when(config.activeValue(key)).thenReturn(Optional.of(value));
    }
}
