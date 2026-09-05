package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BusinessEventSchemaClosureContractTest {
    @Test void normalStartupIncludesAllSixContractsWithoutBusinessDataMutation() throws Exception {
        String filename = "20260831_business_event_schema_closure.sql";
        assertThat(Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"))).contains(filename);
        String sql = Files.readString(Path.of("scripts/migrations", filename));
        assertThat(sql).contains("START TRANSACTION;", "COMMIT;", "'auth.password_reset_completed'",
                "'capacity_replacement.completed'", "'task.completed'", "'earnings.credited'",
                "'genesis.emission_paid'", "'admin.staking_pool_restored'", "'paid_at','timestamp'",
                "'revoked_session_count'", "'review_conclusion'", "'amount_usdt'",
                "current_revision<316", "s.status='ACTIVE' AND s.is_deleted=0",
                "nx_event_schema_property.is_deleted=0", "GREATEST(current_revision,316)");
        assertThat(sql).doesNotContain("UPDATE nx_user", "UPDATE nx_compute", "UPDATE nx_wallet",
                "DELETE FROM", "SET is_deleted=0", "SET lifecycle_state='full'");
    }
}
