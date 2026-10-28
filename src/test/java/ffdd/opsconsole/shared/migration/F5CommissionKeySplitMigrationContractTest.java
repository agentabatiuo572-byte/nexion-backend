package ffdd.opsconsole.shared.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** F5 commission actions use one key per money effect; growth keeps read and thresholds only. */
class F5CommissionKeySplitMigrationContractTest {
    private static final String MIGRATION = "20261028_f5_commission_key_split.sql";

    @Test
    void splitIsTheLastStartupMigrationSoItRunsAfterTheClassicRbacSeed() throws Exception {
        String installer = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"), StandardCharsets.UTF_8);
        var matcher = Pattern.compile("\"scripts\\\\migrations\\\\([^\"]+\\.sql)\"").matcher(installer);
        List<String> migrations = new ArrayList<>();
        while (matcher.find()) migrations.add(matcher.group(1));
        assertThat(migrations).last().isEqualTo(MIGRATION);
        assertThat(migrations).containsOnlyOnce(MIGRATION);

        List<String> post = Files.readAllLines(Path.of("scripts/fresh_db/post.txt"), StandardCharsets.UTF_8).stream()
                .map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        assertThat(post).last().isEqualTo("migrations/" + MIGRATION);
    }

    @Test
    void splitOnlyTouchesRbacTablesAndGrantsTheAgreedMatrix() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations", MIGRATION), StandardCharsets.UTF_8)
                .replaceAll("(?m)--[^\\r\\n]*", "");
        String upper = sql.toUpperCase(Locale.ROOT);
        assertThat(upper).doesNotContain("DROP ", "TRUNCATE ", "ALTER ", "NX_COMMISSION", "NX_WALLET");
        assertThat(sql).contains(
                "'network_f5_commission_freeze'", "'network_f5_commission_suspend'",
                "'network_f5_commission_release'", "'network_f5_commission_reverse'",
                "'network_f5_commission_reissue'");
        assertThat(sql).contains("DELETE FROM nx_admin_permission\nWHERE permission_code IN ('network_f5_commission_dispose','network_f5_commission_reject')");
        assertThat(sql).contains("r.role_code='GROWTH'");
        assertThat(sql).containsPattern("role_code='RISK_LEAD'\\s+AND p.permission_code IN \\('network_f5_write','network_f5_commission_freeze','network_f5_commission_suspend'\\)");
        assertThat(sql).containsPattern("role_code='FINANCE_LEAD'\\s+AND p.permission_code IN \\('network_f5_commission_freeze','network_f5_commission_release',\\s+'network_f5_commission_reverse','network_f5_commission_reissue'\\)");
    }

    @Test
    void classicSeedKeepsGrowthAwayFromCommissionMoneyKeys() throws Exception {
        String ef = Files.readString(Path.of("scripts/rbac-classic-seed/EF.sql"), StandardCharsets.UTF_8);
        assertThat(ef).doesNotContain("network_f5_commission_dispose", "network_f5_commission_reject");
        String roles = Files.readString(Path.of("scripts/rbac-classic-seed/02-role-permission-seed.sql"), StandardCharsets.UTF_8);
        assertThat(roles).contains("WHERE r.role_code='GROWTH' AND LEFT(p.permission_code,22) <> 'network_f5_commission_'");
    }
}
