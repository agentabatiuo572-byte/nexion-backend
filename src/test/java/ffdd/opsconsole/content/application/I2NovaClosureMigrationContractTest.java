package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class I2NovaClosureMigrationContractTest {
    private static final Path MIGRATION = Path.of("scripts/migrations/20260722_i2_nova_closure.sql");
    private static final List<String> CANONICAL_CHANNELS = List.of(
            "welcome", "market", "upgrade", "dailySummary", "tradein",
            "social", "eventClaim", "wrapped", "taskLockMonthly", "quest");

    @Test
    void migrationReconcilesExactlyTheTenAuthoritativeCadenceKeys() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        CANONICAL_CHANNELS.forEach(key -> assertThat(sql).contains("'" + key + "'"));
        long canonicalSeedRows = sql.lines()
                .filter(line -> line.contains("'system:migration', 'CANONICAL_I2_CHANNEL "))
                .count();
        assertThat(sql)
                .contains("CANONICAL_I2_CHANNEL")
                .contains("tradein")
                .contains("taskLockMonthly")
                .contains("P1-P2")
                .contains("P3-P4")
                .contains("P5-P6");
        assertThat(canonicalSeedRows).isEqualTo(10);
    }

    @Test
    void migrationProvidesPublishedLocalizedTemplatesAndSocialRuntimePrerequisites() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("INSERT INTO nx_nova_template")
                .contains("'PUBLISHED'")
                .contains("title_zh")
                .contains("title_vi")
                .contains("body_zh")
                .contains("body_vi")
                .contains("nx_nova_social_runtime_slot");
    }
}
