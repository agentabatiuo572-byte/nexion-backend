package ffdd.opsconsole.overview.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class B4PhaseClosureMigrationContractTest {
    @Test
    void migrationDefinesExactB4PermissionsAndRoleBoundaries() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations/20260723_b4_phase_overview.sql"));
        assertThat(sql)
                .contains("overview_b4_read")
                .contains("overview_b4_jump")
                .contains("overview_b4_export")
                .contains("SUPER_ADMIN")
                .contains("GROWTH")
                .contains("FINANCE")
                .contains("AUDITOR");
    }
}
