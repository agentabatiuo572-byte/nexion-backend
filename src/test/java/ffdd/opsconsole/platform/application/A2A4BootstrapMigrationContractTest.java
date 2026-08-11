package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class A2A4BootstrapMigrationContractTest {
    @Test
    void cleanSchemaAndStartupMigrationSeedAuthoritativePoliciesWithoutOverwritingExistingValues() throws Exception {
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        String migration = Files.readString(Path.of("scripts/migrations/20260811_a2_a4_runtime_policy_closure.sql"));
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        for (String key : new String[] {
                A2RuntimePolicy.REASON_MIN_KEY,
                A2RuntimePolicy.RETENTION_KEY,
                A2RuntimePolicy.SCHEMA_VERSION_KEY,
                A4RuntimePolicyService.SAMPLING_KEY}) {
            assertThat(schema).contains(key);
            assertThat(migration).contains(key);
        }
        assertThat(migration).contains("INSERT IGNORE INTO nx_config_item")
                .doesNotContain("config_value=VALUES(config_value)");
        assertThat(migration).contains("retention_policy_months", "expire_at", "nx_audit_log_archive")
                .doesNotContain("UPDATE nx_audit_log SET expire_at");
        assertThat(runner).contains("20260811_a2_a4_runtime_policy_closure.sql");
    }
}
