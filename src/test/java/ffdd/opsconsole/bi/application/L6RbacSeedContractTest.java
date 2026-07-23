package ffdd.opsconsole.bi.application;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class L6RbacSeedContractTest {

    @Test
    void l6ReadPermissionAndMenuAreGrantedToGrowthRiskAndAuditor() throws Exception {
        String permissions = Files.readString(
                Path.of("scripts/rbac-classic-seed/02-role-permission-seed.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        String menus = Files.readString(
                Path.of("scripts/rbac-classic-seed/01-menu-seed.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertThat(permissions)
                .contains("WHERE r.role_code='GROWTH' AND p.permission_code IN")
                .contains("'bi_l6_read'")
                .contains("WHERE r.role_code='RISK' AND p.permission_code IN")
                .contains("WHERE r.role_code='AUDITOR' AND p.permission_code IN");
        assertThat(menus)
                .contains("m.menu_code IN ('L','L1','L2','L4','L5','L6') WHERE r.role_code='GROWTH'")
                .contains("m.menu_code IN ('L','L3','L4','L5','L6') WHERE r.role_code='RISK'");
    }
}
