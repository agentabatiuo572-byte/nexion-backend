package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UserSelfServiceMigrationContractTest {
    @Test
    void accountDeletionRequestHasDurableIdentityStatusAndStartupWiring() throws Exception {
        String migration = Files.readString(Path.of("scripts/migrations/20260813_user_self_service.sql"));
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(migration)
                .contains("CREATE TABLE IF NOT EXISTS nx_user_account_deletion_request")
                .contains("UNIQUE KEY uk_user_account_deletion_no (request_no)")
                .contains("UNIQUE KEY uk_user_account_deletion_idempotency (user_id,idempotency_key)")
                .contains("CHECK (status IN ('REQUESTED','IN_REVIEW','BLOCKED','COMPLETED','CANCELLED'))");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS nx_user_account_deletion_request");
        assertThat(runner).contains("20260813_user_self_service.sql");
    }
}
