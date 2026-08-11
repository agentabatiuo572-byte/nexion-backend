package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class F5CommissionReissueAtomicityMigrationContractTest {
    @Test
    void migrationAddsAReissueOnlyUniqueSourceGuard() throws Exception {
        String sql = Files.readString(Path.of(
                "scripts/migrations/20260810_f5_commission_reissue_atomicity.sql"));

        assertThat(sql)
                .contains("reissue_source_commission_id")
                .contains("operation_type = ''REISSUE''")
                .doesNotContain("operation_type = ''REISSUE'' AND status")
                .contains("UNIQUE KEY uk_commission_reissue_source (reissue_source_commission_id)");

        assertThat(Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1")))
                .contains("20260810_f5_commission_reissue_atomicity.sql");
    }
}
