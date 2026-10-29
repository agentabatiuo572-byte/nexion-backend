package ffdd.opsconsole.shared.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The App nickname and avatar each change at most once every 7 days; nx_user records the last change of each. */
class AppProfileChangeIntervalMigrationContractTest {
    private static final String MIGRATION = "20261029_app_profile_change_interval.sql";

    @Test
    void migrationRunsInTheStartupChainAfterComputePointsAndOnFreshDatabases() throws Exception {
        String installer = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"), StandardCharsets.UTF_8);
        var matcher = Pattern.compile("\"scripts\\\\migrations\\\\([^\"]+\\.sql)\"").matcher(installer);
        List<String> migrations = new ArrayList<>();
        while (matcher.find()) migrations.add(matcher.group(1));
        assertThat(migrations).containsOnlyOnce(MIGRATION);
        assertThat(migrations.indexOf(MIGRATION))
                .isGreaterThan(migrations.indexOf("20261028_compute_points.sql"));

        List<String> post = Files.readAllLines(Path.of("scripts/fresh_db/post.txt"), StandardCharsets.UTF_8).stream()
                .map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        assertThat(post).last().isEqualTo("migrations/" + MIGRATION);
    }

    @Test
    void migrationAddsBothNullableChangeTimesIdempotently() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations", MIGRATION), StandardCharsets.UTF_8);
        assertThat(sql).contains(
                "ALTER TABLE nx_user ADD COLUMN nickname_changed_at DATETIME NULL",
                "ALTER TABLE nx_user ADD COLUMN avatar_changed_at DATETIME NULL",
                "COLUMN_NAME='nickname_changed_at'",
                "COLUMN_NAME='avatar_changed_at'");
    }
}
