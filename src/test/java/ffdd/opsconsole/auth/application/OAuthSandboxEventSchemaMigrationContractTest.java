package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OAuthSandboxEventSchemaMigrationContractTest {

    @Test
    void historicalOAuthFixtureSchemaCannotRunAtCanonicalStartup() throws Exception {
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260816_oauth_sandbox_event_schema.sql"));
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(migration)
                .contains("auth.oauth_sandbox_account_created", "auth.oauth_sandbox_login",
                        "'user_id'", "'provider'", "'source'", "'sandbox'", "'subject_hash'",
                        "ON DUPLICATE KEY UPDATE", "nx_event_schema_revision");
        assertThat(startup).doesNotContain("20260816_oauth_sandbox_event_schema.sql")
                .contains("20260828_development_passkey_event_schema.sql");
    }
}
