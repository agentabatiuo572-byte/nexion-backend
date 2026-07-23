package ffdd.opsconsole.treasury.dto;

import java.util.Map;

public record TreasuryForecastConfigRequest(
        Map<String, Boolean> reserveCategories,
        Map<String, Boolean> liabilityCategories,
        String forecastWindow,
        Boolean genesisIncluded,
        Boolean includeFarLiabilities,
        String stakingInterestMode,
        Boolean trialStressEnabled,
        Long expectedVersion,
        String reason,
        String operator) {
}
