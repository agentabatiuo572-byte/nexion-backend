package ffdd.opsconsole.shared.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** NEX Holders Share, Regional Race and Re-invest Bonus Week are retired; never-published drafts are tombstoned on startup. */
class H4RetireLegacyEventsMigrationContractTest {
    private static final String MIGRATION = "20261029_h4_retire_legacy_events.sql";

    @Test
    void migrationRunsInTheStartupChainAfterTheProfileChangeInterval() throws Exception {
        String installer = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"), StandardCharsets.UTF_8);
        var matcher = Pattern.compile("\"scripts\\\\migrations\\\\([^\"]+\\.sql)\"").matcher(installer);
        List<String> migrations = new ArrayList<>();
        while (matcher.find()) migrations.add(matcher.group(1));
        assertThat(migrations).containsOnlyOnce(MIGRATION);
        assertThat(migrations.indexOf(MIGRATION))
                .isGreaterThan(migrations.indexOf("20261029_app_profile_change_interval.sql"));
    }

    @Test
    void migrationTombstonesOnlyTheThreeRetiredDrafts() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations", MIGRATION), StandardCharsets.UTF_8);
        var statements = sql.lines().map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("--"))
                .collect(java.util.stream.Collectors.joining(" "));
        assertThat(statements).isEqualTo("UPDATE nx_event_quest SET is_deleted=1,updated_at=NOW()"
                + " WHERE quest_code IN ('evt-nex-holders-share','evt-regional-pk','evt-reinvest-bonus')"
                + " AND status=0 AND is_deleted=0;");
    }
}
