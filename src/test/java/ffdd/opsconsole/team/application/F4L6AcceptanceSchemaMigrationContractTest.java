package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class F4L6AcceptanceSchemaMigrationContractTest {

    @Test
    void startupAppliesTheSchemaNeededByTheF4AndL6ReadModels() throws Exception {
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String migration = Files.readString(Path.of("scripts/migrations/20260811_f4_l6_acceptance_schema.sql"));

        assertThat(startup)
                .contains("20260810_kl_janus_applied_proof.sql")
                .contains("20260811_f4_l6_acceptance_schema.sql");
        assertThat(migration)
                .contains("nx_team_hardware_quota_usage")
                .contains("quota_code")
                .contains("product_no")
                .contains("order_no")
                .contains("remark");
    }

    @Test
    void hardwareQuotaCasUsesTheAuthoritativeOccurrenceWindow() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/team/mapper/TeamCommissionMapper.java"));

        assertThat(mapper).doesNotContain("u.period_month");
        assertThat(mapper).contains("u.occurred_at >= DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01')");
    }
}
