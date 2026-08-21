package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OnboardingSchemaBaselineContractTest {
    @Test
    void baselineOnboardingSeedsNeverOverwriteOperatorOwnedTierOrYieldValues() throws Exception {
        String schema = Files.readString(Path.of("scripts/schema.sql"));

        String tiers = section(schema,
                "INSERT INTO nx_onboarding_phone_tier_config",
                "CREATE TABLE IF NOT EXISTS nx_onboarding_yield_comparison_config");
        assertThat(tiers).contains("ON DUPLICATE KEY UPDATE tier=tier")
                .doesNotContain("=VALUES");

        String comparisons = section(schema,
                "INSERT INTO nx_onboarding_yield_comparison_config",
                "CREATE TABLE IF NOT EXISTS nx_onboarding_calibration");
        assertThat(comparisons).contains("ON DUPLICATE KEY UPDATE config_key=config_key")
                .doesNotContain("=VALUES");
    }

    @Test
    void yieldComparisonsRejectZeroAndNegativeRatesInBaselineAndUpgrade() throws Exception {
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        String migration = Files.readString(Path.of("scripts/migrations/20260816_onboarding_calibration_authority.sql"));

        assertThat(schema).contains("chk_onboarding_yield_comparison_rate CHECK (daily_usdt > 0 AND daily_nex > 0)");
        assertThat(migration).contains("chk_onboarding_yield_comparison_rate CHECK (daily_usdt > 0 AND daily_nex > 0)")
                .contains("ALTER TABLE nx_onboarding_yield_comparison_config ADD CONSTRAINT");
    }

    private String section(String source, String start, String end) {
        int begin = source.indexOf(start);
        int finish = source.indexOf(end, begin + start.length());
        assertThat(begin).as("missing schema marker %s", start).isGreaterThanOrEqualTo(0);
        assertThat(finish).as("missing schema marker %s", end).isGreaterThan(begin);
        return source.substring(begin, finish);
    }
}
