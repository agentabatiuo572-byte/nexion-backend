package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class K2SuperAdminRbacMigrationContractTest {
    private static final Path MIGRATION = Path.of(
            "scripts/migrations/20260722_k2_superadmin_row_action_grants.sql");

    @Test
    void grantsExactlyTheFourK2RowActionsToSuperAdminIdempotently() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");

        assertThat(sql)
                .contains("INSERT IGNORE INTO nx_admin_role_permission")
                .contains("r.role_code = 'SUPER_ADMIN'")
                .contains("r.status = 1")
                .contains("r.is_deleted = 0")
                .contains("p.status = 1")
                .contains("p.is_deleted = 0")
                .doesNotContain("'RISK'");

        Matcher matcher = Pattern.compile("p\\.permission_code IN \\(([^)]*)\\)").matcher(sql);
        assertThat(matcher.find()).isTrue();
        Set<String> permissions = Pattern.compile("'([^']+)'").matcher(matcher.group(1)).results()
                .map(result -> result.group(1))
                .collect(java.util.stream.Collectors.toSet());
        assertThat(permissions).containsExactlyInAnyOrder(
                "risk_k2_row_flag",
                "risk_k2_row_freeze",
                "risk_k2_row_blockgift",
                "risk_k2_row_boardflag");
    }
}
