package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TeamAmbassadorPolicyMigrationContractTest {
    @Test
    void startupRepairsTheAuditColumnsConsumedByTheF4Mapper() throws Exception {
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260905_team_ambassador_policy_audit_columns.sql"));

        assertThat(startup).contains("20260905_team_ambassador_policy_audit_columns.sql");
        assertThat(migration).contains("nx_team_ambassador_policy", "updated_by", "updated_at")
                .contains("INFORMATION_SCHEMA.COLUMNS", "PREPARE", "EXECUTE");
    }
}
