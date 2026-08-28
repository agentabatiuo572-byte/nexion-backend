package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AppTrialCardPolicyMigrationContractTest {
    @Test
    void freeTrialCardQuotaIsInstalledAsAnOperatorOwnedH2Policy() throws Exception {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260821_h2_trial_card_offer.sql"));

        assertThat(migration)
                .contains("CREATE TABLE IF NOT EXISTS nx_growth_trial_daily_quota")
                .contains("quota_date DATE NOT NULL")
                .contains("daily_limit INT UNSIGNED NOT NULL")
                .contains("claimed_count INT UNSIGNED NOT NULL DEFAULT 0")
                .contains("INSERT INTO nx_growth_trial_policy")
                .contains("'seatsLeftToday'")
                .contains("'47','NUMBER',0,'live',0")
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("policy_key=VALUES(policy_key)")
                .doesNotContain("current_value=VALUES(current_value)", "current_value = VALUES(current_value)",
                        "is_deleted=VALUES(is_deleted)", "is_deleted = VALUES(is_deleted)");
    }
}
