package ffdd.opsconsole.shared.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Team contribution is measured in compute points; rank, quota and gate thresholds move from USD to points. */
class ComputePointsMigrationContractTest {
    private static final String MIGRATION = "20261028_compute_points.sql";

    @Test
    void migrationRunsInTheStartupChainAfterTheF5CommissionKeySplit() throws Exception {
        String installer = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"), StandardCharsets.UTF_8);
        var matcher = Pattern.compile("\"scripts\\\\migrations\\\\([^\"]+\\.sql)\"").matcher(installer);
        List<String> migrations = new ArrayList<>();
        while (matcher.find()) migrations.add(matcher.group(1));
        assertThat(migrations).containsOnlyOnce(MIGRATION);
        assertThat(migrations.indexOf(MIGRATION))
                .isGreaterThan(migrations.indexOf("20261028_f5_commission_key_split.sql"));

        List<String> post = Files.readAllLines(Path.of("scripts/fresh_db/post.txt"), StandardCharsets.UTF_8).stream()
                .map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        assertThat(post).contains("migrations/" + MIGRATION);
    }

    @Test
    void migrationRenamesUsdThresholdsToPointsAndStopsAtV8() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations", MIGRATION), StandardCharsets.UTF_8);
        assertThat(sql).contains(
                "CHANGE COLUMN self_buy_usd self_compute_points BIGINT",
                "CHANGE COLUMN team_volume_usd team_compute_points BIGINT",
                "CHANGE COLUMN month_volume_usd month_compute_points BIGINT",
                "ADD COLUMN compute_points BIGINT NOT NULL DEFAULT 0 AFTER price_usdt",
                "ADD COLUMN compute_points BIGINT NOT NULL DEFAULT 0 AFTER amount_usdt",
                "SET compute_points = ROUND(price_usdt * 10)",
                "DELETE FROM nx_v_rank_config WHERE UPPER(rank_code) IN ('V9','V10','V11','V12')",
                "'$.teamComputePointsMin'");
        String schema = Files.readString(Path.of("scripts/schema.sql"), StandardCharsets.UTF_8);
        assertThat(schema).doesNotContain("self_buy_usd", "team_volume_usd", "month_volume_usd");
    }
}
