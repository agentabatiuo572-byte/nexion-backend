package ffdd.opsconsole.shared.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The retired M2 ticket desk's menu and service_m2_* permissions are soft-deleted after every seed rerun. */
class SupportTicketRbacRetirementMigrationContractTest {
    private static final String MIGRATION = "20261028_support_ticket_rbac_retirement.sql";

    @Test
    void retirementRunsInTheStartupChainAfterTheClassicRbacSeed() throws Exception {
        String installer = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"), StandardCharsets.UTF_8);
        var matcher = Pattern.compile("\"scripts\\\\migrations\\\\([^\"]+\\.sql)\"").matcher(installer);
        List<String> migrations = new ArrayList<>();
        while (matcher.find()) migrations.add(matcher.group(1));
        assertThat(migrations).first().isEqualTo("20260712_rbac_classic.sql");
        assertThat(migrations).containsOnlyOnce(MIGRATION);
        assertThat(migrations.indexOf(MIGRATION)).isGreaterThan(migrations.indexOf("20261028_janus_retirement.sql"));

        List<String> post = Files.readAllLines(Path.of("scripts/fresh_db/post.txt"), StandardCharsets.UTF_8).stream()
                .map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        assertThat(post).contains("migrations/" + MIGRATION);
        assertThat(post.indexOf("migrations/" + MIGRATION)).isGreaterThan(post.indexOf("migrations/20261028_janus_retirement.sql"));
    }

    @Test
    void retirementOnlySoftDeletesTicketDeskNavigationPermissionsAndTheirGrants() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations", MIGRATION), StandardCharsets.UTF_8)
                .replaceAll("(?m)--[^\\r\\n]*", "");
        List<String> statements = Arrays.stream(sql.split(";")).map(s -> s.replaceAll("\\s+", " ").trim())
                .filter(s -> !s.isEmpty()).toList();
        assertThat(statements).first().isEqualTo("START TRANSACTION");
        assertThat(statements).last().isEqualTo("COMMIT");
        List<String> updates = statements.subList(1, statements.size() - 1);
        assertThat(updates).hasSize(4).allSatisfy(statement -> assertThat(statement).startsWith("UPDATE "));
        String upper = sql.toUpperCase(Locale.ROOT);
        assertThat(upper).doesNotContain("DROP ", "DELETE ", "INSERT ", "TRUNCATE ", "ALTER ");
        assertThat(sql).doesNotContain("nx_support_ticket", "service_m1", "load-config");
        assertThat(updates.get(0)).startsWith("UPDATE nx_admin_role_menu").contains("rm.is_deleted=1", "'M2'", "'MENU_SERVICE_M2'", "'/service/tickets'");
        assertThat(updates.get(1)).startsWith("UPDATE nx_admin_role_permission").contains("rp.is_deleted=1", "'service_m2_'", "'/service/tickets'");
        assertThat(updates.get(2)).startsWith("UPDATE nx_admin_permission").contains("status=0,is_deleted=1", "'service_m2_'");
        assertThat(updates.get(3)).startsWith("UPDATE nx_admin_menu").contains("status=0,is_deleted=1", "'M2'", "'MENU_SERVICE_M2'");
    }
}
