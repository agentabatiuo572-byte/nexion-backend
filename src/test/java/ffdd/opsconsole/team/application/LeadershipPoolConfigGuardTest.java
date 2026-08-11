package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class LeadershipPoolConfigGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "team.ui.F.pool.configVersion",
            "team.ui.F.pool.ratio",
            "team.ui.F.pool.unlockVRank",
            "team.ui.F.pool.monthlyCap",
            "team.ui.F.pool.settleCron"
    })
    void everyRequiredKeyFailsClosedWhenMissing(String missingKey) {
        Map<String, String> values = validValues();
        values.remove(missingKey);
        LeadershipPoolConfigGuard guard = guard(values);

        assertThatThrownBy(guard::requireValid)
                .isInstanceOfSatisfying(
                        LeadershipPoolConfigGuard.ConfigUnavailableException.class,
                        failure -> {
                            assertThat(failure.key()).isEqualTo(missingKey);
                            assertThat(failure.reason()).isEqualTo("MISSING");
                        });
    }

    @Test
    void bareOneIsUnambiguouslyOnePercent() {
        Map<String, String> values = validValues();
        values.put("team.ui.F.pool.ratio", "1");

        assertThat(guard(values).requireValid().injectRate())
                .isEqualByComparingTo(new BigDecimal("0.01"));
    }

    @Test
    void historicalBareSixAndCanonicalSixPercentAreEquivalent() {
        assertThat(LeadershipPoolConfigGuard.parseConfiguredRate("6"))
                .isEqualByComparingTo(new BigDecimal("0.06"));
        assertThat(LeadershipPoolConfigGuard.parseConfiguredRate("6%"))
                .isEqualByComparingTo(new BigDecimal("0.06"));
        assertThat(LeadershipPoolConfigGuard.canonicalConfiguredPercent("6"))
                .isEqualTo("6%");
        assertThat(LeadershipPoolConfigGuard.canonicalConfiguredPercent("6%"))
                .isEqualTo("6%");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1x3", "%", "6%%", "1e1"})
    void pollutedOrNonDecimalRatesFailClosed(String value) {
        assertThatThrownBy(() -> LeadershipPoolConfigGuard.parseConfiguredRate(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("F4_POOL_RATE_INVALID");
    }

    @Test
    void newDatabaseBootstrapSentinelsStayBlockedUntilEveryValueIsExplicitlyConfigured() {
        Map<String, String> values = new HashMap<>();
        values.put("team.ui.F.pool.configVersion", "1");
        values.put("team.ui.F.pool.ratio", "__UNCONFIGURED__");
        values.put("team.ui.F.pool.unlockVRank", "__UNCONFIGURED__");
        values.put("team.ui.F.pool.monthlyCap", "__UNCONFIGURED__");
        values.put("team.ui.F.pool.settleCron", "__UNCONFIGURED__");

        assertThatThrownBy(guard(values)::requireValid)
                .isInstanceOfSatisfying(
                        LeadershipPoolConfigGuard.ConfigUnavailableException.class,
                        failure -> assertThat(failure.key()).isEqualTo("team.ui.F.pool.ratio"));
    }

    @Test
    void writeAndReadParserShareTheSameThirtyPercentSafetyCeiling() {
        assertThat(LeadershipPoolConfigGuard.canonicalConfiguredPercent("1%"))
                .isEqualTo("1%");
        assertThat(LeadershipPoolConfigGuard.parseConfiguredRate("30"))
                .isEqualByComparingTo("0.30");
        assertThatThrownBy(() -> LeadershipPoolConfigGuard.parseConfiguredRate("30.0001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("F4_POOL_RATE_INVALID");
    }

    @Test
    void bareOneToTwoIsDetectedAsARealPayoutIncreaseForCoverageRedline() {
        assertThat(LeadershipPoolConfigGuard.isConfiguredRateIncrease("1", "2")).isTrue();
        assertThat(LeadershipPoolConfigGuard.isConfiguredRateIncrease("2", "1")).isFalse();
    }

    @Test
    void equivalentHistoricalAndCanonicalRatesDoNotTriggerB1ButRealIncreaseStillDoes() {
        assertThat(LeadershipPoolConfigGuard.isConfiguredRateIncrease("6", "6%")).isFalse();
        assertThat(LeadershipPoolConfigGuard.isConfiguredRateIncrease("6%", "6")).isFalse();
        assertThat(LeadershipPoolConfigGuard.isConfiguredRateIncrease("6", "7%")).isTrue();
    }

    @Test
    void firstPositiveRateFromAnyFailClosedLegacyStateMustPassCoverageRedline() {
        assertThat(LeadershipPoolConfigGuard.isConfiguredRateIncrease("__UNCONFIGURED__", "1")).isTrue();
        assertThat(LeadershipPoolConfigGuard.isConfiguredRateIncrease("", "1")).isTrue();
        assertThat(LeadershipPoolConfigGuard.isConfiguredRateIncrease("legacy-invalid", "1")).isTrue();
        assertThat(LeadershipPoolConfigGuard.isConfiguredRateIncrease("__UNCONFIGURED__", "0")).isFalse();
    }

    private LeadershipPoolConfigGuard guard(Map<String, String> values) {
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        when(config.activeValue(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(values.get(invocation.getArgument(0, String.class))));
        return new LeadershipPoolConfigGuard(config);
    }

    private Map<String, String> validValues() {
        Map<String, String> values = new HashMap<>();
        values.put("team.ui.F.pool.configVersion", "7");
        values.put("team.ui.F.pool.ratio", "5%");
        values.put("team.ui.F.pool.unlockVRank", "V3");
        values.put("team.ui.F.pool.monthlyCap", "5000");
        values.put("team.ui.F.pool.settleCron", "0 59 23 * * 0");
        return values;
    }
}
