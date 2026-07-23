package ffdd.opsconsole.treasury.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class B1RbacSeedContractTest {

    @Test
    void b1UsesAnExactLeastPrivilegeMatrixInsteadOfTheOverviewWildcard() throws Exception {
        String seed = normalized("scripts/rbac-classic-seed/02-role-permission-seed.sql");
        String migration = normalized("scripts/migrations/20260723_b1_dual_ledger_permissions.sql");

        assertThat(seed)
                .contains("p.permission_code NOT LIKE 'overview_b1_%'")
                .contains("WHERE r.role_code IN ('FINANCE','FINANCE_LEAD','RISK','GROWTH','AUDITOR') AND p.permission_code='overview_b1_read'")
                .contains("WHERE r.role_code='FINANCE_LEAD' AND p.permission_code IN ('overview_b1_write','overview_b1_redline_write')");
        assertThat(migration)
                .contains("('wallet.dual-ledger.redline-pct','100','NUMBER'")
                .contains("('wallet.dual-ledger.healthy-pct','110','NUMBER'")
                .contains("CAST(config_value AS DECIMAL(10,2))=85 THEN '100'")
                .contains("CAST(config_value AS DECIMAL(10,2))=100 THEN '110'")
                .contains("WHERE r.role_code <> 'SUPER_ADMIN' AND p.permission_code LIKE 'overview_b1_%'")
                .contains("r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','GROWTH','AUDITOR') AND p.permission_code='overview_b1_read'")
                .contains("r.role_code IN ('SUPER_ADMIN','FINANCE_LEAD') AND p.permission_code IN ('overview_b1_write','overview_b1_redline_write')")
                .doesNotContain("SUPPORT", "CONTENT", "CONFIG_ADMIN",
                        "overview_b1_runrisk_write", "overview_b1_kill_switch_trigger");
    }

    @Test
    void b1DependentD3ActionsKeepTheirOwnExactAuthorities() throws Exception {
        String seed = normalized("scripts/rbac-classic-seed/02-role-permission-seed.sql");

        assertThat(seed)
                .contains("r.role_code IN ('SUPER_ADMIN','FINANCE','FINANCE_LEAD','RISK','AUDITOR') AND p.permission_code='finance_d3_export'")
                .contains("r.role_code IN ('SUPER_ADMIN','FINANCE_LEAD') AND p.permission_code IN ('finance_d3_write','finance_d3_injection_create')");
    }

    private static String normalized(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }
}
