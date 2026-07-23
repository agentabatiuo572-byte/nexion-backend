package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class B3FunnelClosureMigrationContractTest {
    @Test
    void migrationCreatesSavedViewsAndExactRoleGrants() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations/20260723_b3_funnel_closure.sql"));
        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS nx_admin_funnel_view")
                .contains("UNIQUE KEY uk_admin_funnel_view(admin_id,view_name)")
                .contains("overview_b3_view_write")
                .contains("overview_b3_export")
                .contains("'SUPER_ADMIN','GROWTH','FINANCE','FINANCE_LEAD','AUDITOR'");
    }
}
