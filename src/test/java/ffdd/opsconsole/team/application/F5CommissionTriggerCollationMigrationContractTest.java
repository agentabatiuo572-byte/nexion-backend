package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class F5CommissionTriggerCollationMigrationContractTest {

    @Test
    void suspensionTriggerUsesAnExplicitCompatibleCollation() throws IOException {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260728_f5_commission_trigger_collation.sql"));
        String originalMigration = Files.readString(
                Path.of("scripts/migrations/20260727_f5_commission_audit_closure.sql"));
        String cleanSchema = Files.readString(Path.of("scripts/schema.sql"));

        assertThat(migration)
                .contains("ALTER TABLE nx_commission_operation")
                .contains("ALTER TABLE nx_commission_user_suspension")
                .contains("CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
                .contains("DROP TRIGGER IF EXISTS trg_nx_commission_event_suspension")
                .contains("s.kind = LOWER(NEW.commission_type) COLLATE utf8mb4_0900_ai_ci")
                .contains("SET NEW.status = 'FROZEN'");
        assertThat(originalMigration)
                .contains("CREATE TABLE IF NOT EXISTS nx_commission_operation")
                .contains("CREATE TABLE IF NOT EXISTS nx_commission_user_suspension")
                .doesNotContain("COLLATE=utf8mb4_unicode_ci")
                .contains("COLLATE=utf8mb4_0900_ai_ci");
        assertThat(cleanSchema)
                .contains("CREATE TABLE IF NOT EXISTS nx_commission_operation")
                .contains("CREATE TABLE IF NOT EXISTS nx_commission_user_suspension")
                .contains("trg_nx_commission_event_suspension")
                .contains("COLLATE=utf8mb4_0900_ai_ci");
    }
}
