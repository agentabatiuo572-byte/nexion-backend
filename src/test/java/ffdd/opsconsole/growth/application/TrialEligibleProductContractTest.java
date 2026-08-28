package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TrialEligibleProductContractTest {

    @Test
    void trialEligibilityIsAnExplicitDefaultOffE1ProductCapability() throws Exception {
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        String migration = Files.readString(Path.of("scripts/migrations/20260826_product_trial_eligibility.sql"));
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/mapper/AppTrialLifecycleMapper.java"));
        String growth = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/growth/application/OpsGrowthService.java"));

        assertThat(schema).contains("trial_eligible TINYINT NOT NULL DEFAULT 0");
        assertThat(migration).contains("ADD COLUMN trial_eligible TINYINT NOT NULL DEFAULT 0")
                .contains("product_no = 'stellarbox-s1'")
                .contains("trial-eligibility-migration-pending")
                .contains("trial-eligibility-explicit-v1")
                .contains("CREATE TABLE IF NOT EXISTS nx_schema_migration_state")
                .contains("@trial_eligible_existing_comment = 'trial-eligibility-migration-pending'")
                .contains("IF(@legacy_pending_s1_value = 1, 'SEEDED', 'LEGACY_PENDING_REVIEW')")
                .contains("TRIAL_ELIGIBILITY_LEGACY_PENDING_REQUIRES_EXPLICIT_RESOLUTION")
                .contains("AND m.phase = 'PENDING'")
                .contains("SET phase = 'SEEDED'")
                .contains("SET phase = 'FINAL'");
        assertThat(mapper).contains("trial_eligible=1");
        assertThat(growth).contains("product.trialEligible()")
                .contains("E1 商品未开启允许试用");
    }
}
