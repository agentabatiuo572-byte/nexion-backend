package ffdd.opsconsole.developer.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DeveloperApiSchemaMigrationContractTest {
    @Test
    void migrationStoresOnlyHashesAndRegistersStartupOrder() throws Exception {
        String migration = Files.readString(Path.of("scripts/migrations/20260816_developer_api_keys_webhooks.sql"));
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        assertThat(migration).contains("nx_developer_api_key", "secret_hash", "key_prefix", "key_last4",
                "nx_developer_webhook", "events_json", "NOT_DELIVERED", "version", "secret_rotation_key", "secret_rotation_hash");
        assertThat(migration).doesNotContain("secret VARCHAR", "secret TEXT");
        assertThat(runner).contains("20260816_developer_api_keys_webhooks.sql");
    }
}
