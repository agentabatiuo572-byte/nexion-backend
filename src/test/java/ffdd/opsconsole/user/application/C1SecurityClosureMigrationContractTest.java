package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class C1SecurityClosureMigrationContractTest {
    @Test
    void c1RoleMatrixMenusAndA4SchemasAreDurablySeeded() throws Exception {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260718_c1_security_closure.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        String permissionSeed = Files.readString(
                Path.of("scripts/rbac-classic-seed/02-role-permission-seed.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        String menuSeed = Files.readString(
                Path.of("scripts/rbac-classic-seed/01-menu-seed.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertThat(migration)
                .contains("r.role_code='FINANCE'")
                .contains("r.role_code='GROWTH'")
                .contains("'user_c1_read','user_c1_write','user_c1hub_read'")
                .contains("m.menu_code IN ('C','C1')")
                .contains("'admin.user_profile_viewed'")
                .contains("'admin.user_list_exported'")
                .contains("'cards_viewed'")
                .contains("'filter_hash'");
        assertThat(permissionSeed)
                .contains("WHERE r.role_code='FINANCE' AND p.permission_code IN ('user_c1_read','user_c1hub_read')")
                .contains("WHERE r.role_code='GROWTH' AND p.permission_code IN ('user_c1_read','user_c1_write','user_c1hub_read')");
        assertThat(menuSeed)
                .contains("m.menu_code IN ('C','C1') WHERE r.role_code IN ('FINANCE','GROWTH')");
    }
}
