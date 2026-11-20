package ffdd.opsconsole.treasury.dto;

import java.util.Map;

public record TreasuryForecastPreviewRequest(
        Map<String, Boolean> reserveCategories,
        Map<String, Boolean> liabilityCategories,
        String forecastWindow,
        Boolean genesisIncluded,
        Boolean includeFarLiabilities,
        String stakingInterestMode,
        Boolean trialStressEnabled,
        Long expectedVersion) {
}
