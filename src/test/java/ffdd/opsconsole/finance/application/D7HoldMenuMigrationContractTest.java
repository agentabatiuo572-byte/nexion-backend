package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class D7HoldMenuMigrationContractTest {
    @Test
    void serverConfigMigrationKeepsProviderClosedAndSeedsDedicatedAuthorities() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations/20260808_d7_payout_vnd_config.sql"),
                StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("finance.payout_vnd.values")
                .contains("finance.payout_vnd.version")
                .contains("finance.payout_vnd.provider_ready")
                .contains("'false'")
                .contains("finance_d7_read")
                .contains("finance_d7_manage")
                .contains("finance_d7_channel_toggle")
                .contains("finance_d7_force_inverted");
        assertThat(sql)
                .contains("INSERT INTO nx_admin_role_permission")
                .doesNotContain("INSERT IGNORE INTO nx_admin_role_permission");
        assertThat(sql)
                .doesNotContain("ON DUPLICATE KEY UPDATE is_deleted=0")
                .doesNotContain("status=1,is_deleted=0")
                .contains("'SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR'")
                .contains("'SUPER_ADMIN','FINANCE_LEAD'");
        assertThat(sql.split("AND NOT EXISTS", -1).length - 1)
                .as("all D7 role grants must preserve an existing soft-deleted revocation on rerun")
                .isEqualTo(3);

        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"),
                StandardCharsets.UTF_8);
        assertThat(runner).contains("20260808_d7_payout_vnd_config.sql");
    }
}
