package ffdd.opsconsole.janus.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class K6RbacMigrationContractTest {
    @Test
    void publishAndRollbackAuthorityIsRevokedFromEveryNonSuperAdminRole() throws Exception {
        String baseline = compact("scripts/migrations/20260713_k6_janus.sql");
        String repair = compact("scripts/migrations/20260722_k6_admin_permission_scope_repair.sql");

        for (String migration : java.util.List.of(baseline, repair)) {
            assertThat(migration)
                    .contains("permission.permission_code='risk_k6_admin'")
                    .contains("role.role_code<>'SUPER_ADMIN'")
                    .contains("SET relation.is_deleted=1")
                    .contains("relation.is_deleted=0");
        }
    }

    private String compact(String source) throws Exception {
        return Files.readString(Path.of(source), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }
}
