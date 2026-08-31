package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class F5CommissionMutationEventSchemaMigrationContractTest {
    private static final String MIGRATION = "20260829_f5_commission_mutation_event_schema.sql";

    @Test
    void registersEveryF5MutationEventAndExactMoneyChainFields() throws Exception {
        String sql = Files.readString(Path.of("scripts", "migrations", MIGRATION));

        assertThat(sql).contains(
                "'admin.commission_reversed'",
                "'admin.commission_reissued'",
                "'admin.commission_user_suspended'",
                "'admin.commission_user_resumed'",
                "'commission_id'", "'refund_ref'", "'batch_no'", "'commission_ids'",
                "'frozen_open_events'", "'suspended'",
                "VALUES (1,313)");
        assertThat(sql).doesNotContain("DELETE FROM nx_event_schema_property");
    }

    @Test
    void normalAndAcceptanceStartupApplyTheClosure() throws Exception {
        String normal = Files.readString(Path.of("scripts", "apply_startup_schema_migrations.ps1"));
        String acceptance = Files.readString(Path.of("scripts", "acceptance", "Start-TeamAcceptance.ps1"));

        assertThat(normal).contains(MIGRATION);
        assertThat(acceptance).contains(MIGRATION);
    }
}
